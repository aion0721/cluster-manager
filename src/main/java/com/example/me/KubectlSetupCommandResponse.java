package com.example.me;

public record KubectlSetupCommandResponse(
        String namespace,
        String serviceAccount,
        String clusterName,
        String contextName,
        String credentialName,
        String expiresAt,
        String powershell,
        String bash
) {
}
