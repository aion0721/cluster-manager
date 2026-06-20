package com.example.provisioning;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(ProvisioningStepsResourceContainerOnlyTest.ContainerOnlyProfile.class)
class ProvisioningStepsResourceContainerOnlyTest {

    @Test
    void omitsNamespaceStepInContainerOnlyMode() {
        given()
                .header("X-User-Id", "alice")
                .when().get("/api/provisioning-steps")
                .then()
                .statusCode(200)
                .body("size()", equalTo(4))
                .body("key", contains(
                        "serviceAccount",
                        "rbac",
                        "devcontainer",
                        "service"
                ))
                .body("group", contains(
                        "users",
                        "users",
                        "pods",
                        "pods"
                ))
                .body("key", not(hasItem("namespace")))
                .body("order", contains(1, 2, 3, 4));
    }

    public static class ContainerOnlyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("cluster-manager.provisioning.mode", "container-only");
        }
    }
}
