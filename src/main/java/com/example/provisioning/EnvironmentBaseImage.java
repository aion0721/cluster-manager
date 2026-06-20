package com.example.provisioning;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record EnvironmentBaseImage(
        String id,
        String label,
        String description,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String image,
        @JsonProperty("default")
        boolean defaultImage
) {
}
