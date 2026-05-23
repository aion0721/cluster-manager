package com.example.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Comparator;
import java.util.List;

@Path("/api/k8s")
@Produces(MediaType.APPLICATION_JSON)
public class KubernetesHealthResource {

    @Inject
    KubernetesClient kubernetesClient;

    @GET
    @Path("/namespaces")
    public List<String> namespaces() {
        return kubernetesClient.namespaces()
                .list()
                .getItems()
                .stream()
                .map(namespace -> namespace.getMetadata().getName())
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    @GET
    @Path("/nodes")
    public List<String> nodes() {
        return kubernetesClient.nodes()
                .list()
                .getItems()
                .stream()
                .map(node -> node.getMetadata().getName())
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
