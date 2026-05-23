package com.example.provisioning;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder;
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class UserProvisioningService {

    private static final Pattern USER_ID_PATTERN = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

    @Inject
    KubernetesClient kubernetesClient;

    @ConfigProperty(name = "cluster-manager.namespace-prefix")
    String namespacePrefix;

    @ConfigProperty(name = "cluster-manager.managed-by")
    String managedBy;

    @ConfigProperty(name = "cluster-manager.label-prefix")
    String labelPrefix;

    @ConfigProperty(name = "cluster-manager.devcontainer.image")
    String devcontainerImage;

    @ConfigProperty(name = "cluster-manager.devcontainer.service-account")
    String serviceAccountName;

    @ConfigProperty(name = "cluster-manager.devcontainer.deployment-name")
    String deploymentName;

    @ConfigProperty(name = "cluster-manager.devcontainer.service-name")
    String serviceName;

    public List<UserSummary> listUsers() {
        return kubernetesClient.namespaces()
                .list()
                .getItems()
                .stream()
                .filter(this::isManagedUserNamespace)
                .map(this::toUserSummary)
                .sorted(Comparator.comparing(UserSummary::namespace))
                .toList();
    }

    public UserProvisioningResult provision(String userId) {
        validateUserId(userId);

        List<ProvisioningStepResult> results = new ArrayList<>();
        results.add(ensureNamespace(userId));
        results.add(ensureServiceAccount(userId));
        results.add(ensureRbac(userId));
        results.add(ensureDevcontainer(userId));
        results.add(ensureService(userId));

        return new UserProvisioningResult(userId, namespaceName(userId), results);
    }

    public ProvisioningStepResult ensureNamespace(String userId) {
        validateUserId(userId);
        String namespaceName = namespaceName(userId);

        Namespace existing = kubernetesClient.namespaces().withName(namespaceName).get();
        if (existing == null) {
            kubernetesClient.namespaces().resource(new NamespaceBuilder()
                    .withMetadata(new ObjectMetaBuilder()
                            .withName(namespaceName)
                            .withLabels(namespaceLabels(userId))
                            .withAnnotations(namespaceAnnotations(userId))
                            .build())
                    .build()).create();
            return completed("namespace", namespaceName, "Namespace created or updated.");
        }

        kubernetesClient.namespaces().resource(new NamespaceBuilder(existing)
                .editMetadata()
                .addToLabels(namespaceLabels(userId))
                .addToAnnotations(namespaceAnnotations(userId))
                .endMetadata()
                .build()).update();
        return completed("namespace", namespaceName, "Namespace created or updated.");
    }

    public ProvisioningStepResult ensureServiceAccount(String userId) {
        validateUserId(userId);
        String namespaceName = namespaceName(userId);
        ensureNamespace(userId);

        kubernetesClient.serviceAccounts()
                .inNamespace(namespaceName)
                .resource(new ServiceAccountBuilder()
                        .withMetadata(new ObjectMetaBuilder()
                                .withName(serviceAccountName)
                                .withNamespace(namespaceName)
                                .withLabels(commonLabels())
                                .build())
                        .build())
                .createOrReplace();

        return completed("serviceAccount", namespaceName, "ServiceAccount created or updated.");
    }

    public ProvisioningStepResult ensureRbac(String userId) {
        validateUserId(userId);
        String namespaceName = namespaceName(userId);
        ensureServiceAccount(userId);

        String roleName = serviceAccountName;
        kubernetesClient.rbac().roles()
                .inNamespace(namespaceName)
                .resource(new RoleBuilder()
                        .withMetadata(new ObjectMetaBuilder()
                                .withName(roleName)
                                .withNamespace(namespaceName)
                                .withLabels(commonLabels())
                                .build())
                        .withRules(
                                new PolicyRuleBuilder()
                                        .withApiGroups("", "apps")
                                        .withResources("pods", "pods/log", "services", "deployments")
                                        .withVerbs("get", "list", "watch")
                                        .build()
                        )
                        .build())
                .createOrReplace();

        kubernetesClient.rbac().roleBindings()
                .inNamespace(namespaceName)
                .resource(new RoleBindingBuilder()
                        .withMetadata(new ObjectMetaBuilder()
                                .withName(roleName)
                                .withNamespace(namespaceName)
                                .withLabels(commonLabels())
                                .build())
                        .withSubjects(new SubjectBuilder()
                                .withKind("ServiceAccount")
                                .withName(serviceAccountName)
                                .withNamespace(namespaceName)
                                .build())
                        .withRoleRef(new RoleRefBuilder()
                                .withApiGroup("rbac.authorization.k8s.io")
                                .withKind("Role")
                                .withName(roleName)
                                .build())
                        .build())
                .createOrReplace();

        return completed("rbac", namespaceName, "RBAC created or updated.");
    }

    public ProvisioningStepResult ensureDevcontainer(String userId) {
        validateUserId(userId);
        String namespaceName = namespaceName(userId);
        ensureRbac(userId);

        Map<String, String> selectorLabels = devcontainerLabels();
        kubernetesClient.apps().deployments()
                .inNamespace(namespaceName)
                .resource(new DeploymentBuilder()
                        .withMetadata(new ObjectMetaBuilder()
                                .withName(deploymentName)
                                .withNamespace(namespaceName)
                                .withLabels(commonLabels())
                                .build())
                        .withNewSpec()
                        .withReplicas(1)
                        .withNewSelector()
                        .withMatchLabels(selectorLabels)
                        .endSelector()
                        .withTemplate(new PodTemplateSpecBuilder()
                                .withMetadata(new ObjectMetaBuilder()
                                        .withLabels(selectorLabels)
                                        .build())
                                .withSpec(new PodSpecBuilder()
                                        .withServiceAccountName(serviceAccountName)
                                        .withContainers(new ContainerBuilder()
                                                .withName(deploymentName)
                                                .withImage(devcontainerImage)
                                                .withCommand("sleep")
                                                .withArgs("infinity")
                                                .build())
                                        .build())
                                .build())
                        .endSpec()
                        .build())
                .createOrReplace();

        return completed("devcontainer", namespaceName, "DevContainer Deployment created or updated.");
    }

    public ProvisioningStepResult ensureService(String userId) {
        validateUserId(userId);
        String namespaceName = namespaceName(userId);
        ensureDevcontainer(userId);

        kubernetesClient.services()
                .inNamespace(namespaceName)
                .resource(new ServiceBuilder()
                        .withMetadata(new ObjectMetaBuilder()
                                .withName(serviceName)
                                .withNamespace(namespaceName)
                                .withLabels(commonLabels())
                                .build())
                        .withNewSpec()
                        .withType("ClusterIP")
                        .withSelector(devcontainerLabels())
                        .withPorts(new ServicePortBuilder()
                                .withName("ssh")
                                .withPort(22)
                                .withNewTargetPort(22)
                                .build())
                        .endSpec()
                        .build())
                .createOrReplace();

        return completed("service", namespaceName, "DevContainer Service created or updated.");
    }

    public UserDeletionResult deleteUser(String userId) {
        validateUserId(userId);
        String namespaceName = namespaceName(userId);

        Namespace namespace = kubernetesClient.namespaces().withName(namespaceName).get();
        if (namespace == null) {
            throw new NotFoundException("Namespace not found: " + namespaceName);
        }

        Map<String, String> labels = namespace.getMetadata().getLabels();
        if (labels == null
                || !managedBy.equals(labels.get("app.kubernetes.io/managed-by"))
                || !userId.equals(labels.get(labelPrefix + "/user-id"))) {
            throw new ForbiddenException("Namespace is not managed by cluster-manager for userId: " + userId);
        }

        kubernetesClient.namespaces().withName(namespaceName).delete();
        return new UserDeletionResult(userId, namespaceName, "DELETING");
    }

    private ProvisioningStepResult completed(String key, String namespaceName, String message) {
        return new ProvisioningStepResult(key, namespaceName, "completed", message);
    }

    private boolean isManagedUserNamespace(Namespace namespace) {
        Map<String, String> labels = namespace.getMetadata().getLabels();
        return labels != null
                && managedBy.equals(labels.get("app.kubernetes.io/managed-by"))
                && "user-namespace".equals(labels.get(labelPrefix + "/resource-kind"))
                && labels.containsKey(labelPrefix + "/user-id");
    }

    private UserSummary toUserSummary(Namespace namespace) {
        Map<String, String> labels = namespace.getMetadata().getLabels();
        Map<String, String> annotations = namespace.getMetadata().getAnnotations();
        String phase = namespace.getStatus() == null ? null : namespace.getStatus().getPhase();
        return new UserSummary(
                labels.get(labelPrefix + "/user-id"),
                namespace.getMetadata().getName(),
                phase,
                labels == null ? Map.of() : Map.copyOf(labels),
                annotations == null ? Map.of() : Map.copyOf(annotations)
        );
    }

    private String namespaceName(String userId) {
        return namespacePrefix + userId;
    }

    private void validateUserId(String userId) {
        if (userId == null || !USER_ID_PATTERN.matcher(userId).matches()) {
            throw new BadRequestException("userId must match [a-z0-9]([-a-z0-9]*[a-z0-9])?");
        }
        String namespaceName = namespaceName(userId);
        if (namespaceName.length() > 63) {
            throw new BadRequestException("namespace name must be 63 characters or shorter: " + namespaceName);
        }
    }

    private Map<String, String> namespaceLabels(String userId) {
        Map<String, String> labels = commonLabels();
        labels.put(labelPrefix + "/resource-kind", "user-namespace");
        labels.put(labelPrefix + "/user-id", userId);
        return labels;
    }

    private Map<String, String> namespaceAnnotations(String userId) {
        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put(labelPrefix + "/user-id", userId);
        return annotations;
    }

    private Map<String, String> devcontainerLabels() {
        Map<String, String> labels = commonLabels();
        labels.put("app.kubernetes.io/name", deploymentName);
        return labels;
    }

    private Map<String, String> commonLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/managed-by", managedBy);
        labels.put("app.kubernetes.io/part-of", "cluster-manager");
        return labels;
    }
}
