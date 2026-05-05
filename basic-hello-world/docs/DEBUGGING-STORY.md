# Debugging Story: The Silent Pie Chart Bug

## The Bug

The user typed "Plot pie chart from Jan 2026 to March 2026" in the React UI. The system returned a
bar chart. No exception. No HTTP error. No log line flagged a failure. The response was HTTP 200
with valid PNG bytes. The system worked — it just produced the wrong chart type.

---

## Why It Was Hard

Traditional debugging instincts do not apply here.

There was no stack trace to follow. The wrong output was not caused by an exception anywhere in the
call chain — every step completed successfully. The pipeline ran to completion, the E2B sandbox
executed the generated code without error, and the PNG was returned as expected.

The failure was also intermittent-feeling. LLMs are non-deterministic. Re-running the same prompt
occasionally produced slightly different code — sometimes with different wording in comments,
sometimes with minor structural variations. This made it difficult to rule out "it's just a model
consistency issue" without more data.

The bug could have been anywhere in the six-step pipeline. Candidate theories, all plausible before
looking at logs:

- The `DateRangeParser` (Step 1) was dropping the "pie chart" part of the prompt.
- The `buildLlmPrompt` method (Step 3) was not forwarding the user's intent.
- The LLM (Step 4) was ignoring the chart-type instruction.
- The `qwen2.5-coder:1.5b` model was too small to distinguish chart types from instructions.
- The `CodeExtractor` (Step 5) was mangling the code before it reached E2B.

The initial working theory was "the model is too small to follow chart-type instructions." This is
the kind of plausible, hard-to-falsify guess that wastes hours: it leads to experimenting with
different models, adding few-shot examples, or tuning the prompt — all before verifying whether the
model even received the chart-type request in the first place.

---

## The Diagnosis (5 Minutes)

After adding structured per-boundary logging and re-running the failing request, reading Step 3 in
the log revealed the actual prompt sent to Ollama:

```
Task: generate a bar chart using matplotlib and save it to /home/user/chart.png.

Requirements:
- Line 1: import matplotlib
- Line 2: matplotlib.use('Agg')   ← REQUIRED for headless execution
- Line 3: import matplotlib.pyplot as plt
- Use plt.bar() to draw a bar chart
...
```

The user's input — "Plot **pie chart** from Jan 2026 to March 2026" — never appeared in the LLM
prompt. `PromptOrchestrator.buildLlmPrompt()` had a hardcoded instruction: "generate a **bar
chart**." The model was making bar charts because that is what it was explicitly asked to do.
Working as instructed.

Step 4's output confirmed it. The LLM response contained `plt.bar(...)` — the correct response to
the prompt it actually received. Step 5's `CodeExtractor` extracted the code cleanly. Step 6
executed it successfully. Every component behaved correctly; the contract mismatch was between what
the user typed and what `buildLlmPrompt()` forwarded to the model.

---

## The Fix (One Method)

`PromptOrchestrator.buildLlmPrompt()` was updated to forward the user's original prompt into the
LLM input and to include an explicit chart-type-mapping table so the model knew what each chart type
implied in matplotlib terms.

**Before** (hardcoded instruction; user's chart-type intent discarded):

```python
# What Step 3 sent to Ollama when the user asked for a pie chart:
Task: generate a bar chart using matplotlib and save it to /home/user/chart.png.

Requirements:
- Use plt.bar() to draw a bar chart
...
```

**After** (user prompt forwarded; chart-type table added):

```
USER REQUEST: Plot pie chart from Jan 2026 to March 2026

Honor the chart type the user requested:
- "pie chart"            → use plt.pie() with labels and autopct='%1.1f%%'
- "line chart"           → use plt.plot() with marker='o'
- "horizontal bar chart" → use plt.barh()
- "bar chart" or unspecified → use plt.bar()
...
```

The change is in `PromptOrchestrator.java`, method `buildLlmPrompt()`: the hardcoded
`"Task: generate a bar chart..."` instruction was replaced with `"USER REQUEST: %s"` (formatting in
`userPrompt`) followed by the chart-type mapping table. No model upgrade. No few-shot examples. No
prompt engineering tricks.

---

## The Lesson

AI systems fail differently from traditional systems. The failure mode here — "garbage out from
partial input" — was not a bug in any single component. Every component did exactly what it was told.
The contract mismatch between layers was invisible without structured per-boundary logging: Step 3
accepted `userPrompt` as an argument and silently ignored it.

The fix took one line to understand and one method to write. Finding it took five minutes because the
logs captured the exact prompt sent to the LLM. Without that log line, the investigation would have
started at the wrong layer: swapping models, adding few-shot examples, changing sampling
temperature — all the right techniques applied to the wrong problem.

The generalizable principle: every AI POC needs input/output contract logging from day one, at every
boundary where intent is translated — from user to orchestrator, from orchestrator to LLM, from LLM
to execution environment. The cost is a few hundred lines of logging code. The payoff is the
difference between debugging by data and debugging by superstition.

---

See also: [LOGGING.md](LOGGING.md) — the logging architecture that made this diagnosis possible  
See also: [ARCHITECTURE.md](ARCHITECTURE.md) — where `buildLlmPrompt()` sits in the pipeline
