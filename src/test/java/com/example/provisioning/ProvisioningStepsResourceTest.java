package com.example.provisioning;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ProvisioningStepsResourceTest {

    @Test
    void returnsProvisioningStepsInOrder() {
        given()
                .when().get("/api/provisioning-steps")
                .then()
                .statusCode(200)
                .body("size()", equalTo(5))
                .body("key", contains(
                        "namespace",
                        "serviceAccount",
                        "rbac",
                        "devcontainer",
                        "service"
                ))
                .body("method", contains("POST", "POST", "POST", "POST", "POST"))
                .body("endpointTemplate", contains(
                        "/api/users/{userId}/namespace",
                        "/api/users/{userId}/service-account",
                        "/api/users/{userId}/rbac",
                        "/api/users/{userId}/devcontainer",
                        "/api/users/{userId}/service"
                ))
                .body("order", contains(1, 2, 3, 4, 5));
    }
}
