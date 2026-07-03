# Allure Server — REST API Reference

Swagger UI: `GET /swagger-ui.html` (also reachable at `/swagger` and `/api` — both redirect there).

---

## Report API — `/api/report`

Source: `src/main/java/ru/iopump/qa/allure/controller/AllureReportController.java`

### GET /api/report

List all generated reports, optionally filtered by path prefix.

| Parameter | Location | Required | Description |
|---|---|---|---|
| `path` | query | no | Filter: return only reports whose `path` starts with this value |

**Response** `200 OK` — `Collection<ReportResponse>`

```json
[
  {
    "uuid": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "path": "project/main",
    "url": "http://host/reports/3fa85f64-5717-4562-b3fc-2c963f66afa6/",
    "latest": "http://host/reports/project/main"
  }
]
```

Results are cached under the cache name `"reports"`. Any POST or DELETE to `/api/report` or `/api/result` evicts this cache.

---

### POST /api/report

Generate a report from previously uploaded result archives.

**Request body** `application/json`

```json
{
  "reportSpec": {
    "path": ["project", "branch"],
    "executorInfo": {
      "name": "Jenkins",
      "type": "jenkins",
      "url": "https://ci.example.com",
      "buildOrder": 42,
      "buildName": "#42",
      "buildUrl": "https://ci.example.com/job/42",
      "reportUrl": "",
      "reportName": "My Report"
    }
  },
  "results": [
    "3fa85f64-5717-4562-b3fc-2c963f66afa6"
  ],
  "deleteResults": true
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `reportSpec.path` | `string[]` | yes | Path segments joined with `/` to form the logical report path (e.g. `["project","branch"]` -> `project/branch`) |
| `reportSpec.executorInfo` | object | no | CI executor metadata embedded into the generated report |
| `results` | `string[]` | yes | One or more UUIDs returned by `POST /api/result` |
| `deleteResults` | boolean | no (default `true`) | Delete result directories after generation |

**Response** `201 Created` — `ReportResponse`

```json
{
  "uuid": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "path": "project/branch",
  "url": "http://host/reports/3fa85f64-5717-4562-b3fc-2c963f66afa6/",
  "latest": "http://host/reports/project/branch"
}
```

History is copied automatically from the previous report at the same path. The previous report is marked `active = false`.

---

### POST /api/report/{reportPath}

Upload a pre-built Allure report archive (zip).

| Parameter | Location | Required | Description |
|---|---|---|---|
| `reportPath` | path | yes | Logical path to register the report under (e.g. `project/branch`) |
| `allureReportArchive` | multipart form field | yes | ZIP file containing a fully rendered Allure report (must contain `index.html` at the root) |

**Content-Type:** `multipart/form-data`
**Archive Content-Type:** `application/zip` or `application/x-zip-compressed`

**Response** `201 Created` — `ReportResponse`

```json
{
  "uuid": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "path": "project/branch",
  "url": "http://host/reports/3fa85f64-5717-4562-b3fc-2c963f66afa6/",
  "latest": "http://host/reports/project/branch"
}
```

The server validates that the archive is a valid ZIP and contains `index.html`. The previous active report at the same path is marked `active = false`.

---

### DELETE /api/report/history

Delete all inactive (historical) report records and their files. Active reports are preserved; their `history/` subdirectory is also deleted.

**Response** `200 OK` — `Collection<ReportResponse>` (the deleted inactive records)

---

### DELETE /api/report

Delete all reports, or only reports older than a given timestamp.

| Parameter | Location | Required | Description |
|---|---|---|---|
| `seconds` | query | no | Unix epoch seconds (UTC). When supplied, only reports created before this timestamp are deleted. When omitted, all reports are deleted. |

**Response** `200 OK` — `Collection<ReportResponse>` (deleted records)

---

## Result API — `/api/result`

Source: `src/main/java/ru/iopump/qa/allure/controller/AllureResultController.java`

### GET /api/result

List all uploaded result archives.

**Response** `200 OK` — `Collection<ResultResponse>`

```json
[
  {
    "uuid": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "size": 1024,
    "created": "2024-01-15T10:30:00"
  }
]
```

Results are cached under `"results"`. Any POST or DELETE to `/api/result` evicts this cache.

---

### GET /api/result/{uuid}

Get metadata for a specific result archive.

| Parameter | Location | Required | Description |
|---|---|---|---|
| `uuid` | path | yes | UUID returned when the result was uploaded |

**Response** `200 OK` — `ResultResponse`. Returns an empty object if UUID is not found.

---

### POST /api/result

Upload an `allure-results.zip` archive.

| Parameter | Location | Required | Description |
|---|---|---|---|
| `allureResults` | multipart form field | yes | ZIP of Allure result files. Content-Type must be `application/zip` or `application/x-zip-compressed`. File extension must be `.zip`. |

**Content-Type:** `multipart/form-data`

**Response** `201 Created` — `UploadResponse`

```json
{
  "fileName": "allure-results.zip",
  "uuid": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

The UUID is stored as the directory name under `allure/results/`. Use it in `POST /api/report` → `results` array.

---

### DELETE /api/result

Delete all uploaded result archives.

**Response** `200 OK` — `Collection<ResultResponse>` (all records that existed before deletion)

---

### DELETE /api/result/{uuid}

Delete a specific result archive by UUID.

| Parameter | Location | Required | Description |
|---|---|---|---|
| `uuid` | path | yes | UUID of the result to delete |

**Response** `200 OK` — `ResultResponse` (the deleted record)

---

## Response Shapes

### ReportResponse

Source: `src/main/java/ru/iopump/qa/allure/model/ReportResponse.java`

| Field | Type | Description |
|---|---|---|
| `uuid` | UUID | Unique identifier; also the filesystem directory name under `allure/reports/` |
| `path` | string | Logical path (e.g. `project/branch`) |
| `url` | string | Direct URL to this specific report version: `<base>/reports/<uuid>/` |
| `latest` | string | Canonical "latest" URL for this path: `<base>/reports/<path>` — always points to the currently active report |

### ResultResponse

Source: `src/main/java/ru/iopump/qa/allure/model/ResultResponse.java`

| Field | Type | Description |
|---|---|---|
| `uuid` | string | UUID; also the filesystem directory name under `allure/results/` |
| `size` | long | Directory size in KB |
| `created` | LocalDateTime | Creation timestamp (UTC) |

### UploadResponse

Source: `src/main/java/ru/iopump/qa/allure/model/UploadResponse.java`

| Field | Type | Description |
|---|---|---|
| `fileName` | string | Original uploaded filename |
| `uuid` | string | UUID assigned to this result set |

---

## Report Serving

Static report files are served directly by Spring MVC resource handlers configured in `RedirectConfiguration`.

```
GET /reports/{path}
  -> ServeRedirectHelper looks up path in redirectReportPaths map
  -> 302 redirect to /allure/reports/<uuid>/index.html
     (the UUID of the currently active report for that path)

GET /reports/<uuid>/**
  -> served directly from filesystem allure/reports/<uuid>/
     (PathResourceResolver; falls back to index.html for sub-paths)
```

The redirect map is populated at startup (for all active reports) and updated on every report generation or upload.

---

## Authentication

Source: `src/main/java/ru/iopump/qa/allure/security/SecurityConfiguration.java`

Three modes, activated by configuration properties:

| Mode | How to activate | Properties |
|---|---|---|
| **None** (default) | Do not set either property below | — |
| **Basic Auth** | Set `basic.auth.enable=true` | `basic.auth.username` (default `admin`), `basic.auth.password` (default `admin`) |
| **OAuth2 Google** | Set `app.security.enable-oauth2=true` | Standard Spring Security OAuth2 client properties (`spring.security.oauth2.client.*`) |

Both modes can be active simultaneously (`basic.auth.enable=true` + `app.security.enable-oauth2=true`). When any auth mode is enabled, all endpoints require authentication. Vaadin framework internal requests are always permitted without authentication.

Credentials for Basic Auth are stored in-memory (`InMemoryUserDetailsManager`); the user is granted roles `USER` and `ADMIN`.

---

## Error Handling

| Condition | HTTP Status |
|---|---|
| `@Validated` constraint violation (e.g. invalid UUID format in path variable) | `400 Bad Request` |
| Upload: Content-Type not `application/zip` | `400 Bad Request` (IllegalArgumentException) |
| Upload: file extension not `.zip` | `400 Bad Request` (IllegalArgumentException) |
| Redirect path not found in map | `500` (RuntimeException from `ServeRedirectHelper`) |
