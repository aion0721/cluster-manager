package com.example.provisioning;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class EnvironmentBaseImageResourceTest {

    @Test
    void returnsDefaultBaseImageWithoutExposingImage() {
        given()
                .header("X-User-Id", "alice")
                .when().get("/api/environment-base-images")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].id", equalTo("default"))
                .body("[0].label", equalTo("Default"))
                .body("[0].image", nullValue())
                .body("[0].default", equalTo(true));
    }

    @Test
    void rejectsNonAdminUser() {
        given()
                .header("X-User-Id", "bob")
                .when().get("/api/environment-base-images")
                .then()
                .statusCode(403);
    }
}
