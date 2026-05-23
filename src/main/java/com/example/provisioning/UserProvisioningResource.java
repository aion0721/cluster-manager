package com.example.provisioning;

import com.example.security.AdminRequired;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/users")
@Produces(MediaType.APPLICATION_JSON)
@AdminRequired
public class UserProvisioningResource {

    @Inject
    UserProvisioningService provisioningService;

    @GET
    public List<UserSummary> users() {
        return provisioningService.listUsers();
    }

    @GET
    @Path("/{userId}")
    public UserDetail user(@PathParam("userId") String userId) {
        return provisioningService.getUser(userId);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public UserProvisioningResult createUser(CreateUserRequest request) {
        if (request == null) {
            throw new BadRequestException("request body is required");
        }
        return provisioningService.provision(request.userId());
    }

    @POST
    @Path("/{userId}/namespace")
    public ProvisioningStepResult namespace(@PathParam("userId") String userId) {
        return provisioningService.ensureNamespace(userId);
    }

    @POST
    @Path("/{userId}/service-account")
    public ProvisioningStepResult serviceAccount(@PathParam("userId") String userId) {
        return provisioningService.ensureServiceAccount(userId);
    }

    @POST
    @Path("/{userId}/rbac")
    public ProvisioningStepResult rbac(@PathParam("userId") String userId) {
        return provisioningService.ensureRbac(userId);
    }

    @POST
    @Path("/{userId}/devcontainer")
    public ProvisioningStepResult devcontainer(@PathParam("userId") String userId) {
        return provisioningService.ensureDevcontainer(userId);
    }

    @POST
    @Path("/{userId}/service")
    public ProvisioningStepResult service(@PathParam("userId") String userId) {
        return provisioningService.ensureService(userId);
    }

    @POST
    @Path("/{userId}/reconcile")
    public UserProvisioningResult reconcile(@PathParam("userId") String userId) {
        return provisioningService.provision(userId);
    }

    @DELETE
    @Path("/{userId}")
    public UserDeletionResult deleteUser(@PathParam("userId") String userId) {
        return provisioningService.deleteUser(userId);
    }
}
