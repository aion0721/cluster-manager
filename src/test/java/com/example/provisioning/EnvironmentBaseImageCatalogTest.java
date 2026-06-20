package com.example.provisioning;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvironmentBaseImageCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void readsBaseImagesFromMountedConfigFile() throws Exception {
        Path config = tempDir.resolve("images.yaml");
        Files.writeString(config, """
                exposeImage: false
                images:
                  - id: ubuntu
                    label: Ubuntu
                    description: Ubuntu base image
                    image: ubuntu:24.04
                    default: true
                  - id: node-dev
                    label: Node.js
                    description: Node.js development image
                    image: node:22-bookworm
                    default: false
                """);
        EnvironmentBaseImageCatalog catalog = catalog(config);

        assertEquals(2, catalog.images().size());
        assertEquals("ubuntu", catalog.images().get(0).id());
        assertNull(catalog.images().get(0).image());
        assertEquals("node:22-bookworm", catalog.resolveImage("node-dev"));
        assertEquals("ubuntu:24.04", catalog.resolveImage(null));
    }

    @Test
    void rejectsUnknownBaseImageId() throws Exception {
        Path config = tempDir.resolve("images.yaml");
        Files.writeString(config, """
                images:
                  - id: ubuntu
                    label: Ubuntu
                    image: ubuntu:24.04
                    default: true
                """);
        EnvironmentBaseImageCatalog catalog = catalog(config);

        assertThrows(BadRequestException.class, () -> catalog.resolveImage("missing"));
    }

    private EnvironmentBaseImageCatalog catalog(Path config) {
        EnvironmentBaseImageCatalog catalog = new EnvironmentBaseImageCatalog();
        catalog.configPath = Optional.of(config.toString());
        catalog.exposeImage = false;
        catalog.fallbackImage = "ubuntu:24.04";
        return catalog;
    }
}
