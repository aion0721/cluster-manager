package com.example.provisioning;

import com.example.security.AdminRequired;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/provisioning-steps")
@Produces(MediaType.APPLICATION_JSON)
@AdminRequired
public class ProvisioningStepsResource {

    @ConfigProperty(name = "cluster-manager.provisioning.mode")
    String provisioningMode;

    @GET
    public List<ProvisioningStep> steps() {
        return ProvisioningSteps.steps(ProvisioningMode.fromConfig(provisioningMode));
    }
}
