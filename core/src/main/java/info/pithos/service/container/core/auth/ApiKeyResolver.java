package info.pithos.service.container.core.auth;

import info.pithos.auth.model.TokenIntrospection;
import info.pithos.runtime.model.protocol.Context.RequestContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;

public interface ApiKeyResolver {
    CompletableFuture<TokenIntrospection> resolve(RequestContext bootstrap, String rawKey);

    public static String sha256hex(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("input = null or blank");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
