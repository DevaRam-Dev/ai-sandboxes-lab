# AI Sandboxes Lab — basic-hello-world Demo Runbook

This document walks through demoing the AI sandbox project end-to-end. The use case: a user types a
plain-English chart request in the React UI, the Spring Boot backend orchestrates the pipeline
(Ollama LLM generates Python code, the code runs in an E2B cloud sandbox, the PNG comes back), and
the chart appears in the UI. Follow the steps top-to-bottom — pre-flight checks, the actual command,
and verification of expected output. Designed so the presenter never has to think mid-demo.

---

## Architecture recap (one compact diagram)

```
User (browser)
  → React UI  (Vite dev server, :5173)
      → POST /api/ask/stream  (Vite proxies /api/* → localhost:8080, strips /api prefix)
          → Spring Boot ChartController  (:8080)
              → PromptOrchestrator (6-step pipeline)
                  Step 1 — DateRangeParser        (regex, <1 ms)
                  Step 2 — SalesDataGenerator     (random int 50-300 per period, <1 ms)
                  Step 3 — Build LLM prompt       (<1 ms)
                  Step 4 — OllamaClient           (POST /api/generate → qwen2.5-coder:1.5b :11434, ~18 s CPU)
                  Step 5 — CodeExtractor          (strip markdown fences, <1 ms)
                  Step 6 — E2BSandboxClient       (ProcessBuilder → python3 e2b_sandbox_client.py → E2B cloud, ~2.5 s)
  ← SSE stream: step-start / step-end / complete events back to React
  ← PNG rendered inline in chat bubble
```

Full diagram with trust-plane annotations: [ARCHITECTURE.md](ARCHITECTURE.md)

**Three local processes (Ollama, JVM, React dev server) + one cloud sandbox per request. They start
in this order: Ollama → JVM → React.**

---

## Before you start — prerequisites checklist

Run these in Terminal 3 before opening the demo. Every check should pass cleanly.

**Java 21**

```bash
java -version
```

Expected: `openjdk version "21.x.x" …` (or similar 21.x).

**Node.js ≥ 18**

```bash
node -v
```

Expected: `v18.x.x` or higher.

**npm**

```bash
npm -v
```

Expected: any modern version (8+ is fine).

**Python 3 ≥ 3.10**

```bash
python3 --version
```

Expected: `Python 3.10.x` or higher.

**Ollama installed and model pulled**

```bash
ollama list
```

Expected: `qwen2.5-coder:1.5b` appears in the list. If not, run `ollama pull qwen2.5-coder:1.5b`.

**E2B Python SDK installed**

```bash
python3 -c "from e2b_code_interpreter import Sandbox; print('SDK ready')"
```

Expected: `SDK ready`. If not: `pip install e2b-code-interpreter`.

**E2B_API_KEY present in shell**

```bash
source ~/.api_keys
echo "Last 4: $(echo $E2B_API_KEY | tail -c 5)"
```

Expected: `Last 4: 24dc`

**JAR already built**

```bash
ls -la /home/deva/Projects/CursorAI_Projects/AI-sandboxes/ai-sandboxes-lab/basic-hello-world/target/basic-hello-world-0.0.1-SNAPSHOT.jar
```

Expected: file exists with a recent timestamp. If missing: build it (see One-time setup).

**React dependencies installed**

```bash
ls -la /home/deva/Projects/CursorAI_Projects/AI-sandboxes/ai-sandboxes-lab/frontend/node_modules | head -3
```

Expected: `node_modules/` directory with subdirectories. If missing: `npm install` inside the
`frontend/` folder.

---

## One-time setup (do these once per demo session, not per run)

**1. Source API keys into the current shell**

```bash
source ~/.api_keys
```

Verify: `echo $E2B_API_KEY | wc -c` should print `57` (56-char key + newline from echo).

**2. Verify Ollama daemon is running**

```bash
ollama list
```

If the command hangs or returns an error, start Ollama in a background terminal:

```bash
ollama serve
```

Ollama typically auto-starts as a system service; `ollama serve` is only needed if it didn't.

**3. Build the JAR (only if code changed since last demo)**

```bash
cd /home/deva/Projects/CursorAI_Projects/AI-sandboxes/ai-sandboxes-lab/basic-hello-world
mvn clean package -DskipTests
```

Expected last line: `BUILD SUCCESS`. JAR lands at `target/basic-hello-world-0.0.1-SNAPSHOT.jar`.

**4. Install React deps (only if `package.json` changed)**

```bash
cd /home/deva/Projects/CursorAI_Projects/AI-sandboxes/ai-sandboxes-lab/frontend
npm install
```

---

## Three terminals — recommended layout

| Terminal | Purpose |
|---|---|
| **Terminal 1** | `java -jar` — Spring Boot backend (leave running; all pipeline logs appear here) |
| **Terminal 2** | `npm run dev` — Vite/React dev server (leave running) |
| **Terminal 3** | Ad-hoc commands: port checks, curl tests, quick verification |

---

## Pre-flight before any demo run — clean slate

Run these in Terminal 3 before starting any demo. Takes 30 seconds.

**Kill any leftover Spring Boot process**

```bash
pkill -f "basic-hello-world" ; sleep 1
```

No output = nothing was running. A PID line = it was killed.

**Kill any leftover Vite dev server**

```bash
pkill -f "vite"
```

**Verify port 8080 is free**

```bash
ss -lntp | grep 8080
```

Expected: empty. If it shows a `java` process, the old JAR didn't exit — wait a second and retry.

**Verify React port 5173 is free**

```bash
ss -lntp | grep 5173
```

Expected: empty.

**Verify Ollama is still listening on port 11434**

```bash
ss -lntp | grep 11434
```

Expected: a line showing `ollama` (or the process listening). This should stay running throughout.

**Confirm E2B_API_KEY is loaded in this shell**

```bash
echo $E2B_API_KEY | wc -c
```

Expected: `57` (56-char key + newline). If you see `1`, the var is empty — run `source ~/.api_keys`.

---

## DEMO STEP 1 — Start the backend (Spring Boot)

In **Terminal 1**:

```bash
cd /home/deva/Projects/CursorAI_Projects/AI-sandboxes/ai-sandboxes-lab/basic-hello-world
source ~/.api_keys
java -jar target/basic-hello-world-0.0.1-SNAPSHOT.jar
```

Watch for these markers in the startup output:

| Log line to look for | Expected? |
|---|---|
| `OllamaClient initialized — build marker: EXCHANGE_RAW_BYTES_v2 @ …` | ✅ |
| `E2BSandboxClient initialized — build marker: PYTHON_WRAPPER_v3, runner='e2b_sandbox_client.py' …` | ✅ |
| `Tomcat started on port(s): 8080` | ✅ |
| `Started BasicHelloWorldApplication in X.XXX seconds` | ✅ |

The two build-marker lines prove you are running the current code, not a cached binary from a
previous session. Leave this terminal running — every pipeline log line (including E2B subprocess
output) will appear here.

---

## DEMO STEP 2 — Start the frontend (React)

In **Terminal 2**:

```bash
cd /home/deva/Projects/CursorAI_Projects/AI-sandboxes/ai-sandboxes-lab/frontend
npm run dev
```

Expected output:

```
  VITE v8.x.x  ready in xxx ms

  ➜  Local:   http://localhost:5173/
  ➜  Network: use --host to expose
```

Leave this terminal running.

---

## DEMO STEP 3 — Open the UI in the browser

Open in your browser:

```
http://localhost:5173
```

You should see the **Chart Generator** page with:

- A header bar labelled **"Chart Generator"**
- An empty chat area with the hint: *Try: "Plot bar chart from Jan 2026 to March 2026"*
- A sub-hint: *Dynamic Chart Generation (Bar, Pie, etc.) in a Secure Sandbox Environment.*
- A bottom input bar with a multi-line textarea (placeholder: *Describe a chart to generate…*), a
  **Clear** button on the left, and a **Send** button on the right

No chart yet — that's expected for an empty session.

---

## DEMO STEP 4 — Run the canonical demo prompt

In the UI text input, type:

```
Plot bar chart from Jan 2026 to March 2026
```

Click **Send** (or press **Enter**).

**Wait time**: 20–40 seconds typical on a warm Ollama; up to 60+ seconds on a cold model start.

### Expected sequence (user-visible)

| # | What the UI shows |
|---|---|
| a | Your prompt appears as a user bubble immediately |
| b | An assistant bubble appears in "loading" state showing the current step name (e.g., *"Calling LLM (Ollama)"*) with a progress bar crawling forward |
| c | Step names advance: *Parsing date range → Generating data → Building LLM prompt → Calling LLM (Ollama) → Extracting code → Running in sandbox* |
| d | The assistant bubble fills in with a rendered bar chart (3 bars: Jan, Feb, Mar with random sales values). Click the chart to open it full-screen. |

### Expected Terminal 1 log sequence (Spring Boot backend)

```
████ REQUEST START (SSE) ████
PIPELINE START
STEP 1 : DateRangeParser    → granularity=MONTHLY, year=2026, periods=[Jan, Feb, Mar]
STEP 2 : SalesDataGenerator → {Jan=NNN, Feb=NNN, Mar=NNN}
STEP 3 : Build LLM prompt   → <length=~1300 chars>
STEP 4 : OllamaClient       → OUT → Ollama (sending ~1300 chars)
         … ~18 s of silence (CPU inference) …
                             → IN ← Ollama (received ~800 bytes)
STEP 5 : CodeExtractor      → <length=~300 chars clean Python>
STEP 6 : E2BSandboxClient   → OUT → subprocess
[subprocess stdout] [e2b_runner] Code length: NNN chars
[subprocess stdout] [e2b_runner] Creating E2B sandbox...
[subprocess stdout] [e2b_runner] Sandbox ready, executing code...
[subprocess stdout] [e2b_runner] Execution complete. Results: 1 items
[subprocess stdout] [e2b_runner] Result #0: type=..., has_png=True
[subprocess stdout] [e2b_runner] Chart saved: /tmp/e2b-chart-NNN.png (NNNN bytes)
                             → IN ← subprocess  (exit=0)
PIPELINE END  → NNNNms total
```

---

## DEMO STEP 5 — More prompts to try

Each sends a fresh request through the full pipeline (new E2B sandbox each time).

| Prompt | What it produces |
|---|---|
| `Plot pie chart from Jan 2026 to March 2026` | Pie chart, 3 slices Jan/Feb/Mar with % labels |
| `Plot line chart from Jan 2026 to June 2026` | Line chart, 6 data points Jan–Jun |
| `Plot bar chart from Q1 2026 to Q4 2026` | Bar chart, 4 bars Q1–Q4 |
| `Plot horizontal bar chart from Q1 to Q3 2026` | Horizontal bar chart, 3 bars Q1–Q3 |
| `Plot bar chart for Jan to Dec 2026` | Bar chart, all 12 months |

**DateRangeParser accepts only explicit date ranges** — it uses regex, not NLP. Supported patterns:

- `Month YYYY to Month YYYY` — e.g., "Jan 2026 to June 2026"
- `Month to Month YYYY` — e.g., "Jan to June 2026"
- `Qn YYYY to Qn YYYY` — e.g., "Q1 2026 to Q4 2026"
- `Qn to Qn YYYY` — e.g., "Q1 to Q4 2026"

Relative phrases like *"last 3 months"* are **not supported** and will return HTTP 400 with the
message `No recognisable date range found in prompt`.

**Timing expectations:**

- Warm Ollama (model already in RAM): 20–40 sec per request
- Cold Ollama (first request of the session): 60+ sec while model loads
- E2B sandbox spin-up: ~5 sec of the total (this is normal — explain to the audience)

---

## DEMO STEP 6 — Show the audience the moving parts

Talking points while switching between terminals and editor tabs:

**Terminal 1 (backend logs)**
Point out the two build-marker lines that appeared on startup:
`EXCHANGE_RAW_BYTES_v2` (OllamaClient) and `PYTHON_WRAPPER_v3` (E2BSandboxClient). These prove the
JAR you're running is from the current commit, not a cached binary. Then point out the live
`[subprocess stdout] [e2b_runner]` lines that interleave with the Java logs — this is polyglot
delegation working in real time, Java and Python sharing the same log stream via ProcessBuilder stdout drain.

**`e2b_sandbox_client.py` (~67 lines)**
Open it. Show how short it is: read code from a file, call `Sandbox.create()`, call
`sandbox.run_code(code)`, extract the PNG from `result.png`, write it to disk. That's the entire
bridge to E2B's cloud infrastructure. Compare this to what raw Java HTTP would require (see
`E2BSandboxClientHttpRaw.java` in the same service folder — the abandoned direct-HTTP attempt that
was replaced by this wrapper).

**`E2BSandboxClient.java`**
Open it. Point out `ProcessBuilder` — Java spawns Python as a child process, passes the API key via
`pb.environment().put("E2B_API_KEY", apiKey)`, and drains stdout/stderr on background threads so
the Python progress lines (`[e2b_runner] ...`) land in the Java log in real time with the correct
MDC transaction ID attached.

**`frontend/src/App.jsx`**
Open it. Show how thin the frontend is: `fetch('/api/ask/stream', { method: 'POST', body: ... })`,
parse the SSE stream for `step-start` / `step-end` / `complete` / `error` events, render the chart
as an `<img>` from a Blob URL. The entire React app is under 220 lines.

---

## Quick verification cheat sheet

Run any of these in Terminal 3 during the demo if something looks wrong.

| Want to check | Command | Expected |
|---|---|---|
| Backend port 8080 free? | `ss -lntp \| grep 8080` | Empty (or `java` if backend is running) |
| React port 5173 free? | `ss -lntp \| grep 5173` | Empty (or `node` if frontend is running) |
| Ollama responding? | `curl -s http://localhost:11434/api/tags \| head -c 200` | JSON with `"models"` key listing local models |
| E2B key set in current shell? | `echo "Last 4: $(echo $E2B_API_KEY \| tail -c 5)"` | `Last 4: 24dc` |
| E2B SDK importable? | `python3 -c "from e2b_code_interpreter import Sandbox; print('ok')"` | `ok` |
| JAR exists? | `ls -lh target/basic-hello-world-0.0.1-SNAPSHOT.jar` | File with recent timestamp |
| curl test (bypass UI) | `curl -X POST http://localhost:8080/ask -H "Content-Type: application/json" -d '{"prompt":"Plot bar chart from Jan 2026 to March 2026"}' --output /tmp/test.png && file /tmp/test.png` | `PNG image data` |

---

## Common gotchas during a demo

**Stale env var in the JAR's terminal**

Symptom: E2B subprocess exits with code 1; `[subprocess stderr]` shows `401 Unauthorized` or
`E2B_API_KEY not set`.
Cause: forgot to run `source ~/.api_keys` in Terminal 1 before `java -jar`.
Fix: `Ctrl+C` the JAR, run `source ~/.api_keys` in Terminal 1, restart with `java -jar ...`.

**Stale JAR after a code change**

Symptom: code changes don't appear in behavior; build-marker timestamps on startup look old.
Fix: `mvn clean package -DskipTests` in the project root, then restart the JAR.

**Port 8080 already in use**

Symptom: Spring Boot fails to start — `Address already in use: 8080`.
Fix:
```bash
pkill -f basic-hello-world
sleep 1
ss -lntp | grep 8080
```
Should be empty. Then restart the JAR.

**Port 5173 already in use**

Symptom: `npm run dev` fails — `Port 5173 is already in use`.
Fix: `pkill -f vite`, then `npm run dev` again.

**Ollama cold start on first request**

Symptom: the "Calling LLM (Ollama)" step takes 60+ seconds; the progress bar sits frozen.
This is normal — Ollama is loading `qwen2.5-coder:1.5b` into RAM.
Pre-warm before the demo with a throwaway request:
```bash
curl -s -X POST http://localhost:11434/api/generate \
  -d '{"model":"qwen2.5-coder:1.5b","prompt":"hi","stream":false}' \
  > /dev/null
```
Subsequent requests will run at ~18 sec (CPU inference, model already loaded).

**E2B sandbox slow on first request**

Symptom: the "Running in sandbox" step takes 8–12 sec instead of the usual 2–3 sec.
Cause: E2B provisions a fresh Ubuntu VM per request; first one of the session pays a cold-start
penalty. This is normal — tell the audience.

**React shows a network error bubble**

Symptom: assistant bubble shows `Failed to fetch` or similar.
Cause A: Spring Boot isn't running. Check Terminal 1 or `ss -lntp | grep 8080`.
Cause B: Vite dev server isn't running. Check Terminal 2 or `ss -lntp | grep 5173`.

**DateRangeParser 400 error in the UI**

Symptom: assistant bubble shows `400 — No recognisable date range found in prompt`.
Cause: the prompt didn't match the supported regex patterns (e.g., "last 3 months").
Fix: use an explicit range — `Jan 2026 to March 2026` or `Q1 to Q3 2026`.

**JAVA_TOOL_OPTIONS warning on startup**

Symptom: a line like `Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF-8` in Terminal 1.
This is harmless — it comes from the system JVM configuration, not the app. Ignore it.

**`@CrossOrigin` is already set — not a CORS issue**

`ChartController` has `@CrossOrigin(origins = "*")`, so CORS is not a concern even if you hit
the backend directly from `curl` or a different port. If a CORS error does appear, it means the
Vite proxy is not running (check Terminal 2).

---

## Suggested 5–7 min demo flow (for the peer-dev presentation)

Start with **both backend and frontend already running** — do not burn demo time on startup
commands. Have Terminal 1 (backend logs) visible and the browser at `http://localhost:5173` open.

| Time | Action |
|---|---|
| **0:00 – 1:00** | Open [ARCHITECTURE.md](ARCHITECTURE.md). Walk through the 3-process diagram: Ollama (local LLM), JVM (Spring Boot pipeline), React dev server — plus the E2B cloud sandbox that spins up per request. Emphasize: the LLM-generated Python code never runs on the host machine. |
| **1:00 – 2:00** | Switch to the browser. Show the empty "Chart Generator" UI. Describe the input bar (type here, press Send or Enter). |
| **2:00 – 4:00** | Type `Plot bar chart from Jan 2026 to March 2026`. Click **Send**. Narrate while the pipeline runs: the progress bar advances through the 6 steps. Switch to Terminal 1 to show the logs scrolling — Ollama call going out, ~18 sec of silence (CPU inference), response arriving, E2B subprocess spinning up. Chart appears in the UI. |
| **4:00 – 5:00** | Type a second prompt — e.g., `Plot pie chart from Jan 2026 to March 2026`. Send it. Point out it isn't faking results: the progress bar shows a fresh pipeline run, the bars in Terminal 1 show a new transaction ID. Different chart type arrives. |
| **5:00 – 6:00** | Code tour: open `e2b_sandbox_client.py` (67 lines — this is the entire bridge to E2B). Open `E2BSandboxClient.java` — show `ProcessBuilder`, point at `E2BSandboxClientHttpRaw.java` as the deprecated direct-HTTP attempt it replaced. Open `frontend/src/App.jsx` — show `fetch('/api/ask/stream', ...)` and the SSE event loop (~220 lines total). *"Three small files glue it together."* |
| **6:00 – 7:00** | Q&A. |

---

**Total demo time: 5–7 minutes if backend + frontend are pre-started. Pre-flight matters more than
runtime — most demo failures come from stale env vars or stale JARs, not code defects.**
