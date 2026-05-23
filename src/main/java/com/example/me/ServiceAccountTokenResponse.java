package com.example.me;

public record ServiceAccountTokenResponse(
        String token,
        String namespace,
        String serviceAccount,
        String expiresAt
) {
}
