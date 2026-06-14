package info.pithos.service.container.core.grpc;

import info.pithos.auth.model.Auth.LoginRequest;
import info.pithos.auth.model.Auth.LoginResponse;
import info.pithos.auth.model.AuthServiceGrpc;
import info.pithos.runtime.model.protocol.Context.RequestContext;
import info.pithos.service.container.core.LoginHandler;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.stub.StreamObserver;

import java.util.Optional;

public class AuthGrpcService extends AuthServiceGrpc.AuthServiceImplBase {

    /**
     * Populated by {@link GrpcMetadataInterceptor} before each call; retrieve via
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
        GrpcSupport.respond(loginHandler.handle(request, context), responseObserver);
    }
}
