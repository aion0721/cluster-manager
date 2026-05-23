package com.example.me;

import com.example.provisioning.UserDetail;
import com.example.provisioning.UserProvisioningService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class MeResourceTest {

    @InjectMock
    UserProvisioningService provisioningService;

    @Test
    void returnsCurrentUserDetail() {
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
                .when().get("/api/me")
                .then()
                .statusCode(200)
                .body("userId", equalTo("alice"))
                .body("namespace", equalTo("dev-alice"))
                .body("status", equalTo("READY"));

        verify(provisioningService).getUser("alice");
    }

    @Test
    void returnsConnectionGuide() {
        when(provisioningService.connectionGuide("alice"))
                .thenReturn(new ConnectionGuide(
                        "dev-alice",
                        "dev-user",
                        "kubectl -n dev-alice port-forward svc/devcontainer 2222:22"
                ));

        given()
                .header("X-User-Id", "alice")
                .when().get("/api/me/connection-guide")
                .then()
                .statusCode(200)
                .body("namespace", equalTo("dev-alice"))
                .body("serviceAccount", equalTo("dev-user"))
                .body("portForwardCommand", equalTo("kubectl -n dev-alice port-forward svc/devcontainer 2222:22"));

        verify(provisioningService).connectionGuide("alice");
    }

    @Test
    void createsShortLivedServiceAccountToken() {
        when(provisioningService.createServiceAccountToken("alice"))
                .thenReturn(new ServiceAccountTokenResponse(
                        "token-value",
                        "dev-alice",
                        "dev-user",
                        "2026-05-23T10:00:00Z"
                ));

        given()
                .header("X-User-Id", "alice")
                .when().post("/api/me/token")
                .then()
                .statusCode(200)
                .body("token", equalTo("token-value"))
                .body("namespace", equalTo("dev-alice"))
                .body("serviceAccount", equalTo("dev-user"))
                .body("expiresAt", equalTo("2026-05-23T10:00:00Z"));

        verify(provisioningService).createServiceAccountToken("alice");
    }

    @Test
    void returnsKubectlSetupCommand() {
        when(provisioningService.kubectlSetupCommand("alice"))
                .thenReturn(new KubectlSetupCommandResponse(
                        "dev-alice",
                        "dev-user",
                        "k3s",
                        "dev-alice@k3s",
                        "dev-alice-user",
                        "2026-05-23T10:00:00Z",
                        "kubectl config set-credentials dev-alice-user --token=\"token-value\"",
                        "kubectl config set-credentials dev-alice-user --token='token-value'"
                ));

        given()
                .header("X-User-Id", "alice")
                .when().post("/api/me/kubectl-setup-command")
                .then()
                .statusCode(200)
                .body("namespace", equalTo("dev-alice"))
                .body("serviceAccount", equalTo("dev-user"))
                .body("clusterName", equalTo("k3s"))
                .body("contextName", equalTo("dev-alice@k3s"))
                .body("credentialName", equalTo("dev-alice-user"))
                .body("expiresAt", equalTo("2026-05-23T10:00:00Z"))
                .body("powershell", containsString("--token=\"token-value\""))
                .body("bash", containsString("--token='token-value'"));

        verify(provisioningService).kubectlSetupCommand("alice");
    }

    @Test
    void rejectsMissingUserHeaderForKubectlSetupCommand() {
        given()
                .when().post("/api/me/kubectl-setup-command")
                .then()
                .statusCode(400);
    }

    @Test
    void rejectsInvalidUserHeaderForKubectlSetupCommand() {
        given()
                .header("X-User-Id", "Alice")
                .when().post("/api/me/kubectl-setup-command")
                .then()
                .statusCode(400);
    }

    @Test
    void returnsNotFoundForKubectlSetupCommandWhenNamespaceIsMissing() {
        when(provisioningService.kubectlSetupCommand("alice"))
                .thenThrow(new NotFoundException("Namespace not found: dev-alice"));

        given()
                .header("X-User-Id", "alice")
                .when().post("/api/me/kubectl-setup-command")
                .then()
                .statusCode(404);
    }

    @Test
    void returnsNotFoundForKubectlSetupCommandWhenServiceAccountIsMissing() {
        when(provisioningService.kubectlSetupCommand("alice"))
                .thenThrow(new NotFoundException("ServiceAccount not found: dev-alice/dev-user"));

        given()
                .header("X-User-Id", "alice")
                .when().post("/api/me/kubectl-setup-command")
                .then()
                .statusCode(404);
    }

    @Test
    void rejectsMissingUserHeader() {
        given()
                .when().get("/api/me")
                .then()
                .statusCode(400);
    }

    @Test
    void rejectsInvalidUserHeader() {
        given()
                .header("X-User-Id", "Alice")
                .when().get("/api/me")
                .then()
                .statusCode(400);
    }
}
