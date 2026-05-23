package com.example.provisioning;

import java.util.List;

public final class ProvisioningSteps {

    private static final List<ProvisioningStep> STEPS = List.of(
            new ProvisioningStep(
                    "namespace",
                    "Namespace",
                    "Create or update the user's Kubernetes Namespace.",
                    "POST",
                    "/api/users/{userId}/namespace",
                    1
            ),
            new ProvisioningStep(
                    "serviceAccount",
                    "ServiceAccount",
                    "Create or update the ServiceAccount used inside the user's Namespace.",
                    "POST",
                    "/api/users/{userId}/service-account",
                    2
            ),
            new ProvisioningStep(
                    "rbac",
                    "RBAC",
                    "Create or update namespace-scoped RBAC for the user's ServiceAccount.",
                    "POST",
                    "/api/users/{userId}/rbac",
                    3
            ),
            new ProvisioningStep(
                    "devcontainer",
                    "DevContainer",
                    "Create or update the user's DevContainer Deployment.",
                    "POST",
                    "/api/users/{userId}/devcontainer",
                    4
            ),
            new ProvisioningStep(
                    "service",
                    "Service",
                    "Create or update the Service for the user's DevContainer.",
                    "POST",
                    "/api/users/{userId}/service",
                    5
            )
    );

    private ProvisioningSteps() {
    }

    public static List<ProvisioningStep> steps() {
        return STEPS;
    }
}
