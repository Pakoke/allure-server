# Deployment

## Docker

Source: `Dockerfile`

```dockerfile
FROM amazoncorretto:21-alpine
COPY build/libs/*.jar /allure-server-docker.jar
EXPOSE ${PORT:-8080}
ENV JAVA_OPTS="-Xms256m -Xmx2048m"
ENTRYPOINT ["java", "-Dloader.path=/ext", "-jar", "allure-server-docker.jar",
            "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:default}"]
```

| Item | Value |
|---|---|
| Base image | `amazoncorretto:21-alpine` |
| Default port | `8080` (override via `PORT` env var) |
| Heap | 256 MB min / 2 GB max |
| External plugin path | `/ext` (via `PropertiesLauncher`) |
| Spring profile | `SPRING_PROFILES_ACTIVE` env var; defaults to `default` |

Public image: `kochetkovma/allure-server` on DockerHub.

---

## Docker Compose

### docker-compose.yml — App + PostgreSQL

Source: `docker-compose.yml`

```
allure-server  ──► postgres:16.3-alpine
```

| Service | Image | Port |
|---|---|---|
| `allure-server` | `kochetkovma/allure-server` | `8080:8080` |
| `postgres` | `postgres:16.3-alpine` | `5432:5432` |

Volumes:

| Host path | Container path | Purpose |
|---|---|---|
| `./ext` | `/ext` | External plugin JARs |
| `./allure-server-store` | `/allure` | Report and result file storage |
| `./allure-server-store-db` | `/var/lib/postgresql/data` | PostgreSQL data |

TMS configuration is passed via environment variables on the `allure-server` service:

```yaml
TMS_ENABLED: 'false'
TMS_HOST: jira.localhost
TMS_TOKEN: '<token-here>'
TMS_DRYRUN: 'false'
```

### docker-compose-h2.yml — Embedded H2 (single service)

Source: `docker-compose-h2.yml`

Runs the app with embedded H2 — no external database needed. Example showing the OAuth profile:

```yaml
environment:
  SPRING_PROFILES_ACTIVE: oauth
  # BASIC_AUTH_ENABLE: true
```

Volume: `./tmp/allure:/allure/`

---

## Helm Chart

Source: `.helm/allure-server/`

| Field | Value |
|---|---|
| Chart version | `1.0.0` |
| App version | `2.9.1` |
| Chart type | `application` |

### Deployment

Source: `.helm/allure-server/templates/deployment.yaml`

| Setting | Value |
|---|---|
| Container port | `8080` |
| CPU request / limit | `250m` / `500m` |
| Memory request / limit | `384Mi` / `512Mi` |
| Startup probe | `GET /api/result` — `initialDelaySeconds` configurable |
| Liveness probe | `GET /api/result` — period 30 s |
| Readiness probe | `GET /api/result` — period 30 s |

### Service

Source: `.helm/allure-server/templates/service.yaml`

| Setting | Value |
|---|---|
| Type | `ClusterIP` |
| Port | `8080` |

### Ingress

Source: `.helm/allure-server/templates/ingress.yaml` and `values.yaml`

| Setting | Value |
|---|---|
| Class | `nginx` |
| TLS | cert-manager / LetsEncrypt (`letsencrypt-prod` cluster issuer) |
| Max upload body | `100m` (`nginx.ingress.kubernetes.io/proxy-body-size`) |

### Persistent Volume Claim

Source: `.helm/allure-server/templates/storagepvc.yaml`

| Setting | Value |
|---|---|
| StorageClass | `yc-network-hdd` (Yandex Cloud; override for other providers) |
| Access mode | `ReadWriteOnce` |
| Capacity | `1Gi` |
| Mount path | `/allure` |

### Database SSL Certificate

Source: `.helm/allure-server/templates/crt.yaml`

A `ConfigMap` mounts the PostgreSQL CA certificate into the container at `~/.postgresql/`. The certificate can be supplied as inline text (`databaseCrt.crt.sourceText`) or read from a local file (`databaseCrt.crt.sourcePath`). Enabled via `databaseCrt.enabled: true`.

### Secrets

Source: `values.yaml` (`secret.keys`)

Database credentials are read from a Kubernetes `Secret` and injected as environment variables:

```yaml
secret:
  keys:
    - SPRING_DATASOURCE_USERNAME
    - SPRING_DATASOURCE_PASSWORD
```

The secret object must be created manually in the namespace before deployment.

---

## CI/CD

Source: `.github/workflows/`

### check.yml — Build and Test

Trigger: every push and pull request.

```
checkout → JDK 21 (Corretto) + Node 20.13.1
         → gradle --stacktrace --info build
```

| Setting | Value |
|---|---|
| Timeout | 10 minutes |
| Gradle version | 8.8 |
| Task | `build` (compile + test) |

### release.yml — Versioned Release

Trigger: tags matching `v*.*.*`.

```
checkout → set RELEASE_VERSION from tag
         → JDK 21 + Node.js
         → gradle bootJar -Pversion=$RELEASE_VERSION
         → Docker Buildx (multi-arch)
         → push to DockerHub: kochetkovma/allure-server:latest + :$RELEASE_VERSION
         → create GitHub Release
         → upload allure-server.jar as release asset
```

| Setting | Value |
|---|---|
| Docker platforms | `linux/amd64`, `linux/arm64` |
| DockerHub image | `kochetkovma/allure-server` |
| GitHub release asset | `allure-server.jar` |
| Secrets required | `DOCKER_USERNAME`, `DOCKER_PASSWORD`, `GITHUB_TOKEN` |
