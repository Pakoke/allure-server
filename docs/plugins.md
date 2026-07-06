# Allure Server Plugin System

## AllureServerPlugin Interface

Source: `src/main/java/ru/iopump/qa/allure/helper/plugin/AllureServerPlugin.java`

```java
public interface AllureServerPlugin {
    void onGenerationStart(Collection<Path> resultsDirectories, Context context);
    void onGenerationFinish(Path reportDirectory, Collection<LaunchResults> launchResults, Context context);
    String getName();
    default boolean isEnabled(Context context) { return true; }
}
```

| Method | When called | Purpose |
|---|---|---|
| `onGenerationStart` | Before Allure generates the report | Pre-process result directories |
| `onGenerationFinish` | After report HTML is written to disk | Post-process generated report |
| `getName()` | Logging/identification | Returns plugin display name |
| `isEnabled(Context)` | Before each hook invocation | Conditional activation; default `true` |

### Context Interface

Provided to both hooks. Gives access to server-wide state:

| Method | Returns |
|---|---|
| `getAllureProperties()` | `AllureProperties` — logo, title, base URL, storage path |
| `tmsProperties()` | `TmsProperties` — TMS host, token, issue key pattern, dryRun flag |
| `beanFactory()` | Spring `BeanFactory` — retrieve any registered Spring bean |
| `getReportUrl()` | `String` — full URL to the generated report |

---

## Plugin Discovery

Source: `src/main/java/ru/iopump/qa/allure/config/SpringConfiguration.java`

```
Classpath scan
  └── ReflectionUtil.createImplementations(AllureServerPlugin.class, null)
        └── registered as Collection<AllureServerPlugin> Spring bean
```

At startup `SpringConfiguration.allureServerPlugins()` scans the full classpath for all non-abstract implementations of `AllureServerPlugin` and registers them as a single Spring bean. Discovery errors are caught and logged; the server starts with an empty plugin list rather than failing.

External plugins are loaded by placing JARs in the `/ext` directory. The JVM is launched with `-Dloader.path=/ext` (Spring Boot `PropertiesLauncher`), which adds `/ext` to the classpath before scanning.

---

## Built-in Server Plugins

### 1. CustomReportMetaPlugin

Source: `src/main/java/ru/iopump/qa/allure/helper/plugin/CustomReportMetaPlugin.java`

Name: `"Logo Plugin"`. Always enabled.

Actions run in `onGenerationFinish`:

| Config property | Action |
|---|---|
| `allure.logo` (Spring Resource) | Copies logo file into `plugin/custom-logo/` inside the generated report; writes a `styles.css` that points the `.side-nav__brand` CSS class at the new file |
| `allure.title` (String) | Reads `widgets/summary.json`, sets `reportName` field, writes it back |

Neither action runs if the corresponding property is absent.

### 2. YouTrackPlugin

Source: `src/main/java/ru/iopump/qa/allure/helper/plugin/YouTrackPlugin.java`

Name: `"YouTrack integration"`. Enabled only when `tms.enabled=true`.

Actions run in `onGenerationFinish`:

```
launchResults
  └── for each TestResult
        └── for each Link whose URL host matches tms.host
              └── extract issue key via tms.issueKeyPattern regex
  └── group TestResults by issue key
  └── for each issue (parallelStream)
        └── build MarkdownStatisticModel (scenario table)
        └── GET existing comments on issue
        └── if comment with title "E2E testing scenarios" exists → merge + update
            else → create new comment
```

Key behaviours:
- SKIPPED and UNKNOWN test statuses are excluded from the markdown table.
- When `tms.dryRun=true` (or the `dryRun` constructor flag) no HTTP calls are made to YouTrack; the model is still built and logged.
- Uses Feign client `IssuesClient` (obtained via `context.beanFactory()`).
- Source: `src/main/java/ru/iopump/qa/allure/api/youtrack/IssuesClient.java`

### 3. DatadogPlugin

Source: `src/main/java/ru/iopump/qa/allure/helper/plugin/DatadogPlugin.java`

Name: `"Datadog metrics"`. Enabled only when `datadog.enabled=true`.

Sends test report metrics to Datadog via DogStatsD (UDP) in `onGenerationFinish`.

**Metrics sent** (all as gauges, prefixed with `datadog.prefix`):

| Metric | Tags | Description |
|---|---|---|
| `tests.total` | `path` | Total test count |
| `tests.passed` | `path`, `status:passed` | Passed tests |
| `tests.failed` | `path`, `status:failed` | Failed tests |
| `tests.broken` | `path`, `status:broken` | Broken tests |
| `tests.skipped` | `path`, `status:skipped` | Skipped tests |
| `tests.unknown` | `path`, `status:unknown` | Unknown status tests |
| `tests.pass_rate` | `path` | `passed / (total - skipped) * 100` |
| `tests.duration_ms` | `path` | Sum of all test durations |
| `tests.by_suite` | `path`, `suite:{name}`, `status:{status}` | Count per suite label + status |
| `tests.by_feature` | `path`, `feature:{name}`, `status:{status}` | Count per feature label + status |

**Key behaviours:**
- Creates a `NonBlockingStatsDClient` per invocation, sends all gauges, then closes.
- `path` tag is the report directory name (UUID).
- Extra global tags configurable via `datadog.tags` list.
- When `datadog.dryRun=true`, all metrics are logged but no UDP packets are sent.
- Properties obtained via `context.beanFactory().getBean(DatadogProperties.class)`.

Source: `src/main/java/ru/iopump/qa/allure/properties/DatadogProperties.java`

### 4. ExecutorCiPlugin

Source: `src/main/java/ru/iopump/qa/allure/helper/ExecutorCiPlugin.java`

Extends Allure's built-in `ExecutorPlugin`. Overrides `readResults` with the following priority logic:

```
if executor.json exists AND is non-empty
  └── use native executor.json (defer to test framework)
else
  └── read ci-executor.json (written by Allure Server itself)
        └── deserialise into ExecutorInfo and pass to visitor
```

This allows CI-generated executor metadata from the server to coexist with test-framework-generated `executor.json` without conflict.

---

## Bundled Allure UI Plugins

Located in `src/main/resources/plugins/`. All at version **2.29.0**.

| Directory | Purpose |
|---|---|
| `behaviors-plugin` | Groups tests by Allure `@Feature` / `@Story` behaviour tree |
| `custom-logo-plugin` | Allure UI plugin that renders a custom logo (used by `CustomReportMetaPlugin`) |
| `jira-plugin` | Links test results to Jira issues |
| `junit-xml-plugin` | Parses JUnit XML result files |
| `packages-plugin` | Groups tests by Java package hierarchy |
| `screen-diff-plugin` | Renders screenshot diff attachments |
| `trx-plugin` | Parses Visual Studio TRX result files |
| `xctest-plugin` | Parses Apple XCTest result files |
| `xray-plugin` | Links test results to Xray (Jira) test cases |
| `xunit-xml-plugin` | Parses generic xUnit XML result files |

---

## Adding External Plugins

1. Implement `AllureServerPlugin` in a JAR:

```java
public class MyPlugin implements AllureServerPlugin {
    @Override
    public void onGenerationStart(Collection<Path> dirs, Context ctx) { /* ... */ }

    @Override
    public void onGenerationFinish(Path reportDir, Collection<LaunchResults> results, Context ctx) { /* ... */ }

    @Override
    public String getName() { return "My Plugin"; }
}
```

2. Package the JAR (include all dependencies, or rely on server's classpath for Allure/Spring classes).

3. Place the JAR in the `/ext` directory — mount it as a Docker volume:

```yaml
volumes:
  - ./ext:/ext:rw
```

4. The server's `PropertiesLauncher` (`-Dloader.path=/ext`) picks it up on next startup. `SpringConfiguration` scans and registers the implementation automatically.
