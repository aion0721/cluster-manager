package com.example.provisioning;

import com.example.me.ConnectionGuide;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class UserProvisioningResourceTest {

    @InjectMock
    UserProvisioningService provisioningService;

    @Test
    void listsUsersFromNamespaces() {
        when(provisioningService.listUsers())
                .thenReturn(List.of(new UserSummary(
                        "alice",
                        "dev-alice",
                        "Active",
                        java.util.Map.of("app.kubernetes.io/managed-by", "cluster-manager"),
                        java.util.Map.of()
                )));

        given()
                .header("X-User-Id", "alice")
                .when().get("/api/users")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1))
                .body("userId", contains("alice"))
                .body("namespace", contains("dev-alice"))
                .body("phase", contains("Active"));

        verify(provisioningService).listUsers();
    }

    @Test
    void getsUserDetail() {
        when(provisioningService.getUser("alice"))
                .thenReturn(new UserDetail(
                        "alice",
                        "dev-alice",
                        "Active",
                        "dev-user",
                        "devcontainer",
                        "devcontainer",
                        "READY",
                        "2026-05-23T09:00:00Z"
                ));

        given()
                .header("X-User-Id", "alice")
                .when().get("/api/users/alice")
                .then()
                .statusCode(200)
                .body("userId", equalTo("alice"))
                .body("namespace", equalTo("dev-alice"))
                .body("phase", equalTo("Active"))
                .body("serviceAccount", equalTo("dev-user"))
                .body("deployment", equalTo("devcontainer"))
                .body("service", equalTo("devcontainer"))
                .body("status", equalTo("READY"))
                .body("createdAt", equalTo("2026-05-23T09:00:00Z"));

        verify(provisioningService).getUser("alice");
    }

    @Test
    void createsUserResourcesWithoutEnvironment() {
        List<ProvisioningStepResult> steps = List.of(
                new ProvisioningStepResult("namespace", "dev-alice", "completed", "Namespace created or updated."),
                new ProvisioningStepResult("serviceAccount", "dev-alice", "completed", "ServiceAccount created or updated."),
                new ProvisioningStepResult("rbac", "dev-alice", "completed", "RBAC created or updated.")
        );
        when(provisioningService.provisionUser("alice", "Alice Doe"))
                .thenReturn(new UserProvisioningResult("alice", "dev-alice", steps));

        given()
                .header("X-User-Id", "alice")
                .contentType("application/json")
                .body("{\"userId\":\"alice\",\"displayName\":\"Alice Doe\"}")
                .when().post("/api/users")
                .then()
                .statusCode(200)
                .body("userId", equalTo("alice"))
                .body("namespace", equalTo("dev-alice"))
                .body("steps.size()", equalTo(3))
                .body("steps[0].key", equalTo("namespace"));

        verify(provisioningService).provisionUser("alice", "Alice Doe");
    }

    @Test
    void createsNamespaceStep() {
        when(provisioningService.ensureNamespace("alice"))
                .thenReturn(new ProvisioningStepResult("namespace", "dev-alice", "completed", "Namespace created or updated."));

        given()
                .header("X-User-Id", "alice")
                .when().post("/api/users/alice/namespace")
                .then()
                .statusCode(200)
                .body("key", equalTo("namespace"))
                .body("namespace", equalTo("dev-alice"))
                .body("status", equalTo("completed"));

        verify(provisioningService).ensureNamespace("alice");
    }

    @Test
    void createsServiceAccountStep() {
        when(provisioningService.ensureServiceAccount("alice"))
                .thenReturn(new ProvisioningStepResult("serviceAccount", "dev-alice", "completed", "ServiceAccount created or updated."));

        given()
                .header("X-User-Id", "alice")
                .when().post("/api/users/alice/service-account")
                .then()
                .statusCode(200)
                .body("key", equalTo("serviceAccount"))
                .body("namespace", equalTo("dev-alice"))
                .body("status", equalTo("completed"));

        verify(provisioningService).ensureServiceAccount("alice");
    }

    @Test
    void createsRbacStep() {
        when(provisioningService.ensureRbac("alice"))
                .thenReturn(new ProvisioningStepResult("rbac", "dev-alice", "completed", "RBAC created or updated."));

        given()
                .header("X-User-Id", "alice")
                .when().post("/api/users/alice/rbac")
                .then()
                .statusCode(200)
                .body("key", equalTo("rbac"))
                .body("namespace", equalTo("dev-alice"))
                .body("status", equalTo("completed"));

        verify(provisioningService).ensureRbac("alice");
    }

    @Test
    void createsDevcontainerStep() {
        when(provisioningService.ensureDevcontainer("alice"))
                .thenReturn(new ProvisioningStepResult("devcontainer", "dev-alice", "completed", "DevContainer Deployment created or updated."));

        given()
                .header("X-User-Id", "alice")
                .when().post("/api/users/alice/devcontainer")
                .then()
                .statusCode(200)
                .body("key", equalTo("devcontainer"))
                .body("namespace", equalTo("dev-alice"))
                .body("status", equalTo("completed"));

        verify(provisioningService).ensureDevcontainer("alice");
    }

    @Test
    void createsServiceStep() {
        when(provisioningService.ensureService("alice"))
                .thenReturn(new ProvisioningStepResult("service", "dev-alice", "completed", "DevContainer Service created or updated."));

        given()
                .header("X-User-Id", "alice")
                .when().post("/api/users/alice/service")
                .then()
                .statusCode(200)
                .body("key", equalTo("service"))
                .body("namespace", equalTo("dev-alice"))
                .body("status", equalTo("completed"));

        verify(provisioningService).ensureService("alice");
    }

    @Test
    void reconcilesUserByRunningAllSteps() {
        when(provisioningService.provision("alice"))
                .thenReturn(new UserProvisioningResult("alice", "dev-alice", List.of(
                        new ProvisioningStepResult("namespace", "dev-alice", "completed", "Namespace created or updated.")
                )));

        given()
                .header("X-User-Id", "alice")
                .when().post("/api/users/alice/reconcile")
                .then()
                .statusCode(200)
                .body("userId", equalTo("alice"))
                .body("namespace", equalTo("dev-alice"));

        verify(provisioningService).provision("alice");
    }

    @Test
    void createsEnvironmentForExistingUser() {
        when(provisioningService.provisionEnvironment("alice"))
                .thenReturn(new UserProvisioningResult("alice", "dev-alice", List.of(
                        new ProvisioningStepResult("devcontainer", "dev-alice", "completed", "DevContainer Deployment created or updated."),
                        new ProvisioningStepResult("service", "dev-alice", "completed", "DevContainer Service created or updated.")
                )));

        given()
                .header("X-User-Id", "alice")
                .when().post("/api/users/alice/environment")
                .then()
                .statusCode(200)
                .body("userId", equalTo("alice"))
                .body("steps.size()", equalTo(2))
                .body("steps[0].key", equalTo("devcontainer"));

        verify(provisioningService).provisionEnvironment("alice");
    }

    @Test
    void deletesEnvironmentForExistingUser() {
        when(provisioningService.deleteEnvironment("alice"))
                .thenReturn(new UserDeletionResult("alice", "dev-alice", "DELETED"));

        given()
                .header("X-User-Id", "alice")
                .when().delete("/api/users/alice/environment")
                .then()
                .statusCode(200)
                .body("userId", equalTo("alice"))
                .body("namespace", equalTo("dev-alice"))
                .body("status", equalTo("DELETED"));

        verify(provisioningService).deleteEnvironment("alice");
    }

    @Test
    void returnsPortForwardCommandGuide() {
        when(provisioningService.connectionGuide("alice"))
                .thenReturn(new ConnectionGuide(
                        "dev-alice",
                        "dev-user",
                        "kubectl -n dev-alice port-forward svc/devcontainer 2222:22",
                        "devcontainer",
                        "ClusterIP",
                        22,
                        null,
                        null,
                        null
                ));

        given()
                .header("X-User-Id", "alice")
                .when().get("/api/users/alice/port-forward-command")
                .then()
                .statusCode(200)
                .body("namespace", equalTo("dev-alice"))
                .body("portForwardCommand", equalTo("kubectl -n dev-alice port-forward svc/devcontainer 2222:22"));

        verify(provisioningService).connectionGuide("alice");
    }

    @Test
    void deletesUserNamespace() {
        when(provisioningService.deleteUser("alice"))
                .thenReturn(new UserDeletionResult("alice", "dev-alice", "DELETING"));

        given()
                .header("X-User-Id", "alice")
                .when().delete("/api/users/alice")
                .then()
                .statusCode(200)
                .body("userId", equalTo("alice"))
                .body("namespace", equalTo("dev-alice"))
                .body("status", equalTo("DELETING"));

        verify(provisioningService).deleteUser("alice");
    }

    @Test
    void rejectsNonAdminUser() {
        given()
                .header("X-User-Id", "bob")
                .when().get("/api/users")
                .then()
                .statusCode(403);
    }

    @Test
    void rejectsMissingAdminUserHeader() {
        given()
                .when().get("/api/users")
                .then()
                .statusCode(400);
    }
}
