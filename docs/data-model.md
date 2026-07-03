# Allure Server — Data Model & Storage

## ReportEntity

**Source:** `src/main/java/ru/iopump/qa/allure/entity/ReportEntity.java`
**JPA table:** `REPORT_ENTITY` (auto-created / auto-migrated)

| Column | Java Type | JPA | Default | Description |
|---|---|---|---|---|
| `uuid` | `UUID` | `@Id` | — | Primary key; assigned at report creation time |
| `createdDateTime` | `LocalDateTime` | `@Basic` | — | Creation timestamp; stored in UTC (see `version`) |
| `url` | `String` | `@NotEmpty` | — | Direct URL to this specific report (`/reports/{uuid}/`) |
| `path` | `String` | `@NotEmpty` | — | Logical path key used for grouping history (e.g. `my-project/main`) |
| `active` | `boolean` | `@NotNull` | — | `true` for the latest report on a path; `false` for historical entries |
| `level` | `long` | `@PositiveOrZero` | `0` | Monotonically increasing generation counter per path |
| `size` | `long` | `@PositiveOrZero` | `0` | Report directory size in KB |
| `version` | `int` | `@PositiveOrZero` | `1` | `0` = legacy (system timezone); `1` = UTC; controls timezone conversion in `getCreatedDateTime()` |
| `buildUrl` | `String` | `@NotNull` | `""` | CI build URL injected via the executor API or executor.json |

**Timezone note:** `getCreatedDateTime()` normalises all timestamps to UTC on read.
Entries with `version = 0` are converted from the JVM system timezone; entries with `version = 1` are stored directly in UTC.

---

## Filesystem Layout

```
allure/
├── results/                   # Uploaded allure-results zip archives (extracted)
│   ├── <uuid-1>/              # One directory per upload
│   │   ├── *.json             # Test result files
│   │   └── attachments/
│   └── <uuid-2>/
├── reports/                   # Generated and uploaded reports
│   ├── <uuid-a>/              # One directory per ReportEntity row
│   │   ├── index.html
│   │   ├── data/
│   │   ├── widgets/
│   │   └── history/           # Trend data copied from previous report
│   └── <uuid-b>/
├── plugins/                   # Allure plugins extracted at startup (runtime, not committed)
└── db.mv.db                   # H2 database file (default profile only)
```

All paths are relative to the process working directory. Override roots with `allure.resultsDir` and `allure.reports.dir`.

---

## Storage Lifecycle

```
Upload ZIP
    |
    v
Extract to results/<uuid>/
    |
    v
POST /api/report  (generate)
    |
    +-- Copy history/ from previous report (path's latest)
    |
    +-- Run allure generate
    |
    v
Report written to reports/<uuid>/
    |
    +-- ReportEntity persisted (active=true, level=N+1)
    |
    +-- Previous ReportEntity for same path set active=false
    |
    +-- Excess history (> history-level) deleted from disk + DB
    |
    v
GET /reports/{path}
    |
    v
302 redirect  ->  /allure/reports/<uuid>/index.html
                  (resolved via ServeRedirectHelper in-memory map)
```

---

## History Management

| Concept | Detail |
|---|---|
| Grouping key | `path` field on `ReportEntity`; set by the caller at generation time |
| Active report | Exactly one `active=true` row per path at any time |
| Level | Integer counter incremented on each generation; used for ordering history |
| Max depth | `allure.reports.history-level` (default `20`) |
| Pruning | After generation, reports with `level < (latest - history-level)` are deleted from disk and DB |
| Trend data | `history/` directory copied from the current latest report into the new report before `allure generate` runs, enabling Allure trend charts |

---

## Results (Filesystem Only)

Results have no database representation — they exist only on disk.

| Aspect | Detail |
|---|---|
| Location | `allure/results/<uuid>/` |
| Created by | `POST /api/result` — accepts a zip archive |
| UUID source | Generated server-side at upload time |
| Zip extraction | Nested directories matching `allure-.+` or `report.*` are flattened one level |
| Lifecycle | Optionally deleted after report generation via `deleteResults=true` query parameter |
| Consumed by | `POST /api/report` — one or more result UUIDs passed as body |
