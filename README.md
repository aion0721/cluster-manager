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
```

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
