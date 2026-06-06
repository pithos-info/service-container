package info.pithos.service.container.core;

import com.google.protobuf.Message;

/**
 * Async handler for HTTP PATCH. Partial update — {@code Req} carries only the
 * fields to be modified, deserialised from the request body.
 */
public interface PatchHandler<Req extends Message, Resp extends Message>
        extends ServiceHandler<Req, Resp> {
}
