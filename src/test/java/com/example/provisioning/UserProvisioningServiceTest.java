package com.example.provisioning;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceList;
import io.fabric8.kubernetes.api.model.NamespaceListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProvisioningServiceTest {

    private UserProvisioningService service;
    private MixedOperation<Namespace, NamespaceList, Resource<Namespace>> namespaces;
    private Resource<Namespace> namespaceResource;

    @BeforeEach
    void setUp() {
        service = new UserProvisioningService();
        service.kubernetesClient = mock(KubernetesClient.class);
        service.namespacePrefix = "dev-";
        service.managedBy = "cluster-manager";
        service.labelPrefix = "cluster-manager.example.com";

        namespaces = mock(MixedOperation.class);
        namespaceResource = mock(Resource.class);
        when(service.kubernetesClient.namespaces()).thenReturn(namespaces);
        when(namespaces.withName("dev-alice")).thenReturn(namespaceResource);
    }

    @Test
    void listsManagedUserNamespaces() {
        when(namespaces.list()).thenReturn(new NamespaceListBuilder()
                .addToItems(new NamespaceBuilder()
                        .withNewMetadata()
                        .withName("dev-bob")
                        .withLabels(Map.of(
                                "app.kubernetes.io/managed-by", "cluster-manager",
                                "cluster-manager.example.com/resource-kind", "user-namespace",
                                "cluster-manager.example.com/user-id", "bob"
                        ))
                        .endMetadata()
                        .withNewStatus()
                        .withPhase("Active")
                        .endStatus()
                        .build())
                .addToItems(new NamespaceBuilder()
                        .withNewMetadata()
                        .withName("kube-system")
                        .withLabels(Map.of("kubernetes.io/metadata.name", "kube-system"))
                        .endMetadata()
                        .build())
                .addToItems(new NamespaceBuilder()
                        .withNewMetadata()
                        .withName("dev-alice")
                        .withLabels(Map.of(
                                "app.kubernetes.io/managed-by", "cluster-manager",
                                "cluster-manager.example.com/resource-kind", "user-namespace",
                                "cluster-manager.example.com/user-id", "alice"
                        ))
                        .withAnnotations(Map.of("cluster-manager.example.com/user-id", "alice"))
                        .endMetadata()
                        .withNewStatus()
                        .withPhase("Active")
                        .endStatus()
                        .build())
                .build());

        List<UserSummary> users = service.listUsers();

        assertEquals(2, users.size());
        assertEquals("alice", users.get(0).userId());
        assertEquals("dev-alice", users.get(0).namespace());
        assertEquals("Active", users.get(0).phase());
        assertEquals("bob", users.get(1).userId());
        assertEquals("dev-bob", users.get(1).namespace());
    }

    @Test
    void deletesManagedNamespace() {
        when(namespaceResource.get()).thenReturn(namespaceWithLabels(Map.of(
                "app.kubernetes.io/managed-by", "cluster-manager",
                "cluster-manager.example.com/user-id", "alice"
        )));
        doReturn(null).when(namespaceResource).delete();

        UserDeletionResult result = service.deleteUser("alice");

        assertEquals("alice", result.userId());
        assertEquals("dev-alice", result.namespace());
        assertEquals("DELETING", result.status());
        verify(namespaceResource).delete();
    }

    @Test
    void returnsNotFoundWhenNamespaceDoesNotExist() {
        when(namespaceResource.get()).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.deleteUser("alice"));

        verify(namespaceResource, never()).delete();
    }

    @Test
    void rejectsNamespaceWithoutManagedByLabel() {
        when(namespaceResource.get()).thenReturn(namespaceWithLabels(Map.of(
                "cluster-manager.example.com/user-id", "alice"
        )));

        assertThrows(ForbiddenException.class, () -> service.deleteUser("alice"));

        verify(namespaceResource, never()).delete();
    }

    @Test
    void rejectsNamespaceWithDifferentUserIdLabel() {
        when(namespaceResource.get()).thenReturn(namespaceWithLabels(Map.of(
                "app.kubernetes.io/managed-by", "cluster-manager",
                "cluster-manager.example.com/user-id", "bob"
        )));

        assertThrows(ForbiddenException.class, () -> service.deleteUser("alice"));

        verify(namespaceResource, never()).delete();
    }

    private Namespace namespaceWithLabels(Map<String, String> labels) {
        return new NamespaceBuilder()
                .withNewMetadata()
                .withName("dev-alice")
                .withLabels(labels)
                .endMetadata()
                .build();
    }
}
