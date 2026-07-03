# Allure Server — Architecture

## High-Level Diagram

```
HTTP Clients / CI Pipelines
         |
         v
+-----------------------------+        +---------------------+
|   REST Controllers          |        |   Vaadin UI         |
|  AllureReportController     |<------>|   /ui/**            |
|  AllureResultController     |        |  MainLayout + Views |
+-----------------------------+        +---------------------+
         |                                       |
         v                                       v
+------------------------------------------------------------+
|                        Services                            |
|  JpaReportService   ResultService   CleanUpService         |
|                                     Configuration          |
+------------------------------------------------------------+
         |                     |
         v                     v
+------------------+   +---------------------+
|  JPA Repository  |   |    Filesystem        |
|  JpaReportReport |   |  allure/results/     |
|  Repository      |   |  allure/reports/     |
|  (H2/PostgreSQL) |   +---------------------+
+------------------+
         |
         v
+------------------------------------------------------------+
|                     Plugin System                          |
|  AllureServerPlugin (interface)                            |
|  CustomReportMetaPlugin  YouTrackPlugin                    |
|  + external plugins loaded from /ext or allure/plugins/   |
+------------------------------------------------------------+
         |
         v
+-----------------------------+
|   TMS Integration           |
|  YouTrack via FeignClient   |
|  (IssuesClient / Feign)     |
+-----------------------------+
```

Report serving (static files) is handled by `RedirectConfiguration` via Spring MVC resource handlers at `reports/**` and redirect mappings maintained by `ServeRedirectHelper`.

---

## Layer Breakdown

| Layer | Key Classes | Source Paths | Responsibilities |
|---|---|---|---|
| Entry point | `Application` | `src/main/java/ru/iopump/qa/allure/Application.java` | Spring Boot bootstrap |
| REST Controllers | `AllureReportController`, `AllureResultController` | `src/main/java/ru/iopump/qa/allure/controller/` | HTTP endpoints; input validation; Spring Cache eviction |
| Vaadin UI | `MainLayout`, `ReportsView`, `ResultsView`, `SwaggerView`, `AboutView` | `src/main/java/ru/iopump/qa/allure/gui/` | Server-side rendered UI at `/ui/**` |
| Services | `JpaReportService`, `ResultService`, `CleanUpServiceConfiguration` | `src/main/java/ru/iopump/qa/allure/service/` | Business logic: generate/upload/delete reports, unzip results, scheduled cleanup |
| Report generation | `AllureReportGenerator`, `ExecutorCiPlugin`, `ServeRedirectHelper` | `src/main/java/ru/iopump/qa/allure/helper/` | Wrap Allure CLI generator, manage CI executor metadata, maintain path→UUID redirect map |
| Plugin system | `AllureServerPlugin`, `CustomReportMetaPlugin`, `YouTrackPlugin` | `src/main/java/ru/iopump/qa/allure/helper/plugin/` | Lifecycle hooks around report generation; loaded via `ReflectionUtil` |
| JPA Entity & Repo | `ReportEntity`, `JpaReportRepository` | `src/main/java/ru/iopump/qa/allure/entity/`, `src/main/java/ru/iopump/qa/allure/repo/` | Report metadata persistence; Spring Data JPA |
| Configuration | `SpringConfiguration`, `RedirectConfiguration` | `src/main/java/ru/iopump/qa/allure/config/` | Plugin discovery bean, static resource handlers, Vaadin/Swagger redirects |
| Security | `SecurityConfiguration`, `SecurityUtils`, `CustomRequestCache` | `src/main/java/ru/iopump/qa/allure/security/` | None / Basic Auth / OAuth2 Google; Spring Security filter chain |
| Properties | `AllureProperties`, `BasicProperties`, `CleanUpProperties`, `TmsProperties` | `src/main/java/ru/iopump/qa/allure/properties/` | Typed configuration bound from `application.properties` |
| TMS client | `IssuesClient`, `FeignConfiguration` | `src/main/java/ru/iopump/qa/allure/api/` | Feign HTTP client for YouTrack REST API |

---

## Plugin System

### Interface

```java
// src/main/java/ru/iopump/qa/allure/helper/plugin/AllureServerPlugin.java
public interface AllureServerPlugin {
    void onGenerationStart(Collection<Path> resultsDirectories, Context context);
    void onGenerationFinish(Path reportDirectory, Collection<LaunchResults> launchResults, Context context);
    String getName();
    default boolean isEnabled(Context context) { return true; }

    interface Context {
        AllureProperties getAllureProperties();
        TmsProperties tmsProperties();
        BeanFactory beanFactory();
        String getReportUrl();
    }
}
```

### Lifecycle

```
AllureReportGenerator.generate()
    |
    +-- plugins.filter(isEnabled)
    |
    +-- [parallel] plugin.onGenerationStart(resultsDirs, ctx)
    |
    +-- delegate.generate(outputDir, resultsDirs)   // Allure CLI
    |       captures LaunchResults via AggregatorGrabber
    |
    +-- [parallel] plugin.onGenerationFinish(reportDir, launchResults, ctx)
```

### Plugin Discovery

`SpringConfiguration.allureServerPlugins()` calls `ReflectionUtil.createImplementations(AllureServerPlugin.class, null)`.
This scans the classpath (and any jars placed in `/ext` at runtime) for concrete implementations of the interface and instantiates them.

### Built-in Plugins

| Plugin | Class | Trigger | Action |
|---|---|---|---|
| Logo Plugin | `CustomReportMetaPlugin` | `onGenerationFinish` | Injects custom logo PNG + CSS into `plugin/custom-logo/`; updates `widgets/summary.json` with custom title |
| YouTrack integration | `YouTrackPlugin` | `onGenerationFinish` | Extracts issue keys from test result links; posts/updates Markdown statistics comments on YouTrack issues via `IssuesClient` (Feign) |

External plugins: place plugin jars in the `/ext` directory or set `allure.plugins.directory` system property to a directory containing Allure plugin folders. Loaded by `DefaultPluginLoader` in `AllureReportGenerator.loadPlugins()`.

---

## Request Flow Diagrams

### 1. Upload Results then Generate Report

```
Client
  |
  | POST /api/result  (multipart: allureResults.zip)
  v
AllureResultController.uploadResults()
  |
  +-- ResultService.unzipAndStore(inputStream)
  |       unzips to allure/results/<uuid>/
  |       returns uuid
  |
  +-- returns UploadResponse { fileName, uuid }

Client (stores uuid)
  |
  | POST /api/report  (JSON body: ReportGenerateRequest)
  |   { reportSpec: { path: ["project","branch"] },
  |     results: ["<uuid>"],
  |     deleteResults: true }
  v
AllureReportController.generateReport()
  |
  +-- JpaReportService.generate(reportPath, resultDirs, deleteResults, executorInfo, baseUrl)
  |       copies history from previous report (if exists)
  |       writes executor.json to results dir
  |       AllureReportGenerator.generate() -> allure/reports/<new-uuid>/
  |       persists ReportEntity to DB
  |       ServeRedirectHelper.mapRequestTo("reports/project/branch", "allure/reports/<new-uuid>")
  |       marks previous active report as inactive
  |
  +-- returns ReportResponse { uuid, path, url, latest }

Client
  |
  | GET /reports/project/branch        (latest URL)
  v
ServeRedirectHelper.reportPathRedirectToUuid()
  |
  +-- lookup redirectReportPaths map
  +-- 302 redirect -> /allure/reports/<uuid>/index.html
```

### 2. Upload Pre-Built Report

```
Client
  |
  | POST /api/report/{reportPath}  (multipart: allureReportArchive.zip)
  v
AllureReportController.uploadReport(reportPath, allureReportArchive)
  |
  +-- validates Content-Type == application/zip
  +-- JpaReportService.uploadReport(reportPath, inputStream, executorInfo, baseUrl)
  |       ResultService.unzipAndStore() -> allure/reports/<uuid>/
  |       validates index.html present in archive
  |       writes executor.json
  |       persists ReportEntity to DB
  |       ServeRedirectHelper.mapRequestTo(reportPath, allure/reports/<uuid>)
  |       marks previous active report as inactive
  |
  +-- returns ReportResponse { uuid, path, url, latest }  (HTTP 201)
```

### 3. Scheduled Cleanup

```
CleanUpServiceConfiguration (implements SchedulingConfigurer)
  |
  | daily at configured time (allure.clean.time, default MIDNIGHT)
  v
trigger fires
  |
  +-- JpaReportRepository.findAllByCreatedDateTimeIsBefore(closestEdgeDate)
  |
  +-- for each candidate:
  |     match against allure.clean.paths[] rules (per-path ageDays)
  |     fallback to global allure.clean.age-days
  |     if older than edge date:
  |       repository.delete(entity)
  |       FileUtils.deleteQuietly(allure/reports/<uuid>/)
  |
  +-- logs results (dry-run mode: evaluates but does not delete)
```

---

## Vaadin UI Structure

URL namespace: `/ui/**`  (root `/` redirects to `/ui` via `RedirectConfiguration`)

```
MainLayout (AppLayout)
  +-- Header: DrawerToggle + "Allure Server" title
  +-- Drawer (vertical tabs):
  |     Reports  -> ReportsView   (route = "")
  |     Results  -> ResultsView   (route = "results")
  |     Swagger  -> SwaggerView   (route = "swagger-ui")
  |     About    -> AboutView     (route = "about")
  +-- Footer: GitHub / DockerHub / LinkedIn icon links
```

| View | Class | Route | Function |
|---|---|---|---|
| Reports | `ReportsView` | `/ui/` | Grid of `ReportEntity`; delete selection; upload pre-built report dialog |
| Results | `ResultsView` | `/ui/results` | Grid of result archives (UUID, size, created); delete actions |
| Swagger | `SwaggerView` | `/ui/swagger-ui` | Embeds Swagger UI iframe |
| About | `AboutView` | `/ui/about` | Project info |

All views are server-side rendered (Vaadin Flow). Data is fetched directly from Spring beans injected by constructor (no REST calls from UI to API — same JVM process).

---

## Key Filesystem Paths (defaults)

| Path | Configured by | Content |
|---|---|---|
| `allure/results/` | `allure.results-dir` | Uploaded result zips, each in `<uuid>/` subdirectory |
| `allure/reports/` | `allure.reports.dir` | Generated/uploaded reports, each in `<uuid>/` subdirectory |
| `allure/plugins/` | extracted at startup | Allure CLI plugins extracted from classpath `/plugins/**` |
| `allure/reports/history/<uuid>/` | temporary | History directory copied between report generations; deleted after use |
