package com.example.provisioning;

public record ProvisioningStep(
        String key,
        String label,
        String description,
        String method,
        String endpointTemplate,
        int order
) {
}
