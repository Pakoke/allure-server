# Development

## Prerequisites

| Tool | Purpose | How |
|---|---|---|
| [Devbox](https://www.jetbrains.com/devbox/) | Reproducible dev environment | `curl -fsSL https://get.jetpack.io/devbox \| bash` |
| Docker | PostgreSQL container + Docker image builds | https://docs.docker.com/get-docker/ |

Without Devbox: install JDK 21 and Gradle manually, ensure `JAVA_HOME` points at JDK 21.

---

## Devbox Setup

Source: `devbox.json`

Packages provided by Devbox:

| Package | Version |
|---|---|
| JDK | 21 |
| Gradle | latest |
| Node.js | 24 |
| docker-compose | latest |

Enter the environment:

```sh
devbox shell
```

`init_hook` exports `JAVA_HOME`, sources `.envrc` if present, and prints a quick-start banner.

---

## Build Commands

Source: `devbox.json` (`scripts` section)

| Command | What it does |
|---|---|
| `devbox run build` | `./gradlew build -x test` — compile + package, skip tests |
| `devbox run test` | `./gradlew test` |
| `devbox run run` | `./gradlew bootRun` — starts with embedded H2 |
| `devbox run run:pg` | `docker compose up -d postgres` then `./gradlew bootRun` with PostgreSQL env vars |
| `devbox run run:debug` | `./gradlew bootRun --debug-jvm` — JVM debug port 5005 |
| `devbox run run:pg:debug` | Same as `run:pg` with `--debug-jvm` |
| `devbox run docker:build` | `./gradlew build -x test && docker build -t allure-server:local .` |
| `devbox run docker:up` | `docker compose up -d` |
| `devbox run docker:down` | `docker compose down` |
| `devbox run docker:logs` | `docker compose logs -f` |
| `devbox run clean` | `./gradlew clean` |

PostgreSQL env vars set by `run:pg` / `run:pg:debug`:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/allure
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres
SPRING_JPA_DATABASE=postgresql
```

---

## VSCode Debug

Source: `.vscode/launch.json`

Two attach configurations (both connect to `localhost:5005`):

| Configuration | Pre-launch task |
|---|---|
| `Debug Allure Server` | none |
| `Debug Allure Server (PostgreSQL)` | `start-postgres` |

Workflow:

```
1. devbox run run:debug          # starts app, suspends JVM at port 5005
2. F5 in VSCode                  # attach debugger
```

For PostgreSQL variant use `devbox run run:pg:debug` instead of step 1 (the `preLaunchTask` starts postgres automatically when launching from VSCode).

---

## Test Structure

Source: `gradle/testing.gradle`

Framework: JUnit 5 (JUnit Platform) + AssertJ + Spring Boot Test.

Heap: 256 MB min / 2 GB max per test JVM.

| Test file | Location | What it covers |
|---|---|---|
| `ResultServiceTest.java` | `src/test/java/ru/iopump/qa/allure/service/` | Zip extraction — positive and negative cases |
| `UtilTest.java` | `src/test/java/ru/iopump/qa/allure/helper/` | URL parsing utilities |
| `DateTimeResolverTest.java` | `src/test/java/ru/iopump/qa/allure/gui/` | Timezone-aware date formatting |
| `YouTrackPluginTest.java` | `src/test/java/ru/iopump/qa/allure/helper/plugin/` | Spring integration test for the YouTrack TMS plugin |
| `MarkdownStatisticModelTest.java` | `src/test/java/ru/iopump/qa/allure/helper/plugin/youtrack/` | Markdown comment model serialisation and merging |

Test resources include zip fixture archives, allure-result directories, and the YouTrack OpenAPI spec used by the Feign client generator.

---

## Project Structure

```
src/main/java/ru/iopump/qa/allure/
├── controller/          # REST API
│   ├── AllureReportController.java   — report CRUD + generation endpoints
│   └── AllureResultController.java   — result upload/delete endpoints
├── service/             # Business logic
│   ├── JpaReportService.java         — report lifecycle, calls generator + plugins
│   ├── ResultService.java            — zip extraction and result storage
│   └── CleanUpServiceConfiguration.java — scheduled report cleanup
├── entity/
│   └── ReportEntity.java             — JPA entity (report metadata, UUID, path)
├── helper/              # Core helpers
│   ├── AllureReportGenerator.java    — invokes Allure CLI + plugin hooks
│   ├── ExecutorCiPlugin.java         — CI executor metadata injection
│   ├── ServeRedirectHelper.java      — redirect logic for report serving
│   └── plugin/          # Plugin system
│       ├── AllureServerPlugin.java   — plugin interface + Context interface
│       ├── CustomReportMetaPlugin.java — logo + title injection
│       ├── YouTrackPlugin.java       — YouTrack comment integration
│       └── youtrack/
│           └── MarkdownStatisticModel.java — markdown comment model
├── config/
│   ├── SpringConfiguration.java      — plugin discovery bean
│   └── RedirectConfiguration.java    — Spring MVC redirect setup
├── security/
│   ├── SecurityConfiguration.java    — Spring Security (basic auth / OAuth)
│   └── BasicProperties.java          — basic auth config binding
├── properties/          # @ConfigurationProperties bindings
│   ├── AllureProperties.java         — allure.* (storage path, logo, title, base URL)
│   ├── TmsProperties.java            — tms.* (host, token, issueKeyPattern, dryRun)
│   └── CleanUpProperties.java        — allure.clean.* (age, paths, dryRun)
├── gui/                 # Vaadin UI
│   ├── MainLayout.java               — app shell / navigation
│   ├── DateTimeResolver.java         — timezone-aware date helper
│   ├── view/                         — ReportsView, ResultsView, AboutView, SwaggerView
│   └── component/                    — FilteredGrid, ReportGenerateDialog, ResultUploadDialog
└── api/                 # Feign clients
    └── youtrack/
        └── IssuesClient.java         — YouTrack REST API client (generated from OpenAPI spec)
```
