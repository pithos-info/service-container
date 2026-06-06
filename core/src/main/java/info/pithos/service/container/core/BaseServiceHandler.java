package info.pithos.service.container.core;

import com.google.protobuf.Message;
import info.pithos.runtime.model.protocol.http.RequestContextOuterClass.AuthContext;
import info.pithos.runtime.model.protocol.http.RequestContextOuterClass.LogLevelType;
import info.pithos.runtime.model.protocol.http.RequestContextOuterClass.RequestContext;
import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;

import java.util.Set;

/**
 * Abstract base for all service handlers. Subclasses implement the business
 * logic via {@link #handle(Message, RequestContext)}; the HTTP entry point
 * {@link #handleHttp(Message, MultiMap)} maps inbound headers to the shared
 * {@link RequestContext} proto before delegating.
 *
 * <p>gRPC interceptors should build {@link RequestContext} from
 * {@code io.grpc.Metadata} and call {@link #handle(Message, RequestContext)}
 * directly.
 */
public abstract class BaseServiceHandler<Req extends Message, Resp extends Message>
        implements ServiceHandler<Req, Resp> {

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

    public final Uni<Resp> handleHttp(Req request, MultiMap httpHeaders) {
        return handle(request, buildRequestContext(httpHeaders));
    }

    private static RequestContext buildRequestContext(MultiMap h) {
        RequestContext.Builder ctx = RequestContext.newBuilder();
        AuthContext.Builder auth = AuthContext.newBuilder();

        String requestId = coalesce(h, "X-Request-Id", "X-Correlation-Id");
        if (requestId != null) ctx.setRequestId(requestId);

        String enterpriseId = h.get("X-Enterprise-Id");
        if (enterpriseId != null) {
            ctx.setEnterpriseId(enterpriseId);
            auth.setEnterpriseId(enterpriseId);
        }

        String authToken = h.get("Authorization");
        if (authToken != null) auth.setUserAuthToken(authToken);

        String userId = h.get("X-User-Id");
        if (userId != null) auth.setUserId(userId);

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
}
