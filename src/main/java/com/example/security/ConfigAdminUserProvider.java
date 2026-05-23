package com.example.security;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class ConfigAdminUserProvider implements AdminUserProvider {

    private final Set<String> adminUserIds;

    public ConfigAdminUserProvider(@ConfigProperty(name = "cluster-manager.admin-user-ids", defaultValue = "") String adminUserIds) {
        this.adminUserIds = Arrays.stream(adminUserIds.split(","))
                .map(String::trim)
                .filter(userId -> !userId.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean isAdmin(String userId) {
        return adminUserIds.contains(userId);
    }
}
