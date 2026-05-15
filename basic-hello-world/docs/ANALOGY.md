# ANALOGY.md — Visualize the Entire System Without Looking at Code

> Close your eyes. By the end of this document, every port number, every class, every design
> decision should have a face, a place, and a story. Read it once slowly. It will stick.

---

## 1. The Grand Metaphor: A Custom Art Commission Studio

Imagine a **bespoke art studio** that accepts commissions over a counter.

A customer walks up and says:  
*"I want a pie chart painting covering January through March 2026."*

Here is what happens inside the studio:

1. A **receptionist** at the front desk takes the order slip.
2. The **project manager** in the back coordinates the whole job.
3. A **calendar specialist** decodes what the date range actually means.
4. A **data analyst** invents realistic sales numbers for those months.
5. A **brief writer** composes a precise instruction card for the artist.
6. An **AI artist** — who lives in a private studio inside the building — reads the card and writes a detailed recipe ("step-by-step painting instructions").
7. A **recipe editor** cleans the scribbles off the instructions.
8. The recipe is handed through a slot in a **sealed, bomb-proof creative booth** that gets bulldozed after every single job.
9. The finished painting slides out of the booth.
10. The receptionist hands the framed PNG to the customer.

That is the **entire system** in one paragraph. Every technical piece below maps to one of those ten roles.

---

## 2. Every Technology and Its Real-World Nickname

### Master Nickname Table

| Layer | Java/Tech Component | Real-World Nickname | Simple Words |
|---|---|---|---|
| Entry point | `BasicHelloWorldApplication.java` | **The Building Itself** | Spring Boot starts the whole studio |
| Front desk | `ChartController` | **The Receptionist** | Receives orders, returns pictures |
| Request tag | `TransactionIdFilter` | **The Order Ticket** | Every order gets a unique slip number |
| Pipeline manager | `PromptOrchestrator` | **The Project Manager** | Runs all 6 steps in the right sequence |
| Date decoder | `DateRangeParser` | **The Calendar Specialist** | Converts "Jan to March" into a structured list |
| Fake data source | `SalesDataGenerator` | **The Data Analyst** | Makes up believable numbers (50–300) |
| Prompt builder | Step 3 of `PromptOrchestrator` | **The Brief Writer** | Writes the instruction card for the AI artist |
| LLM client | `OllamaClient` | **The Intercom to the Private Studio** | Sends the instruction card over HTTP |
| Local LLM server | Ollama (process on machine) | **The Private Studio** | Runs entirely inside your house, never calls out |
| AI model | `qwen2.5-coder:1.5b` | **The Junior Artist** | 1.5 billion brain cells, paints code not pictures |
| Response cleaner | `CodeExtractor` | **The Recipe Editor** | Strips the markdown gift-wrap off the artist's output |
| Sandbox launcher | `E2BSandboxClient` | **The Booth Manager** | Slides instructions into the sealed booth |
| Python bridge | `e2b_sandbox_client.py` | **The Slot in the Wall** | The only physical connection between Java and E2B |
| Cloud sandbox | E2B VM (`code-interpreter-v1`) | **The Sealed Bomb-Proof Booth** | Ephemeral Ubuntu VM; destroyed after every job |
| Process spawner | Java `ProcessBuilder` | **The Phone Call** | Java rings Python, passes the file paths |
| HTTP client | Spring `RestClient` | **The Walkie-Talkie** | Talks to Ollama over HTTP |
| Web UI | React app (port 5173) | **The Storefront Window** | The pretty glass front the customer sees |
| Dev server | Vite | **The Intercom Between Storefront and Back Office** | Proxies `/api/*` → `localhost:8080` |
| Logging framework | SLF4J + Logback | **The Studio Security Camera System** | Records every moment of every job |
| Request correlation | SLF4J MDC | **The Sticky Label on Every Log Entry** | Order ticket number printed on every camera frame |
| Log file | `ai-catalina.log` | **The VHS Tape Archive** | Daily rolling, 30-day retention, 100 MB max |
| Build tool | Maven | **The Construction Crew** | Compiles Java, assembles the fat JAR building |
| DTO: AskRequest | `AskRequest.java` | **The Order Form** | `{prompt: "..."}` — one field, nothing else |
| DTO: DateRange | `DateRange.java` | **The Parsed Calendar Card** | Granularity + periods + year |
| SSE emitter | `SseStepEmitter.java` | **The Live Status Board** | Streams step-by-step progress to the storefront |
| Async config | `AsyncConfig.java` | **The Staffing Agency** | Provides a fixed pool of 4 background workers |

---

## 3. The Three Security Zones — A Nuclear Plant Floor Plan

Picture a nuclear power plant with three concentric zones, each requiring a different badge.

```
╔══════════════════════════════════════════════════════════════════════╗
║  ZONE 1 — CONTROL ROOM (Green Badge)                                 ║
║  Java JVM: Spring Boot, all controllers, services, orchestrator      ║
║  Trust: FULL — you wrote and compiled every line                     ║
║                                                                      ║
║    ┌─────────────────────────────────────────────────────────────┐   ║
║    │  ZONE 2 — AUTHORIZED CONTRACTOR AREA (Yellow Badge)         │   ║
║    │  Python subprocess: e2b_sandbox_client.py                   │   ║
║    │  Trust: FULL — you wrote it; spawned under your control     │   ║
║    │                                                             │   ║
║    │    ┌───────────────────────────────────────────────────┐   │   ║
║    │    │  ZONE 3 — REACTOR CORE / CONTAINMENT (Red Badge)  │   │   ║
║    │    │  E2B Cloud Ubuntu VM                              │   │   ║
║    │    │  Trust: ZERO — LLM-generated code runs here       │   │   ║
║    │    │  If it explodes: only the VM burns. You are safe. │   │   ║
║    │    └───────────────────────────────────────────────────┘   │   ║
║    └─────────────────────────────────────────────────────────────┘   ║
╚══════════════════════════════════════════════════════════════════════╝
```

### Why Three Zones Exist

When the AI model generates code like:
```python
import os
os.system("rm -rf /")          # tries to delete everything
open("/etc/passwd").read()      # tries to steal passwords
import socket; socket.connect(...)  # tries to call home
```

…none of that touches your machine. The containment VM absorbs it. E2B destroys the VM after
the job. The host machine never knew it happened.

**The Java process treats the Python code as an opaque string — it never executes it.**  
**The Python subprocess treats the code as a file argument — it never imports it.**  
**Only the E2B Ubuntu VM runs the code — and that VM is ephemeral, disposable, and isolated.**

This is the entire point of the architecture. Every other design decision is a consequence of enforcing these three zones.

---

## 4. Narrative Story: A `curl` Request, Step by Step

*The customer has no GUI. They type at a terminal.*

### The Order Arrives

```
curl -X POST http://localhost:8080/ask \
     -H "Content-Type: application/json" \
     -d '{"prompt": "Plot pie chart from Jan 2026 to March 2026"}' \
     --output chart.png
```

**Port 8080** is the studio's street address. The customer sends a JSON envelope with one field
inside: `prompt`. Think of it as dropping a note through the mail slot.

---

### Step 0 — The Order Ticket is Stamped

Before the receptionist even sees the note, `TransactionIdFilter` stamps it.

The timestamp `20260509143022417` is printed on every single camera frame for this job. If 50
customers send orders at the same moment, you can still pull *one* customer's entire journey
from the logs by grepping for their ticket number. This is **SLF4J MDC** — the sticky label
on every log line.

---

### Step 1 — The Receptionist Takes the Order

`ChartController.handleAsk()` receives the `POST /ask` call.

- Validates the request is not empty.
- Calls the Project Manager (`PromptOrchestrator.handle()`).
- Waits for PNG bytes.
- Returns `HTTP 200 image/png`.

The receptionist does **no business logic**. They just take the note, hand it to the back office,
and return whatever comes back. If something goes wrong, they translate it to an HTTP error:
- Bad request (empty prompt) → **400**
- LLM unreachable → **502 Bad Gateway**
- Sandbox failure → **500**

---

### Step 2 — The Calendar Specialist Reads the Dates

`DateRangeParser.parse("Plot pie chart from Jan 2026 to March 2026")`

The calendar specialist has memorized three reading rules (regex patterns):

| Rule Name | Matches | Example |
|---|---|---|
| Monthly with year | `Mon YYYY to Mon YYYY` | "Jan 2026 to March 2026" |
| Monthly inferred year | `Mon to Mon YYYY` | "Jan to March 2026" |
| Quarterly | `QN to QN YYYY` | "Q1 to Q3 2026" |

The specialist returns a structured **Calendar Card** (`DateRange`):
```
granularity = MONTHLY
periods     = ["Jan", "Feb", "Mar"]
year        = 2026
```

Duration: **1 ms**. This is pure string pattern matching. No network. No AI. Just regex.

---

### Step 3 — The Data Analyst Makes Up Numbers

`SalesDataGenerator.generate(dateRange)`

The data analyst has no real database. They generate plausible random integers between 50 and 300
for each period in the calendar card.

```
Jan → 187
Feb → 243
Mar → 91
```

This data gets inlined into the LLM instruction card. The model never knows it was random.
Duration: **0 ms** (microseconds — pure in-memory math).

---

### Step 4 — The Brief Writer Composes the Instruction Card

Step 3 of `PromptOrchestrator` assembles the full LLM prompt from parts:

- System instruction: "You are a Python code generator. Output ONLY executable Python code."
- Chart type intent: extracted from the user's original prompt ("pie chart")
- Four few-shot examples (bar, pie, line, horizontal bar) — showing the model what good code looks like
- The sales data table, inlined
- The exact output path: `/home/user/chart.png`
- A matplotlib backend line: `matplotlib.use('Agg')` (prevents GUI pop-up in headless VM)

The finished instruction card is **~1004 characters**. Duration: **1 ms**.

> **The pie-chart bug lived here.** An early version of the brief writer always wrote "bar chart"
> regardless of what the customer asked. The user's intent was thrown in the trash before the
> artist ever saw it. See Section 8 for the full story.

---

### Step 5 — The Walkie-Talkie Calls the Private Studio

`OllamaClient.generate(prompt)` sends `POST http://localhost:11434/api/generate`

**Ollama** is a local LLM inference server running on your own machine. Think of it as a private
artist's studio inside the building — the instruction card never leaves the premises. No API key.
No internet call. No data egress.

The request body:
```json
{
  "model": "qwen2.5-coder:1.5b",
  "prompt": "... 1004 chars ...",
  "stream": false
}
```

`stream: false` means: don't trickle the response — wait until the artist is done, then send
the whole thing at once.

The walkie-talkie has two timeouts baked in:
- **10 seconds** to connect (if Ollama isn't running, fail fast)
- **60 seconds** to read (the artist needs thinking time)

The model **qwen2.5-coder:1.5b** — the Junior Artist — has 1.5 billion parameters. It runs
entirely in CPU memory. No GPU required. The cost is speed.

Duration: **~18,879 ms** (~19 seconds). This is 88% of the total pipeline time. The studio is
waiting in silence for the artist to finish. The logs show this silence explicitly:

```
OUT → Ollama (sending 1004 chars)
[... 18 seconds of nothing ...]
IN  ← Ollama (received 2772 bytes)
```

---

### Step 6 — The Recipe Editor Cleans the Output

`CodeExtractor.extract(rawLlmResponse)`

The Junior Artist wraps their code in markdown gift paper. A typical response looks like:

```
Here is the Python code to generate a pie chart:

```python
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
...
```

Let me know if you need any changes!
```

The recipe editor tries four strategies in order until one succeeds:

| Strategy | What It Looks For | Used When |
|---|---|---|
| 1. Python fence | ` ```python ... ``` ` | Model is well-behaved |
| 2. Plain fence | ` ``` ... ``` ` | Model used no language tag |
| 3. First import line | `import X` or `from X import` | Model skipped the fences |
| 4. Fallback | Return everything trimmed | Model gave raw code directly |

The output is clean Python code, ready for execution. Duration: **1 ms**.

---

### Step 7 — The Phone Call to the Slot in the Wall

`E2BSandboxClient.execute(pythonCode)`

The Booth Manager does four things:

1. **Writes the Python code to a temp file** on the local disk:
   `/tmp/e2b_code_XXXXXX.py`

2. **Places the phone call** (Java `ProcessBuilder`):
   ```
   python3 e2b_sandbox_client.py /tmp/e2b_code_XXXXXX.py /tmp/output_XXXXXX.png
   ```
   The E2B API key is passed as an environment variable — never hardcoded.

3. **Listens to the call** in real time: two background threads drain stdout and stderr from the
   Python process and write them to the Java logger (with the transaction ID still attached).

4. **Waits** up to **120 seconds** for the Python process to finish.

---

### Step 8 — The Slot in the Wall Runs the Sealed Booth

`e2b_sandbox_client.py` (Python, 67 lines) — The Slot in the Wall:

```
Python subprocess receives:
  arg[1] = /tmp/e2b_code_XXXXXX.py    ← the generated code
  arg[2] = /tmp/output_XXXXXX.png     ← where to write the result
```

The slot does:

1. **Reads** the Python code from the file (treats it as a string, never runs it locally).
2. **Calls the E2B Control Plane** (`POST https://api.e2b.dev/sandboxes`): *"Create me a fresh Ubuntu VM."*
3. **Uploads** the code string into the sandbox (E2B Data Plane / `envd` daemon).
4. **Sends**: `Process/Start → python3 the_code.py` — the VM runs it.
5. **Downloads** `/home/user/chart.png` from the VM as base64.
6. **Decodes** and writes the PNG to the local output file.
7. **Deletes the sandbox**: `DELETE /sandboxes/{id}` — the VM is gone forever.

Duration: **~2,556 ms** (~2.5 seconds). This includes VM boot, code execution, and teardown.

---

### Step 9 — The Painting Returns

The Java Booth Manager reads `/tmp/output_XXXXXX.png` as raw bytes.
The temp files are cleaned up.
The bytes travel back up to `ChartController`.
HTTP response: `200 OK`, `Content-Type: image/png`.

The `curl` command writes the bytes to `chart.png`. The terminal is silent. The painting exists.

**Total wall-clock time: ~21,441 ms (~21 seconds).**

---

## 5. Narrative Story: A React UI Request

*The customer uses the browser.*

### Two Ports, One Experience

```
Customer's browser                     Studio's back office
     :5173                                  :8080
  [Storefront]      ──── HTTP ────▶    [Reception Desk]
```

**Vite** (the Intercom System) runs the storefront on port **5173**. Its `vite.config.js` says:
*"Any request to `/api/*` — strip the `/api` prefix and forward it to `localhost:8080`."*

This means:
- The React app sends `POST /api/ask` (same origin, no CORS issue)
- Vite silently rewrites it to `POST localhost:8080/ask`
- The Spring Boot backend sees a normal `POST /ask` — it never knows it came from a browser

From Step 1 onward, **identical to the curl flow**.

### What the Storefront Shows

The React app has four components, each with one job:

| Component | Nickname | Job |
|---|---|---|
| `App.jsx` | **The Waiter** | Holds the message list, sends orders, waits for the kitchen |
| `ChatMessage.jsx` | **The Tray** | Displays one bubble: user text or assistant chart |
| `InputBar.jsx` | **The Order Pad** | Captures what you type; grays out while the kitchen is busy |
| `ChartModal.jsx` | **The Gallery Frame** | Click a chart → fullscreen overlay |

When the response arrives as a PNG blob, the Waiter converts it to an object URL
(`URL.createObjectURL(blob)`) and hands the Tray an `<img src="...">`. The gallery frame pops
up when you click.

### The Streaming Variant (POST /ask/stream)

The studio also has a **Live Status Board** (`SseStepEmitter`). Instead of waiting 21 seconds in
silence, the customer sees progress messages appear in real time:

```
[Step 1 starting] Parsing date range...
[Step 1 complete] Found: Jan, Feb, Mar 2026
[Step 2 starting] Generating sales data...
[Step 2 complete] Generated 3 data points
...
[Step 6 complete] Chart ready
[complete] <base64-encoded PNG>
```

This uses **Server-Sent Events (SSE)** — a one-way stream from server to browser. The backend
uses 4 background threads (`AsyncConfig` fixed pool) to run the pipeline while the SSE
connection stays open.

---

## 6. The Logging System — A Security Camera Network

### Why AI Systems Need Different Cameras

Traditional apps fail loudly: stack trace, HTTP 500, someone's phone rings.

AI pipelines fail silently: the system returns a result — just the **wrong** result. No exception.
No alert. Just a customer who got a bar chart when they asked for a pie chart.

To debug silent failures, you need cameras at **every door between rooms**, not just at the exit.

| Traditional App Camera | AI Pipeline Camera |
|---|---|
| Capture exceptions | Capture inputs AND outputs at every step |
| Stack trace points to the line | Logs reveal which boundary swallowed the intent |
| Same input → same output → reproducible | Same prompt → different LLM output → need the exact log |
| One camera at the exit is enough | Need cameras at every boundary |

### The Camera Network Layout

```
REQUEST ARRIVES
      │
      ▼
[████ TRANSACTION ID STAMPED ████]   ← Order ticket number on every frame
      │
      ▼
[╔══ STEP 1: Date Parser ══╗]
║  Input:  raw prompt string          ║
║  Output: DateRange record           ║
║  Time:   1 ms                       ║
╚═══════════════════════════════╝
      │
      ▼
[╔══ STEP 2: Sales Generator ══╗]
[╔══ STEP 3: Prompt Builder  ══╗]
[╔══ STEP 4: Ollama LLM      ══╗]  ← OUT logged before call, IN logged after (19 s gap visible)
[╔══ STEP 5: Code Extractor  ══╗]
[╔══ STEP 6: E2B Sandbox     ══╗]  ← subprocess stdout streamed in real time
      │
      ▼
[████ RESPONSE SENT ████]
```

### Visual Grammar

| Symbol | Means | Why |
|---|---|---|
| `████` | Request boundary (start/end) | Heavy block — unmissable |
| `╔══╗` | Pipeline step | Medium box — easy to scan |
| Plain lines | Sub-events within a step | Streams in real time, no repeated timestamp |

### The Sticky Label (MDC)

`TransactionIdFilter` runs before everything else. It calls:
```java
MDC.put("transactionId", "20260509143022417");
```

The Logback pattern includes `%X{transactionId}`. From that moment, **every log line in that
thread** automatically prints the transaction ID. When 10 requests run simultaneously, you can:
```bash
grep "20260509143022417" ai-catalina.log
```
…and pull the full story of one request from the interleaved noise.

The filter clears the MDC after the request completes, preventing the label from leaking onto
the next request that reuses the thread.

---

## 7. The Pie-Chart Bug — A Real Story in Real-World Terms

### What Happened

A customer walked up and said: *"I want a pie chart."*

The receptionist passed the order to the Project Manager.  
The Calendar Specialist correctly decoded the dates.  
The Data Analyst generated the numbers.  
The Brief Writer… **threw the customer's chart-type preference in the trash**.

The Brief Writer's template was hardcoded:
```
Task: generate a bar chart using matplotlib...
- Use plt.bar() to draw a bar chart
```

The Junior Artist (LLM) received this instruction and drew exactly what was asked: a bar chart.
The customer got a perfectly rendered bar chart. The system "worked."

### What Everyone Thought

*"The Junior Artist is too dumb. We need to hire a Senior Artist (upgrade to a larger model)."*

Upgrading the model would have taken hours of download time and 10× more compute per request.

### What the Security Cameras Showed

After adding per-step logging, the next pie-chart request played back on the cameras:

```
╔══ STEP 3: Build LLM Prompt ══╗
   Input  : userPrompt="Plot pie chart from Jan 2026 to March 2026"
   Output : <length=1004 chars>
   Full LLM prompt:
   ...
   Task: generate a bar chart using matplotlib and save it to /home/user/chart.png.
   - Use plt.bar() to draw a bar chart
   ...
╚════════════════════════════════╝
```

The customer's intent (`pie chart`) entered STEP 3.  
The customer's intent did **not** exit STEP 3.  
The LLM prompt said `bar chart`.

The Junior Artist was innocent. The Brief Writer was dropping the order on the floor.

### The Fix

One method change: forward the user's chart type into the LLM prompt template.

- **Diagnosis time: 5 minutes**
- **Fix time: 10 minutes**
- **Without logs: possibly days of model-swapping with the wrong hypothesis**

### The Lesson

> AI pipeline bugs live at the **boundary between layers**, not inside any single layer.  
> The Brief Writer's code was correct. The Artist's behavior was correct. The bug was an  
> unspoken assumption that crossed the boundary. Only logs that capture *both* the input  
> and the output of every layer make that boundary visible.

---

## 8. Why This Architecture? Real-World Comparisons

### Why Ollama (Local Artist) Instead of a Cloud Artist?

| Factor | Ollama (local) | Cloud LLM (OpenAI/Anthropic) |
|---|---|---|
| Data privacy | Sales data never leaves machine | Sales data sent to external server |
| Cost | Free after download | Pay per token |
| API key | None needed | Required |
| Offline use | Works with no internet | Requires internet |
| Speed | 19 s (CPU-only) | 1–3 s (GPU cloud) |
| Model quality | Junior Artist (1.5B params) | Senior Artist (billions more params) |

For a proof-of-concept with real sales numbers, **data privacy wins**. A cloud model would be
faster but turns every prompt into a data-egress event.

### Why E2B (Sealed Booth) Instead of Running Code Locally?

Imagine letting the AI artist's recipe be cooked directly in your kitchen.

If the recipe says:
```python
import os; os.system("rm -rf /home/deva/Projects")
```

…your actual project files are gone.

E2B creates a **throw-away kitchen** for every job. The recipe runs in there. When the job is
done, E2B demolishes the kitchen. Your house is untouched.

| Execution location | Risk if LLM generates malicious code |
|---|---|
| Inside JVM (JSR-223) | Can crash the JVM, read JVM memory |
| Local `ProcessBuilder` (no sandbox) | Can read files, delete files, make network calls |
| E2B Cloud VM | VM is destroyed; host is completely isolated |

### Why a Python Subprocess Instead of a Java E2B SDK?

E2B publishes official SDKs for **Python** and **Node.js** only. No Java SDK exists on Maven Central.

`E2BSandboxClientHttpRaw.java` (present in the repo, marked deprecated) was the original attempt
to speak E2B's Connect RPC protocol directly from Java. It was abandoned because:

- E2B's Connect RPC protocol for process execution is subtle and changes between SDK versions
- Keeping raw HTTP frame parsing in sync with SDK evolution is fragile maintenance work
- A 67-line Python wrapper using the official SDK is more robust and maintainable

**ProcessBuilder** is the bridge: Java spawns Python, passes two file paths as arguments, reads
back PNG bytes. Two trusted processes, one message (a file path), one result (a PNG file).

---

## 9. Key Numbers to Remember

### Ports — The Studio's Street Addresses

| Port | Who Lives There | Nickname |
|---|---|---|
| **8080** | Spring Boot backend | The Back Office |
| **5173** | Vite / React frontend | The Storefront Window |
| **11434** | Ollama LLM server | The Private Artist's Studio |

### Timeouts — How Long the Studio Waits

| Timeout | Where | Value | Why |
|---|---|---|---|
| Ollama connect timeout | `OllamaClient` | **10 seconds** | Fail fast if Ollama isn't running |
| Ollama read timeout | `OllamaClient` | **60 seconds** | Give the CPU-only artist thinking time |
| E2B subprocess timeout | `E2BSandboxClient` | **120 seconds** | Allow sandbox boot + execution + teardown |

### Timing — Where Your 21 Seconds Go

| Step | Component | Time | % of Total |
|---|---|---|---|
| 1 | DateRangeParser | 1 ms | < 0.01% |
| 2 | SalesDataGenerator | 0 ms | < 0.01% |
| 3 | Prompt Builder | 1 ms | < 0.01% |
| 4 | **OllamaClient (LLM inference)** | **18,879 ms** | **88%** |
| 5 | CodeExtractor | 1 ms | < 0.01% |
| 6 | **E2BSandboxClient** | **2,556 ms** | **12%** |
| — | **Total** | **21,441 ms** | **100%** |

**Memory hook:** The artist takes 88% of the time. The booth takes 12%. Everything else is rounding error.

### Sizes and Limits

| Number | What It Is |
|---|---|
| 1.5 billion | Parameters in `qwen2.5-coder:1.5b` (the Junior Artist's brain cells) |
| 4 | Chart types the system handles (bar, pie, line, horizontal bar) |
| 4 | Background threads in `AsyncConfig` fixed pool |
| 50–300 | Sales data value range (random integers per period) |
| ~1004 chars | Typical LLM prompt size (one pie-chart request) |
| ~2772 bytes | Typical LLM raw response size |
| 100 MB | Max log file size before rolling |
| 30 days | Log file retention |
| 67 lines | `e2b_sandbox_client.py` — the entire Python bridge |
| 6 | Pipeline steps in `PromptOrchestrator` |
| 12 | Layers in the full AI system layer model |

### Addresses and IDs

| Value | What It Is |
|---|---|
| `localhost:11434` | Ollama (local, never changes) |
| `api.e2b.dev` | E2B control plane (cloud) |
| `code-interpreter-v1` | E2B sandbox template (Ubuntu VM with Python + matplotlib) |
| `qwen2.5-coder:1.5b` | The LLM model name (tell Ollama which artist to wake up) |
| `yyyyMMddHHmmssSSS` | Transaction ID format (17 digits, millisecond precision) |
| `com.sandboxlab` | Java base package for all application code |
| `transactionId` | MDC key used in every log line |

---

## 10. The Generic AI Layer Map — Reading the Future

This system is the simplest possible AI pipeline. Future systems (RAG, agents, function calling)
reuse the same 12-layer skeleton. Only the richness of each layer changes.

| Layer # | Generic Name | In This Project | In a RAG System | In an Agent System |
|---|---|---|---|---|
| 1 | **Interface** | React UI / curl | Chat UI | Chat UI / API |
| 2 | **API** | `POST /ask` (Spring MVC) | `POST /query` | `POST /agent/run` |
| 3 | **Orchestration** | `PromptOrchestrator` | RAG pipeline | Agent loop / planner |
| 4 | **Pre-processing** | `DateRangeParser`, `SalesDataGenerator` | Document chunking, query rewriting | Tool selection logic |
| 5 | **Retrieval** | *(none — data is generated)* | Vector search + reranker | Episodic memory lookup |
| 6 | **Prompt Assembly** | Step 3 of orchestrator | Template + retrieved chunks | System prompt + tool defs + history |
| 7 | **Model Inference** | `OllamaClient` → `qwen2.5-coder:1.5b` | Same | Same |
| 8 | **Response Parsing** | `CodeExtractor` | JSON parser, citation extractor | Tool-call parser |
| 9 | **Tool / Code Execution** | `E2BSandboxClient` → E2B VM | *(rare in pure RAG)* | API calls, DB queries, sandbox |
| 10 | **Post-processing** | Return PNG bytes | Add citations, format answer | Aggregate tool results |
| 11 | **Persistence / State** | *(none — stateless POC)* | Conversation history, indexed docs | Episodic memory, agent state |
| 12 | **Observability** | SLF4J + Logback + MDC | Same + retrieval metrics | Same + agent step traces |

**The most important layer that does not exist in this POC:** Layer 12 (Safety / Guardrails).
In production, this layer sits between Layer 8 (Response Parsing) and Layer 9 (Tool Execution)
and checks: *"Is this code safe to run?"* In this POC, E2B's VM isolation is the only guardrail.

**The layer that caused the pie-chart bug:** Layer 6 (Prompt Assembly). It silently dropped the
user's chart-type intent. Every AI pipeline has a Layer 6. Every Layer 6 is a potential silent
bug.

---

## 11. One-Paragraph Summary to Recite From Memory

> A customer types a natural-language chart request into a React storefront (port 5173). Vite
> proxies the request to a Spring Boot backend (port 8080). A servlet filter stamps a unique
> transaction ID on the request. The PromptOrchestrator runs six steps: a regex parser converts
> the date range into a structured record (1 ms), a random generator invents sales data (0 ms),
> a brief writer composes a ~1000-character instruction for the AI artist (1 ms), a REST client
> sends it to Ollama — a local LLM server at port 11434 running a 1.5-billion-parameter model
> called qwen2.5-coder:1.5b — and waits up to 60 seconds for Python code back (typically 19 s),
> a regex extractor strips the markdown gift-wrap off the code (1 ms), and finally a ProcessBuilder
> phone call spawns a 67-line Python subprocess that uploads the code to a disposable Ubuntu VM in
> the E2B cloud, runs it, downloads the resulting PNG, and destroys the VM (typically 2.5 s). The
> PNG bytes travel back to the controller, which returns them as `image/png`. Total: ~21 seconds.
> Every step's input, output, and duration is logged with the transaction ID. If the chart is
> wrong, grep the log for the transaction ID and read the Step 3 block — that is where intent goes
> to die.

---

*End of ANALOGY.md*
