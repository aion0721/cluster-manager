package com.example.provisioning;

import java.util.List;

public record UserProvisioningResult(
        String userId,
        String namespace,
        List<ProvisioningStepResult> steps
) {
}
