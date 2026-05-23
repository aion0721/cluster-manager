package com.example.provisioning;

import com.example.me.ConnectionGuide;
import com.example.me.KubectlSetupCommandResponse;
import com.example.me.ServiceAccountTokenResponse;
import com.example.security.UserIdValidator;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.authentication.TokenRequest;
import io.fabric8.kubernetes.api.model.authentication.TokenRequestBuilder;
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

@ApplicationScoped
public class UserProvisioningService {

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

    @ConfigProperty(name = "cluster-manager.kubeconfig.cluster-name")
    String kubeconfigClusterName;

    @ConfigProperty(name = "cluster-manager.kubeconfig.server")
    String kubeconfigServer;

    @ConfigProperty(name = "cluster-manager.kubeconfig.insecure-skip-tls-verify")
    boolean kubeconfigInsecureSkipTlsVerify;

    @ConfigProperty(name = "cluster-manager.service-account-token.expiration-seconds")
    long serviceAccountTokenExpirationSeconds;

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

    public UserDetail getUser(String userId) {
        validateUserId(userId);
        String namespaceName = namespaceName(userId);

        Namespace namespace = getManagedNamespace(userId);

        boolean serviceAccountExists = kubernetesClient.serviceAccounts()
                .inNamespace(namespaceName)
                .withName(serviceAccountName)
                .get() != null;
        boolean deploymentExists = kubernetesClient.apps().deployments()
                .inNamespace(namespaceName)
                .withName(deploymentName)
                .get() != null;
        boolean serviceExists = kubernetesClient.services()
                .inNamespace(namespaceName)
                .withName(serviceName)
                .get() != null;

        return new UserDetail(
                userId,
                namespaceName,
                namespace.getStatus() == null ? null : namespace.getStatus().getPhase(),
                serviceAccountExists ? serviceAccountName : null,
                deploymentExists ? deploymentName : null,
                serviceExists ? serviceName : null,
                userStatus(namespace, serviceAccountExists, deploymentExists, serviceExists),
                namespace.getMetadata().getCreationTimestamp()
        );
    }

    public ConnectionGuide connectionGuide(String userId) {
        validateUserId(userId);
        String namespaceName = namespaceName(userId);
        getManagedNamespace(userId);

        String portForwardCommand = "kubectl -n " + namespaceName + " port-forward svc/" + serviceName + " 2222:22";
        return new ConnectionGuide(namespaceName, serviceAccountName, portForwardCommand);
    }

    public ServiceAccountTokenResponse createServiceAccountToken(String userId) {
        validateUserId(userId);
        String namespaceName = namespaceName(userId);
        getManagedNamespace(userId);

        ServiceAccount serviceAccount = kubernetesClient.serviceAccounts()
                .inNamespace(namespaceName)
                .withName(serviceAccountName)
                .get();
        if (serviceAccount == null) {
            throw new NotFoundException("ServiceAccount not found: " + namespaceName + "/" + serviceAccountName);
        }

        TokenRequest tokenRequest = kubernetesClient.serviceAccounts()
                .inNamespace(namespaceName)
                .withName(serviceAccountName)
                .tokenRequest(new TokenRequestBuilder()
                        .withNewSpec()
                        .withExpirationSeconds(serviceAccountTokenExpirationSeconds)
                        .endSpec()
                        .build());

        return new ServiceAccountTokenResponse(
                tokenRequest.getStatus().getToken(),
                namespaceName,
                serviceAccountName,
                tokenRequest.getStatus().getExpirationTimestamp()
        );
    }

    public KubectlSetupCommandResponse kubectlSetupCommand(String userId) {
        ServiceAccountTokenResponse token = createServiceAccountToken(userId);
        String contextName = token.namespace() + "@" + kubeconfigClusterName;
        String credentialName = token.namespace() + "-user";

        String powershell = String.join(System.lineSeparator(),
                "kubectl config set-cluster " + kubeconfigClusterName
                        + " --server=" + kubeconfigServer
                        + " --insecure-skip-tls-verify=" + kubeconfigInsecureSkipTlsVerify,
                "kubectl config set-credentials " + credentialName + " --token=\"" + escapePowerShellDoubleQuoted(token.token()) + "\"",
                "kubectl config set-context " + contextName
                        + " --cluster=" + kubeconfigClusterName
                        + " --user=" + credentialName
                        + " --namespace=" + token.namespace(),
                "kubectl config use-context " + contextName,
                "kubectl get pods"
        );

        String bash = String.join(System.lineSeparator(),
                "kubectl config set-cluster " + kubeconfigClusterName
                        + " --server=" + kubeconfigServer
                        + " --insecure-skip-tls-verify=" + kubeconfigInsecureSkipTlsVerify,
                "kubectl config set-credentials " + credentialName + " --token='" + escapeBashSingleQuoted(token.token()) + "'",
                "kubectl config set-context " + contextName
                        + " --cluster=" + kubeconfigClusterName
                        + " --user=" + credentialName
                        + " --namespace=" + token.namespace(),
                "kubectl config use-context " + contextName,
                "kubectl get pods"
        );

        return new KubectlSetupCommandResponse(
                token.namespace(),
                token.serviceAccount(),
                kubeconfigClusterName,
                contextName,
                credentialName,
                token.expiresAt(),
                powershell,
                bash
        );
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

        assertManagedUserNamespace(namespace, userId);

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

    private Namespace getManagedNamespace(String userId) {
        String namespaceName = namespaceName(userId);
        Namespace namespace = kubernetesClient.namespaces().withName(namespaceName).get();
        if (namespace == null) {
            throw new NotFoundException("Namespace not found: " + namespaceName);
        }
        assertManagedUserNamespace(namespace, userId);
        return namespace;
    }

    private void assertManagedUserNamespace(Namespace namespace, String userId) {
        Map<String, String> labels = namespace.getMetadata().getLabels();
        if (labels == null
                || !managedBy.equals(labels.get("app.kubernetes.io/managed-by"))
                || !userId.equals(labels.get(labelPrefix + "/user-id"))) {
            throw new ForbiddenException("Namespace is not managed by cluster-manager for userId: " + userId);
        }
    }

    private String userStatus(Namespace namespace, boolean serviceAccountExists, boolean deploymentExists, boolean serviceExists) {
        if (namespace.getMetadata().getDeletionTimestamp() != null) {
            return "DELETING";
        }
        if (serviceAccountExists && deploymentExists && serviceExists) {
            return "READY";
        }
        return "PARTIAL";
    }

    private String namespaceName(String userId) {
        return namespacePrefix + userId;
    }

    private String escapePowerShellDoubleQuoted(String value) {
        return value.replace("`", "``").replace("\"", "`\"");
    }

    private String escapeBashSingleQuoted(String value) {
        return value.replace("'", "'\"'\"'");
    }

    private void validateUserId(String userId) {
        UserIdValidator.validate(userId);
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
