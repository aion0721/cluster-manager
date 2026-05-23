package com.example.provisioning;

public record UserDeletionResult(
        String userId,
        String namespace,
        String status
) {
}
