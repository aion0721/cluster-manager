package com.example.provisioning;

import java.util.Map;

public record UserSummary(
        String userId,
        String displayName,
        String namespace,
        String phase,
        String serviceAccount,
        String deployment,
        String service,
        String status,
        String createdAt,
        String mode,
        DevcontainerEndpoint devcontainerEndpoint,
        Map<String, String> labels,
        Map<String, String> annotations
) {
    public UserSummary(
            String userId,
            String namespace,
            String phase,
            Map<String, String> labels,
            Map<String, String> annotations
    ) {
        this(userId, null, namespace, phase, null, null, null, null, null, null, null, labels, annotations);
    }
}
