/*
 * Copyright 2026 Pithos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package info.pithos.service.container.core;

import com.google.inject.Inject;
import com.google.protobuf.Message;
import info.pithos.runtime.core.context.ApplicationContext;
import info.pithos.runtime.core.context.ErrorCode;
import info.pithos.runtime.core.context.ServiceException;
import info.pithos.runtime.model.protocol.Context.LogLevelType;
import info.pithos.serde.ProtoBufSerde;
import info.pithos.serde.SerdeException;
import io.vertx.ext.web.RoutingContext;

public final class RouteHelper {

    private final ApplicationContext applicationContext;

    @Inject
    public RouteHelper(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public <Req extends Message, Resp extends Message> void route(
            RoutingContext ctx, int successStatus,
            BaseServiceHandler<Req, Resp> handler, Req req) {
        handler.handleHttp(req, ctx.request().headers())
            .subscribe().with(
                resp -> respond(ctx, successStatus, resp),
                err  -> routingError(ctx, err)
            );
    }

    public <Req extends Message, Resp extends Message> void routeNoContent(
            RoutingContext ctx,
            BaseServiceHandler<Req, Resp> handler, Req req) {
        handler.handleHttp(req, ctx.request().headers())
            .subscribe().with(
                resp -> ctx.response().setStatusCode(204).end(),
                err  -> routingError(ctx, err)
            );
    }

    @SuppressWarnings("unchecked")
    public <T extends Message> T parseBody(RoutingContext ctx, Message.Builder builder) {
        String body = ctx.body().asString();
        if (body == null || body.isBlank()) {
            routingError(ctx, new ServiceException(ErrorCode.BAD_REQUEST, "Request body is required"));
            return null;
        }
        try {
            return (T) new ProtoBufSerde<>(body, builder).getObject();
        } catch (SerdeException e) {
            routingError(ctx, new ServiceException(ErrorCode.BAD_REQUEST,
                "Invalid request body: " + e.getMessage()));
            return null;
        }
    }

    public void respond(RoutingContext ctx, int status, Message proto) {
        try {
            ctx.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end(new ProtoBufSerde<>(proto).serialize());
        } catch (Exception e) {
            applicationContext.getSystemContext().getLogger()
                .logRequest(null, RouteHelper.class, LogLevelType.ERROR, "Failed to serialize response proto", e);
            routingError(ctx, e);
        }
    }

    public void routingError(RoutingContext ctx, Throwable t) {
        Throwable normalized = normalizeException(t);
        if (!(t instanceof ServiceException)) {
            applicationContext.getSystemContext().getLogger()
                .logRequest(null, RouteHelper.class, LogLevelType.ERROR, "Unhandled exception in handler", t);
        }
        int status = 500;
        String message = "Internal server error";
        if (normalized instanceof ServiceException se) {
            message = se.getMessage() != null ? se.getMessage() : message;
            status = switch (se.getErrorCode()) {
                case BAD_REQUEST         -> 400;
                case UNAUTHORIZED        -> 401;
                case FORBIDDEN           -> 403;
                case NOT_FOUND           -> 404;
                case CONFLICT            -> 409;
                case TOO_MANY_REQUESTS   -> 429;
                case NOT_IMPLEMENTED     -> 501;
                case SERVICE_UNAVAILABLE -> 503;
                default                  -> 500;
            };
        }
        String safe = message.replace("\\", "\\\\").replace("\"", "\\\"");
        ctx.response()
            .setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end("{\"error\":\"" + safe + "\"}");
    }

    static Throwable normalizeException(Throwable t) {
        if (t instanceof ServiceException) return t;
        if (t instanceof IllegalArgumentException)
            return new ServiceException(ErrorCode.BAD_REQUEST, t.getMessage(), t);
        if (t instanceof SecurityException)
            return new ServiceException(ErrorCode.UNAUTHORIZED, t.getMessage(), t);
        return new ServiceException(ErrorCode.INTERNAL_SERVER_ERROR, t.getMessage(), t);
    }
}
