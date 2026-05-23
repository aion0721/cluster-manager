package com.example.provisioning;

import java.util.Map;

public record UserSummary(
        String userId,
        String namespace,
        String phase,
        Map<String, String> labels,
        Map<String, String> annotations
) {
}
