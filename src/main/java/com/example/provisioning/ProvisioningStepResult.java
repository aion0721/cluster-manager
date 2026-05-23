package com.example.provisioning;

public record ProvisioningStepResult(
        String key,
        String namespace,
        String status,
        String message
) {
}
