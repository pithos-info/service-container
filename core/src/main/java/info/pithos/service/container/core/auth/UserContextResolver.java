package info.pithos.service.container.core.auth;

import info.pithos.runtime.model.protocol.Context.RequestContext;

import java.util.concurrent.CompletableFuture;

public interface UserContextResolver {
    CompletableFuture<RequestContext> resolve(RequestContext rc);
}
