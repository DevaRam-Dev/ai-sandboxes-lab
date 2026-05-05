# Architecture

## Overview

basic-hello-world is a proof-of-concept that demonstrates the full AI sandboxing pattern in a single
deployable artifact: the user types a natural-language chart request, a local LLM generates Python
code for it, and that code executes inside an isolated E2B cloud sandbox before the PNG result is
returned to the caller. The interesting design challenge is not the chart rendering — it is the
boundary between trusted orchestration logic (the Java JVM) and untrusted, model-generated code (the
Python that runs in E2B). Every architectural decision in the codebase flows from keeping that
boundary explicit and enforceable.

---

## Usage Flows

Both interfaces — `curl` and the React UI — call the same `POST /ask` backend endpoint. The only
difference is how the request arrives.

```
┌─────────────────────────┐         ┌──────────────────────────────────────┐
│  curl / API client      │         │  React UI  (Vite dev server, :5173)  │
│                         │         │  proxies /api/* → localhost:8080     │
└────────────┬────────────┘         └──────────────────┬───────────────────┘
             │  POST /ask                              │  POST /api/ask
             │  {"prompt": "Plot bar chart …"}         │  (proxied → /ask)
             └──────────────────────┬──────────────────┘
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  ChartController  (Spring MVC, port 8080)                               │
└─────────────────────────────────────────────────────────────────────────┘
  │
  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  PromptOrchestrator  (pipeline coordinator)                             │
│                                                                         │
│  1. DateRangeParser    ─── regex ──▶  DateRange (periods + year)        │
│  2. SalesDataGenerator ─── random ─▶ Map<period, value 50-300>         │
│  3. Build LLM prompt with data inlined                                  │
│  4. OllamaClient       ─── HTTP ──▶  qwen2.5-coder:1.5b (localhost:11434) │
│  5. CodeExtractor      ─── regex ──▶  clean Python code                 │
│  6. E2BSandboxClient   ─── REST ──▶  E2B Cloud Sandbox                 │
└─────────────────────────────────────────────────────────────────────────┘
                                             │
                              ┌──────────────┴──────────────────────────┐
                              │  E2B Sandbox (Ubuntu VM in the cloud)   │
                              │                                         │
                              │  Control Plane (api.e2b.dev)           │
                              │    POST /sandboxes  → create            │
                              │    DELETE /sandboxes/{id}  → kill       │
                              │                                         │
                              │  Data Plane  (envd daemon)              │
                              │    POST /files?path=…  → upload .py    │
                              │    Connect RPC: Process/Start  → run   │
                              │    GET  /files?path=…  → download PNG  │
                              └─────────────────────────────────────────┘
                                             │
  PNG bytes ◀──────────────────────────────────
  │
  ▼
 HTTP 200  image/png
```

### curl flow

The caller sends `POST /ask` directly to port 8080 with a JSON body. The response is raw PNG bytes
(`Content-Type: image/png`). This path requires no frontend and is useful for scripting and
integration tests.

```bash
curl -X POST http://localhost:8080/ask \
     -H "Content-Type: application/json" \
     -d '{"prompt": "Plot pie chart from Jan 2026 to March 2026"}' \
     --output chart.png
```

### React UI flow

The Vite dev server runs on port 5173 and is configured to proxy `/api/*` requests to
`localhost:8080`. The React app posts to `/api/ask`; Vite strips the `/api` prefix and forwards to
`/ask`. No CORS configuration is needed because both ports are on the same machine and the proxy
makes them appear as the same origin. The response blob is converted to an object URL and rendered
inline in the chat view.

---

## Component Breakdown

| Component | Role | Technology | Location |
|---|---|---|---|
| React UI | Chat interface; renders user messages and PNG responses in a scrollable feed; proxies requests through Vite | React 18, Vite, JavaScript | `../frontend/src/` |
| `App.jsx` | Top-level state: message list, loading flag, modal control | React hooks | `../frontend/src/App.jsx` |
| `ChatMessage.jsx` | Renders one message bubble (user text or assistant chart/error) | React | `../frontend/src/ChatMessage.jsx` |
| `InputBar.jsx` | Controlled text input with submit; disables during in-flight request | React | `../frontend/src/InputBar.jsx` |
| `ChartModal.jsx` | Full-screen overlay for clicking into a chart | React | `../frontend/src/ChartModal.jsx` |
| `TransactionIdFilter` | Servlet filter; assigns a `yyyyMMddHHmmssSSS` transaction ID to every request via SLF4J MDC | Spring `OncePerRequestFilter` | `filter/TransactionIdFilter.java` |
| `ChartController` | HTTP boundary; validates the request, delegates to `PromptOrchestrator`, maps exceptions to HTTP status codes | Spring MVC `@RestController` | `controller/ChartController.java` |
| `PromptOrchestrator` | Pipeline coordinator; calls each service in order, logs step inputs/outputs and timing | Spring `@Component` | `service/PromptOrchestrator.java` |
| `DateRangeParser` | Converts a free-text prompt into a structured `DateRange` using three compiled regex patterns; no NLP libraries | Java regex | `service/DateRangeParser.java` |
| `SalesDataGenerator` | Produces random integer sales values (50–300) for each period in the `DateRange` | Java `Random` | `service/SalesDataGenerator.java` |
| `OllamaClient` | HTTP client for Ollama's `/api/generate` endpoint; uses Spring `RestClient` with a 60 s read timeout; `stream=false` | Spring `RestClient` | `service/OllamaClient.java` |
| `CodeExtractor` | Strips markdown fences and preamble from the raw LLM response; four extraction strategies tried in order | Java regex | `service/CodeExtractor.java` |
| `E2BSandboxClient` | Writes the generated code to a temp file, spawns `python3 e2b_sandbox_client.py` via `ProcessBuilder`, reads the output PNG | Java `ProcessBuilder` | `service/E2BSandboxClient.java` |
| Ollama | Local LLM inference server; serves `qwen2.5-coder:1.5b` on port 11434 | External process | `localhost:11434` |
| E2B Cloud Sandbox | Ephemeral Ubuntu VM; executes the LLM-generated Python code under `e2b-code-interpreter-v1`; isolated from the host | E2B managed service | `api.e2b.dev` |

---

## Three Trust Planes

| Plane | What runs there | Trust level |
|---|---|---|
| Java JVM (Spring Boot process) | Application code: controllers, orchestrator, all service beans | Trusted — you wrote and compiled it |
| Python subprocess (`e2b_sandbox_client.py`) | E2B SDK wrapper; creates sandbox, uploads code, retrieves PNG | Trusted — you wrote it; spawned via `ProcessBuilder` with a controlled environment |
| E2B cloud sandbox (Ubuntu VM) | LLM-generated Python code; matplotlib execution | Untrusted — generated by the model at runtime, never statically verified |

The separation of the third plane from the first two is the entire point of the sandboxing pattern.
When the LLM produces code that calls `os.system("rm -rf /")`, reads `/etc/passwd`, or makes
outbound network requests, none of that touches the host machine — it runs inside an ephemeral VM
that E2B destroys after the request completes. The Java process never evaluates the Python code; it
only handles it as an opaque string, writes it to a temp file, and reads back PNG bytes. The Python
subprocess does the same: it passes the code to E2B's SDK and waits for output. Neither trusted
plane interprets the model's output as instructions.

---

## Design Decisions

### Why Ollama (local LLM)

Prompts sent to the LLM include real sales data values. Running inference locally on Ollama means
that data never leaves the machine, no API key is required for the LLM step, and the entire POC
works offline. The tradeoff is latency: on a CPU-only machine, `qwen2.5-coder:1.5b` takes 15–20
seconds per request. A cloud-hosted model would be faster but would add egress, cost, and a privacy
boundary to manage.

### Why E2B Cloud Sandbox

Code execution needs to be isolated from the host. Running untrusted, LLM-generated Python inside
the same JVM — via JSR-223 or a ProcessBuilder call that writes to the local filesystem — would be a
security risk: the generated code could read local files, exfiltrate environment variables, or crash
the JVM. E2B provides ephemeral Ubuntu VMs that are created fresh for each request and killed
afterward. The Python code runs inside the VM with no route back to the host's filesystem or network.

### Why a Python Subprocess

E2B publishes official SDKs for Python and Node.js only. There is no Java SDK on Maven Central.
`E2BSandboxClientHttpRaw.java` (present in the repo) is the original direct-HTTP attempt; it was
abandoned because E2B's Connect RPC protocol for process execution is subtle to implement correctly
and changed between SDK versions. Delegating to a small Python wrapper (`e2b_sandbox_client.py`) that
uses the official SDK is more robust and easier to keep in sync with E2B API changes.

### Why ProcessBuilder (not a Java E2B SDK)

`ProcessBuilder` lets Java spawn the Python wrapper as a child process, pass it the code file and
output path as arguments, and inherit the `E2B_API_KEY` environment variable without hardcoding it.
Stdout and stderr are drained on background threads so the Java logs receive the Python wrapper's
progress lines in real time, interleaved with the MDC transaction ID. The subprocess timeout (default
120 s) is enforced by `Process.waitFor(timeout, TimeUnit.SECONDS)`.

---

## End-to-End Timing

Measured from a real request (`transactionId: 20260505124636419`, prompt: "Plot pie chart from Jan
2026 to March 2026", model: `qwen2.5-coder:1.5b`, running on CPU).

| Step | Component | Duration |
|---|---|---|
| 1 | DateRangeParser | 1 ms |
| 2 | SalesDataGenerator | 0 ms |
| 3 | Build LLM prompt | 1 ms |
| 4 | OllamaClient (LLM inference) | 18,879 ms |
| 5 | CodeExtractor | 1 ms |
| 6 | E2BSandboxClient (sandbox + execution) | 2,556 ms |
| — | **Total pipeline** | **21,441 ms** |

Ollama is the dominant cost at ~88% of total time, entirely due to CPU-only inference. E2B sandbox
creation and teardown account for most of the remaining 2.5 seconds. All other steps are
microsecond-level string operations.

---

## File Layout

```
ai-sandboxes-lab/
├── basic-hello-world/           ← this Maven project (Spring Boot fat JAR)
│   ├── pom.xml
│   ├── .env.example
│   ├── README.md
│   ├── e2b_sandbox_client.py    ← Python subprocess wrapper (E2B SDK)
│   ├── docs/                    ← this folder
│   │   ├── ARCHITECTURE.md
│   │   ├── LOGGING.md
│   │   └── DEBUGGING-STORY.md
│   └── src/main/
│       ├── java/com/sandboxlab/
│       │   ├── BasicHelloWorldApplication.java   ← Spring Boot entry point
│       │   ├── controller/
│       │   │   └── ChartController.java           ← POST /ask endpoint
│       │   ├── filter/
│       │   │   └── TransactionIdFilter.java       ← MDC transaction ID injection
│       │   ├── service/
│       │   │   ├── DateRangeParser.java           ← NL → DateRange (regex)
│       │   │   ├── SalesDataGenerator.java        ← DateRange → random sales data
│       │   │   ├── OllamaClient.java              ← calls Ollama /api/generate
│       │   │   ├── CodeExtractor.java             ← strips markdown from LLM response
│       │   │   ├── E2BSandboxClient.java          ← subprocess-based E2B integration
│       │   │   └── PromptOrchestrator.java        ← coordinates all services
│       │   └── dto/
│       │       ├── AskRequest.java                ← {prompt: "..."} request body
│       │       └── DateRange.java                 ← granularity + periods + year
│       └── resources/
│           ├── application.properties
│           └── logback-spring.xml                 ← logging configuration
└── frontend/                    ← React UI (Vite, port 5173) — sibling of this project
    ├── package.json
    ├── vite.config.js           ← proxy: /api/* → localhost:8080
    └── src/
        ├── App.jsx              ← message list, fetch logic, modal state
        ├── ChatMessage.jsx      ← renders one bubble (user or assistant)
        ├── InputBar.jsx         ← text input + submit button
        └── ChartModal.jsx       ← full-screen chart overlay
```

---

See also: [LOGGING.md](LOGGING.md) — logging architecture and MDC transaction tracing  
See also: [DEBUGGING-STORY.md](DEBUGGING-STORY.md) — how a silent chart-type bug was diagnosed in 5 minutes
