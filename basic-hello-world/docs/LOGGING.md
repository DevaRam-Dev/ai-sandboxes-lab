# Logging for AI Systems

## Why AI systems need different logging

For the last two decades, application logging has been shaped by the assumption that software is deterministic. Same input, same output. When something breaks, the bug lives in your code — and a stack trace usually points at the line. Logging in this world is a safety net: capture exceptions, record key business events, keep enough context to investigate the rare incident.

AI-driven systems break this assumption. The behaviour of a Large Language Model is non-deterministic by design. The same prompt can produce slightly different outputs across runs. The "bug" might not be in your code at all — it might be in the prompt your code constructed, the way the model interpreted that prompt, the tool the model chose to call, the data your retriever returned, or the sandbox in which the generated code executed. There is rarely a stack trace, because the system did not crash. It just produced the wrong answer.

This shifts the role of logging from "incident forensics" to "continuous behavioural inspection." Every component along the pipeline — prompt builder, retriever, model, code extractor, executor — has an input and an output, and the logs need to capture both. Without that, debugging becomes guesswork: change a prompt, change a model, change a temperature, hope the symptom goes away. With it, debugging becomes a methodical walk along the pipeline, comparing what each component received with what it produced.

Put differently: traditional logs answer "what crashed?" AI logs need to answer "what did each component think it received, and what did it produce?"

## Traditional Spring app vs AI pipeline

| Dimension | Traditional Spring app | AI pipeline |
|---|---|---|
| Failure mode | Throws exception with stack trace | Returns a wrong answer silently |
| Determinism | Same input → same output | Same input → different outputs across runs |
| Where the bug lives | In your code | In your code, your prompt, the model's interpretation, the tool, the retrieved context, or the sandbox |
| Reproducibility | High — replay the request | Low — model output may vary even with identical inputs |
| Primary debugging artifact | Stack trace + exception message | Full input/output of each pipeline boundary |
| Detection | HTTP 500, alerts, monitoring | Often discovered only by user complaint |
| Fix verification | Re-run test, see green | Re-run multiple times, check distribution of outputs |
| What logs need to capture | Errors, key business events, latency | Full prompts, full responses, intermediate state, per-step timing, request correlation |

The most consequential row is the third. In a traditional app, a NullPointerException at line 47 narrows the search to a few possible causes. In an AI pipeline, "the chart was wrong" could mean a dozen different things, and the only way to localize the fault is to read what each layer actually saw. That requires logs designed for inspection, not just incident response.

## The layer model

Every AI system, no matter how simple, has a small number of recurring layers: presentation, API, orchestration, retrieval, prompt assembly, model inference, response parsing, tool execution, post-processing, and observability. Each layer has an input contract and an output contract. A bug is almost always a contract mismatch between two adjacent layers.

The job of structured logging is to make every contract visible. One log block per layer, showing what that layer received and what it produced. When the system misbehaves, the bug is the boundary where the input was correct but the output was not. Read the logs top-down until you find that boundary.

### Layers in the current project

| # | Layer | Component in this project | Technology | Trust |
|---|---|---|---|---|
| 1 | Presentation | Chat UI / curl client | React 18 + Vite 8, plain HTTP client | Trusted |
| 2 | Transport / API | REST entry point `POST /ask` | Spring MVC (Spring Boot 3.2), Jackson | Trusted |
| 3 | Orchestration | `ChartController`, `PromptOrchestrator` | Java 21, Spring `@Service` | Trusted |
| 4 | Pre-processing | `DateRangeParser`, `SalesDataGenerator` | Java + regex, `java.util.Random` | Trusted |
| 5 | Prompt assembly | Step 3 of `PromptOrchestrator` | Java string composition | Trusted |
| 6 | Model inference | `OllamaClient` → local LLM | Spring `RestClient` → Ollama (HTTP) → qwen2.5-coder:1.5b (GGML) | Trusted infra, **non-deterministic output** |
| 7 | Response parsing | `CodeExtractor` (strip fences, validate) | Java + regex | Trusted |
| 8 | Tool / Code execution | `E2BSandboxClient` → Python wrapper → cloud sandbox | Java `ProcessBuilder` → Python 3 + E2B SDK → E2B Cloud (Linux VM, code-interpreter-v1) | **Untrusted** (LLM-generated code runs here) |
| 9 | Post-processing | Return PNG bytes to controller | Java byte array → HTTP `image/png` | Trusted |
| 10 | Persistence / state | None yet (stateless POC) | — | — |
| 11 | Observability | Structured logs, request correlation | SLF4J + Logback, MDC, `OncePerRequestFilter` | Cross-cutting |
| 12 | Safety / guardrails | None yet | — | — |

Two of these layers do not exist in traditional Spring apps and deserve special attention. **Prompt assembly** is the layer that bit us in the pie-chart bug — it translates user intent into LLM input, and any silent loss of context here cascades through everything downstream. 
**Model inference** is the only fundamentally non-deterministic layer in the stack — everything above it is deterministic Java, everything below it is deterministic Python execution. The model is the wildcard, which is why logging the exact prompt going in and the exact response coming out is non-negotiable.

### The same layers in generic AI-system terms

The vocabulary above is industry-standard. Future POCs (RAG, function calling, agentic systems) reuse the same layers and add a few:

| Generic layer | In this project | In a RAG system | In an agent system | Common technology choices |
|---|---|---|---|---|
| Interface | React UI / curl | Chat UI | Chat UI / API | React, Vue, Streamlit, curl |
| API | Spring `/ask` | Spring `/query` | Spring `/agent/run` | Spring MVC, FastAPI, Express |
| Orchestration | `PromptOrchestrator` | RAG pipeline | Agent loop / planner | Plain Java/Python, LangChain, Spring AI, LangGraph |
| Pre-processing | Date parser, data generator | Document chunking, query rewriting | Tool selection | Custom code, NLP libraries |
| Retrieval | (none — data generated) | Vector search + reranker | Memory lookup | pgvector, Pinecone, Weaviate, Chroma |
| Prompt assembly | Step 3 of orchestrator | Prompt template + retrieved chunks | System prompt + tool defs + history | Custom code, Jinja2, Spring AI templates |
| Model inference | Ollama qwen2.5-coder:1.5b | Same | Same | Ollama, OpenAI/Anthropic API, vLLM, Spring AI client |
| Response parsing | `CodeExtractor` | JSON parser, citation extractor | Tool-call parser, JSON schema validator | Regex, Jackson, Pydantic |
| Tool execution | E2B sandbox | (rare in pure RAG) | API calls, DB queries, sandbox runs | E2B, Modal, native HTTP clients |
| Post-processing | Return PNG | Add citations, format answer | Aggregate tool results into reply | Custom code |
| Persistence / state | None | Conversation history, indexed docs | Episodic memory, agent state | Postgres, Redis, vector DB |
| Observability | Logback + MDC | Same + retrieval metrics | Same + agent step traces | SLF4J/Logback, OpenTelemetry, Langfuse |
| Safety / guardrails | None | Output filtering | Tool allowlists, output validation | Custom rules, NeMo Guardrails, Llama Guard |

The vocabulary is stable; what changes between POCs is which layers are present and how rich each one is.

## Approach

The approach that holds up across AI POCs has four pillars.

**Per-request correlation.** Every incoming request gets a unique transaction ID generated at the entry point and propagated through every downstream call. In Java, SLF4J's MDC (Mapped Diagnostic Context) is the cleanest mechanism — set the ID once in a servlet filter, reference it in the log pattern as `%X{transactionId}`, and it appears automatically on every log line for that request. No need to thread the ID through method signatures. When parallel requests interleave in a busy log file, you can `grep` by transaction ID and pull a single request's full trace in seconds.

**Input/output contracts at every boundary.** Each step in the pipeline logs what it received and what it produced. For small values, log them inline. For large values (full LLM prompts, retrieved document chunks, generated code), log a length summary on the header line and the full content on subsequent lines. The discipline matters: skip one boundary and you create a blind spot exactly where bugs love to hide.

**Step timing.** Every step records its duration. AI pipelines have wildly uneven latency profiles — an embedding lookup might take 50ms while an LLM call takes 20 seconds. Without per-step timing, "the system feels slow" is unactionable. With it, you instantly know whether to optimize the model, the retriever, or the code execution layer.

**Real-time visibility for slow steps.** When a step takes 20+ seconds (an LLM call, a sandbox execution), do not bundle its sub-events into one log statement at the end. Stream them: "OUT → Ollama (sending 1004 chars)" when you send, then "IN ← Ollama (received 2772 bytes)" when the response arrives. The 18 seconds of silence between them is itself information — it tells you the system is waiting on the model, not stuck.

## Format

Plain text, structured for both human reading and machine parsing. ANSI colors and bold are tempting but problematic — they look great in the console and turn into garbage when the file is opened in an editor or piped through `grep`. Use Unicode box-drawing characters (`╔══╗`, `████`) for visual hierarchy instead. They render in any modern terminal and any text editor.

Each step is a single log call, so the timestamp and transaction ID prefix appear once at the step header rather than repeating on every child line. Child labels are indented and aligned vertically so the eye can scan a step's contents at a glance.

Sample step:

```
╔══════════════════════════════════════════════════════════════════════════╗
║  STEP 3 : Build LLM prompt                                               ║
╚══════════════════════════════════════════════════════════════════════════╝
   Input       : userPrompt="Plot pie chart from Jan 2026 to March 2026"
   Description : Compose code-generation prompt with inlined sales data
   Output      : <length=1004 chars>
   Duration    : 1ms
   Full LLM prompt:
You are a Python code generator. Output ONLY executable Python code.
...
```

Heavy boxes (`████`) mark request boundaries. Medium boxes (`╔══╗`) mark pipeline phases and step headers. Sub-events (LLM call OUT/IN, subprocess OUT/IN) stay as plain log lines so they stream in real time during slow steps. The result reads top-to-bottom like a story: a request arrived, it went through six steps, here is what each step received and produced, here is how long each took, here is the response.

## Summary table

| Concern | Choice | Technology | Why |
|---|---|---|---|
| Framework | SLF4J + Logback | Spring Boot default | No new dependency, well-known |
| Destinations | Console + rolling file | Logback `ConsoleAppender` + `RollingFileAppender` | Console for live development, file for after-the-fact analysis |
| File rotation | Daily, 30-day retention, 100MB max | `TimeBasedRollingPolicy` + `SizeAndTimeBasedRollingPolicy` | Bounded disk, enough history for week-old issues |
| Request correlation | SLF4J MDC + servlet filter | `org.slf4j.MDC`, Spring `OncePerRequestFilter` | Auto-propagation, no method-signature pollution |
| Visual structure | Unicode box-drawing characters | UTF-8 plain text | Works in console, editor, and through `grep`/`tail` |
| Step format | One log call per step | Java `StringBuilder` / `String.format` | Avoids repeating timestamp on every child line |
| Slow steps (LLM, sandbox) | Stream sub-events separately | Multiple `log.info()` calls per step | Real-time visibility during long waits |
| Large values (prompts, responses) | Inline length on header, full content below | Plain string concatenation | Greppable summary + complete forensic record |
| Colors | None in file, optional in console | Pattern with `%clr(...)` for console only | ANSI codes corrupt files when opened in editors |

## Why this is critical for debugging

Code-generation tools (GitHub Copilot, Claude Code, Cursor) are excellent at writing components — controllers, services, parsers. They are far less helpful at finding logical bugs in AI pipelines, because those bugs typically live in the *contract between layers*, not inside any single layer.

Consider what a tool would have to know to find a bug like "user asks for a pie chart but receives a bar chart":

- The user's input is the natural-language prompt
- That prompt passes through a date parser, then a data generator, then a prompt builder
- The prompt builder's output is what reaches the LLM
- The LLM's output is parsed and executed in a sandbox
- The "bug" is that the user's chart-type intent is silently dropped at the prompt builder, but the system as a whole still produces a valid (just wrong) chart

There is no exception. No type mismatch. No failing test. The code in every individual class is correct. The bug is an *unspoken assumption* — the prompt builder assumes the chart is always a bar chart — and unspoken assumptions are nearly invisible to static analysis or AI code review tools.

Logs make these bugs visible in minutes. A single grep through a log file shows the prompt that actually reached the LLM, side-by-side with the user's original request, and the mismatch is immediately obvious. Without the logs, the same bug might survive weeks of "swap the model, try a bigger one, tweak the temperature" before someone realizes the user input was being thrown away before it ever reached the model.

This is why structured per-boundary logging is not optional infrastructure for AI systems. It is a primary debugging tool — arguably the primary debugging tool. AI coding assistants are powerful for building components; logs are how you debug systems built from those components.

## Story: the pie-chart bug

A small example from a real session.

The system was a Spring Boot service that takes a natural-language prompt ("Plot bar chart from Jan 2026 to March 2026"), uses a local LLM to generate Python matplotlib code, executes the code in an E2B cloud sandbox, and returns the resulting PNG. End-to-end, it worked. Bar chart requests returned bar charts.

A user typed "Plot pie chart from Jan 2026 to March 2026." The system returned a bar chart.

There was no exception, no HTTP error, no log of failure. The system "worked" — it just produced the wrong chart type. The initial hypothesis was that the small local model (qwen2.5-coder:1.5b) was too weak to follow chart-type instructions. The proposed fix was to upgrade to a larger model, which would have taken hours and cost more compute per request.

Before doing that, structured logging was added to the pipeline: per-request transaction ID, every step's input and output, the full prompt sent to the LLM, the full response received. The next pie-chart request was fired through the UI. The Step 3 log block, captured in plain text in the log file, told the entire story:

```
╔══════════════════════════════════════════════════════════════════════════╗
║  STEP 3 : Build LLM prompt                                               ║
╚══════════════════════════════════════════════════════════════════════════╝
   Input       : userPrompt="Plot pie chart from Jan 2026 to March 2026"
   Output      : <length=1004 chars>
   Full LLM prompt:
You are a Python code generator. Output ONLY executable Python code.
...
Task: generate a bar chart using matplotlib and save it to /home/user/chart.png.
- Use plt.bar() to draw a bar chart
...
```

The user's intent — `pie chart` — never reached the LLM. The prompt builder was hardcoded to ask for a bar chart, regardless of what the user typed. The LLM was doing exactly what it was instructed to do. The model was not the problem. The model upgrade would not have fixed anything.

The fix took one method change: forward the user's original prompt into the LLM input and add a small chart-type-mapping rule. No model upgrade, no few-shot examples, no prompt-engineering tricks. Just: stop dropping the user's input on the floor.

Total diagnosis time: five minutes. Total fix time: ten minutes. Without the structured logging, the same investigation could have taken a day of swapping models, tweaking temperatures, and reading model documentation — chasing a hypothesis that was wrong from the start.

The lesson generalizes. AI pipeline bugs are usually contract mismatches between layers, invisible from outside, undetectable by any single component's tests. The only reliable way to find them is to read what each layer actually saw and produced. That requires logs designed for that purpose, in place from day one — not added after the first incident.
