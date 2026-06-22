# cluster-manager

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Building a container image for k3s

For the MVP, build the JVM container image locally and import it into the Raspberry Pi k3s container runtime.

On the development machine:

```powershell
.\mvnw.cmd package
docker build -f src/main/docker/Dockerfile.jvm -t cluster-manager-backend:0.1.0 .
docker save cluster-manager-backend:0.1.0 -o cluster-manager-backend_0.1.0.tar
```

Copy `cluster-manager-backend_0.1.0.tar` to the Raspberry Pi, then import it into k3s:

```bash
sudo k3s ctr images import cluster-manager-backend_0.1.0.tar
sudo k3s ctr images list | grep cluster-manager-backend
```

Use the same image tag in the Kubernetes Deployment:

```yaml
containers:
  - name: cluster-manager-backend
    image: cluster-manager-backend:0.1.0
    imagePullPolicy: IfNotPresent
```

If the image is built on a machine with a different CPU architecture from the Raspberry Pi, build for the Pi architecture explicitly. For a 64-bit Raspberry Pi OS, use `linux/arm64`:

```powershell
docker buildx build --platform linux/arm64 -f src/main/docker/Dockerfile.jvm -t cluster-manager-backend:0.1.0 --load .
docker save cluster-manager-backend:0.1.0 -o cluster-manager-backend_0.1.0.tar
```

Backend configuration can be passed as environment variables in the Deployment. For example:

```yaml
env:
  - name: CLUSTER_MANAGER_AUTH_MODE
    value: "simple"
  - name: CLUSTER_MANAGER_ADMIN_USER_IDS
    value: "koba,tanaka"
  - name: CLUSTER_MANAGER_SERVICE_ACCOUNT_TOKEN_EXPIRATION_SECONDS
    value: "3600"
  - name: CLUSTER_MANAGER_KUBECONFIG_CLUSTER_NAME
    value: "k3s"
  - name: CLUSTER_MANAGER_KUBECONFIG_SERVER
    value: "https://rp.local:6443"
  - name: CLUSTER_MANAGER_KUBECONFIG_INSECURE_SKIP_TLS_VERIFY
    value: "true"
  - name: CLUSTER_MANAGER_ENVIRONMENT_BASE_IMAGES_CONFIG_PATH
    value: "/etc/cluster-manager/base-images/images.yaml"
```

## Authentication

`cluster-manager` can run in two authentication modes:

- `simple`: the default mode. The backend reads the current user ID from the `X-User-Id` request header.
- `keycloak`: the backend uses Quarkus OIDC bearer-token authentication and reads the user ID from the authenticated principal.

Simple mode is intended for local development or deployments where another trusted component has already authenticated the request:

```properties
cluster-manager.auth.mode=simple
%dev.quarkus.oidc.enabled=false
%test.quarkus.oidc.enabled=false
```

Keycloak mode example:

```properties
cluster-manager.auth.mode=keycloak
quarkus.oidc.enabled=true
quarkus.oidc.auth-server-url=https://keycloak.example.com/realms/dev
quarkus.oidc.client-id=cluster-manager
quarkus.oidc.application-type=service
quarkus.oidc.token.principal-claim=preferred_username
cluster-manager.admin-user-ids=alice,bob
```

When deploying with environment variables, set `CLUSTER_MANAGER_AUTH_MODE=keycloak` and the matching `QUARKUS_OIDC_*` values. Keep `cluster-manager.admin-user-ids` aligned with the Keycloak principal claim because admin APIs still use that configured allow-list.

Do not build the production image with an unprofiled `quarkus.oidc.enabled=false`. It is a Quarkus build-time property; if it is disabled while building the image, runtime `QUARKUS_OIDC_ENABLED=true` does not re-enable the bearer authentication mechanism. Keep OIDC disabled only in dev/test profiles unless intentionally building a simple-mode-only image.

## Provisioning modes

`cluster-manager` supports two provisioning modes:

- `namespace`: each developer gets a Namespace named `dev-{userId}`. User resources live in that Namespace. The DevContainer Service is a ClusterIP Service.
- `container-only`: the backend does not create a Namespace. It creates user-specific ServiceAccount, RBAC, DevContainer Deployment, and NodePort Service inside `cluster-manager.container-only.namespace`.

Container-only example:

```properties
cluster-manager.provisioning.mode=container-only
cluster-manager.container-only.namespace=devcontainers
cluster-manager.devcontainer.ssh-host=rp.local
```

For user `alice`, container-only mode creates resources such as `dev-user-alice` and `devcontainer-alice` in the shared Namespace. `/api/me` and `/api/me/connection-guide` include the assigned NodePort Service information and an SSH command such as `ssh -p 30022 rp.local` after Kubernetes assigns the NodePort.

## Admin user and environment APIs

The admin UI separates user setup from DevContainer environment setup. User setup creates identity and permission resources; environment setup creates the Deployment and Service.

User APIs:

- `POST /api/users`: accepts `userId` and optional `displayName`. It creates the user resources only: Namespace in `namespace` mode, ServiceAccount, and RBAC. It does not create a DevContainer Deployment or Service.
- `GET /api/users`: lists users. The response includes user fields and any environment fields that currently exist.
- `GET /api/users/{userId}`: returns user detail. If the user resources exist but the environment has not been created yet, the response is still `200` with `deployment`, `service`, and `devcontainerEndpoint` unset.
- `DELETE /api/users/{userId}`: deletes the user. In `container-only` mode, it deletes Service, Deployment, RoleBinding, Role, and ServiceAccount in that order.

Environment APIs:

- `POST /api/users/{userId}/environment`: creates the DevContainer Deployment and Service for an existing user.
- `DELETE /api/users/{userId}/environment`: deletes only the DevContainer Service and Deployment.
- `POST /api/users/{userId}/reconcile`: runs the full reconciliation path and recreates missing user and environment resources.
- `GET /api/users/{userId}/port-forward-command`: returns the same connection guide shape used by `/api/me/connection-guide`.

`displayName` is stored in Kubernetes annotations, not in a database. The backend returns it as `displayName`; clients do not need to know the annotation key.

Common status values:

- `USER_READY`: ServiceAccount and RBAC are present, but the environment is not created yet.
- `READY`: user resources and environment resources are present.
- `PARTIAL`: some expected resources are missing.
- `DELETING`: the Namespace is being deleted.

Provisioning step metadata is available from `GET /api/provisioning-steps`. Each step includes a `group` field:

- `users`: `namespace`, `serviceAccount`, `rbac`
- `pods`: `devcontainer`, `service`

## Environment base images

Environment creation can select a configured base image by ID. The frontend sends only the image ID; the backend validates it against an allow-list and resolves it to the real container image.

```http
GET /api/environment-base-images
```

Response fields:

- `id`
- `label`
- `description`
- `default`
- `image`, only when image exposure is enabled

Create an environment with a selected base image:

```http
POST /api/users/alice/environment
Content-Type: application/json

{
  "baseImage": "node-dev"
}
```

If `baseImage` is omitted, the configured default image is used. Unknown IDs return `400 Bad Request`.

For operations, mount the base image catalog from a ConfigMap:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: cluster-manager-base-images
  namespace: cluster-manager-system
data:
  images.yaml: |
    exposeImage: false
    images:
      - id: ubuntu
        label: Ubuntu
        description: Ubuntu base image
        image: ubuntu:24.04
        default: true
      - id: node-dev
        label: Node.js
        description: Node.js development image
        image: node:22-bookworm
        default: false
```

Mount it into the backend Deployment and point the backend to the file:

```yaml
env:
  - name: CLUSTER_MANAGER_ENVIRONMENT_BASE_IMAGES_CONFIG_PATH
    value: "/etc/cluster-manager/base-images/images.yaml"
volumeMounts:
  - name: base-images
    mountPath: /etc/cluster-manager/base-images
    readOnly: true
volumes:
  - name: base-images
    configMap:
      name: cluster-manager-base-images
```

If the ConfigMap is not configured or the mounted file does not exist, the backend falls back to `cluster-manager.devcontainer.image` as a single default base image. By default the API does not expose the real image string. Set `cluster-manager.environment-base-images.expose-image=true` or `exposeImage: true` in the catalog if the UI should display it.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/cluster-manager-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Related Guides

- REST Jackson ([guide](https://quarkus.io/guides/rest#json-serialisation)): Jackson serialization support for Quarkus REST. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it
- SmallRye OpenAPI ([guide](https://quarkus.io/guides/openapi-swaggerui)): Generate OpenAPI schemas and serve Swagger UI for REST API documentation
- Kubernetes Client ([guide](https://quarkus.io/guides/kubernetes-client)): Interact with Kubernetes and develop Kubernetes Operators

## Provided Code

### REST

Easily start your REST Web Services

[Related guide section...](https://quarkus.io/guides/getting-started-reactive#reactive-jax-rs-resources)
