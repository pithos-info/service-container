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

import info.pithos.authn.model.Auth.LoginRequest;
import info.pithos.authn.model.Auth.LoginResponse;
import info.pithos.authn.model.AuthServiceGrpc;
import info.pithos.runtime.model.protocol.Context.RequestContext;
import info.pithos.service.container.core.LoginHandler;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.stub.StreamObserver;

import java.util.Optional;

public class AuthGrpcService extends AuthServiceGrpc.AuthServiceImplBase {

    /**
     * Populated by {@link GrpcMetadataInterceptor} before each call; retrieve via
     * {@code METADATA_KEY.get()} inside service methods to access request metadata.
     */
    public static final Context.Key<Metadata> METADATA_KEY = Context.key("grpc-request-metadata");

    private final LoginHandler loginHandler;

    public AuthGrpcService(LoginHandler loginHandler) {
        this.loginHandler = loginHandler;
    }

    @Override
    public void login(LoginRequest request, StreamObserver<LoginResponse> responseObserver) {
        Metadata metadata = Optional.ofNullable(METADATA_KEY.get()).orElseGet(Metadata::new);
        RequestContext context = GrpcRequestContextBuilder.build(metadata);
        GrpcSupport.respond(loginHandler.handleGrpc(request, context), responseObserver);
    }
}
