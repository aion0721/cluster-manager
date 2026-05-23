package com.example.me;

import com.example.provisioning.UserDetail;
import com.example.provisioning.UserProvisioningService;
import com.example.security.CurrentUser;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/me")
@Produces(MediaType.APPLICATION_JSON)
public class MeResource {

    @Inject
    CurrentUser currentUser;

    @Inject
    UserProvisioningService provisioningService;

    @GET
    public UserDetail me() {
        return provisioningService.getUser(currentUser.userId());
    }

    @GET
    @Path("/connection-guide")
    public ConnectionGuide connectionGuide() {
        return provisioningService.connectionGuide(currentUser.userId());
    }

    @POST
    @Path("/token")
    public ServiceAccountTokenResponse token() {
        return provisioningService.createServiceAccountToken(currentUser.userId());
    }

    @POST
    @Path("/kubectl-setup-command")
    public KubectlSetupCommandResponse kubectlSetupCommand() {
        return provisioningService.kubectlSetupCommand(currentUser.userId());
    }
}
