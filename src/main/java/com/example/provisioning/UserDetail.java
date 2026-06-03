package com.example.provisioning;

public record UserDetail(
        String userId,
        String namespace,
        String phase,
        String serviceAccount,
        String deployment,
        String service,
        String status,
        String createdAt,
        String mode,
        DevcontainerEndpoint devcontainerEndpoint
) {
    public UserDetail(
            String userId,
            String namespace,
            String phase,
            String serviceAccount,
            String deployment,
            String service,
            String status,
            String createdAt
    ) {
        this(userId, namespace, phase, serviceAccount, deployment, service, status, createdAt, null, null);
    }
}
