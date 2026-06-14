package info.pithos.service.container.core.grpc;

import info.pithos.runtime.core.context.ServiceException;
import info.pithos.runtime.model.protocol.Context.RequestContext;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import io.smallrye.mutiny.Uni;

import java.util.Optional;

public final class GrpcSupport {

    public static RequestContext context() {
        Metadata metadata = Optional.ofNullable(AuthGrpcService.METADATA_KEY.get()).orElseGet(Metadata::new);
        return GrpcRequestContextBuilder.build(metadata);
    }

    public static <T> void respond(Uni<T> uni, StreamObserver<T> observer) {
        uni.subscribe().with(
            resp -> { observer.onNext(resp); observer.onCompleted(); },
            err  -> observer.onError(toStatusException(err))
        );
    }

    public static StatusException toStatusException(Throwable t) {
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

    private GrpcSupport() {}
}
