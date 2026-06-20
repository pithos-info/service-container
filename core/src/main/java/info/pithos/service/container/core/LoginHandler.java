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

import info.pithos.auth.OAuthClient;
import info.pithos.auth.model.Auth.LoginRequest;
import info.pithos.auth.model.Auth.LoginResponse;
import info.pithos.auth.model.TokenResponse;
import info.pithos.runtime.core.context.ErrorCode;
import info.pithos.runtime.core.context.ServiceException;
import info.pithos.runtime.model.protocol.Context.RequestContext;
import io.smallrye.mutiny.Uni;

public class LoginHandler extends BaseServiceHandler<LoginRequest, LoginResponse> {

    public LoginHandler(OAuthClient oAuthClient) {
        super(oAuthClient);
    }

    @Override
    protected boolean requiresAuthentication() {
        return false;
    }

    @Override
    public Uni<LoginResponse> handle(LoginRequest request, RequestContext context) {
        String idToken = request.getIdToken();
        if (!idToken.isBlank()) {
            return Uni.createFrom()
                    .<TokenResponse>completionStage(() -> oAuthClient().loginWithIdToken(context, idToken))
                    .map(LoginHandler::toLoginResponse);
        }
        String username = request.getUsername();
        String password = request.getPassword();
        if (username.isBlank() || password.isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "username, password, or idToken is required");
        }
        return Uni.createFrom()
                .<TokenResponse>completionStage(() -> oAuthClient().login(context, username, password))
                .map(LoginHandler::toLoginResponse);
    }

    private static LoginResponse toLoginResponse(TokenResponse token) {
        return LoginResponse.newBuilder()
                .setAccessToken(token.accessToken() != null ? token.accessToken() : "")
                .setRefreshToken(token.refreshToken() != null ? token.refreshToken() : "")
                .setExpiresIn(token.expiresIn())
                .setTokenType(token.tokenType() != null ? token.tokenType() : "Bearer")
                .setScope(token.scope() != null ? token.scope() : "")
                .build();
    }
}
