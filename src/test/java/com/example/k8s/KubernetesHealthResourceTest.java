package com.example.k8s;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceListBuilder;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeListBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceList;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@QuarkusTest
class KubernetesHealthResourceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Test
    void returnsNamespaceNames() {
        MixedOperation<Namespace, NamespaceList, Resource<Namespace>> namespaces = mock(MixedOperation.class);
        when(kubernetesClient.namespaces()).thenReturn(namespaces);
        when(namespaces.list()).thenReturn(new NamespaceListBuilder()
                .addToItems(new NamespaceBuilder().withNewMetadata().withName("kube-system").endMetadata().build())
                .addToItems(new NamespaceBuilder().withNewMetadata().withName("default").endMetadata().build())
                .build());

        given()
                .when().get("/api/k8s/namespaces")
                .then()
                .statusCode(200)
                .body("", contains("default", "kube-system"));
    }

    @Test
    void returnsNodeNames() {
        NonNamespaceOperation<Node, NodeList, Resource<Node>> nodes = mock(NonNamespaceOperation.class);
        when(kubernetesClient.nodes()).thenReturn(nodes);
        when(nodes.list()).thenReturn(new NodeListBuilder()
                .addToItems(new NodeBuilder().withNewMetadata().withName("worker-2").endMetadata().build())
                .addToItems(new NodeBuilder().withNewMetadata().withName("worker-1").endMetadata().build())
                .build());

        given()
                .when().get("/api/k8s/nodes")
                .then()
                .statusCode(200)
                .body("", contains("worker-1", "worker-2"));
    }
}
