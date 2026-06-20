package com.example.provisioning;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class EnvironmentBaseImageCatalog {

    @ConfigProperty(name = "cluster-manager.environment-base-images.config-path")
    Optional<String> configPath;

    @ConfigProperty(name = "cluster-manager.environment-base-images.expose-image", defaultValue = "false")
    boolean exposeImage;

    @ConfigProperty(name = "cluster-manager.devcontainer.image")
    String fallbackImage;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public List<EnvironmentBaseImage> images() {
        Catalog catalog = loadCatalog();
        return catalog.images().stream()
                .map(image -> new EnvironmentBaseImage(
                        image.id(),
                        image.label(),
                        image.description(),
                        catalog.exposeImage() ? image.image() : null,
                        image.defaultImage()
                ))
                .toList();
    }

    public String resolveImage(String baseImageId) {
        Catalog catalog = loadCatalog();
        if (baseImageId == null || baseImageId.isBlank()) {
            return defaultImage(catalog).image();
        }
        return catalog.images().stream()
                .filter(image -> image.id().equals(baseImageId.trim()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Unsupported environment baseImage: " + baseImageId))
                .image();
    }

    private Catalog loadCatalog() {
        if (configPath.isPresent() && !configPath.get().isBlank()) {
            Path path = Path.of(configPath.get().trim());
            if (Files.exists(path)) {
                try {
                    CatalogFile file = yamlMapper.readValue(path.toFile(), CatalogFile.class);
                    List<ImageConfig> images = file.images() == null ? List.of() : file.images();
                    if (!images.isEmpty()) {
                        return validate(new Catalog(file.exposeImage() != null ? file.exposeImage() : exposeImage, images));
                    }
                } catch (IOException e) {
                    throw new BadRequestException("Failed to read environment base images config: " + path, e);
                }
            }
        }
        return new Catalog(exposeImage, List.of(new ImageConfig(
                "default",
                "Default",
                "Default DevContainer image.",
                fallbackImage,
                true
        )));
    }

    private Catalog validate(Catalog catalog) {
        long defaultCount = catalog.images().stream().filter(ImageConfig::defaultImage).count();
        if (defaultCount != 1) {
            throw new BadRequestException("Exactly one environment base image must be marked as default.");
        }
        for (ImageConfig image : catalog.images()) {
            if (isBlank(image.id()) || isBlank(image.label()) || isBlank(image.image())) {
                throw new BadRequestException("Environment base image id, label, and image are required.");
            }
        }
        return catalog;
    }

    private ImageConfig defaultImage(Catalog catalog) {
        return catalog.images().stream()
                .filter(ImageConfig::defaultImage)
                .findFirst()
                .orElseThrow(() -> new BadRequestException("No default environment base image is configured."));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record Catalog(boolean exposeImage, List<ImageConfig> images) {
    }

    record CatalogFile(Boolean exposeImage, List<ImageConfig> images) {
    }

    record ImageConfig(
            String id,
            String label,
            String description,
            String image,
            @JsonProperty("default")
            boolean defaultImage
    ) {
    }
}
