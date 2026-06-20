package com.example.provisioning;

import com.example.security.AdminRequired;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/environment-base-images")
@Produces(MediaType.APPLICATION_JSON)
@AdminRequired
public class EnvironmentBaseImageResource {

    @Inject
    EnvironmentBaseImageCatalog catalog;

    @GET
    public List<EnvironmentBaseImage> images() {
        return catalog.images();
    }
}
