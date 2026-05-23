package com.example.provisioning;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/provisioning-steps")
@Produces(MediaType.APPLICATION_JSON)
public class ProvisioningStepsResource {

    @GET
    public List<ProvisioningStep> steps() {
        return ProvisioningSteps.steps();
    }
}
