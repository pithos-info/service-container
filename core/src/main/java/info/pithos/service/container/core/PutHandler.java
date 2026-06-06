package info.pithos.service.container.core;

import com.google.protobuf.Message;

/**
 * Async handler for HTTP PUT. Full replacement — {@code Req} carries the
 * complete resource state deserialised from the request body.
 */
public interface PutHandler<Req extends Message, Resp extends Message>
        extends ServiceHandler<Req, Resp> {
}
