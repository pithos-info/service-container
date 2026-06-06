package info.pithos.service.container.core;

import com.google.protobuf.Message;
import info.pithos.serde.ProtoBufSerde;
import info.pithos.serde.SerdeException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * Jakarta RS provider that bridges protobuf {@link Message} objects to/from HTTP.
 * JSON marshaling delegates to {@link ProtoBufSerde}; gRPC is handled natively
 * by quarkus-grpc on the same port.
 */
@Provider
@Produces({MediaType.APPLICATION_JSON, "application/x-protobuf"})
@Consumes(MediaType.APPLICATION_JSON)
public class ProtobufJsonProvider implements MessageBodyWriter<Message>, MessageBodyReader<Message> {

    // ---- MessageBodyWriter --------------------------------------------------

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return Message.class.isAssignableFrom(type);
    }

    @Override
    public void writeTo(Message message, Class<?> type, Type genericType, Annotation[] annotations,
                        MediaType mediaType, MultivaluedMap<String, Object> httpHeaders,
                        OutputStream out) throws IOException {
        if ("application/x-protobuf".equals(mediaType.getType() + "/" + mediaType.getSubtype())) {
            message.writeTo(out);
        } else {
            out.write(new ProtoBufSerde<>(message).serialize().getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---- MessageBodyReader --------------------------------------------------

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return Message.class.isAssignableFrom(type);
    }

    @Override
    public Message readFrom(Class<Message> type, Type genericType, Annotation[] annotations,
                            MediaType mediaType, MultivaluedMap<String, String> httpHeaders,
                            InputStream in) throws IOException {
        String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        try {
            Message.Builder builder = (Message.Builder) type.getMethod("newBuilder").invoke(null);
            return new ProtoBufSerde<>(json, builder).getObject();
        } catch (SerdeException e) {
            throw new WebApplicationException("Invalid protobuf JSON: " + e.getMessage(), 400);
        } catch (ReflectiveOperationException e) {
            throw new WebApplicationException("Cannot instantiate protobuf builder for " + type.getName(), 500);
        }
    }
}
