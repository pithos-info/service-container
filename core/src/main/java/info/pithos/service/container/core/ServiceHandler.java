package info.pithos.service.container.core;

import com.google.protobuf.Message;
import info.pithos.runtime.model.protocol.Context.RequestContext;
import io.smallrye.mutiny.Uni;

public interface ServiceHandler<Req extends Message, Resp extends Message> {

    Uni<Resp> handle(Req request, RequestContext context);
}
