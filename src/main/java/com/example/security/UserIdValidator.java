package com.example.security;

import jakarta.ws.rs.BadRequestException;

import java.util.regex.Pattern;

public final class UserIdValidator {

    private static final Pattern USER_ID_PATTERN = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

    private UserIdValidator() {
    }

    public static void validate(String userId) {
        if (userId == null || !USER_ID_PATTERN.matcher(userId).matches()) {
            throw new BadRequestException("userId must match [a-z0-9]([-a-z0-9]*[a-z0-9])?");
        }
    }
}
