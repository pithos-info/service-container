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
    public Uni<LoginResponse> handle(LoginRequest request, RequestContext context) {
        String username = request.getUsername();
        String password = request.getPassword();
        if (username.isBlank() || password.isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "username and password are required");
        }
        return Uni.createFrom()
                .<TokenResponse>completionStage(() -> oAuthClient().login(context, username, password))
                .map(token -> LoginResponse.newBuilder()
                        .setAccessToken(token.accessToken() != null ? token.accessToken() : "")
                        .setRefreshToken(token.refreshToken() != null ? token.refreshToken() : "")
                        .setExpiresIn(token.expiresIn())
                        .setTokenType(token.tokenType() != null ? token.tokenType() : "Bearer")
                        .setScope(token.scope() != null ? token.scope() : "")
                        .build());
    }
}
