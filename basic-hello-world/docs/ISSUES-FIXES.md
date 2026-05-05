# AI Sandboxes Lab — Issues & Fixes Reference

This document captures every real bug encountered during the build of basic-hello-world, in
chronological order. Each issue follows the same 4-field pattern: Symptom (what you see), Cause
(why it happens), Fix (the exact change), Lesson (the generalizable principle). Use this as a
debugging cheat sheet when symptoms recur.

---

## Quick index

| # | Category | One-line symptom summary | Jump |
|---|---|---|---|
| 1 | Spring HTTP | `content type [application/octet-stream] not supported` calling Ollama | [→](#issue-1--ollamaclient-deserialization-failure) |
| 2 | Env Management | E2B returns 401 even though key is visible in another terminal | [→](#issue-2--stale-env-var-in-the-jars-terminal-e2b-401) |
| 3 | Performance | `Read timed out` on Ollama — first request never completes | [→](#issue-3--ollama-cold-start-timeout-wrong-model-choice) |
| 4 | E2B Raw HTTP | `502: The sandbox is running but port is not open` on every probe | [→](#issue-4--e2b-raw-http--port-502-envd-port-not-open) |
| 5 | E2B Raw HTTP | `400: templateID missing` on sandbox creation | [→](#issue-5--e2b-400-templateid-missing) |
| 6 | Env Management | `401: Invalid key format` despite key appearing correct | [→](#issue-6--e2b-401-api-key-typo-4dce-vs-24dc) |
| 7 | Architecture | Raw Java HTTP stalls on new protocol error after every fix | [→](#issue-7--raw-java-http--python-wrapper-architectural-pivot) |
| 8 | Python SDK | `TypeError: SandboxBase.__init__() missing 5 required positional arguments` | [→](#issue-8--e2b-python-sdk-api-drift-sandbox--sandboxcreate) |
| 9 | Python Subprocess | Subprocess fails with exit code 1; error message is empty | [→](#issue-9--javas-processbuilder-silently-discarded-python-stderr) |
| 10 | Packaging | `no such option: --break-system-packages` running pip install | [→](#issue-10--pip---break-system-packages-flag-not-recognized) |
| 11 | Naming | No obvious link between Python script and the Java class that invokes it | [→](#issue-11--naming-inconsistency-across-language-boundary) |

---

## Issue 1 — OllamaClient deserialization failure

### Symptom

Spring's `RestClient` threw a deserialization error when calling Ollama's `/api/generate` endpoint:

```
content type [application/octet-stream] not supported
```

This happened even though a direct `curl` call to the same endpoint confirmed Ollama returns
`Content-Type: application/json`.

### Cause

`RestClient.retrieve().body(GenerateResponse.class)` performs strict `Content-Type` header checking
before handing the response body to the JSON converter. For some Ollama versions or HTTP stack
configurations, the response was being classified as `application/octet-stream` before Spring's
converter pipeline could treat it as JSON. Spring refused to deserialize it rather than trying the
JSON converter anyway.

### Fix

Switched from `.retrieve().body()` to `.exchange()`, which exposes the raw response at the
`InputStream` level. The response body is read as bytes directly, then decoded with `ObjectMapper`:

```java
// OllamaClient.java
String rawJson = restClient.post()
    .uri("/api/generate")
    .contentType(MediaType.APPLICATION_JSON)
    .accept(MediaType.APPLICATION_JSON)
    .body(new GenerateRequest(model, prompt, false))
    .exchange((request, response) -> {
        try (java.io.InputStream is = response.getBody()) {
            byte[] bytes = is.readAllBytes();
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    });

GenerateResponse parsed = objectMapper.readValue(rawJson, GenerateResponse.class);
```

Build marker updated to `EXCHANGE_RAW_BYTES_v2` to confirm the new code path is running at startup.

### Lesson

When Spring's auto-conversion pipeline fights you on `Content-Type`, drop one level: get the raw
bytes, parse manually with `ObjectMapper`. This bypasses the framework's content-type negotiation
entirely without abandoning `RestClient` or introducing a new HTTP library. The `.exchange()` escape
hatch exists precisely for cases where the framework's assumptions don't match the server's behavior.

---

## Issue 2 — Stale env var in the JAR's terminal (E2B 401)

### Symptom

The JAR returned HTTP 401 Unauthorized from E2B on every request. In a separate terminal,
`echo $E2B_API_KEY` showed the correct key value.

### Cause

The terminal used to run `java -jar` was opened **before** `~/.api_keys` was updated with the
correct key. That shell had the old value of `E2B_API_KEY` in its environment. The JVM process
inherited the stale value at startup.

Environment variables are copied from the parent process at the moment the child is created. They
are never refreshed automatically from file changes. Updating `~/.api_keys` has no effect on shells
that are already open.

### Fix

Always `source ~/.api_keys` in the **same terminal** where `java -jar` will run, immediately before
the `java -jar` command:

```bash
source ~/.api_keys
echo "Last 4: $(echo $E2B_API_KEY | tail -c 5)"   # verify: should print 24dc
java -jar target/basic-hello-world-0.0.1-SNAPSHOT.jar
```

If you see a 401 mid-demo, `Ctrl+C` the JAR, re-source, and restart.

### Lesson

Env vars are inherited at process creation and never refreshed. Changing the file does nothing for
shells already open. Re-source in every affected terminal. When debugging auth failures, always
verify the credential the JVM **actually received** — not what a separate terminal shows. The two
can differ indefinitely.

---

## Issue 3 — Ollama cold-start timeout (wrong model choice)

### Symptom

The first end-to-end test timed out after 60 seconds with `Read timed out` on the Ollama HTTP call.
The LLM response never arrived.

### Cause

The configured model was `gemma4:e2b` (7.2 GB, referenced in the stale comment at the top of
`application.properties` and in `BasicHelloWorldApplication.java`). On CPU with no GPU offload —
verified by Ollama's startup log showing `offloaded 0/36 layers to GPU` — cold-start model loading
plus CPU inference totalled 60–180 seconds per request. `OllamaClient` has a 60-second read
timeout, so the request expired before the model finished responding.

### Fix

Two changes:

1. **`application.properties`** — switch the model:

   ```properties
   # Before:
   ollama.model=gemma4:e2b

   # After:
   ollama.model=qwen2.5-coder:1.5b
   ```

   `qwen2.5-coder:1.5b` is ~2 GB in RAM and responds in 5–20 seconds on CPU.

2. **Pre-warm Ollama** before the first real request to avoid cold-start during demos:

   ```bash
   curl -s -X POST http://localhost:11434/api/generate \
     -d '{"model":"qwen2.5-coder:1.5b","prompt":"hi","stream":false}' \
     > /dev/null
   ```

### Lesson

Match the model to the task. Writing a matplotlib snippet does not require a 7B general-purpose LLM.
A 1.5B code-specialized model (`qwen2.5-coder`) is 5–10× faster with equal quality for code
generation on CPU hardware. Latency matters in a live demo. Over-provisioning the model wastes RAM
and turns interactive demos into wait-fests.

---

## Issue 4 — E2B Raw HTTP — port 502 (envd port not open)

### Symptom

All HTTP probes to the E2B sandbox's envd daemon returned:

```json
{"message": "The sandbox is running but port is not open", "port": 49982}
```

Port 49982 was the value set in `application.properties` (`e2b.envd.port=49982`) and used by
`E2BSandboxClientHttpRaw.java` to construct the envd URL:

```java
String envdBase = "https://" + envdPort + "-" + sandboxId + "." + domain;
// → https://49982-{sandboxId}.e2b.app
```

Tested port 49983 and variations with and without the client-ID subdomain prefix. All returned 502.

### Cause

E2B's envd daemon listens on a port that is determined by the sandbox version and communicated only
through the official SDK's internal response parsing. The subdomain routing format
`https://{port}-{sandboxId}.e2b.app` is real, but the **port value is opaque** — it cannot be
reliably discovered or hardcoded without SDK-level negotiation.

### Fix

Stopped probing for the port. This issue directly motivated the architectural pivot to the Python
SDK wrapper (see Issue 7). The deprecated Java raw HTTP implementation is preserved in
`E2BSandboxClientHttpRaw.java` (no `@Component` annotation — passive, not a Spring bean) for
reference and comparison.

### Lesson

When a vendor's internal ports are undocumented and version-dependent, hardcoding a port is a dead
end. Use the official SDK, which handles port resolution internally — even if that means bridging to
a different language. The 502 error here wasn't a configuration mistake; it was a signal that the
integration approach itself was wrong.

---

## Issue 5 — E2B 400 templateID missing

### Symptom

`POST https://api.e2b.dev/sandboxes` returned:

```json
{"code": 400, "message": "templateID missing"}
```

### Cause

The initial `createSandbox()` implementation sent an empty or minimal JSON body. E2B's sandbox
creation endpoint requires a `templateID` field that identifies the pre-built sandbox image to use.
Without it the API rejects the request with 400.

### Fix

Added the required field to the request body in `E2BSandboxClientHttpRaw.java`. The
`code-interpreter-v1` template has Python 3, matplotlib, pandas, and numpy pre-installed:

```java
Map<String, Object> body = Map.of(
    "templateID", templateId,   // from application.properties: e2b.template-id=code-interpreter-v1
    "timeout",    300
);
```

The fix is also tracked in the raw client's build marker: `TEMPLATE_ID_FIX_v1`.

### Lesson

Required fields in REST APIs don't always appear in error messages as field-level validation errors.
A missing required field often surfaces as a generic 400. Read the full API contract before sending
requests — don't assume `{}` is a valid empty body.

---

## Issue 6 — E2B 401 API key typo (4dce vs 24dc)

### Symptom

Every E2B API request returned HTTP 401 `Invalid key format` — even after confirming the key was
set in `~/.api_keys` and sourced in the current shell.

### Cause

Typo when first copying the key into `~/.api_keys`: the suffix was stored as `4dce` instead of
`24dc` (two hex digits transposed). The error was subtle enough that repeated visual inspection
missed it. `echo $E2B_API_KEY` appeared "correct" at a glance.

### Fix

Cross-checked the stored value character-by-character against the E2B dashboard
(`e2b.dev/dashboard → API keys`). Corrected the transposed digits in `~/.api_keys`. Re-sourced the
shell. Verified with a deliberate last-4 check:

```bash
source ~/.api_keys
echo "Last 4: $(echo $E2B_API_KEY | tail -c 5)"
# Expected output: Last 4: 24dc
```

### Lesson

When an API consistently returns auth errors despite "correct-looking" credentials, don't debug the
code — verify the credential character-by-character against the source-of-truth dashboard. Visual
inspection of hex strings is unreliable. Keep a permanent note of the key's last N characters as a
quick sanity-check command. `tail -c 5` is faster than squinting.

---

## Issue 7 — Raw Java HTTP → Python wrapper (architectural pivot)

### Symptom

After Issues 4, 5, and 6 were resolved, Java-native HTTP integration kept uncovering new
protocol-level obstacles: Connect-RPC binary frame encoding, undocumented process-path changes
between envd versions, multipart upload format differences. Progress had stalled — each fix exposed
the next unknown.

### Cause

E2B's official SDK is Python/Node only. A Java-native integration requires reverse-engineering:

- Connect-RPC binary frame protocol (5-byte header + length-prefixed JSON messages — implemented
  in `E2BSandboxClientHttpRaw.parseConnectFrames()`)
- Opaque port resolution (Issue 4)
- Sandbox lifecycle management with correct `templateID` (Issue 5)
- Undocumented envd API endpoint paths (`e2b.envd.process.path=/envd.process.v1.Process/Start`)

None of this is officially documented for Java. Every detail required trial and error.

### Fix

Architectural pivot: Java continues to orchestrate the pipeline, but all E2B-specific communication
is delegated to a small Python wrapper (`e2b_sandbox_client.py`) that uses E2B's official Python
SDK. Java's `E2BSandboxClient.java` calls this script via `ProcessBuilder`.

```
Before:
  Java → raw HTTP → E2B API  (~200 lines, fragile, undocumented)

After:
  Java → ProcessBuilder → python3 e2b_sandbox_client.py → E2B Python SDK → E2B API
  (~30 lines of Python, official SDK, stable)
```

The old implementation is preserved in `E2BSandboxClientHttpRaw.java` (no `@Component` — passive,
not instantiated by Spring) for reference. The new bean carries build marker `PYTHON_WRAPPER_v3`.

### Lesson

Polyglot subprocess delegation is a real production pattern, not a workaround. When a vendor's
official SDK exists in another language, bridging to that language via a subprocess is almost always
faster and more reliable than reimplementing the SDK from scratch. Language pride is a worse
engineering outcome than working software. The subprocess boundary is also explicit — it's a
documented, testable seam rather than hidden HTTP magic.

---

## Issue 8 — E2B Python SDK API drift (`Sandbox()` → `Sandbox.create()`)

### Symptom

The first run of `e2b_sandbox_client.py` failed immediately with:

```
TypeError: SandboxBase.__init__() missing 5 required positional arguments:
  'sandbox_id', 'envd_version', 'envd_access_token', 'sandbox_domain', 'connection_config'
```

### Cause

The Python wrapper was initially written as:

```python
with Sandbox() as sandbox:
```

This was based on older E2B SDK documentation. In the installed version (`e2b-code-interpreter`
2.6.2), `Sandbox()` is a constructor that expects an **existing** sandbox's connection details as
positional arguments (i.e., it reconnects to a running sandbox). To **create** a new sandbox, the
correct API is the classmethod `Sandbox.create()`.

### Fix

Single-line change in `e2b_sandbox_client.py`:

```python
# Before:
with Sandbox() as sandbox:

# After:
with Sandbox.create() as sandbox:
```

Discovered the correct API surface by introspecting the installed class:

```bash
python3 -c "from e2b_code_interpreter import Sandbox; print([m for m in dir(Sandbox) if not m.startswith('_')])"
```

`create` appeared in the output alongside `connect`, `list`, and `kill` — the canonical lifecycle
methods for the current SDK version.

### Lesson

SDK API drift is real, even between minor versions. When a constructor raises a
positional-argument count error, the class may have split its "create new" and "reconnect to
existing" paths into separate methods. Introspect with `dir(ClassName)` — a classmethod like
`.create()` or `.connect()` is often the new instantiation path in modern SDKs that distinguish
resource creation from resource attachment.

---

## Issue 9 — Java's ProcessBuilder silently discarded Python stderr

### Symptom

The Python wrapper subprocess failed with exit code 1. The error message Java received was empty or
useless — `stdout: ` (blank). Debugging required guessing what the Python side was doing because no
Python traceback appeared in the Spring Boot logs.

### Cause

`ProcessBuilder` by default exposes stdout and stderr as two separate streams:
`proc.getInputStream()` (stdout) and `proc.getErrorStream()` (stderr). The initial implementation
only drained `proc.getInputStream()`. Python runtime exceptions, SDK error messages, and
`print(..., file=sys.stderr)` output all go to `proc.getErrorStream()` — which was never read.
Unread streams fill their OS pipe buffer and block the subprocess, or the data is simply discarded
when the process exits.

### Fix

Added a second background thread in `E2BSandboxClient.java` to drain `proc.getErrorStream()`
alongside the existing stdout drain thread. Both threads log their output in real time while the
subprocess runs:

```java
// Drain stdout — Python's [e2b_runner] progress lines
Thread stdoutReader = new Thread(() -> {
    try (var reader = new BufferedReader(
            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
            log.info("[subprocess stdout] {}", line);   // INFO — expected output
            stdoutCapture.append(line).append("\n");
        }
    } catch (Exception ignored) {}
});

// Drain stderr — Python tracebacks, SDK warnings, error messages
Thread stderrReader = new Thread(() -> {
    try (var reader = new BufferedReader(
            new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
            log.warn("[subprocess stderr] {}", line);   // WARN — distinguishable in logs
            stderrCapture.append(line).append("\n");
        }
    } catch (Exception ignored) {}
});

stdoutReader.start();
stderrReader.start();
```

Keeping the streams separate means stdout appears at INFO level and stderr at WARN level — they
remain distinguishable in the log output without mixing them into a single stream.

### Lesson

When spawning a subprocess, always drain **both** stdout and stderr, either on separate background
threads (as here) or by merging them with `pb.redirectErrorStream(true)`. Python tracebacks, package
warnings, and SDK debug messages all go to stderr. Without explicit draining, you are debugging
blind — the subprocess fails silently and the error message is lost to the OS pipe buffer.

---

## Issue 10 — pip `--break-system-packages` flag not recognized

### Symptom

Running:

```bash
pip install e2b-code-interpreter --break-system-packages
```

Returned:

```
ERROR: no such option: --break-system-packages
```

The install failed without installing the package.

### Cause

`--break-system-packages` was introduced in pip 23.x for newer Ubuntu/Debian systems that enforce
PEP 668 ("externally managed environments"). The flag is an explicit override for the new
"don't install packages into the system Python" restriction. The machine's pip version was 22.x,
which predates this restriction entirely — there is nothing to override, and the flag is simply
unrecognised.

### Fix

Remove the flag:

```bash
pip install e2b-code-interpreter
```

The install completed immediately without the flag.

### Lesson

Commands copied from blog posts and documentation often include flags designed for a specific
OS/pip version or to work around a specific restriction. When a flag causes an error, try the command
without it. Your environment may not need the escape hatch the author was working around. Flags like
`--break-system-packages`, `--user`, and `--no-build-isolation` are environment-specific, not
universally required.

---

## Issue 11 — Naming inconsistency across language boundary

### Symptom

The Python wrapper was named `e2b_runner.py` but the Java class that invokes it was
`E2BSandboxClient.java`. New contributors reading the codebase could not identify which Python file
paired with which Java class without tracing the `ProcessBuilder` call. The pairing was invisible
at a glance.

The stale reference is still visible today — `E2BSandboxClientHttpRaw.java`'s class-header comment
reads:

```
* Replaced by E2BSandboxClient.java which uses ProcessBuilder to invoke
* e2b_runner.py (Python wrapper around E2B's official Python SDK).
```

This is the old name. The rename happened after that comment was written.

### Cause

`e2b_runner.py` was named for brevity during rapid prototyping. It describes what the script does
(runs code in a sandbox) but not what it is architecturally — the Python-side E2B client that
mirrors `E2BSandboxClient.java`.

### Fix

Renamed `e2b_runner.py` → `e2b_sandbox_client.py`. Updated two references:

1. **`application.properties`:**

   ```properties
   # Before:
   e2b.runner-script=e2b_runner.py

   # After:
   e2b.runner-script=e2b_sandbox_client.py
   ```

2. **`E2BSandboxClient.java`** — the `@Value` default that falls back if the property is absent:

   ```java
   // Before:
   @Value("${e2b.runner-script:e2b_runner.py}") String runnerScriptPath

   // After:
   @Value("${e2b.runner-script:e2b_sandbox_client.py}") String runnerScriptPath
   ```

### Lesson

In polyglot projects, name files across language boundaries to signal their architectural pairing —
even when the languages use different casing conventions. `e2b_sandbox_client.py` (Python
snake\_case) and `E2BSandboxClient.java` (Java PascalCase) are unambiguously paired at a glance.
`e2b_runner.py` could be paired with anything. The rename cost 10 minutes; the readability benefit
persists for the lifetime of the project.

---

## Summary table

| # | Issue title | Category | Fix in one line | Lesson in one line |
|---|---|---|---|---|
| 1 | OllamaClient deserialization failure | Spring HTTP | Switch from `.retrieve().body()` to `.exchange()` + manual `ObjectMapper` parse | When Spring's content-type converter fights you, read raw bytes and parse yourself |
| 2 | Stale env var in JAR's terminal (E2B 401) | Env Management | `source ~/.api_keys` in the **same** terminal before `java -jar` | Env vars are copied at process creation; file changes don't refresh open shells |
| 3 | Ollama cold-start timeout | Performance | Switch `ollama.model` from `gemma4:e2b` to `qwen2.5-coder:1.5b` | Match the model to the task; a 1.5B code model is 5–10× faster than a 7B general model on CPU |
| 4 | E2B port 502 (envd port not open) | E2B Raw HTTP | Stop guessing the port; pivot to official Python SDK | Undocumented vendor ports can't be hardcoded; use the SDK that resolves them internally |
| 5 | E2B 400 templateID missing | E2B Raw HTTP | Add `"templateID": "code-interpreter-v1"` to the create-sandbox request body | Required fields in REST APIs produce generic 400s — read the full contract first |
| 6 | E2B 401 API key typo (4dce vs 24dc) | Env Management | Cross-check key against dashboard; always verify with `tail -c 5` | Visual inspection of hex strings fails; keep a last-4-chars sanity check |
| 7 | Raw Java HTTP → Python wrapper | Architecture | Replace `E2BSandboxClientHttpRaw.java` with `ProcessBuilder` → `e2b_sandbox_client.py` | When the official SDK exists in another language, bridge to it — language pride is worse than working software |
| 8 | E2B Python SDK API drift | Python SDK | `Sandbox()` → `Sandbox.create()` | SDK constructors split into lifecycle methods between versions; `dir(ClassName)` before writing |
| 9 | ProcessBuilder silently discarded Python stderr | Python Subprocess | Add background thread draining `proc.getErrorStream()` at WARN level | Always drain both stdout and stderr from subprocesses or you debug blind |
| 10 | pip `--break-system-packages` not recognised | Packaging | Remove the flag — older pip doesn't need or recognise it | Environment-specific flags in blog commands should be dropped when they error |
| 11 | Naming inconsistency across language boundary | Naming | Rename `e2b_runner.py` → `e2b_sandbox_client.py`; update `application.properties` and `@Value` default | Name polyglot files to signal their architectural pairing across language conventions |

---

## Cross-cutting lessons

### Environment & secrets management (Issues 2, 6)

Both issues stem from the same root: credentials are invisible data that fail silently. Issue 2
demonstrates that a credential can be "correct" in one shell and wrong in the shell that matters —
the JVM inherits whatever was set at spawn time. Issue 6 demonstrates that a credential can look
correct to human eyes and still be wrong. The defence in both cases is the same: make the credential
visible just enough to verify, without printing it in full. `echo "Last 4: $(echo $E2B_API_KEY | tail
-c 5)"` is not paranoia — it is a five-second check that eliminates an entire class of
hard-to-diagnose failures. Every demo runbook should include it. Every startup script should assert
it.

### Framework HTTP and subprocess integration (Issues 1, 9)

Both issues are instances of the same problem: the framework's abstraction hid something you needed
to see. In Issue 1, Spring's content-type converter hid the raw bytes and refused to deserialize
them. In Issue 9, `ProcessBuilder`'s default stream configuration hid Python's stderr. In both
cases the fix was to drop one level of abstraction — use `.exchange()` instead of `.body()`, drain
`getErrorStream()` instead of ignoring it. The lesson is not to avoid frameworks, but to know where
their escape hatches are. `.exchange()` in Spring and explicit stderr draining in `ProcessBuilder`
are exactly those escape hatches.

### Third-party API integration (Issues 4, 5, 7, 8, 10)

Issues 4, 5, and 7 form a single arc: an attempt to integrate with E2B via raw Java HTTP, the
successive obstacles that approach surfaced (undocumented ports, missing required fields,
version-fragile Connect-RPC encoding), and the architectural decision to abandon the attempt in
favour of the official Python SDK wrapper. Issue 8 is the API-drift tax that came with the SDK
approach — a classmethod rename between minor versions. Issue 10 is a packaging flag that exists
only in certain pip versions. Taken together, these five issues reflect a consistent truth about
third-party API integration: the official SDK absorbs far more maintenance burden than it appears to
from the outside. Reimplementing what an SDK does is almost always slower, more fragile, and harder
to keep in sync with API changes. The cost of a subprocess bridge is negligible compared to the cost
of maintaining a homemade protocol implementation.

### Engineering craft (Issues 3, 11, and the polyglot decision in 7)

These three issues have nothing to do with bugs in the traditional sense. Issue 3 is a model choice
made before measuring latency on the actual hardware. Issue 11 is a name chosen for convenience
during prototyping that survived into the permanent codebase. The polyglot decision in Issue 7
required trading a language-homogeneous codebase for a working one. All three required stepping back
from the immediate tactical problem and asking a strategic question: is the tool right for the task
(model size), does the name communicate intent (file naming), and is language consistency worth the
integration cost (Python subprocess)? In all three cases, the answer favoured pragmatism over
uniformity. That is the craft lesson: build the right thing first, then make it clean.

---

See also: [DEBUGGING-STORY.md](DEBUGGING-STORY.md) — detailed narrative of the silent pie-chart bug (Issues at the prompt-assembly and model-inference layers)  
See also: [ARCHITECTURE.md](ARCHITECTURE.md) — how the Python subprocess fits into the three trust planes  
See also: [LOGGING.md](LOGGING.md) — why per-layer logging was essential for diagnosing Issues 1, 8, and 9
