package info.pithos.service.container.core.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

public final class GrpcMetadataInterceptor implements ServerInterceptor {

    public GrpcMetadataInterceptor() {}

    @Override
    public <Req, Resp> ServerCall.Listener<Req> interceptCall(
            ServerCall<Req, Resp> call,
            Metadata headers,
            ServerCallHandler<Req, Resp> next) {
        Context ctx = Context.current().withValue(AuthGrpcService.METADATA_KEY, headers);
        return Contexts.interceptCall(ctx, call, headers, next);
    }
}
