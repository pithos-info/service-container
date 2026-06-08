package info.pithos.service.container.core;

import com.google.protobuf.Message;
import info.pithos.auth.OAuthClient;
import info.pithos.auth.model.TokenIntrospection;
import info.pithos.runtime.core.context.ErrorCode;
import info.pithos.runtime.core.context.ServiceException;
import info.pithos.runtime.model.protocol.Context.AuthContext;
import info.pithos.runtime.model.protocol.Context.LogLevelType;
import info.pithos.runtime.model.protocol.Context.RequestContext;
import info.pithos.serde.ProtoBufSerde;
import info.pithos.serde.SerdeException;
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

    private static final String BEARER_PREFIX = "Bearer ";

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

    public final Uni<Resp> handleHttp(Req request, MultiMap httpHeaders) {
        String authHeader = httpHeaders.get("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());
            RequestContext bootstrap = buildRequestContext(httpHeaders, null);
            return Uni.createFrom()
                    .completionStage(() -> oAuthClient.introspectToken(bootstrap, token))
                    .flatMap(introspection -> {
                        if (!introspection.active()) {
                            return Uni.createFrom().failure(
                                    new ServiceException(ErrorCode.UNAUTHORIZED, "OAuth token is not active"));
                        }
                        return handle(request, buildRequestContext(httpHeaders, introspection));
                    })
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

        // prefer the validated subject from token introspection over the header claim
        if (introspection != null && introspection.subject() != null) {
            auth.setUserId(introspection.subject());
        } else {
            String userId = h.get("X-User-Id");
            if (userId != null) auth.setUserId(userId);
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
            ctx.fail(500, e);
        }
    }

    /** Maps a {@link ServiceException} (or any throwable) to an HTTP error response. */
    public static void routingError(RoutingContext ctx, Throwable t) {
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
