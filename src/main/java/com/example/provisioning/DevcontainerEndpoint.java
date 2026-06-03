package com.example.provisioning;

public record DevcontainerEndpoint(
        String service,
        String serviceType,
        Integer servicePort,
        Integer nodePort,
        String sshHost,
        String sshCommand
) {
}
