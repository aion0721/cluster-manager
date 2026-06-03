package com.example.me;

public record ConnectionGuide(
        String namespace,
        String serviceAccount,
        String portForwardCommand,
        String service,
        String serviceType,
        Integer servicePort,
        Integer nodePort,
        String sshHost,
        String sshCommand
) {
    public ConnectionGuide(
            String namespace,
            String serviceAccount,
            String portForwardCommand
    ) {
        this(namespace, serviceAccount, portForwardCommand, null, null, null, null, null, null);
    }
}
