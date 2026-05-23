package com.example.security;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;

@RequestScoped
public class CurrentUser {

    @Context
    HttpHeaders httpHeaders;

    public String userId() {
        String userId = httpHeaders.getHeaderString("X-User-Id");
        if (userId == null || userId.isBlank()) {
            throw new BadRequestException("X-User-Id header is required");
        }
        String normalizedUserId = userId.trim();
        UserIdValidator.validate(normalizedUserId);
        return normalizedUserId;
    }
}
