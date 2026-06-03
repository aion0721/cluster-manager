package com.example.provisioning;

import jakarta.ws.rs.BadRequestException;

public enum ProvisioningMode {
    NAMESPACE("namespace"),
    CONTAINER_ONLY("container-only");

    private final String configValue;

    ProvisioningMode(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static ProvisioningMode fromConfig(String value) {
        for (ProvisioningMode mode : values()) {
            if (mode.configValue.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new BadRequestException("Unsupported provisioning mode: " + value);
    }
}
