package com.example.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@RequestScoped
public class CurrentUser {

    @Context
    HttpHeaders httpHeaders;

    @Inject
    SecurityIdentity securityIdentity;

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
        String principalName = securityIdentity.getPrincipal().getName();
        if (principalName == null || principalName.isBlank()) {
            throw new BadRequestException("authenticated principal name is required");
        }
        return principalName.trim();
    }

    @ApplicationScoped
    static class CurrentUserConfig {

        private final AuthMode authMode;

        CurrentUserConfig(@ConfigProperty(name = "cluster-manager.auth.mode", defaultValue = "simple") String authMode) {
            this.authMode = AuthMode.valueOf(authMode.trim().replace('-', '_').toUpperCase());
        }

        AuthMode authMode() {
            return authMode;
        }
    }
}
