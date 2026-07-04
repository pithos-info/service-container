/*
 * Copyright 2026 Pithos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package info.pithos.service.container.core;

import com.google.protobuf.Message;
import info.pithos.authn.OAuthClient;
import info.pithos.authn.model.TokenIntrospection;
import info.pithos.runtime.core.context.ApplicationContext;
import info.pithos.runtime.core.context.ErrorCode;
import info.pithos.runtime.core.context.ServiceException;
import info.pithos.runtime.core.metrics.MetricsCommitter;
import info.pithos.runtime.model.metrics.Metrics.MetricEvent;
import info.pithos.runtime.model.metrics.Metrics.MetricUnit;
import info.pithos.runtime.model.metrics.Metrics.ProtocolType;
import info.pithos.runtime.model.protocol.Context.AuthContext;
import info.pithos.runtime.model.protocol.Context.LogLevelType;
import info.pithos.runtime.model.protocol.Context.RequestContext;
import info.pithos.service.container.core.auth.ApiKeyResolver;
import info.pithos.service.container.core.auth.UserContextResolver;
import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.ext.web.RoutingContext;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Abstract base for all service handlers. Subclasses implement the business
 * logic via {@link #handle(Message, RequestContext)}; the HTTP entry point
 * {@link #handleHttp(Message, RoutingContext)} validates the Bearer token,
 * resolves requestId/traceId, sets {@code X-Request-Id} on the response, and
 * maps inbound headers to the shared {@link RequestContext} proto before delegating.
 *
 * <p>requestId spans the full client session (propagated via {@code X-Request-Id}).
 * traceId is per entry-point: a new UUID each call if the client owns the requestId,
 * or equal to requestId on the very first call (when the service generates it).
 *
 * <p>gRPC services should build {@link RequestContext} from
 * {@code io.grpc.Metadata} and call {@link #handleGrpc(Message, RequestContext)}
 * to get metrics recorded automatically.
 */
public abstract class BaseServiceHandler<Req extends Message, Resp extends Message>
        implements ServiceHandler<Req, Resp> {

    private static final String BEARER_PREFIX = "Bearer ";

    private static volatile ApiKeyResolver globalApiKeyResolver;
    private static volatile UserContextResolver globalUserContextResolver;

    public static void setApiKeyResolver(ApiKeyResolver resolver) {
        globalApiKeyResolver = resolver;
    }

    public static void setUserContextResolver(UserContextResolver resolver) {
        globalUserContextResolver = resolver;
    }

    private static final Set<String> MAPPED_HEADERS = Set.of(
            "x-request-id", "x-correlation-id",
            "x-enterprise-id",
            "x-user-id",
            "x-log-level",
            "x-forwarded-for", "x-real-ip",
            "authorization",
            "host",
            "user-agent",
            "cache-control",
            "accept-language"
    );

    private final ApplicationContext applicationContext;
    private final OAuthClient oAuthClient;

    protected BaseServiceHandler(ApplicationContext applicationContext, OAuthClient oAuthClient) {
        if (applicationContext == null) throw new IllegalArgumentException("applicationContext must not be null");
        if (oAuthClient == null) throw new IllegalArgumentException("oAuthClient must not be null");
        this.applicationContext = applicationContext;
        this.oAuthClient = oAuthClient;
    }

    protected final ApplicationContext applicationContext() {
        return applicationContext;
    }

    protected final OAuthClient oAuthClient() {
        return oAuthClient;
    }

    protected boolean requiresAuthentication() {
        return true;
    }

    /**
     * The operation name recorded as the service-tier metric, e.g. "login", "logout",
     * "createUser". Combined with ".latency" / ".success" / ".failure" / ".timeout".
     */
    protected abstract String operationName();

    // ── Service-tier metric helpers ───────────────────────────────────────────

    private String resolvedHttpMethod() {
        if (this instanceof GetHandler)   return "GET";
        if (this instanceof PutHandler)   return "PUT";
        if (this instanceof PatchHandler) return "PATCH";
        return "POST";
    }

    private void recordServiceOp(RequestContext rc, String method, ProtocolType protocol,
                                  long startMs, Throwable ex) {
        MetricsCommitter mc = applicationContext.getMetricsCommitter();
        if (mc == null) return;
        long elapsedMs = System.currentTimeMillis() - startMs;
        String op = operationName();
        mc.record(rc, MetricEvent.newBuilder()
                .setMetric(op + ".latency").setUnit(MetricUnit.MS).setValue(elapsedMs)
                .setMethod(method).setProtocol(protocol).build());
        String outcome = (ex == null) ? op + ".success"
                : (isTimeout(ex) ? op + ".timeout" : op + ".failure");
        mc.record(rc, MetricEvent.newBuilder()
                .setMetric(outcome).setUnit(MetricUnit.COUNT).setValue(1.0)
                .setMethod(method).setProtocol(protocol).build());
    }

    private static boolean isTimeout(Throwable t) {
        while (t != null) {
            if (t instanceof TimeoutException) return true;
            t = t.getCause();
        }
        return false;
    }

    public final Uni<Resp> handleHttp(Req request, RoutingContext routingContext) {
        long startMs = System.currentTimeMillis();
        String method = resolvedHttpMethod();
        MultiMap httpHeaders = routingContext.request().headers();

        // requestId: use client-supplied value to continue an existing session, else generate one
        String inbound = coalesce(httpHeaders, "X-Request-Id", "X-Correlation-Id");
        boolean clientOwned = inbound != null;
        String requestId = clientOwned ? inbound : UUID.randomUUID().toString();

        // traceId: unique per entry-point hop; equals requestId only on the first call
        // (when the service itself generated the requestId and both IDs mark the same event)
        String traceId = clientOwned ? UUID.randomUUID().toString() : requestId;

        // always echo requestId back so clients that didn't send one can adopt it
        routingContext.response().putHeader("X-Request-Id", requestId);

        // Holds the best available RequestContext for metric tagging; upgraded as auth resolves
        RequestContext[] rcHolder = { buildRequestContext(httpHeaders, null, requestId, traceId) };

        Uni<Resp> pipeline;
        String authHeader = httpHeaders.get("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());
            RequestContext bootstrap = rcHolder[0];
            ApiKeyResolver resolver = globalApiKeyResolver;
            pipeline = Uni.createFrom()
                    .completionStage(isApiKey(token) && resolver != null
                        ? () -> resolver.resolve(bootstrap, token)
                        : () -> oAuthClient.introspectToken(bootstrap, token))
                    .flatMap(introspection -> {
                        if (!introspection.active()) {
                            return Uni.createFrom().failure(
                                    new ServiceException(ErrorCode.UNAUTHORIZED, "OAuth token is not active"));
                        }
                        RequestContext rc = buildRequestContext(httpHeaders, introspection, requestId, traceId);
                        if (rc.getAuthContext().getUserId().isBlank()) {
                            return Uni.createFrom().failure(
                                    new ServiceException(ErrorCode.UNAUTHORIZED, "authenticated user required"));
                        }
                        rcHolder[0] = rc;
                        UserContextResolver ucr = globalUserContextResolver;
                        Uni<RequestContext> ctxUni = ucr != null
                            ? Uni.createFrom().completionStage(() -> ucr.resolve(rc))
                            : Uni.createFrom().item(rc);
                        return ctxUni.flatMap(resolvedRc -> {
                            rcHolder[0] = resolvedRc;
                            return handle(request, resolvedRc);
                        });
                    })
                    .onFailure().transform(this::normalizeException);
        } else if (requiresAuthentication()) {
            pipeline = Uni.createFrom().<Resp>failure(
                    new ServiceException(ErrorCode.UNAUTHORIZED, "authentication required"))
                    .onFailure().transform(this::normalizeException);
        } else {
            pipeline = handle(request, rcHolder[0])
                    .onFailure().transform(this::normalizeException);
        }

        return pipeline
                .invoke(resp -> recordServiceOp(rcHolder[0], method, ProtocolType.HTTP, startMs, null))
                .onFailure().invoke(ex -> recordServiceOp(rcHolder[0], method, ProtocolType.HTTP, startMs, ex));
    }

    public final Uni<Resp> handleGrpc(Req request, RequestContext rc) {
        long startMs = System.currentTimeMillis();
        return handle(request, rc)
                .onFailure().transform(this::normalizeException)
                .invoke(resp -> recordServiceOp(rc, "UNARY", ProtocolType.GRPC, startMs, null))
                .onFailure().invoke(ex -> recordServiceOp(rc, "UNARY", ProtocolType.GRPC, startMs, ex));
    }

    private Throwable normalizeException(Throwable t) {
        if (t instanceof ServiceException) return t;
        if (t instanceof IllegalArgumentException)
            return new ServiceException(ErrorCode.BAD_REQUEST, t.getMessage(), t);
        if (t instanceof SecurityException)
            return new ServiceException(ErrorCode.UNAUTHORIZED, t.getMessage(), t);
        applicationContext.getSystemContext().getLogger()
            .logRequest(null, getClass(), LogLevelType.ERROR, "Unexpected exception in handler", t);
        return new ServiceException(ErrorCode.INTERNAL_SERVER_ERROR, t.getMessage(), t);
    }

    private static RequestContext buildRequestContext(MultiMap h, TokenIntrospection introspection,
                                                      String requestId, String traceId) {
        RequestContext.Builder ctx = RequestContext.newBuilder();
        AuthContext.Builder auth = AuthContext.newBuilder();

        ctx.setRequestId(requestId);
        ctx.setTraceId(traceId);

        String enterpriseId = h.get("X-Enterprise-Id");
        if (enterpriseId != null) {
            ctx.setEnterpriseId(enterpriseId);
            auth.setEnterpriseId(enterpriseId);
        }

        String authToken = h.get("Authorization");
        if (authToken != null) auth.setUserAuthToken(authToken);

        // prefer validated identity from token introspection over header claims
        if (introspection != null && introspection.subject() != null) {
            auth.setUserId(introspection.subject());
        } else {
            String userId = h.get("X-User-Id");
            if (userId != null) auth.setUserId(userId);
        }
        if (introspection != null && introspection.enterpriseId() != null) {
            ctx.setEnterpriseId(introspection.enterpriseId());
            auth.setEnterpriseId(introspection.enterpriseId());
        }

        String host = h.get("Host");
        if (host != null) ctx.setHost(host);

        String source = coalesce(h, "X-Forwarded-For", "X-Real-IP");
        if (source != null) ctx.setSource(source);

        String userAgent = h.get("User-Agent");
        if (userAgent != null) ctx.setUserAgent(userAgent);

        String cacheControl = h.get("Cache-Control");
        if (cacheControl != null) ctx.setCacheControl(cacheControl);

        String locale = h.get("Accept-Language");
        if (locale != null) ctx.setLocale(locale);

        String logLevel = h.get("X-Log-Level");
        if (logLevel != null) {
            try {
                ctx.setLogLevel(LogLevelType.valueOf(logLevel.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // unknown log level — leave as default
            }
        }

        // anything not explicitly mapped goes into attributes for downstream use
        h.names().stream()
                .filter(name -> !MAPPED_HEADERS.contains(name.toLowerCase()))
                .forEach(name -> ctx.putAttributes(name, h.get(name)));

        return ctx.setAuthContext(auth).build();
    }

    // JWTs have exactly two dots (header.payload.signature); API keys have none
    private static boolean isApiKey(String token) {
        int dots = 0;
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) == '.') dots++;
        }
        return dots != 2;
    }

    private static String coalesce(MultiMap h, String... names) {
        for (String name : names) {
            String v = h.get(name);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

}
