package com.example.me;

import com.example.provisioning.UserDetail;
import com.example.provisioning.UserProvisioningService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(MeResourceKeycloakAuthTest.KeycloakAuthProfile.class)
class MeResourceKeycloakAuthTest {

    @InjectMock
    UserProvisioningService provisioningService;

    @Test
    @TestSecurity(user = "alice")
    void returnsCurrentUserFromSecurityIdentityInKeycloakMode() {
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
                .when().get("/api/me")
                .then()
                .statusCode(200)
                .body("userId", equalTo("alice"))
                .body("namespace", equalTo("dev-alice"));

        verify(provisioningService).getUser("alice");
    }

    @Test
    void rejectsAnonymousUserInKeycloakMode() {
        given()
                .when().get("/api/me")
                .then()
                .statusCode(401);
    }

    public static class KeycloakAuthProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "cluster-manager.auth.mode", "keycloak",
                    "quarkus.oidc.enabled", "false"
            );
        }
    }
}
