package info.pithos.service.container.core;

import com.google.protobuf.Message;

/**
 * Async handler for HTTP POST. {@code Req} is deserialised from the request body.
 */
public interface PostHandler<Req extends Message, Resp extends Message>
        extends ServiceHandler<Req, Resp> {
}
