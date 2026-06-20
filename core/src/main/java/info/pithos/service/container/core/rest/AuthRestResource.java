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

package info.pithos.service.container.core.rest;

import info.pithos.auth.model.Auth.LoginRequest;
import info.pithos.auth.model.Auth.LoginResponse;
import info.pithos.service.container.core.LoginHandler;
import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

@Path("/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AuthRestResource {

    private final LoginHandler loginHandler;

    public AuthRestResource(LoginHandler loginHandler) {
        this.loginHandler = loginHandler;
    }

    @POST
    @Path("/login")
    public Uni<LoginResponse> login(LoginRequest request, @Context HttpHeaders headers) {
        return loginHandler.handleHttp(request, toMultiMap(headers));
    }

    private static MultiMap toMultiMap(HttpHeaders httpHeaders) {
        MultiMap map = MultiMap.caseInsensitiveMultiMap();
        httpHeaders.getRequestHeaders().forEach(
            (name, values) -> values.forEach(value -> map.add(name, value))
        );
        return map;
    }
}
