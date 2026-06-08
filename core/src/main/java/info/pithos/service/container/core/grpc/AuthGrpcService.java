package info.pithos.service.container.core.grpc;

import info.pithos.auth.model.Auth.LoginRequest;
import info.pithos.auth.model.Auth.LoginResponse;
import info.pithos.auth.model.AuthServiceGrpc;
import info.pithos.runtime.core.context.ServiceException;
import info.pithos.runtime.model.protocol.Context.RequestContext;
import info.pithos.service.container.core.LoginHandler;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;

import java.util.Optional;

public class AuthGrpcService extends AuthServiceGrpc.AuthServiceImplBase {

    /**
     * Populated by a server-side interceptor before each call; retrieve via
     * {@code METADATA_KEY.get()} inside service methods to access request metadata.
     */
    public static final Context.Key<Metadata> METADATA_KEY = Context.key("grpc-request-metadata");

    private final LoginHandler loginHandler;

    public AuthGrpcService(LoginHandler loginHandler) {
        this.loginHandler = loginHandler;
    }

    @Override
    public void login(LoginRequest request, StreamObserver<LoginResponse> responseObserver) {
        Metadata metadata = Optional.ofNullable(METADATA_KEY.get()).orElseGet(Metadata::new);
        RequestContext context = GrpcRequestContextBuilder.build(metadata);
        loginHandler.handle(request, context)
                .subscribe().with(
                    resp -> {
                        responseObserver.onNext(resp);
                        responseObserver.onCompleted();
                    },
                    err -> responseObserver.onError(toStatusException(err))
                );
    }

    private static StatusException toStatusException(Throwable t) {
        if (t instanceof ServiceException se) {
            Status status = switch (se.getErrorCode()) {
                case BAD_REQUEST         -> Status.INVALID_ARGUMENT;
                case UNAUTHORIZED        -> Status.UNAUTHENTICATED;
                case FORBIDDEN           -> Status.PERMISSION_DENIED;
                case NOT_FOUND           -> Status.NOT_FOUND;
                case CONFLICT            -> Status.ALREADY_EXISTS;
                case TOO_MANY_REQUESTS   -> Status.RESOURCE_EXHAUSTED;
                case SERVICE_UNAVAILABLE -> Status.UNAVAILABLE;
                case NOT_IMPLEMENTED     -> Status.UNIMPLEMENTED;
                default                  -> Status.INTERNAL;
            };
            return status.withDescription(se.getMessage()).asException();
        }
        return Status.INTERNAL.withCause(t).withDescription("Internal server error").asException();
    }
}
