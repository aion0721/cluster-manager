package com.example.provisioning;

public record UserDetail(
        String userId,
        String namespace,
        String phase,
        String serviceAccount,
        String deployment,
        String service,
        String status,
        String createdAt
) {
}
