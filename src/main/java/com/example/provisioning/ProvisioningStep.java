package com.example.provisioning;

public record ProvisioningStep(
        String key,
        String group,
        String label,
        String description,
        String method,
        String endpointTemplate,
        int order
) {
}
