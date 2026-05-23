package com.example.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigAdminUserProviderTest {

    @Test
    void parsesCommaSeparatedAdminUserIds() {
        ConfigAdminUserProvider provider = new ConfigAdminUserProvider("koba, tanaka,,sato ");

        assertTrue(provider.isAdmin("koba"));
        assertTrue(provider.isAdmin("tanaka"));
        assertTrue(provider.isAdmin("sato"));
        assertFalse(provider.isAdmin("bob"));
    }
}
