package com.example.provisioning;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceList;
import io.fabric8.kubernetes.api.model.NamespaceListBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountListBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountList;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.authentication.TokenRequest;
import io.fabric8.kubernetes.api.model.authentication.TokenRequestBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetList;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingList;
import io.fabric8.kubernetes.api.model.rbac.RoleList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.RbacAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.dsl.ServiceAccountResource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProvisioningServiceTest {

    private UserProvisioningService service;
    private MixedOperation<Namespace, NamespaceList, Resource<Namespace>> namespaces;
    private Resource<Namespace> namespaceResource;
    private MixedOperation<ServiceAccount, ServiceAccountList, ServiceAccountResource> serviceAccountOperation;
    private MixedOperation<ServiceAccount, ServiceAccountList, ServiceAccountResource> namespacedServiceAccounts;

    @BeforeEach
    void setUp() {
        service = new UserProvisioningService();
        service.kubernetesClient = mock(KubernetesClient.class);
        service.namespacePrefix = "dev-";
        service.provisioningMode = "namespace";
        service.containerOnlyNamespace = "devcontainers";
        service.managedBy = "cluster-manager";
        service.labelPrefix = "cluster-manager.example.com";
        service.serviceAccountName = "dev-user";
        service.deploymentName = "devcontainer";
        service.sshHost = "rp.local";
        service.kubeconfigClusterName = "k3s";
        service.kubeconfigServer = "https://k3s.example.test:6443";
        service.kubeconfigInsecureSkipTlsVerify = true;
        service.serviceAccountTokenExpirationSeconds = 7200L;

        namespaces = mock(MixedOperation.class);
        namespaceResource = mock(Resource.class);
        when(service.kubernetesClient.namespaces()).thenReturn(namespaces);
        when(namespaces.withName("dev-alice")).thenReturn(namespaceResource);
    }

    @Test
    void getsReadyUserDetailWhenAllResourcesExist() {
        mockUserDetailResources(
                namespaceWithLabels(Map.of(
                        "app.kubernetes.io/managed-by", "cluster-manager",
                        "cluster-manager.example.com/user-id", "alice"
                )),
                new ServiceAccountBuilder().withNewMetadata().withName("dev-user").endMetadata().build(),
                new StatefulSetBuilder().withNewMetadata().withName("devcontainer").endMetadata().build(),
                new ServiceBuilder().withNewMetadata().withName("devcontainer").endMetadata().build()
        );

        UserDetail detail = service.getUser("alice");

        assertEquals("alice", detail.userId());
        assertEquals("dev-alice", detail.namespace());
        assertEquals("Active", detail.phase());
        assertEquals("dev-user", detail.serviceAccount());
        assertEquals("devcontainer", detail.deployment());
        assertEquals(null, detail.service());
        assertEquals("READY", detail.status());
    }

    @Test
    void getsUserReadyDetailWhenEnvironmentIsMissing() {
        mockUserDetailResources(
                namespaceWithLabels(Map.of(
                        "app.kubernetes.io/managed-by", "cluster-manager",
                        "cluster-manager.example.com/user-id", "alice"
                )),
                new ServiceAccountBuilder().withNewMetadata().withName("dev-user").endMetadata().build(),
                null,
                null
        );

        UserDetail detail = service.getUser("alice");

        assertEquals("dev-user", detail.serviceAccount());
        assertEquals(null, detail.deployment());
        assertEquals(null, detail.service());
        assertEquals("USER_READY", detail.status());
    }

    @Test
    void getsDeletingUserDetailWhenNamespaceIsBeingDeleted() {
        mockUserDetailResources(namespaceWithDeletionTimestamp(), null, null, null);

        UserDetail detail = service.getUser("alice");

        assertEquals("DELETING", detail.status());
    }

    @Test
    void returnsNotFoundWhenGettingMissingUserDetail() {
        mockUserDetailResources(null, null, null, null);

        assertThrows(NotFoundException.class, () -> service.getUser("alice"));
    }

    @Test
    void rejectsUserDetailForUnmanagedNamespace() {
        mockUserDetailResources(
                namespaceWithLabels(Map.of(
                        "app.kubernetes.io/managed-by", "someone-else",
                        "cluster-manager.example.com/user-id", "alice"
                )),
                null,
                null,
                null
        );

        assertThrows(ForbiddenException.class, () -> service.getUser("alice"));
    }

    @Test
    void returnsConnectionGuideForCurrentUser() {
        mockUserDetailResources(
                namespaceWithLabels(Map.of(
                        "app.kubernetes.io/managed-by", "cluster-manager",
                        "cluster-manager.example.com/user-id", "alice"
                )),
                null,
                null,
                null
        );

        com.example.me.ConnectionGuide guide = service.connectionGuide("alice");

        assertEquals("dev-alice", guide.namespace());
        assertEquals("dev-user", guide.serviceAccount());
        assertEquals("kubectl -n dev-alice port-forward pod/devcontainer-0 2222:22", guide.portForwardCommand());
    }

    @Test
    void getsContainerOnlyUserDetailWithNodePortEndpoint() {
        service.provisioningMode = "container-only";
        mockContainerOnlyUserDetailResources(
                containerOnlyServiceAccount("alice"),
                new StatefulSetBuilder().withNewMetadata().withName("devcontainer-alice").endMetadata().build(),
                containerOnlyService("alice", 30022)
        );

        UserDetail detail = service.getUser("alice");

        assertEquals("alice", detail.userId());
        assertEquals("devcontainers", detail.namespace());
        assertEquals(null, detail.phase());
        assertEquals("dev-user-alice", detail.serviceAccount());
        assertEquals("devcontainer-alice", detail.deployment());
        assertEquals(null, detail.service());
        assertEquals("READY", detail.status());
        assertEquals("container-only", detail.mode());
        assertEquals(null, detail.devcontainerEndpoint());
    }

    @Test
    void getsContainerOnlyUserDetailWithoutEnvironment() {
        service.provisioningMode = "container-only";
        mockContainerOnlyUserDetailResources(
                containerOnlyServiceAccount("alice"),
                null,
                null
        );

        UserDetail detail = service.getUser("alice");

        assertEquals("alice", detail.userId());
        assertEquals("devcontainers", detail.namespace());
        assertEquals("dev-user-alice", detail.serviceAccount());
        assertEquals(null, detail.deployment());
        assertEquals(null, detail.service());
        assertEquals("USER_READY", detail.status());
        assertEquals(null, detail.devcontainerEndpoint());
    }

    @Test
    void returnsNotFoundWhenContainerOnlyUserDoesNotExist() {
        service.provisioningMode = "container-only";
        mockContainerOnlyUserDetailResources(null, null, null);

        assertThrows(NotFoundException.class, () -> service.getUser("alice"));
    }

    @Test
    void returnsContainerOnlyConnectionGuideWithoutPortForwardCommand() {
        service.provisioningMode = "container-only";
        mockContainerOnlyUserDetailResources(
                containerOnlyServiceAccount("alice"),
                new StatefulSetBuilder().withNewMetadata().withName("devcontainer-alice").endMetadata().build(),
                containerOnlyService("alice", 30022)
        );

        com.example.me.ConnectionGuide guide = service.connectionGuide("alice");

        assertEquals("devcontainers", guide.namespace());
        assertEquals("dev-user-alice", guide.serviceAccount());
        assertEquals("kubectl -n devcontainers port-forward pod/devcontainer-alice-0 2222:22", guide.portForwardCommand());
        assertEquals(null, guide.service());
        assertEquals(null, guide.serviceType());
        assertEquals(null, guide.servicePort());
        assertEquals(null, guide.nodePort());
        assertEquals("ssh -p 2222 localhost", guide.sshCommand());
    }

    @Test
    void createsContainerOnlyServiceAsNodePortInSharedNamespace() {
        service.provisioningMode = "container-only";
        UserProvisioningService serviceSpy = spy(service);
        doReturn(new ProvisioningStepResult("devcontainer", "devcontainers", "completed", "DevContainer StatefulSet created or updated."))
                .when(serviceSpy).ensureDevcontainer("alice");
        serviceSpy.kubernetesClient = mock(KubernetesClient.class);
        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperation = mock(MixedOperation.class);
        MixedOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(MixedOperation.class);
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        when(serviceSpy.kubernetesClient.services()).thenReturn(serviceOperation);
        when(serviceOperation.inNamespace("devcontainers")).thenReturn(namespacedServices);
        when(namespacedServices.withName("devcontainer-alice")).thenReturn(serviceResource);

        serviceSpy.ensureService("alice");

        verify(namespacedServices).withName("devcontainer-alice");
        verify(serviceResource).delete();
    }

    @Test
    void grantsPodPortForwardInNamespaceModeRbac() {
        UserProvisioningService serviceSpy = spy(service);
        doReturn(new ProvisioningStepResult("serviceAccount", "dev-alice", "completed", "ServiceAccount created or updated."))
                .when(serviceSpy).ensureServiceAccount("alice");
        serviceSpy.kubernetesClient = mock(KubernetesClient.class);
        RbacAPIGroupDSL rbac = mock(RbacAPIGroupDSL.class);
        MixedOperation<Role, RoleList, Resource<Role>> roleOperation = mock(MixedOperation.class);
        MixedOperation<Role, RoleList, Resource<Role>> namespacedRoles = mock(MixedOperation.class);
        Resource<Role> roleResource = mock(Resource.class);
        MixedOperation<RoleBinding, RoleBindingList, Resource<RoleBinding>> roleBindingOperation = mock(MixedOperation.class);
        MixedOperation<RoleBinding, RoleBindingList, Resource<RoleBinding>> namespacedRoleBindings = mock(MixedOperation.class);
        Resource<RoleBinding> roleBindingResource = mock(Resource.class);
        when(serviceSpy.kubernetesClient.rbac()).thenReturn(rbac);
        when(rbac.roles()).thenReturn(roleOperation);
        when(roleOperation.inNamespace("dev-alice")).thenReturn(namespacedRoles);
        when(namespacedRoles.resource(any(Role.class))).thenReturn(roleResource);
        when(rbac.roleBindings()).thenReturn(roleBindingOperation);
        when(roleBindingOperation.inNamespace("dev-alice")).thenReturn(namespacedRoleBindings);
        when(namespacedRoleBindings.resource(any(RoleBinding.class))).thenReturn(roleBindingResource);

        serviceSpy.ensureRbac("alice");

        org.mockito.ArgumentCaptor<Role> roleCaptor = org.mockito.ArgumentCaptor.forClass(Role.class);
        verify(namespacedRoles).resource(roleCaptor.capture());
        Role role = roleCaptor.getValue();
        assertTrue(role.getRules().stream().anyMatch(rule ->
                rule.getResources().contains("pods/portforward") && rule.getVerbs().contains("create")));
    }

    @Test
    void grantsOnlyUserPodPortForwardInContainerOnlyRbac() {
        service.provisioningMode = "container-only";
        UserProvisioningService serviceSpy = spy(service);
        doReturn(new ProvisioningStepResult("serviceAccount", "devcontainers", "completed", "ServiceAccount created or updated."))
                .when(serviceSpy).ensureServiceAccount("alice");
        serviceSpy.kubernetesClient = mock(KubernetesClient.class);
        RbacAPIGroupDSL rbac = mock(RbacAPIGroupDSL.class);
        MixedOperation<Role, RoleList, Resource<Role>> roleOperation = mock(MixedOperation.class);
        MixedOperation<Role, RoleList, Resource<Role>> namespacedRoles = mock(MixedOperation.class);
        Resource<Role> roleResource = mock(Resource.class);
        MixedOperation<RoleBinding, RoleBindingList, Resource<RoleBinding>> roleBindingOperation = mock(MixedOperation.class);
        MixedOperation<RoleBinding, RoleBindingList, Resource<RoleBinding>> namespacedRoleBindings = mock(MixedOperation.class);
        Resource<RoleBinding> roleBindingResource = mock(Resource.class);
        when(serviceSpy.kubernetesClient.rbac()).thenReturn(rbac);
        when(rbac.roles()).thenReturn(roleOperation);
        when(roleOperation.inNamespace("devcontainers")).thenReturn(namespacedRoles);
        when(namespacedRoles.resource(any(Role.class))).thenReturn(roleResource);
        when(rbac.roleBindings()).thenReturn(roleBindingOperation);
        when(roleBindingOperation.inNamespace("devcontainers")).thenReturn(namespacedRoleBindings);
        when(namespacedRoleBindings.resource(any(RoleBinding.class))).thenReturn(roleBindingResource);

        serviceSpy.ensureRbac("alice");

        org.mockito.ArgumentCaptor<Role> roleCaptor = org.mockito.ArgumentCaptor.forClass(Role.class);
        verify(namespacedRoles).resource(roleCaptor.capture());
        Role role = roleCaptor.getValue();
        assertTrue(role.getRules().stream().anyMatch(rule ->
                rule.getResources().contains("pods/portforward")
                        && rule.getResourceNames().contains("devcontainer-alice-0")
                        && rule.getVerbs().contains("create")));
    }

    @Test
    void createsDevcontainerWithResolvedBaseImage() {
        service.provisioningMode = "container-only";
        service.environmentBaseImageCatalog = mock(EnvironmentBaseImageCatalog.class);
        when(service.environmentBaseImageCatalog.resolveImage("node-dev")).thenReturn("node:22-bookworm");
        UserProvisioningService serviceSpy = spy(service);
        doReturn(new ProvisioningStepResult("rbac", "devcontainers", "completed", "RBAC created or updated."))
                .when(serviceSpy).ensureRbac("alice");
        serviceSpy.kubernetesClient = mock(KubernetesClient.class);
        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        MixedOperation<StatefulSet, StatefulSetList, RollableScalableResource<StatefulSet>> statefulSetOperation = mock(MixedOperation.class);
        MixedOperation<StatefulSet, StatefulSetList, RollableScalableResource<StatefulSet>> namespacedStatefulSets = mock(MixedOperation.class);
        RollableScalableResource<StatefulSet> statefulSetResource = mock(RollableScalableResource.class);
        when(serviceSpy.kubernetesClient.apps()).thenReturn(apps);
        when(apps.statefulSets()).thenReturn(statefulSetOperation);
        when(statefulSetOperation.inNamespace("devcontainers")).thenReturn(namespacedStatefulSets);
        when(namespacedStatefulSets.resource(any(StatefulSet.class))).thenReturn(statefulSetResource);

        serviceSpy.ensureDevcontainer("alice", "node-dev");

        org.mockito.ArgumentCaptor<StatefulSet> statefulSetCaptor = org.mockito.ArgumentCaptor.forClass(StatefulSet.class);
        verify(namespacedStatefulSets).resource(statefulSetCaptor.capture());
        assertEquals("node:22-bookworm", statefulSetCaptor.getValue().getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
    }

    @Test
    void provisionEnvironmentDoesNotRecreateDevcontainerWithDefaultImage() {
        service.provisioningMode = "container-only";
        UserProvisioningService serviceSpy = spy(service);
        doReturn(new ProvisioningStepResult("devcontainer", "devcontainers", "completed", "DevContainer StatefulSet created or updated."))
                .when(serviceSpy).ensureDevcontainer("alice", "node-dev");
        serviceSpy.kubernetesClient = mock(KubernetesClient.class);
        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperation = mock(MixedOperation.class);
        MixedOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(MixedOperation.class);
        ServiceResource<Service> serviceResource = mock(ServiceResource.class);
        when(serviceSpy.kubernetesClient.services()).thenReturn(serviceOperation);
        when(serviceOperation.inNamespace("devcontainers")).thenReturn(namespacedServices);
        when(namespacedServices.withName("devcontainer-alice")).thenReturn(serviceResource);

        serviceSpy.provisionEnvironment("alice", "node-dev");

        verify(serviceSpy).ensureDevcontainer("alice", "node-dev");
        verify(serviceSpy, never()).ensureDevcontainer("alice");
    }

    @Test
    void preservesDisplayNameAnnotationWhenRefreshingServiceAccount() {
        service.provisioningMode = "container-only";
        service.kubernetesClient = mock(KubernetesClient.class);
        serviceAccountOperation = mock(MixedOperation.class);
        namespacedServiceAccounts = mock(MixedOperation.class);
        ServiceAccountResource serviceAccountLookup = mock(ServiceAccountResource.class);
        when(service.kubernetesClient.serviceAccounts()).thenReturn(serviceAccountOperation);
        when(serviceAccountOperation.inNamespace("devcontainers")).thenReturn(namespacedServiceAccounts);
        when(namespacedServiceAccounts.withName("dev-user-alice")).thenReturn(serviceAccountLookup);
        when(serviceAccountLookup.get()).thenReturn(new ServiceAccountBuilder()
                .withNewMetadata()
                .withName("dev-user-alice")
                .withAnnotations(Map.of("cluster-manager.example.com/display-name", "Alice Doe"))
                .endMetadata()
                .build());
        when(namespacedServiceAccounts.resource(any(ServiceAccount.class))).thenReturn(serviceAccountLookup);

        service.ensureServiceAccount("alice");

        org.mockito.ArgumentCaptor<ServiceAccount> serviceAccountCaptor = org.mockito.ArgumentCaptor.forClass(ServiceAccount.class);
        verify(namespacedServiceAccounts).resource(serviceAccountCaptor.capture());
        assertEquals("Alice Doe", serviceAccountCaptor.getValue().getMetadata().getAnnotations().get("cluster-manager.example.com/display-name"));
    }

    @Test
    void createsServiceAccountTokenWithoutPersistingIt() {
        ServiceAccountResource serviceAccountLookup = mockUserDetailResources(
                namespaceWithLabels(Map.of(
                        "app.kubernetes.io/managed-by", "cluster-manager",
                        "cluster-manager.example.com/user-id", "alice"
                )),
                new ServiceAccountBuilder().withNewMetadata().withName("dev-user").endMetadata().build(),
                null,
                null
        );
        when(serviceAccountLookup.tokenRequest(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new TokenRequestBuilder()
                        .withNewStatus()
                        .withToken("token-value")
                        .withExpirationTimestamp("2026-05-23T10:00:00Z")
                        .endStatus()
                        .build());

        com.example.me.ServiceAccountTokenResponse response = service.createServiceAccountToken("alice");

        assertEquals("token-value", response.token());
        assertEquals("dev-alice", response.namespace());
        assertEquals("dev-user", response.serviceAccount());
        assertEquals("2026-05-23T10:00:00Z", response.expiresAt());
        verify(serviceAccountLookup).tokenRequest(org.mockito.ArgumentMatchers.any());
        verify(serviceAccountOperation, times(2)).inNamespace("dev-alice");
        verify(namespacedServiceAccounts, times(2)).withName("dev-user");
    }

    @Test
    void serviceAccountTokenRequestTargetsUserNamespaceAndServiceAccountWithoutAudiences() {
        ServiceAccountResource serviceAccountLookup = mockUserDetailResources(
                namespaceWithLabels(Map.of(
                        "app.kubernetes.io/managed-by", "cluster-manager",
                        "cluster-manager.example.com/user-id", "alice"
                )),
                new ServiceAccountBuilder().withNewMetadata().withName("dev-user").endMetadata().build(),
                null,
                null
        );
        when(serviceAccountLookup.tokenRequest(any()))
                .thenReturn(new TokenRequestBuilder()
                        .withNewStatus()
                        .withToken("token-value")
                        .withExpirationTimestamp("2026-05-23T10:00:00Z")
                        .endStatus()
                        .build());

        service.createServiceAccountToken("alice");

        org.mockito.ArgumentCaptor<TokenRequest> tokenRequestCaptor = org.mockito.ArgumentCaptor.forClass(TokenRequest.class);
        verify(serviceAccountOperation, times(2)).inNamespace(eq("dev-alice"));
        verify(namespacedServiceAccounts, times(2)).withName(eq("dev-user"));
        verify(serviceAccountLookup).tokenRequest(tokenRequestCaptor.capture());
        TokenRequest tokenRequest = tokenRequestCaptor.getValue();
        assertEquals(7200L, tokenRequest.getSpec().getExpirationSeconds());
        assertTrue(tokenRequest.getSpec().getAudiences() == null || tokenRequest.getSpec().getAudiences().isEmpty());
    }

    @Test
    void createsContainerOnlyServiceAccountToken() {
        service.provisioningMode = "container-only";
        mockContainerOnlyUserDetailResources(containerOnlyServiceAccount("alice"), null, null);
        when(namespacedServiceAccounts.withName("dev-user-alice").tokenRequest(any()))
                .thenReturn(new TokenRequestBuilder()
                        .withNewStatus()
                        .withToken("token-value")
                        .withExpirationTimestamp("2026-05-23T10:00:00Z")
                        .endStatus()
                        .build());
        org.mockito.Mockito.clearInvocations(serviceAccountOperation, namespacedServiceAccounts);

        com.example.me.ServiceAccountTokenResponse response = service.createServiceAccountToken("alice");

        assertEquals("token-value", response.token());
        assertEquals("devcontainers", response.namespace());
        assertEquals("dev-user-alice", response.serviceAccount());
        verify(serviceAccountOperation, times(2)).inNamespace("devcontainers");
        verify(namespacedServiceAccounts, times(2)).withName("dev-user-alice");
    }

    @Test
    void returnsKubectlSetupCommandWithPowerShellAndBash() {
        ServiceAccountResource serviceAccountLookup = mockUserDetailResources(
                namespaceWithLabels(Map.of(
                        "app.kubernetes.io/managed-by", "cluster-manager",
                        "cluster-manager.example.com/user-id", "alice"
                )),
                new ServiceAccountBuilder().withNewMetadata().withName("dev-user").endMetadata().build(),
                null,
                null
        );
        when(serviceAccountLookup.tokenRequest(any()))
                .thenReturn(new TokenRequestBuilder()
                        .withNewStatus()
                        .withToken("token-value")
                        .withExpirationTimestamp("2026-05-23T10:00:00Z")
                        .endStatus()
                        .build());

        com.example.me.KubectlSetupCommandResponse response = service.kubectlSetupCommand("alice");

        assertEquals("dev-alice", response.namespace());
        assertEquals("dev-user", response.serviceAccount());
        assertEquals("k3s", response.clusterName());
        assertEquals("dev-alice@k3s", response.contextName());
        assertEquals("dev-alice-user", response.credentialName());
        assertEquals("2026-05-23T10:00:00Z", response.expiresAt());
        org.junit.jupiter.api.Assertions.assertTrue(response.powershell().contains("kubectl config set-cluster k3s --server=https://k3s.example.test:6443 --insecure-skip-tls-verify=true"));
        org.junit.jupiter.api.Assertions.assertTrue(response.powershell().contains("kubectl config set-credentials dev-alice-user --token=\"token-value\""));
        org.junit.jupiter.api.Assertions.assertTrue(response.powershell().contains("kubectl config set-context dev-alice@k3s --cluster=k3s --user=dev-alice-user --namespace=dev-alice"));
        org.junit.jupiter.api.Assertions.assertTrue(response.powershell().contains("kubectl config use-context dev-alice@k3s"));
        org.junit.jupiter.api.Assertions.assertTrue(response.powershell().contains("kubectl get pod devcontainer-0"));
        org.junit.jupiter.api.Assertions.assertTrue(response.bash().contains("kubectl config set-credentials dev-alice-user --token='token-value'"));
        verify(serviceAccountLookup).tokenRequest(any());
        verify(serviceAccountOperation, times(2)).inNamespace("dev-alice");
        verify(namespacedServiceAccounts, times(2)).withName("dev-user");
    }

    @Test
    void returnsContainerOnlyKubectlSetupCommandForSharedNamespace() {
        service.provisioningMode = "container-only";
        mockContainerOnlyUserDetailResources(containerOnlyServiceAccount("alice"), null, null);
        when(namespacedServiceAccounts.withName("dev-user-alice").tokenRequest(any()))
                .thenReturn(new TokenRequestBuilder()
                        .withNewStatus()
                        .withToken("token-value")
                        .withExpirationTimestamp("2026-05-23T10:00:00Z")
                        .endStatus()
                        .build());

        com.example.me.KubectlSetupCommandResponse response = service.kubectlSetupCommand("alice");

        assertEquals("devcontainers", response.namespace());
        assertEquals("dev-user-alice", response.serviceAccount());
        assertEquals("devcontainers@k3s", response.contextName());
        assertEquals("devcontainers-user", response.credentialName());
        org.junit.jupiter.api.Assertions.assertTrue(response.powershell().contains("kubectl config set-context devcontainers@k3s --cluster=k3s --user=devcontainers-user --namespace=devcontainers"));
        org.junit.jupiter.api.Assertions.assertTrue(response.powershell().contains("kubectl get pod devcontainer-alice-0"));
        org.junit.jupiter.api.Assertions.assertTrue(response.bash().contains("kubectl get pod devcontainer-alice-0"));
    }

    @Test
    void returnsNotFoundForKubectlSetupCommandWhenNamespaceIsMissing() {
        mockUserDetailResources(null, null, null, null);

        assertThrows(NotFoundException.class, () -> service.kubectlSetupCommand("alice"));
    }

    @Test
    void returnsNotFoundForKubectlSetupCommandWhenServiceAccountIsMissing() {
        mockUserDetailResources(
                namespaceWithLabels(Map.of(
                        "app.kubernetes.io/managed-by", "cluster-manager",
                        "cluster-manager.example.com/user-id", "alice"
                )),
                null,
                null,
                null
        );

        assertThrows(NotFoundException.class, () -> service.kubectlSetupCommand("alice"));
    }

    @Test
    void kubectlSetupCommandDoesNotPersistToken() {
        ServiceAccountResource serviceAccountLookup = mockUserDetailResources(
                namespaceWithLabels(Map.of(
                        "app.kubernetes.io/managed-by", "cluster-manager",
                        "cluster-manager.example.com/user-id", "alice"
                )),
                new ServiceAccountBuilder().withNewMetadata().withName("dev-user").endMetadata().build(),
                null,
                null
        );
        when(serviceAccountLookup.tokenRequest(any()))
                .thenReturn(new TokenRequestBuilder()
                        .withNewStatus()
                        .withToken("token-value")
                        .withExpirationTimestamp("2026-05-23T10:00:00Z")
                        .endStatus()
                        .build());

        service.kubectlSetupCommand("alice");

        verify(serviceAccountLookup).tokenRequest(any());
        verify(serviceAccountLookup, never()).create();
        verify(serviceAccountLookup, never()).createOrReplace();
        verify(serviceAccountLookup, never()).update();
        verify(serviceAccountLookup, never()).patch();
    }

    @Test
    void rejectsTokenWhenServiceAccountIsMissing() {
        mockUserDetailResources(
                namespaceWithLabels(Map.of(
                        "app.kubernetes.io/managed-by", "cluster-manager",
                        "cluster-manager.example.com/user-id", "alice"
                )),
                null,
                null,
                null
        );

        assertThrows(NotFoundException.class, () -> service.createServiceAccountToken("alice"));
    }

    @Test
    void listsManagedUserNamespaces() {
        mockUserDetailResources(
                namespaceWithLabels(Map.of(
                        "app.kubernetes.io/managed-by", "cluster-manager",
                        "cluster-manager.example.com/resource-kind", "user-namespace",
                        "cluster-manager.example.com/user-id", "alice"
                )),
                new ServiceAccountBuilder().withNewMetadata().withName("dev-user").endMetadata().build(),
                null,
                null
        );
        when(namespaces.list()).thenReturn(new NamespaceListBuilder()
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

        assertEquals(1, users.size());
        assertEquals("alice", users.get(0).userId());
        assertEquals("dev-alice", users.get(0).namespace());
        assertEquals("Active", users.get(0).phase());
        assertEquals("USER_READY", users.get(0).status());
    }

    @Test
    void trimsContainerOnlyNamespaceConfigurationWhenListingUsers() {
        service.provisioningMode = " container-only ";
        service.containerOnlyNamespace = " devcontainers ";
        service.kubernetesClient = mock(KubernetesClient.class);
        serviceAccountOperation = mock(MixedOperation.class);
        namespacedServiceAccounts = mock(MixedOperation.class);
        ServiceAccountResource serviceAccountLookup = mock(ServiceAccountResource.class);
        when(service.kubernetesClient.serviceAccounts()).thenReturn(serviceAccountOperation);
        when(serviceAccountOperation.inNamespace("devcontainers")).thenReturn(namespacedServiceAccounts);
        when(namespacedServiceAccounts.list()).thenReturn(new ServiceAccountListBuilder()
                .addToItems(containerOnlyServiceAccount("alice"))
                .build());
        when(namespacedServiceAccounts.withName("dev-user-alice")).thenReturn(serviceAccountLookup);
        when(serviceAccountLookup.get()).thenReturn(containerOnlyServiceAccount("alice"));

        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        MixedOperation<StatefulSet, StatefulSetList, RollableScalableResource<StatefulSet>> statefulSetOperation = mock(MixedOperation.class);
        MixedOperation<StatefulSet, StatefulSetList, RollableScalableResource<StatefulSet>> namespacedStatefulSets = mock(MixedOperation.class);
        RollableScalableResource<StatefulSet> statefulSetLookup = mock(RollableScalableResource.class);
        when(service.kubernetesClient.apps()).thenReturn(apps);
        when(apps.statefulSets()).thenReturn(statefulSetOperation);
        when(statefulSetOperation.inNamespace("devcontainers")).thenReturn(namespacedStatefulSets);
        when(namespacedStatefulSets.withName("devcontainer-alice")).thenReturn(statefulSetLookup);
        when(statefulSetLookup.get()).thenReturn(null);

        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperation = mock(MixedOperation.class);
        MixedOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(MixedOperation.class);
        ServiceResource<Service> serviceLookup = mock(ServiceResource.class);
        when(service.kubernetesClient.services()).thenReturn(serviceOperation);
        when(serviceOperation.inNamespace("devcontainers")).thenReturn(namespacedServices);
        when(namespacedServices.withName("devcontainer-alice")).thenReturn(serviceLookup);
        when(serviceLookup.get()).thenReturn(null);
        mockRbacResources("devcontainers", "dev-user-alice", true, true);

        List<UserSummary> users = service.listUsers();

        assertEquals(1, users.size());
        assertEquals("alice", users.get(0).userId());
        assertEquals("devcontainers", users.get(0).namespace());
        assertEquals("USER_READY", users.get(0).status());
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
                .withCreationTimestamp("2026-05-23T09:00:00Z")
                .endMetadata()
                .withNewStatus()
                .withPhase("Active")
                .endStatus()
                .build();
    }

    private ServiceAccountResource mockUserDetailResources(Namespace namespace, ServiceAccount serviceAccount, StatefulSet statefulSet, Service userService) {
        service.kubernetesClient = mock(KubernetesClient.class);

        Resource<Namespace> namespaceLookup = mock(Resource.class);
        namespaces = mock(MixedOperation.class);
        when(service.kubernetesClient.namespaces()).thenReturn(namespaces);
        when(namespaces.withName("dev-alice")).thenReturn(namespaceLookup);
        when(namespaceLookup.get()).thenReturn(namespace);

        serviceAccountOperation = mock(MixedOperation.class);
        namespacedServiceAccounts = mock(MixedOperation.class);
        ServiceAccountResource serviceAccountLookup = mock(ServiceAccountResource.class);
        when(service.kubernetesClient.serviceAccounts()).thenReturn(serviceAccountOperation);
        when(serviceAccountOperation.inNamespace("dev-alice")).thenReturn(namespacedServiceAccounts);
        when(namespacedServiceAccounts.withName("dev-user")).thenReturn(serviceAccountLookup);
        when(serviceAccountLookup.get()).thenReturn(serviceAccount);

        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        MixedOperation<StatefulSet, StatefulSetList, RollableScalableResource<StatefulSet>> statefulSetOperation = mock(MixedOperation.class);
        MixedOperation<StatefulSet, StatefulSetList, RollableScalableResource<StatefulSet>> namespacedStatefulSets = mock(MixedOperation.class);
        RollableScalableResource<StatefulSet> statefulSetLookup = mock(RollableScalableResource.class);
        when(service.kubernetesClient.apps()).thenReturn(apps);
        when(apps.statefulSets()).thenReturn(statefulSetOperation);
        when(statefulSetOperation.inNamespace("dev-alice")).thenReturn(namespacedStatefulSets);
        when(namespacedStatefulSets.withName("devcontainer")).thenReturn(statefulSetLookup);
        when(statefulSetLookup.get()).thenReturn(statefulSet);

        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperation = mock(MixedOperation.class);
        MixedOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(MixedOperation.class);
        ServiceResource<Service> serviceLookup = mock(ServiceResource.class);
        when(service.kubernetesClient.services()).thenReturn(serviceOperation);
        when(serviceOperation.inNamespace("dev-alice")).thenReturn(namespacedServices);
        when(namespacedServices.withName("devcontainer")).thenReturn(serviceLookup);
        when(serviceLookup.get()).thenReturn(userService);

        mockRbacResources("dev-alice", "dev-user", true, true);

        return serviceAccountLookup;
    }

    private void mockContainerOnlyUserDetailResources(ServiceAccount serviceAccount, StatefulSet statefulSet, Service userService) {
        service.kubernetesClient = mock(KubernetesClient.class);

        serviceAccountOperation = mock(MixedOperation.class);
        namespacedServiceAccounts = mock(MixedOperation.class);
        ServiceAccountResource serviceAccountLookup = mock(ServiceAccountResource.class);
        when(service.kubernetesClient.serviceAccounts()).thenReturn(serviceAccountOperation);
        when(serviceAccountOperation.inNamespace("devcontainers")).thenReturn(namespacedServiceAccounts);
        when(namespacedServiceAccounts.withName("dev-user-alice")).thenReturn(serviceAccountLookup);
        when(serviceAccountLookup.get()).thenReturn(serviceAccount);

        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        MixedOperation<StatefulSet, StatefulSetList, RollableScalableResource<StatefulSet>> statefulSetOperation = mock(MixedOperation.class);
        MixedOperation<StatefulSet, StatefulSetList, RollableScalableResource<StatefulSet>> namespacedStatefulSets = mock(MixedOperation.class);
        RollableScalableResource<StatefulSet> statefulSetLookup = mock(RollableScalableResource.class);
        when(service.kubernetesClient.apps()).thenReturn(apps);
        when(apps.statefulSets()).thenReturn(statefulSetOperation);
        when(statefulSetOperation.inNamespace("devcontainers")).thenReturn(namespacedStatefulSets);
        when(namespacedStatefulSets.withName("devcontainer-alice")).thenReturn(statefulSetLookup);
        when(statefulSetLookup.get()).thenReturn(statefulSet);

        MixedOperation<Service, ServiceList, ServiceResource<Service>> serviceOperation = mock(MixedOperation.class);
        MixedOperation<Service, ServiceList, ServiceResource<Service>> namespacedServices = mock(MixedOperation.class);
        ServiceResource<Service> serviceLookup = mock(ServiceResource.class);
        when(service.kubernetesClient.services()).thenReturn(serviceOperation);
        when(serviceOperation.inNamespace("devcontainers")).thenReturn(namespacedServices);
        when(namespacedServices.withName("devcontainer-alice")).thenReturn(serviceLookup);
        when(serviceLookup.get()).thenReturn(userService);

        mockRbacResources("devcontainers", "dev-user-alice", serviceAccount != null, serviceAccount != null);
    }

    private void mockRbacResources(String namespace, String roleName, boolean roleExists, boolean roleBindingExists) {
        RbacAPIGroupDSL rbac = mock(RbacAPIGroupDSL.class);
        MixedOperation<Role, RoleList, Resource<Role>> roleOperation = mock(MixedOperation.class);
        MixedOperation<Role, RoleList, Resource<Role>> namespacedRoles = mock(MixedOperation.class);
        Resource<Role> roleLookup = mock(Resource.class);
        MixedOperation<RoleBinding, RoleBindingList, Resource<RoleBinding>> roleBindingOperation = mock(MixedOperation.class);
        MixedOperation<RoleBinding, RoleBindingList, Resource<RoleBinding>> namespacedRoleBindings = mock(MixedOperation.class);
        Resource<RoleBinding> roleBindingLookup = mock(Resource.class);

        when(service.kubernetesClient.rbac()).thenReturn(rbac);
        when(rbac.roles()).thenReturn(roleOperation);
        when(roleOperation.inNamespace(namespace)).thenReturn(namespacedRoles);
        when(namespacedRoles.withName(roleName)).thenReturn(roleLookup);
        when(roleLookup.get()).thenReturn(roleExists ? new Role() : null);
        when(rbac.roleBindings()).thenReturn(roleBindingOperation);
        when(roleBindingOperation.inNamespace(namespace)).thenReturn(namespacedRoleBindings);
        when(namespacedRoleBindings.withName(roleName)).thenReturn(roleBindingLookup);
        when(roleBindingLookup.get()).thenReturn(roleBindingExists ? new RoleBinding() : null);
    }

    private Service containerOnlyService(String userId, int nodePort) {
        return new ServiceBuilder()
                .withNewMetadata()
                .withName("devcontainer-" + userId)
                .withNamespace("devcontainers")
                .withCreationTimestamp("2026-05-23T09:00:00Z")
                .withLabels(Map.of(
                        "app.kubernetes.io/managed-by", "cluster-manager",
                        "cluster-manager.example.com/resource-kind", "service",
                        "cluster-manager.example.com/user-id", userId
                ))
                .endMetadata()
                .withNewSpec()
                .withType("NodePort")
                .withPorts(new ServicePortBuilder()
                        .withName("ssh")
                        .withPort(22)
                        .withNodePort(nodePort)
                        .build())
                .endSpec()
                .build();
    }

    private ServiceAccount containerOnlyServiceAccount(String userId) {
        return new ServiceAccountBuilder()
                .withNewMetadata()
                .withName("dev-user-" + userId)
                .withNamespace("devcontainers")
                .withLabels(Map.of(
                        "app.kubernetes.io/managed-by", "cluster-manager",
                        "cluster-manager.example.com/resource-kind", "service-account",
                        "cluster-manager.example.com/user-id", userId
                ))
                .endMetadata()
                .build();
    }

    private Namespace namespaceWithDeletionTimestamp() {
        return new NamespaceBuilder()
                .withNewMetadata()
                .withName("dev-alice")
                .withLabels(Map.of(
                        "app.kubernetes.io/managed-by", "cluster-manager",
                        "cluster-manager.example.com/user-id", "alice"
                ))
                .withDeletionTimestamp("2026-05-23T10:00:00Z")
                .endMetadata()
                .withNewStatus()
                .withPhase("Terminating")
                .endStatus()
                .build();
    }
}
