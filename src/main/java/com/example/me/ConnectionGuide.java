package com.example.me;

public record ConnectionGuide(
        String namespace,
        String serviceAccount,
        String portForwardCommand
) {
}
