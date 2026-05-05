# Debugging Story: The Silent Pie Chart Bug

## The Bug

The user typed "Plot pie chart from Jan 2026 to March 2026" in the React UI. The system returned a
bar chart. No exception. No HTTP error. No log line flagged a failure. The response was HTTP 200
with valid PNG bytes. The system worked — it just produced the wrong chart type.

This happened twice, for two different reasons, at two different layers. Fixing the first cause
exposed the second. Both were invisible without per-layer logs.

---

## Why It Was Hard

There was no stack trace. Every pipeline step completed successfully — the LLM responded, the
code extractor ran, the E2B sandbox executed the code and returned a PNG. The failure mode was
"silent wrong output," not a crash.

The failure also felt intermittent. LLMs are non-deterministic; re-running the same prompt produced
slightly different code each time. This made it easy to wonder whether the issue was model
consistency rather than a structural defect — an uncomfortable ambiguity that gets worse, not
better, with each manual rerun.

The bug could have been at any of five candidate layers: the date parser dropping context, the
prompt builder not forwarding intent, the model ignoring instructions, the code extractor mangling
output, or the model simply being too small for the task. The instinctive first theory — "the
model is too small" — was plausible, untested, and wrong. It would have led directly to model
swapping and prompt engineering before anyone had verified what the model actually received.

---

## Act 1: Layer 5 — Prompt Assembly

### Diagnosis

After adding structured per-boundary logging and re-running the failing request, reading Step 3 in
the log revealed the prompt that was actually sent to Ollama:

```
===== STEP 3: Build LLM prompt =====
Full LLM prompt:
Task: generate a bar chart using matplotlib and save it to /home/user/chart.png.

Requirements:
- Line 1: import matplotlib
- Line 2: matplotlib.use('Agg')   ← REQUIRED for headless execution
- Line 3: import matplotlib.pyplot as plt
- Use plt.bar() to draw a bar chart
...
```

The user typed "pie chart." The prompt said "bar chart." `PromptOrchestrator.buildLlmPrompt()` had
a hardcoded instruction that never referenced `userPrompt` at all. The model produced bar charts
because it was explicitly asked for bar charts — working as instructed.

### Fix

`PromptOrchestrator.buildLlmPrompt()` was updated to forward the user's original prompt verbatim
and add an explicit chart-type mapping table:

```
USER REQUEST: Plot pie chart from Jan 2026 to March 2026

Honor the chart type the user requested:
- "pie chart"            → use plt.pie() with labels and autopct='%1.1f%%'
- "line chart"           → use plt.plot() with marker='o'
- "horizontal bar chart" → use plt.barh()
- "bar chart" or unspecified → use plt.bar()
```

The code change: `"Task: generate a bar chart..."` → `"USER REQUEST: %s".formatted(userPrompt)`,
with the rules table appended. No model upgrade; no few-shot examples; no sampling changes.

---

## Why We Thought We Were Done

The prompt now forwarded the user's intent correctly. Step 3 in the next run showed exactly what
it should: the user's words, verbatim, at the top of the prompt, followed by an unambiguous
instruction table. By all expectations, the model should have followed it.

---

## Act 2: Layer 6 — Model Inference

### Diagnosis

The symptom was the same: bar chart. Re-running with the corrected prompt and reading the logs
layer by layer located the new failure at Step 5 — one layer further down than before.

Step 3 now looked correct:

```
===== STEP 3: Build LLM prompt =====
Full LLM prompt:
You are a Python code generator. Output ONLY executable Python code.
...
USER REQUEST: Plot pie chart from Jan 2026 to March 2026

Honor the chart type the user requested:
- "pie chart"            → use plt.pie() with labels and autopct='%1.1f%%'
- "line chart"           → use plt.plot() with marker='o'
...
```

But Step 5 — the extracted code — told the real story:

```
===== STEP 5: CodeExtractor =====
Extracted code:
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
sales_data = {'Jan': 55, 'Feb': 216, 'Mar': 178}

plt.bar(sales_data.keys(), sales_data.values(), color=['blue', 'orange', 'green'])
plt.ylabel('Sales')
plt.title('Monthly Sales — 2026')
plt.tight_layout()
plt.savefig('/home/user/chart.png', dpi=100, bbox_inches='tight')
```

`plt.bar()`. Again. The model had received the right prompt, read the rules table, and then ignored
it. Notably, it had added `color=['blue', 'orange', 'green']` — a detail typically associated with
pie charts — to the bars. The model picked up a superficial stylistic cue from the word "pie" while
disregarding the structural instruction to switch chart functions.

`qwen2.5-coder:1.5b` is a 1.5-billion-parameter quantised model. It is fast and capable of
generating syntactically correct Python. It is not reliable at parsing and following multi-rule
instruction tables, especially when the correct behaviour requires departing from its most common
training pattern (bar charts are far more common than pie charts in matplotlib tutorials).

### Fix

The rules table was replaced with concrete few-shot examples — one complete, correct code block per
chart type. Instead of instructing the model to select from a mapping ("pie chart → plt.pie()"),
the prompt showed it what a pie chart implementation looks like. The model's task became imitation
rather than rule interpretation, which is what small instruction-following models do reliably.

After this fix, "Plot pie chart from Jan 2026 to March 2026" produced a pie chart.

---

## The Lesson

The most important observation from this debugging session is not about either fix individually. It
is about what the logs made visible between them: **the bug moved**.

Without per-layer logging, after the Act 1 fix the team would have re-run the request, seen the
same bar chart, and concluded "the fix didn't work." That conclusion would have been wrong. The
fix did work — at Layer 5. A second, independent bug at Layer 6 had become the new blocker. The
surface symptom was identical; the root cause was completely different; the correct fix was the
opposite of what you would try for a Layer 5 bug.

Structured per-layer logging is what made "the bug moved" visible instead of "the fix failed."
Reading Step 3 confirmed Layer 5 was now correct. Reading Step 5 located the new fault at Layer 6.
Each of those reads took under a minute.

| Layer | Failure mode | Fix pattern |
|---|---|---|
| 5 — Prompt assembly | User intent silently dropped; hardcoded instruction sent instead | Forward original input verbatim into the prompt |
| 6 — Model inference | Instructions received but not followed; model defaulted to its most common pattern | Replace rule tables with few-shot examples the model can imitate |

Both failure classes appear in every AI POC, regardless of application domain. In a RAG system,
Layer 5 fails when the retrieved context is assembled without the original query; Layer 6 fails when
a small model cannot reason over a long retrieved document. In an agent system, Layer 5 fails when
the planner omits the user's constraint from the tool call; Layer 6 fails when the model selects
the wrong tool despite a correct system prompt. The diagnostic technique is the same in every case:
read the logs at each layer boundary, find where the input was correct but the output was not, and
fix that specific layer.

---

See also: [LOGGING.md](LOGGING.md) — the logging architecture that made layer-by-layer diagnosis possible  
See also: [ARCHITECTURE.md](ARCHITECTURE.md) — where prompt assembly (Layer 5) and model inference (Layer 6) sit in the pipeline
