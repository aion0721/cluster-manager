package com.example.security;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

@Provider
@AdminRequired
@Priority(Priorities.AUTHORIZATION)
public class AdminRequiredFilter implements ContainerRequestFilter {

    @Inject
    CurrentUser currentUser;

    @Inject
    AdminUserProvider adminUserProvider;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String userId = currentUser.userId();
        if (!adminUserProvider.isAdmin(userId)) {
            throw new ForbiddenException("admin user is required");
        }
    }
}
