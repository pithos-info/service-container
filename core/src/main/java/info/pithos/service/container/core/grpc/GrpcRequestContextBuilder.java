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

package info.pithos.service.container.core.grpc;

import info.pithos.runtime.model.protocol.Context.AuthContext;
import info.pithos.runtime.model.protocol.Context.LogLevelType;
import info.pithos.runtime.model.protocol.Context.RequestContext;
import io.grpc.Metadata;

import java.util.UUID;

class GrpcRequestContextBuilder {

    static RequestContext build(Metadata metadata) {
        RequestContext.Builder ctx = RequestContext.newBuilder();
        AuthContext.Builder auth = AuthContext.newBuilder();

        // requestId: continue client session if supplied, else generate
        String inbound = coalesce(metadata, "x-request-id", "x-correlation-id");
        boolean clientOwned = inbound != null;
        String requestId = clientOwned ? inbound : UUID.randomUUID().toString();

        // traceId: per entry-point hop; equals requestId only on the first call
        String traceId = clientOwned ? UUID.randomUUID().toString() : requestId;

        ctx.setRequestId(requestId);
        ctx.setTraceId(traceId);

        String enterpriseId = get(metadata, "x-enterprise-id");
        if (enterpriseId != null) {
            ctx.setEnterpriseId(enterpriseId);
            auth.setEnterpriseId(enterpriseId);
        }

        String userId = get(metadata, "x-user-id");
        if (userId != null) auth.setUserId(userId);

        String userAgent = get(metadata, "user-agent");
        if (userAgent != null) ctx.setUserAgent(userAgent);

        String source = coalesce(metadata, "x-forwarded-for", "x-real-ip");
        if (source != null) ctx.setSource(source);

        String logLevel = get(metadata, "x-log-level");
        if (logLevel != null) {
            try {
                ctx.setLogLevel(LogLevelType.valueOf(logLevel.toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }

        return ctx.setAuthContext(auth).build();
    }

    private static String get(Metadata metadata, String name) {
        return metadata.get(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER));
    }

    private static String coalesce(Metadata metadata, String... names) {
        for (String name : names) {
            String v = get(metadata, name);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private GrpcRequestContextBuilder() {}
}
