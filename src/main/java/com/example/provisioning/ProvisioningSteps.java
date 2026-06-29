package com.example.provisioning;

import java.util.List;

public final class ProvisioningSteps {

    private static final ProvisioningStep NAMESPACE_STEP = new ProvisioningStep(
            "namespace",
            "users",
            "Namespace",
            "Create or update the user's Kubernetes Namespace.",
            "POST",
            "/api/users/{userId}/namespace",
            1
    );

    private static final List<ProvisioningStep> NAMESPACE_STEPS = List.of(
            NAMESPACE_STEP,
            serviceAccountStep(2),
            rbacStep(3),
            devcontainerStep(4)
    );

    private static final List<ProvisioningStep> CONTAINER_ONLY_STEPS = List.of(
            serviceAccountStep(1),
            rbacStep(2),
            devcontainerStep(3)
    );

    private ProvisioningSteps() {
    }

    public static List<ProvisioningStep> steps(ProvisioningMode mode) {
        return mode == ProvisioningMode.NAMESPACE ? NAMESPACE_STEPS : CONTAINER_ONLY_STEPS;
    }

    private static ProvisioningStep serviceAccountStep(int order) {
        return new ProvisioningStep(
                "serviceAccount",
                "users",
                "ServiceAccount",
                "Create or update the ServiceAccount used by the user's DevContainer.",
                "POST",
                "/api/users/{userId}/service-account",
                order
        );
    }

    private static ProvisioningStep rbacStep(int order) {
        return new ProvisioningStep(
                "rbac",
                "users",
                "RBAC",
                "Create or update namespace-scoped RBAC for the user's ServiceAccount.",
                "POST",
                "/api/users/{userId}/rbac",
                order
        );
    }

    private static ProvisioningStep devcontainerStep(int order) {
        return new ProvisioningStep(
                "devcontainer",
                "pods",
                "DevContainer",
                "Create or update the user's DevContainer StatefulSet.",
                "POST",
                "/api/users/{userId}/devcontainer",
                order
        );
    }
}
