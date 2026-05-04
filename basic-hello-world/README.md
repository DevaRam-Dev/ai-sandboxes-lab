# AI Sandboxes — basic-hello-world

**POC #2 in the ai-sandboxes-lab portfolio.**

Demonstrates the end-to-end AI sandbox pattern:
a natural-language prompt → a local LLM generates Python → an E2B cloud sandbox executes it → a PNG chart is returned to the caller.

---

## Architecture

```
 User
  │  POST /ask  {"prompt": "Plot bar chart from Jan 2026 to March 2026"}
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
│  4. OllamaClient       ─── HTTP ──▶  gemma4:e2b  (localhost:11434)     │
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

---

## Tech Stack

| Concern          | Choice                              |
|------------------|-------------------------------------|
| Language         | Java 21                             |
| Framework        | Spring Boot 3.2.12 (no Spring Boot) |
| HTTP client      | Spring `RestClient` (Spring 6.1)    |
| JSON             | Jackson (via starter-web)           |
| Local LLM        | Ollama + gemma4:e2b                 |
| Code execution   | E2B cloud sandbox (direct HTTP)     |
| Build tool       | Maven                               |
| Packaging        | Fat JAR (no Docker, no WAR)         |

---

## Pre-requisites

### 1. Java 21 + Maven
```bash
java --version   # must show 21.x
mvn --version    # 3.8+
```

### 2. Ollama (local LLM server)
```bash
# Install Ollama: https://ollama.com
ollama serve                   # start the server (default port 11434)
ollama pull gemma4:e2b         # pull the model used by this POC
```

### 3. E2B API Key
Sign up at https://e2b.dev and copy your API key from the dashboard.

```bash
export E2B_API_KEY=e2b_your_key_here
```

---

## Build & Run

```bash
# 1. Build (skipping tests — no test suite yet)
mvn clean package -DskipTests

# 2. Set your E2B API key
export E2B_API_KEY=e2b_your_key_here

# 3. Start Ollama (separate terminal)
ollama serve

# 4. Start the Spring Boot app
java -jar target/basic-hello-world-0.0.1-SNAPSHOT.jar
```

The app starts on **port 8080**. You should see:
```
Started BasicHelloWorldApplication in X.XXX seconds
```

---

## Test the Endpoint

```bash
# Monthly bar chart
curl -X POST http://localhost:8080/ask \
     -H "Content-Type: application/json" \
     -d '{"prompt": "Plot bar chart from Jan 2026 to March 2026"}' \
     --output chart.png

# Quarterly bar chart
curl -X POST http://localhost:8080/ask \
     -H "Content-Type: application/json" \
     -d '{"prompt": "Show Q1 to Q3 2026 sales"}' \
     --output chart.png

# Open the chart
xdg-open chart.png    # Linux
open chart.png         # macOS
```

---

## Supported Prompt Formats

| Format | Example |
|--------|---------|
| Monthly, year before "to" | `"Jan 2026 to March 2026"` |
| Monthly, year at end | `"Jan to March 2026"` |
| Quarterly | `"Q1 to Q3 2026"` |
| Quarterly with year before "to" | `"Q1 2026 to Q3 2026"` |

Month names can be full (`January`) or abbreviated (`Jan`), case-insensitive.

---

## Configuration

All settings live in `src/main/resources/application.properties`.

| Property | Default | Notes |
|----------|---------|-------|
| `server.port` | `8080` | HTTP port |
| `ollama.base-url` | `http://localhost:11434` | Ollama server |
| `ollama.model` | `gemma4:e2b` | Any model Ollama has pulled |
| `e2b.api.key` | `${E2B_API_KEY}` | Set via env var |
| `e2b.base-url` | `https://api.e2b.dev` | E2B control plane (may be `api.e2b.app`) |
| `e2b.envd.port` | `49982` | envd daemon port inside sandbox |
| `e2b.envd.process.path` | `/envd.process.v1.Process/Start` | Connect RPC path |

---

## E2B API Notes

The current implementation uses **direct HTTP** to E2B (no Java SDK exists on Maven Central).

Two adjustments you may need:

1. **Control-plane URL**: The research used for this POC shows `api.e2b.app` as the primary URL,
   but the property defaults to `api.e2b.dev` (as documented in E2B's quickstart).
   If sandbox creation fails with a 404, change `e2b.base-url=https://api.e2b.app`.

2. **Connect RPC path**: Command execution uses E2B's Connect RPC protocol (gRPC-compatible JSON
   over HTTP). The path `/envd.process.v1.Process/Start` is derived from E2B's proto service
   definition. If this returns a non-200, check the current path in E2B's SDK source:
   https://github.com/e2b-dev/E2B/tree/main/packages/python-sdk

---

## Project Structure

```
basic-hello-world/
├── pom.xml
├── .env.example
├── README.md
└── src/main/
    ├── java/com/sandboxlab/
    │   ├── BasicHelloWorldApplication.java   ← Spring Boot entry point
    │   ├── controller/
    │   │   └── ChartController.java           ← POST /ask endpoint
    │   ├── service/
    │   │   ├── DateRangeParser.java           ← NL → DateRange (regex)
    │   │   ├── SalesDataGenerator.java        ← DateRange → random sales data
    │   │   ├── OllamaClient.java              ← calls Ollama /api/generate
    │   │   ├── CodeExtractor.java             ← strips markdown from LLM response
    │   │   ├── E2BSandboxClient.java          ← E2B sandbox lifecycle + code execution
    │   │   └── PromptOrchestrator.java        ← coordinates all services
    │   └── dto/
    │       ├── AskRequest.java                ← {prompt: "..."} request body
    │       └── DateRange.java                 ← granularity + periods + year
    └── resources/
        └── application.properties
```

---

## What's Next (POC #3)

- Add a minimal frontend (HTML + fetch) that shows the chart in the browser
- Add streaming: show the Ollama generation progress in real time
- Add retry logic for E2B rate-limit errors (HTTP 429)
- Parametrise the chart type (bar, line, pie) in the prompt
