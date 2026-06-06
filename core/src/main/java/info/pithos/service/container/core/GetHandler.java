package info.pithos.service.container.core;

import com.google.protobuf.Message;

/**
 * Async handler for HTTP GET. {@code Req} is typically an identifier or query
 * wrapper populated from path/query parameters — no request body.
 */
public interface GetHandler<Req extends Message, Resp extends Message>
        extends ServiceHandler<Req, Resp> {
}
