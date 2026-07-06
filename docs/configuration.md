# Allure Server — Configuration Reference

## AllureProperties

**Prefix:** `allure`
**Source:** `src/main/java/ru/iopump/qa/allure/properties/AllureProperties.java`

| Property | Default | Description |
|---|---|---|
| `allure.resultsDir` | `allure/results/` | Directory where uploaded result archives are extracted |
| `allure.reports.dir` | `allure/reports/` | Filesystem directory where generated reports are stored |
| `allure.reports.path` | `reports/` | URL path prefix for serving reports |
| `allure.reports.history-level` | `20` | Maximum number of historical reports retained per path |
| `allure.date-format` | `yy/MM/dd HH:mm:ss` | Date format used in UI and API responses |
| `allure.server-base-url` | _(auto-detected)_ | Base URL prepended to report links; auto-detected from the incoming request when not set |
| `allure.logo` | `""` | Resource path (`file:/images/logo.png`) or URL for a custom logo; empty string uses default |
| `allure.title` | `BrewCode \| Allure Report` | Page title shown in the UI |

---

## CleanUpProperties

**Prefix:** `allure.clean`
**Source:** `src/main/java/ru/iopump/qa/allure/properties/CleanUpProperties.java`

| Property | Default | Description |
|---|---|---|
| `allure.clean.dryRun` | `false` | When `true`, cleanup runs in simulation mode — logs what would be deleted without deleting |
| `allure.clean.time` | `00:00` | Time of day (HH:mm) at which the scheduled cleanup task fires |
| `allure.clean.ageDays` | `90` | Global age threshold in days; reports older than this are deleted |
| `allure.clean.paths` | `[]` | Per-path overrides; each item has `path` (string) and `ageDays` (int) |

**Per-path override example (YAML):**

```yaml
allure:
  clean:
    paths:
      - path: "manual_uploaded"
        ageDays: 30
```

When `paths` entries are present the cleanup scheduler uses the minimum of all `ageDays` values as its earliest edge date so no configured retention is skipped.

---

## BasicProperties

**Prefix:** `basic.auth`
**Source:** `src/main/java/ru/iopump/qa/allure/properties/BasicProperties.java`

| Property | Default | Description |
|---|---|---|
| `basic.auth.enable` | `false` | Enables HTTP Basic authentication for all endpoints |
| `basic.auth.username` | `admin` | Username for Basic auth |
| `basic.auth.password` | `admin` | Password for Basic auth |

---

## TmsProperties

**Prefix:** `tms`
**Source:** `src/main/java/ru/iopump/qa/allure/properties/TmsProperties.java`

| Property | Default | Description |
|---|---|---|
| `tms.enabled` | `false` | Enables TMS (YouTrack) integration |
| `tms.host` | `tms.localhost` | YouTrack hostname |
| `tms.api-base-url` | `https://${tms.host}/api` | Full API base URL; defaults to `https://{tms.host}/api` |
| `tms.project` | _(none)_ | YouTrack project key |
| `tms.token` | _(none)_ | Bearer token for YouTrack API authentication |
| `tms.issue-key-pattern` | `[A-Za-z]+-\d+` | Regex used to extract issue keys from test names |
| `tms.dry-run` | `false` | When `true`, TMS calls are logged but not executed |

---

## DatadogProperties

**Prefix:** `datadog`
**Source:** `src/main/java/ru/iopump/qa/allure/properties/DatadogProperties.java`

| Property | Default | Description |
|---|---|---|
| `datadog.enabled` | `false` | Enables Datadog metrics plugin |
| `datadog.host` | `localhost` | DogStatsD agent hostname |
| `datadog.port` | `8125` | DogStatsD agent UDP port |
| `datadog.prefix` | `allure` (env: `DATADOG_PREFIX`) | Metric name prefix (e.g. `voxsmart.allure`) |
| `datadog.tags` | `[]` | Extra global tags added to every metric |
| `datadog.dry-run` | `false` | When `true`, metrics are logged but not sent |

---

## Spring Profiles

**Sources:**
- `src/main/resources/application.yaml`
- `src/main/resources/application-oauth.yaml`

| Profile | Effect |
|---|---|
| _(default)_ | H2 embedded file-based database; no OAuth2; Basic auth disabled by default |
| `oauth` | Enables Google OAuth2 login; requires `OAUTH2_GOOGLE_ALLURE_CLIENT_ID` and `OAUTH2_GOOGLE_ALLURE_CLIENT_SECRET` env vars; sets `app.security.enable-oauth2: true` |

Activate a profile via `SPRING_PROFILES_ACTIVE=oauth` or `--spring.profiles.active=oauth`.

---

## Environment Variables (Docker / Kubernetes)

| Variable | Maps To | Notes |
|---|---|---|
| `PORT` | `server.port` | HTTP listen port; default `8080` |
| `JAVA_OPTS` | JVM flags | Passed to the JVM at startup (e.g. `-Xmx512m`) |
| `SPRING_PROFILES_ACTIVE` | Spring profiles | E.g. `oauth` to enable OAuth2 |
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` | JDBC URL; override for PostgreSQL |
| `SPRING_DATASOURCE_USERNAME` | `spring.datasource.username` | Database username |
| `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` | Database password |
| `SPRING_JPA_DATABASE` | `spring.jpa.database` | Set to `postgresql` when using Postgres |
| `BASIC_AUTH_ENABLE` | `basic.auth.enable` | `true` to enable HTTP Basic auth |
| `TMS_ENABLED` | `tms.enabled` | `true` to enable YouTrack integration |
| `TMS_HOST` | `tms.host` | YouTrack hostname |
| `TMS_TOKEN` | `tms.token` | YouTrack bearer token |
| `TMS_DRYRUN` | `tms.dry-run` | `true` for simulation mode |
| `DATADOG_ENABLED` | `datadog.enabled` | `true` to enable Datadog metrics |
| `DATADOG_PREFIX` | `datadog.prefix` | Metric prefix (e.g. `voxsmart.allure`) |
| `ALLURE_CLEAN_AGEDAYS` | `allure.clean.ageDays` | Global report retention in days |
| `OAUTH2_GOOGLE_ALLURE_CLIENT_ID` | `spring.security.oauth2...google.client-id` | Required when `oauth` profile is active |
| `OAUTH2_GOOGLE_ALLURE_CLIENT_SECRET` | `spring.security.oauth2...google.client-secret` | Required when `oauth` profile is active |

---

## Database

| Aspect | Default (H2) | Production (PostgreSQL) |
|---|---|---|
| Driver | H2 embedded | PostgreSQL JDBC |
| JDBC URL | `jdbc:h2:file:./allure/db` | `SPRING_DATASOURCE_URL` |
| Username | `sa` | `SPRING_DATASOURCE_USERNAME` |
| Password | _(empty)_ | `SPRING_DATASOURCE_PASSWORD` |
| `spring.jpa.database` | `H2` | `postgresql` |
| DDL auto | `update` (schema auto-migrated) | `update` (schema auto-migrated) |

Schema is managed by Hibernate `ddl-auto: update` — no migration tooling required for standard upgrades. The H2 data file is written to `allure/db.mv.db` relative to the working directory.
