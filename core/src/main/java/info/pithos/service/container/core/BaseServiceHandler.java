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
import info.pithos.auth.OAuthClient;
import info.pithos.auth.model.TokenIntrospection;
import info.pithos.runtime.core.context.ErrorCode;
import info.pithos.runtime.core.context.ServiceException;
import info.pithos.runtime.model.protocol.Context.AuthContext;
import info.pithos.runtime.model.protocol.Context.LogLevelType;
import info.pithos.runtime.model.protocol.Context.RequestContext;
import java.util.logging.Level;
import java.util.logging.Logger;
import info.pithos.serde.ProtoBufSerde;
import info.pithos.serde.SerdeException;
import info.pithos.service.container.core.auth.ApiKeyResolver;
import info.pithos.service.container.core.auth.UserContextResolver;
import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.ext.web.RoutingContext;

import java.util.Set;
import java.util.UUID;

/**
 * Abstract base for all service handlers. Subclasses implement the business
 * logic via {@link #handle(Message, RequestContext)}; the HTTP entry point
 * {@link #handleHttp(Message, MultiMap)} validates the Bearer token via
 * {@link OAuthClient#introspectToken} and maps inbound headers to the shared
 * {@link RequestContext} proto before delegating.
 *
 * <p>gRPC interceptors should build {@link RequestContext} from
 * {@code io.grpc.Metadata} and call {@link #handle(Message, RequestContext)}
 * directly.
 */
public abstract class BaseServiceHandler<Req extends Message, Resp extends Message>
        implements ServiceHandler<Req, Resp> {

    private static final Logger log = Logger.getLogger(BaseServiceHandler.class.getName());
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

    private final OAuthClient oAuthClient;

    protected BaseServiceHandler(OAuthClient oAuthClient) {
        if (oAuthClient == null) throw new IllegalArgumentException("oAuthClient must not be null");
        this.oAuthClient = oAuthClient;
    }

    protected final OAuthClient oAuthClient() {
        return oAuthClient;
    }

    protected boolean requiresAuthentication() {
        return true;
    }

    public final Uni<Resp> handleHttp(Req request, MultiMap httpHeaders) {
        String authHeader = httpHeaders.get("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());
            RequestContext bootstrap = buildRequestContext(httpHeaders, null);
            ApiKeyResolver resolver = globalApiKeyResolver;
            return Uni.createFrom()
                    .completionStage(isApiKey(token) && resolver != null
                        ? () -> resolver.resolve(bootstrap, token)
                        : () -> oAuthClient.introspectToken(bootstrap, token))
                    .flatMap(introspection -> {
                        if (!introspection.active()) {
                            return Uni.createFrom().failure(
                                    new ServiceException(ErrorCode.UNAUTHORIZED, "OAuth token is not active"));
                        }
                        RequestContext rc = buildRequestContext(httpHeaders, introspection);
                        if (rc.getAuthContext().getUserId().isBlank()) {
                            return Uni.createFrom().failure(
                                    new ServiceException(ErrorCode.UNAUTHORIZED, "authenticated user required"));
                        }
                        UserContextResolver ucr = globalUserContextResolver;
                        Uni<RequestContext> ctxUni = ucr != null
                            ? Uni.createFrom().completionStage(() -> ucr.resolve(rc))
                            : Uni.createFrom().item(rc);
                        return ctxUni.flatMap(resolvedRc -> handle(request, resolvedRc));
                    })
                    .onFailure().transform(BaseServiceHandler::normalizeException);
        }
        if (requiresAuthentication()) {
            return Uni.createFrom().<Resp>failure(
                    new ServiceException(ErrorCode.UNAUTHORIZED, "authentication required"))
                    .onFailure().transform(BaseServiceHandler::normalizeException);
        }
        return handle(request, buildRequestContext(httpHeaders, null))
                .onFailure().transform(BaseServiceHandler::normalizeException);
    }

    private static Throwable normalizeException(Throwable t) {
        if (t instanceof ServiceException) return t;
        if (t instanceof IllegalArgumentException)
            return new ServiceException(ErrorCode.BAD_REQUEST, t.getMessage(), t);
        if (t instanceof SecurityException)
            return new ServiceException(ErrorCode.UNAUTHORIZED, t.getMessage(), t);
        log.log(Level.SEVERE, "Unexpected exception in handler", t);
        return new ServiceException(ErrorCode.INTERNAL_SERVER_ERROR, t.getMessage(), t);
    }

    private static RequestContext buildRequestContext(MultiMap h, TokenIntrospection introspection) {
        RequestContext.Builder ctx = RequestContext.newBuilder();
        AuthContext.Builder auth = AuthContext.newBuilder();

        String requestId = coalesce(h, "X-Request-Id", "X-Correlation-Id");
        if (requestId != null) {
            ctx.setRequestId(requestId);
        } else {
            ctx.setRequestId(UUID.randomUUID().toString());
        }

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

    // ── Vert.x routing helpers ────────────────────────────────────────────────

    /**
     * Calls {@link #handleHttp} with the routing context's headers, writes the
     * proto response as JSON on success, maps {@link ServiceException} to the
     * appropriate HTTP status on failure.
     */
    public static <Req extends Message, Resp extends Message> void route(
            RoutingContext ctx, int successStatus,
            BaseServiceHandler<Req, Resp> handler, Req req) {
        handler.handleHttp(req, ctx.request().headers())
            .subscribe().with(
                resp -> respond(ctx, successStatus, resp),
                err  -> routingError(ctx, err)
            );
    }

    /** Variant for operations that produce no response body (204 No Content). */
    public static <Req extends Message, Resp extends Message> void routeNoContent(
            RoutingContext ctx,
            BaseServiceHandler<Req, Resp> handler, Req req) {
        handler.handleHttp(req, ctx.request().headers())
            .subscribe().with(
                resp -> ctx.response().setStatusCode(204).end(),
                err  -> routingError(ctx, err)
            );
    }

    /**
     * Parses the JSON request body into the given proto builder.
     * Sends a 400 response and returns {@code null} on parse failure —
     * callers must guard against null before proceeding.
     */
    @SuppressWarnings("unchecked")
    public static <T extends Message> T parseBody(RoutingContext ctx, Message.Builder builder) {
        String body = ctx.body().asString();
        if (body == null || body.isBlank()) {
            routingError(ctx, new ServiceException(ErrorCode.BAD_REQUEST, "Request body is required"));
            return null;
        }
        try {
            return (T) new ProtoBufSerde<>(body, builder).getObject();
        } catch (SerdeException e) {
            routingError(ctx, new ServiceException(ErrorCode.BAD_REQUEST,
                "Invalid request body: " + e.getMessage()));
            return null;
        }
    }

    /** Writes a proto message as JSON with the given HTTP status. */
    public static void respond(RoutingContext ctx, int status, Message proto) {
        try {
            ctx.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(new ProtoBufSerde<>(proto).serialize());
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to serialize response proto", e);
            routingError(ctx, e);
        }
    }

    /** Maps a {@link ServiceException} (or any throwable) to an HTTP error response. */
    public static void routingError(RoutingContext ctx, Throwable t) {
        if (!(t instanceof ServiceException)) {
            log.log(Level.SEVERE, "Unhandled exception in handler", t);
        }
        Throwable normalized = normalizeException(t);
        int status = 500;
        String message = "Internal server error";
        if (normalized instanceof ServiceException se) {
            message = se.getMessage() != null ? se.getMessage() : message;
            status = switch (se.getErrorCode()) {
                case BAD_REQUEST         -> 400;
                case UNAUTHORIZED        -> 401;
                case FORBIDDEN           -> 403;
                case NOT_FOUND           -> 404;
                case CONFLICT            -> 409;
                case TOO_MANY_REQUESTS   -> 429;
                case NOT_IMPLEMENTED     -> 501;
                case SERVICE_UNAVAILABLE -> 503;
                default                  -> 500;
            };
        }
        String safe = message.replace("\\", "\\\\").replace("\"", "\\\"");
        ctx.response()
            .setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end("{\"error\":\"" + safe + "\"}");
    }
}
