package com.example.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

@RequestScoped
public class CurrentUser {

    @Context
    HttpHeaders httpHeaders;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    Instance<JsonWebToken> jsonWebToken;

    @Inject
    CurrentUserConfig config;

    public String userId() {
        String userId = switch (config.authMode()) {
            case SIMPLE -> simpleUserId();
            case KEYCLOAK -> keycloakUserId();
        };
        UserIdValidator.validate(userId);
        return userId;
    }

    private String simpleUserId() {
        String userId = httpHeaders.getHeaderString("X-User-Id");
        if (userId == null || userId.isBlank()) {
            throw new BadRequestException("X-User-Id header is required");
        }
        return userId.trim();
    }

    private String keycloakUserId() {
        if (securityIdentity == null || securityIdentity.isAnonymous()) {
            throw new NotAuthorizedException("Bearer token is required");
        }
        String claimUserId = claimUserId();
        if (claimUserId != null && !claimUserId.isBlank()) {
            return claimUserId.trim();
        }
        String principalName = securityIdentity.getPrincipal().getName();
        if (principalName == null || principalName.isBlank()) {
            throw new BadRequestException("authenticated principal name is required");
        }
        return principalName.trim();
    }

    private String claimUserId() {
        if (jsonWebToken.isResolvable()) {
            Object claim = jsonWebToken.get().getClaim(config.userIdClaim());
            if (claim instanceof String stringClaim) {
                return stringClaim;
            }
        }

        Object attribute = securityIdentity.getAttribute(config.userIdClaim());
        if (attribute instanceof String stringAttribute) {
            return stringAttribute;
        }

        return null;
    }

    @ApplicationScoped
    static class CurrentUserConfig {

        private final AuthMode authMode;
        private final String userIdClaim;

        CurrentUserConfig(
                @ConfigProperty(name = "cluster-manager.auth.mode", defaultValue = "simple") String authMode,
                @ConfigProperty(name = "cluster-manager.auth.user-id-claim", defaultValue = "preferred_username") String userIdClaim
        ) {
            this.authMode = AuthMode.valueOf(authMode.trim().replace('-', '_').toUpperCase());
            this.userIdClaim = userIdClaim.trim();
        }

        AuthMode authMode() {
            return authMode;
        }

        String userIdClaim() {
            return userIdClaim;
        }
    }
}
