package info.pithos.service.container.core;

import info.pithos.auth.model.TokenIntrospection;
import info.pithos.runtime.model.protocol.Context.RequestContext;

import java.util.concurrent.CompletableFuture;

public interface ApiKeyResolver {
    CompletableFuture<TokenIntrospection> resolve(RequestContext bootstrap, String rawKey);
}
