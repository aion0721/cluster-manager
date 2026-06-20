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
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.rbac.PolicyRule;
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

    @Inject
    EnvironmentBaseImageCatalog environmentBaseImageCatalog;

    @ConfigProperty(name = "cluster-manager.namespace-prefix")
    String namespacePrefix;

    @ConfigProperty(name = "cluster-manager.provisioning.mode")
    String provisioningMode;

    @ConfigProperty(name = "cluster-manager.container-only.namespace")
    String containerOnlyNamespace;

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

    @ConfigProperty(name = "cluster-manager.devcontainer.service-type")
    String serviceType;

    @ConfigProperty(name = "cluster-manager.devcontainer.ssh-host")
    String sshHost;

    @ConfigProperty(name = "cluster-manager.kubeconfig.cluster-name")
    String kubeconfigClusterName;

    @ConfigProperty(name = "cluster-manager.kubeconfig.server")
    String kubeconfigServer;

    @ConfigProperty(name = "cluster-manager.kubeconfig.insecure-skip-tls-verify")
    boolean kubeconfigInsecureSkipTlsVerify;

    @ConfigProperty(name = "cluster-manager.service-account-token.expiration-seconds")
    long serviceAccountTokenExpirationSeconds;

    public List<UserSummary> listUsers() {
        if (mode() == ProvisioningMode.CONTAINER_ONLY) {
            return kubernetesClient.serviceAccounts()
                    .inNamespace(containerOnlyNamespace())
                    .list()
                    .getItems()
                    .stream()
                    .filter(this::isManagedUserServiceAccount)
                    .map(serviceAccount -> toUserSummary(getUser(serviceAccount.getMetadata().getLabels().get(labelPrefix + "/user-id"))))
                    .sorted(Comparator.comparing(UserSummary::namespace).thenComparing(UserSummary::userId))
                    .toList();
        }

        return kubernetesClient.namespaces()
                .list()
                .getItems()
                .stream()
                .filter(this::isManagedUserNamespace)
                .map(namespace -> toUserSummary(getUser(namespace.getMetadata().getLabels().get(labelPrefix + "/user-id"))))
                .sorted(Comparator.comparing(UserSummary::namespace))
                .toList();
    }

    public UserDetail getUser(String userId) {
        validateUserId(userId);
        String namespaceName = workloadNamespace(userId);
        Namespace namespace = mode() == ProvisioningMode.NAMESPACE ? getManagedNamespace(userId) : null;

        ServiceAccount userServiceAccount = kubernetesClient.serviceAccounts()
                .inNamespace(namespaceName)
                .withName(serviceAccountName(userId))
                .get();
        if (mode() == ProvisioningMode.CONTAINER_ONLY && userServiceAccount != null) {
            assertManagedUserServiceAccount(userServiceAccount, userId);
        }
        boolean serviceAccountExists = userServiceAccount != null;
        boolean roleExists = kubernetesClient.rbac().roles()
                .inNamespace(namespaceName)
                .withName(serviceAccountName(userId))
                .get() != null;
        boolean roleBindingExists = kubernetesClient.rbac().roleBindings()
                .inNamespace(namespaceName)
                .withName(serviceAccountName(userId))
                .get() != null;
        boolean deploymentExists = kubernetesClient.apps().deployments()
                .inNamespace(namespaceName)
                .withName(deploymentName(userId))
                .get() != null;
        Service userService = kubernetesClient.services()
                .inNamespace(namespaceName)
                .withName(serviceName(userId))
                .get();
        if (mode() == ProvisioningMode.CONTAINER_ONLY && userService != null) {
            assertManagedUserService(userService, userId);
        }
        boolean serviceExists = userService != null;
        if (mode() == ProvisioningMode.CONTAINER_ONLY && !serviceAccountExists && !roleExists && !roleBindingExists) {
            throw new NotFoundException("User not found for userId: " + userId);
        }

        return new UserDetail(
                userId,
                displayName(namespace, userServiceAccount),
                namespaceName,
                namespace == null || namespace.getStatus() == null ? null : namespace.getStatus().getPhase(),
                serviceAccountExists ? serviceAccountName(userId) : null,
                deploymentExists ? deploymentName(userId) : null,
                serviceExists ? serviceName(userId) : null,
                userStatus(namespace, serviceAccountExists, roleExists, roleBindingExists, deploymentExists, serviceExists),
                createdAt(namespace, userService),
                mode().configValue(),
                endpoint(userService)
        );
    }

    public ConnectionGuide connectionGuide(String userId) {
        validateUserId(userId);
        String namespaceName = workloadNamespace(userId);
        if (mode() == ProvisioningMode.NAMESPACE) {
            getManagedNamespace(userId);
        }

        Service userService = kubernetesClient.services()
                .inNamespace(namespaceName)
                .withName(serviceName(userId))
                .get();
        if (mode() == ProvisioningMode.CONTAINER_ONLY && userService != null) {
            assertManagedUserService(userService, userId);
        }
        DevcontainerEndpoint endpoint = endpoint(userService);
        String portForwardCommand = mode() == ProvisioningMode.NAMESPACE
                ? "kubectl -n " + namespaceName + " port-forward svc/" + serviceName(userId) + " 2222:22"
                : null;
        return new ConnectionGuide(
                namespaceName,
                serviceAccountName(userId),
                portForwardCommand,
                endpoint == null ? serviceName(userId) : endpoint.service(),
                endpoint == null ? null : endpoint.serviceType(),
                endpoint == null ? null : endpoint.servicePort(),
                endpoint == null ? null : endpoint.nodePort(),
                endpoint == null ? sshHost : endpoint.sshHost(),
                endpoint == null ? null : endpoint.sshCommand()
        );
    }

    public ServiceAccountTokenResponse createServiceAccountToken(String userId) {
        validateUserId(userId);
        String namespaceName = workloadNamespace(userId);
        if (mode() != ProvisioningMode.NAMESPACE) {
            throw new BadRequestException("ServiceAccount token API is available only in namespace mode.");
        }
        getManagedNamespace(userId);

        ServiceAccount serviceAccount = kubernetesClient.serviceAccounts()
                .inNamespace(namespaceName)
                .withName(serviceAccountName(userId))
                .get();
        if (serviceAccount == null) {
            throw new NotFoundException("ServiceAccount not found: " + namespaceName + "/" + serviceAccountName(userId));
        }

        TokenRequest tokenRequest = kubernetesClient.serviceAccounts()
                .inNamespace(namespaceName)
                .withName(serviceAccountName(userId))
                .tokenRequest(new TokenRequestBuilder()
                        .withNewSpec()
                        .withExpirationSeconds(serviceAccountTokenExpirationSeconds)
                        .endSpec()
                        .build());

        return new ServiceAccountTokenResponse(
                tokenRequest.getStatus().getToken(),
                namespaceName,
                serviceAccountName(userId),
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
        if (mode() == ProvisioningMode.NAMESPACE) {
            results.add(ensureNamespace(userId));
        }
        results.add(ensureServiceAccount(userId));
        results.add(ensureRbac(userId));
        results.add(ensureDevcontainer(userId));
        results.add(ensureService(userId));

        return new UserProvisioningResult(userId, workloadNamespace(userId), results);
    }

    public UserProvisioningResult provisionUser(String userId, String displayName) {
        validateUserId(userId);

        List<ProvisioningStepResult> results = new ArrayList<>();
        if (mode() == ProvisioningMode.NAMESPACE) {
            results.add(ensureNamespace(userId, displayName));
        }
        results.add(ensureServiceAccount(userId, displayName));
        results.add(ensureRbac(userId));

        return new UserProvisioningResult(userId, workloadNamespace(userId), results);
    }

    public UserProvisioningResult provisionEnvironment(String userId) {
        return provisionEnvironment(userId, null);
    }

    public UserProvisioningResult provisionEnvironment(String userId, String baseImageId) {
        validateUserId(userId);

        List<ProvisioningStepResult> results = new ArrayList<>();
        results.add(ensureDevcontainer(userId, baseImageId));
        results.add(ensureService(userId, false));

        return new UserProvisioningResult(userId, workloadNamespace(userId), results);
    }

    public ProvisioningStepResult ensureNamespace(String userId) {
        return ensureNamespace(userId, null);
    }

    public ProvisioningStepResult ensureNamespace(String userId, String displayName) {
        validateUserId(userId);
        String namespaceName = namespaceName(userId);
        if (mode() != ProvisioningMode.NAMESPACE) {
            throw new BadRequestException("Namespace step is available only in namespace mode.");
        }

        Namespace existing = kubernetesClient.namespaces().withName(namespaceName).get();
        if (existing == null) {
            kubernetesClient.namespaces().resource(new NamespaceBuilder()
                    .withMetadata(new ObjectMetaBuilder()
                             .withName(namespaceName)
                             .withLabels(namespaceLabels(userId))
                             .withAnnotations(namespaceAnnotations(userId, displayName))
                             .build())
                     .build()).create();
            return completed("namespace", namespaceName, "Namespace created or updated.");
        }

        kubernetesClient.namespaces().resource(new NamespaceBuilder(existing)
                .editMetadata()
                .addToLabels(namespaceLabels(userId))
                .addToAnnotations(namespaceAnnotations(userId, displayName))
                .endMetadata()
                .build()).update();
        return completed("namespace", namespaceName, "Namespace created or updated.");
    }

    public ProvisioningStepResult ensureServiceAccount(String userId) {
        return ensureServiceAccount(userId, null);
    }

    public ProvisioningStepResult ensureServiceAccount(String userId, String displayName) {
        validateUserId(userId);
        String namespaceName = workloadNamespace(userId);
        if (mode() == ProvisioningMode.NAMESPACE) {
            ensureNamespace(userId);
        }

        kubernetesClient.serviceAccounts()
                .inNamespace(namespaceName)
                .resource(new ServiceAccountBuilder()
                         .withMetadata(new ObjectMetaBuilder()
                                 .withName(serviceAccountName(userId))
                                 .withNamespace(namespaceName)
                                 .withLabels(userResourceLabels(userId, "service-account"))
                                 .withAnnotations(serviceAccountAnnotations(namespaceName, userId, displayName))
                                 .build())
                         .build())
                .createOrReplace();

        return completed("serviceAccount", namespaceName, "ServiceAccount created or updated.");
    }

    public ProvisioningStepResult ensureRbac(String userId) {
        validateUserId(userId);
        String namespaceName = workloadNamespace(userId);
        ensureServiceAccount(userId);

        String roleName = serviceAccountName(userId);
        kubernetesClient.rbac().roles()
                .inNamespace(namespaceName)
                .resource(new RoleBuilder()
                        .withMetadata(new ObjectMetaBuilder()
                                .withName(roleName)
                                .withNamespace(namespaceName)
                                .withLabels(userResourceLabels(userId, "role"))
                                .build())
                        .withRules(rbacRules(userId))
                        .build())
                .createOrReplace();

        kubernetesClient.rbac().roleBindings()
                .inNamespace(namespaceName)
                .resource(new RoleBindingBuilder()
                        .withMetadata(new ObjectMetaBuilder()
                                .withName(roleName)
                                .withNamespace(namespaceName)
                                .withLabels(userResourceLabels(userId, "role-binding"))
                                .build())
                        .withSubjects(new SubjectBuilder()
                                .withKind("ServiceAccount")
                                .withName(serviceAccountName(userId))
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
        return ensureDevcontainer(userId, null);
    }

    public ProvisioningStepResult ensureDevcontainer(String userId, String baseImageId) {
        validateUserId(userId);
        String namespaceName = workloadNamespace(userId);
        ensureRbac(userId);
        String containerImage = environmentBaseImageCatalog == null
                ? devcontainerImage
                : environmentBaseImageCatalog.resolveImage(baseImageId);

        Map<String, String> selectorLabels = devcontainerLabels(userId);
        kubernetesClient.apps().deployments()
                .inNamespace(namespaceName)
                .resource(new DeploymentBuilder()
                        .withMetadata(new ObjectMetaBuilder()
                                .withName(deploymentName(userId))
                                .withNamespace(namespaceName)
                                .withLabels(userResourceLabels(userId, "deployment"))
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
                                        .withServiceAccountName(serviceAccountName(userId))
                                        .withContainers(new ContainerBuilder()
                                                .withName(deploymentName)
                                                .withImage(containerImage)
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
        return ensureService(userId, true);
    }

    private ProvisioningStepResult ensureService(String userId, boolean ensureDevcontainer) {
        validateUserId(userId);
        String namespaceName = workloadNamespace(userId);
        if (ensureDevcontainer) {
            ensureDevcontainer(userId);
        }

        kubernetesClient.services()
                .inNamespace(namespaceName)
                .resource(new ServiceBuilder()
                        .withMetadata(new ObjectMetaBuilder()
                                .withName(serviceName(userId))
                                .withNamespace(namespaceName)
                                .withLabels(userResourceLabels(userId, "service"))
                                .build())
                        .withNewSpec()
                        .withType(serviceTypeForMode())
                        .withSelector(devcontainerLabels(userId))
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

    public UserDeletionResult deleteEnvironment(String userId) {
        validateUserId(userId);
        String namespaceName = workloadNamespace(userId);
        if (mode() == ProvisioningMode.NAMESPACE) {
            getManagedNamespace(userId);
        } else {
            ServiceAccount userServiceAccount = kubernetesClient.serviceAccounts()
                    .inNamespace(namespaceName)
                    .withName(serviceAccountName(userId))
                    .get();
            if (userServiceAccount == null) {
                throw new NotFoundException("User not found for userId: " + userId);
            }
            assertManagedUserServiceAccount(userServiceAccount, userId);
        }

        kubernetesClient.services()
                .inNamespace(namespaceName)
                .withName(serviceName(userId))
                .delete();
        kubernetesClient.apps().deployments()
                .inNamespace(namespaceName)
                .withName(deploymentName(userId))
                .delete();

        return new UserDeletionResult(userId, namespaceName, "DELETED");
    }

    public UserDeletionResult deleteUser(String userId) {
        validateUserId(userId);
        String namespaceName = workloadNamespace(userId);

        if (mode() == ProvisioningMode.CONTAINER_ONLY) {
            ServiceAccount userServiceAccount = kubernetesClient.serviceAccounts()
                    .inNamespace(namespaceName)
                    .withName(serviceAccountName(userId))
                    .get();
            if (userServiceAccount == null) {
                throw new NotFoundException("User not found for userId: " + userId);
            }
            assertManagedUserServiceAccount(userServiceAccount, userId);
            kubernetesClient.services()
                    .inNamespace(namespaceName)
                    .withName(serviceName(userId))
                    .delete();
            kubernetesClient.apps().deployments()
                    .inNamespace(namespaceName)
                    .withName(deploymentName(userId))
                    .delete();
            kubernetesClient.rbac().roleBindings()
                    .inNamespace(namespaceName)
                    .withName(serviceAccountName(userId))
                    .delete();
            kubernetesClient.rbac().roles()
                    .inNamespace(namespaceName)
                    .withName(serviceAccountName(userId))
                    .delete();
            kubernetesClient.serviceAccounts()
                    .inNamespace(namespaceName)
                    .withName(serviceAccountName(userId))
                    .delete();
            return new UserDeletionResult(userId, namespaceName, "DELETED");
        }

        namespaceName = namespaceName(userId);
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

    private UserSummary toUserSummary(UserDetail detail) {
        return new UserSummary(
                detail.userId(),
                detail.displayName(),
                detail.namespace(),
                detail.phase(),
                detail.serviceAccount(),
                detail.deployment(),
                detail.service(),
                detail.status(),
                detail.createdAt(),
                detail.mode(),
                detail.devcontainerEndpoint(),
                Map.of(),
                Map.of()
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

    private String userStatus(
            Namespace namespace,
            boolean serviceAccountExists,
            boolean roleExists,
            boolean roleBindingExists,
            boolean deploymentExists,
            boolean serviceExists
    ) {
        if (namespace != null && namespace.getMetadata().getDeletionTimestamp() != null) {
            return "DELETING";
        }
        boolean userReady = serviceAccountExists && roleExists && roleBindingExists;
        boolean environmentReady = deploymentExists && serviceExists;
        if (userReady && !deploymentExists && !serviceExists) {
            return "USER_READY";
        }
        if (userReady && environmentReady) {
            return "READY";
        }
        if (serviceAccountExists && deploymentExists && serviceExists) {
            return "READY";
        }
        return "PARTIAL";
    }

    private String namespaceName(String userId) {
        return namespacePrefix + userId;
    }

    private String workloadNamespace(String userId) {
        return mode() == ProvisioningMode.NAMESPACE ? namespaceName(userId) : containerOnlyNamespace();
    }

    private String containerOnlyNamespace() {
        return containerOnlyNamespace.trim();
    }

    private ProvisioningMode mode() {
        return ProvisioningMode.fromConfig(provisioningMode);
    }

    private String serviceAccountName(String userId) {
        return resourceName(serviceAccountName, userId);
    }

    private String deploymentName(String userId) {
        return resourceName(deploymentName, userId);
    }

    private String serviceName(String userId) {
        return resourceName(serviceName, userId);
    }

    private String resourceName(String baseName, String userId) {
        if (mode() == ProvisioningMode.NAMESPACE) {
            return baseName;
        }
        return baseName + "-" + userId;
    }

    private String serviceTypeForMode() {
        return mode() == ProvisioningMode.CONTAINER_ONLY ? "NodePort" : serviceType;
    }

    private List<PolicyRule> rbacRules(String userId) {
        if (mode() == ProvisioningMode.NAMESPACE) {
            return List.of(new PolicyRuleBuilder()
                    .withApiGroups("", "apps")
                    .withResources("pods", "pods/log", "services", "deployments")
                    .withVerbs("get", "list", "watch")
                    .build());
        }

        return List.of(
                new PolicyRuleBuilder()
                        .withApiGroups("")
                        .withResources("services")
                        .withResourceNames(serviceName(userId))
                        .withVerbs("get")
                        .build(),
                new PolicyRuleBuilder()
                        .withApiGroups("apps")
                        .withResources("deployments")
                        .withResourceNames(deploymentName(userId))
                        .withVerbs("get")
                        .build()
        );
    }

    private boolean isManagedUserService(Service service) {
        Map<String, String> labels = service.getMetadata().getLabels();
        return labels != null
                && managedBy.equals(labels.get("app.kubernetes.io/managed-by"))
                && "service".equals(labels.get(labelPrefix + "/resource-kind"))
                && labels.containsKey(labelPrefix + "/user-id");
    }

    private boolean isManagedUserServiceAccount(ServiceAccount serviceAccount) {
        Map<String, String> labels = serviceAccount.getMetadata().getLabels();
        return labels != null
                && managedBy.equals(labels.get("app.kubernetes.io/managed-by"))
                && "service-account".equals(labels.get(labelPrefix + "/resource-kind"))
                && labels.containsKey(labelPrefix + "/user-id");
    }

    private void assertManagedUserServiceAccount(ServiceAccount serviceAccount, String userId) {
        Map<String, String> labels = serviceAccount.getMetadata().getLabels();
        if (labels == null
                || !managedBy.equals(labels.get("app.kubernetes.io/managed-by"))
                || !"service-account".equals(labels.get(labelPrefix + "/resource-kind"))
                || !userId.equals(labels.get(labelPrefix + "/user-id"))) {
            throw new ForbiddenException("ServiceAccount is not managed by cluster-manager for userId: " + userId);
        }
    }

    private void assertManagedUserService(Service service, String userId) {
        Map<String, String> labels = service.getMetadata().getLabels();
        if (labels == null
                || !managedBy.equals(labels.get("app.kubernetes.io/managed-by"))
                || !"service".equals(labels.get(labelPrefix + "/resource-kind"))
                || !userId.equals(labels.get(labelPrefix + "/user-id"))) {
            throw new ForbiddenException("Service is not managed by cluster-manager for userId: " + userId);
        }
    }

    private String createdAt(Namespace namespace, Service service) {
        if (namespace != null) {
            return namespace.getMetadata().getCreationTimestamp();
        }
        return service == null ? null : service.getMetadata().getCreationTimestamp();
    }

    private String displayName(Namespace namespace, ServiceAccount serviceAccount) {
        Map<String, String> annotations = namespace != null
                ? namespace.getMetadata().getAnnotations()
                : serviceAccount == null ? null : serviceAccount.getMetadata().getAnnotations();
        if (annotations == null) {
            return null;
        }
        return annotations.get(labelPrefix + "/display-name");
    }

    private DevcontainerEndpoint endpoint(Service service) {
        if (service == null || service.getSpec() == null || service.getSpec().getPorts() == null
                || service.getSpec().getPorts().isEmpty()) {
            return null;
        }

        var port = service.getSpec().getPorts().get(0);
        Integer nodePort = port.getNodePort();
        String command = nodePort == null ? null : "ssh -p " + nodePort + " " + sshHost;
        return new DevcontainerEndpoint(
                service.getMetadata().getName(),
                service.getSpec().getType(),
                port.getPort(),
                nodePort,
                nodePort == null ? null : sshHost,
                command
        );
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
        if (mode() == ProvisioningMode.CONTAINER_ONLY) {
            validateResourceName(serviceAccountName(userId), "serviceAccount name");
            validateResourceName(deploymentName(userId), "deployment name");
            validateResourceName(serviceName(userId), "service name");
        }
    }

    private void validateResourceName(String value, String fieldName) {
        if (value.length() > 63) {
            throw new BadRequestException(fieldName + " must be 63 characters or shorter: " + value);
        }
    }

    private Map<String, String> namespaceLabels(String userId) {
        Map<String, String> labels = commonLabels();
        labels.put(labelPrefix + "/resource-kind", "user-namespace");
        labels.put(labelPrefix + "/user-id", userId);
        return labels;
    }

    private Map<String, String> namespaceAnnotations(String userId, String displayName) {
        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put(labelPrefix + "/user-id", userId);
        if (displayName != null && !displayName.isBlank()) {
            annotations.put(labelPrefix + "/display-name", displayName.trim());
        }
        return annotations;
    }

    private Map<String, String> userAnnotations(String userId, String displayName) {
        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put(labelPrefix + "/user-id", userId);
        if (displayName != null && !displayName.isBlank()) {
            annotations.put(labelPrefix + "/display-name", displayName.trim());
        }
        return annotations;
    }

    private Map<String, String> serviceAccountAnnotations(String namespaceName, String userId, String displayName) {
        Map<String, String> annotations = userAnnotations(userId, displayName);
        if (displayName == null || displayName.isBlank()) {
            ServiceAccount existing = kubernetesClient.serviceAccounts()
                    .inNamespace(namespaceName)
                    .withName(serviceAccountName(userId))
                    .get();
            if (existing != null && existing.getMetadata().getAnnotations() != null) {
                String existingDisplayName = existing.getMetadata().getAnnotations().get(labelPrefix + "/display-name");
                if (existingDisplayName != null && !existingDisplayName.isBlank()) {
                    annotations.put(labelPrefix + "/display-name", existingDisplayName);
                }
            }
        }
        return annotations;
    }

    private Map<String, String> devcontainerLabels(String userId) {
        Map<String, String> labels = commonLabels();
        labels.put("app.kubernetes.io/name", deploymentName(userId));
        labels.put(labelPrefix + "/user-id", userId);
        return labels;
    }

    private Map<String, String> userResourceLabels(String userId, String resourceKind) {
        Map<String, String> labels = commonLabels();
        labels.put(labelPrefix + "/resource-kind", resourceKind);
        labels.put(labelPrefix + "/user-id", userId);
        return labels;
    }

    private Map<String, String> commonLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/managed-by", managedBy);
        labels.put("app.kubernetes.io/part-of", "cluster-manager");
        return labels;
    }
}
