/**
 * PromptOrchestrator.java
 *
 * Glues the entire pipeline together. Exposes two entry points:
 *
 *   handle()        — synchronous, returns PNG bytes (used by POST /ask / curl)
 *   handleWithSse() — same pipeline, emits SSE events per step (used by POST /ask/stream)
 *
 * Both delegate to the private runPipeline() method, which runs all six steps
 * in order and optionally fires SSE events when sse != null:
 *
 *   DateRangeParser     → parse the user's natural-language prompt
 *   SalesDataGenerator  → generate random sales data for the parsed range
 *   OllamaClient        → call the local LLM with a code-generation prompt
 *   CodeExtractor       → strip markdown/preamble from the LLM response
 *   E2BSandboxClient    → run the Python code in E2B and return PNG bytes
 *
 * Pipeline position: ChartController → PromptOrchestrator → (all services)
 */
package com.sandboxlab.service;

import com.sandboxlab.dto.DateRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PromptOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PromptOrchestrator.class);

    private final DateRangeParser    dateRangeParser;
    private final SalesDataGenerator salesDataGenerator;
    private final OllamaClient       ollamaClient;
    private final CodeExtractor      codeExtractor;
    private final E2BSandboxClient   e2bSandboxClient;

    public PromptOrchestrator(
            DateRangeParser    dateRangeParser,
            SalesDataGenerator salesDataGenerator,
            OllamaClient       ollamaClient,
            CodeExtractor      codeExtractor,
            E2BSandboxClient   e2bSandboxClient) {
        this.dateRangeParser    = dateRangeParser;
        this.salesDataGenerator = salesDataGenerator;
        this.ollamaClient       = ollamaClient;
        this.codeExtractor      = codeExtractor;
        this.e2bSandboxClient   = e2bSandboxClient;
    }

    /**
     * Synchronous entry point — used by POST /ask (curl / non-streaming).
     * Returns raw PNG bytes; all pipeline exceptions propagate to the caller.
     */
    public byte[] handle(String userPrompt) {
        return runPipeline(userPrompt, null);
    }

    /**
     * SSE entry point — used by POST /ask/stream (browser fetch + ReadableStream).
     * Runs the same pipeline but emits step-start / step-end / complete / error
     * events via the supplied SseStepEmitter.  Exceptions are swallowed here
     * because they have already been forwarded to the client via sse.emitError().
     */
    public void handleWithSse(String userPrompt, SseStepEmitter sse) {
        try {
            runPipeline(userPrompt, sse);
        } catch (RuntimeException e) {
            // Error already forwarded via sse.emitError() before the re-throw
            log.debug("Pipeline exception already forwarded via SSE: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Core pipeline — runs all 6 steps; optionally emits SSE events
    // ─────────────────────────────────────────────────────────────────────────

    private byte[] runPipeline(String userPrompt, SseStepEmitter sse) {
        long pipelineStart = System.currentTimeMillis();
        log.info(box("PIPELINE START") + lbl("Input", "\"" + userPrompt + "\""));

        int    currentStep     = 0;
        String currentStepName = "";

        try {
            // ── Step 1: DateRangeParser ───────────────────────────────────────
            currentStep = 1; currentStepName = "Parsing date range";
            long t1 = System.currentTimeMillis();
            if (sse != null) sse.emitStepStart(1, currentStepName);
            DateRange dateRange = dateRangeParser.parse(userPrompt);
            if (sse != null) sse.emitStepEnd(1, currentStepName, System.currentTimeMillis() - t1);
            log.info(box("STEP 1 : DateRangeParser")
                + lbl("Input",       "\"" + userPrompt + "\"")
                + lbl("Description", "Parse natural-language date range into structured DateRange")
                + lbl("Output",      "granularity=" + dateRange.granularity()
                                     + ", year=" + dateRange.year()
                                     + ", periods=" + dateRange.periods())
                + lbl("Duration",    (System.currentTimeMillis() - t1) + "ms"));

            // ── Step 2: SalesDataGenerator ────────────────────────────────────
            currentStep = 2; currentStepName = "Generating data";
            long t2 = System.currentTimeMillis();
            if (sse != null) sse.emitStepStart(2, currentStepName);
            Map<String, Integer> salesData = salesDataGenerator.generate(dateRange);
            if (sse != null) sse.emitStepEnd(2, currentStepName, System.currentTimeMillis() - t2);
            log.info(box("STEP 2 : SalesDataGenerator")
                + lbl("Input",       dateRange)
                + lbl("Description", "Generate random sales values (50-300) for each period")
                + lbl("Output",      salesData)
                + lbl("Duration",    (System.currentTimeMillis() - t2) + "ms"));

            // ── Step 3: Build LLM prompt ──────────────────────────────────────
            currentStep = 3; currentStepName = "Building LLM prompt";
            long t3 = System.currentTimeMillis();
            if (sse != null) sse.emitStepStart(3, currentStepName);
            String llmPrompt = buildLlmPrompt(userPrompt, dateRange, salesData);
            if (sse != null) sse.emitStepEnd(3, currentStepName, System.currentTimeMillis() - t3);
            log.info(box("STEP 3 : Build LLM prompt")
                + lbl("Input",       "userPrompt=\"" + userPrompt + "\", salesData=" + salesData)
                + lbl("Description", "Compose code-generation prompt with inlined sales data")
                + lbl("Output",      "<length=" + llmPrompt.length() + " chars>")
                + "\n   Full LLM prompt:\n" + llmPrompt
                + lbl("Duration",    (System.currentTimeMillis() - t3) + "ms"));

            // ── Step 4: OllamaClient ──────────────────────────────────────────
            currentStep = 4; currentStepName = "Calling LLM (Ollama)";
            log.info(box("STEP 4 : OllamaClient")
                + lbl("Input",       "<length=" + llmPrompt.length() + " chars>")
                + lbl("Description", "Call local Ollama LLM to generate Python chart code"));
            long t4 = System.currentTimeMillis();
            if (sse != null) sse.emitStepStart(4, currentStepName);
            String rawLlmResponse = ollamaClient.generateCode(llmPrompt);
            if (sse != null) sse.emitStepEnd(4, currentStepName, System.currentTimeMillis() - t4);
            log.info("\n   Output      : <length={} chars>\n   Duration    : {}ms",
                rawLlmResponse.length(), System.currentTimeMillis() - t4);

            // ── Step 5: CodeExtractor ─────────────────────────────────────────
            currentStep = 5; currentStepName = "Extracting code";
            log.info(box("STEP 5 : CodeExtractor")
                + lbl("Input",       "<length=" + rawLlmResponse.length() + " chars>")
                + lbl("Description", "Strip markdown fences and preamble; extract clean Python"));
            long t5 = System.currentTimeMillis();
            if (sse != null) sse.emitStepStart(5, currentStepName);
            String cleanCode = codeExtractor.extract(rawLlmResponse);
            String finalCode = ensureAggBackend(cleanCode);
            if (sse != null) sse.emitStepEnd(5, currentStepName, System.currentTimeMillis() - t5);
            log.info("\n   Output      : <length={} chars>\n   Duration    : {}ms",
                cleanCode.length(), System.currentTimeMillis() - t5);
            log.debug("Final Python code:\n{}", finalCode);

            // ── Step 6: E2BSandboxClient ──────────────────────────────────────
            currentStep = 6; currentStepName = "Running in sandbox";
            log.info(box("STEP 6 : E2BSandboxClient")
                + lbl("Input",       "<length=" + finalCode.length() + " chars>")
                + lbl("Description", "Execute Python in E2B cloud sandbox; retrieve PNG chart"));
            long t6 = System.currentTimeMillis();
            if (sse != null) sse.emitStepStart(6, currentStepName);
            byte[] png = e2bSandboxClient.executePythonAndGetChart(finalCode);
            if (sse != null) sse.emitStepEnd(6, currentStepName, System.currentTimeMillis() - t6);
            log.info("\n   Output      : <length={} bytes PNG>\n   Duration    : {}ms",
                png.length, System.currentTimeMillis() - t6);

            long totalDuration = System.currentTimeMillis() - pipelineStart;
            log.info(box("PIPELINE END") + lbl("Duration", totalDuration + "ms"));
            if (sse != null) sse.emitComplete(png, totalDuration);
            return png;

        } catch (RuntimeException e) {
            // Forward error to SSE client before re-throwing so ChartController.ask()
            // also gets the exception for its HTTP error mapping.
            if (sse != null) {
                sse.emitError(currentStep, currentStepName,
                    e.getMessage() != null ? e.getMessage() : "Unknown error");
            }
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the few-shot prompt we send to the LLM.
     *
     * Small models (qwen2.5-coder:1.5b) follow concrete code examples far more
     * reliably than instruction lists. Four examples (bar, pie, line, horizontal
     * bar) give the model a template to copy; the USER REQUEST tells it which one
     * to use; the actual sales data at the bottom overrides the placeholder values.
     *
     * String concatenation is used instead of a text block so that Python's
     * % characters (e.g. autopct='%1.1f%%') reach the model literally without
     * any Java format-specifier escaping.
     */
    private String buildLlmPrompt(String userPrompt, DateRange dateRange, Map<String, Integer> salesData) {
        String dataLines = salesData.entrySet().stream()
            .map(e -> "  " + e.getKey() + ": " + e.getValue())
            .collect(Collectors.joining("\n"));

        return "You are a Python code generator. Output ONLY executable Python code.\n"
            + "Do NOT include any explanation, markdown, code fences, or comments.\n"
            + "The first line of your response must start with: import matplotlib\n"
            + "\n"
            + "USER REQUEST: " + userPrompt + "\n"
            + "\n"
            + "Match the user's chart type EXACTLY. Use the closest matching example below\n"
            + "as your template, then adapt the data values:\n"
            + "\n"
            + "EXAMPLE — bar chart for {Jan: 10, Feb: 20, Mar: 30}:\n"
            + "import matplotlib\n"
            + "matplotlib.use('Agg')\n"
            + "import matplotlib.pyplot as plt\n"
            + "data = {'Jan': 10, 'Feb': 20, 'Mar': 30}\n"
            + "plt.bar(list(data.keys()), list(data.values()))\n"
            + "plt.title('Monthly Sales — 2026')\n"
            + "plt.ylabel('Sales')\n"
            + "plt.grid(axis='y', alpha=0.3)\n"
            + "plt.tight_layout()\n"
            + "plt.savefig('/home/user/chart.png', dpi=100, bbox_inches='tight')\n"
            + "\n"
            + "EXAMPLE — pie chart for {Jan: 10, Feb: 20, Mar: 30}:\n"
            + "import matplotlib\n"
            + "matplotlib.use('Agg')\n"
            + "import matplotlib.pyplot as plt\n"
            + "data = {'Jan': 10, 'Feb': 20, 'Mar': 30}\n"
            + "plt.pie(list(data.values()), labels=list(data.keys()), autopct='%1.1f%%')\n"
            + "plt.title('Monthly Sales — 2026')\n"
            + "plt.tight_layout()\n"
            + "plt.savefig('/home/user/chart.png', dpi=100, bbox_inches='tight')\n"
            + "\n"
            + "EXAMPLE — line chart for {Jan: 10, Feb: 20, Mar: 30}:\n"
            + "import matplotlib\n"
            + "matplotlib.use('Agg')\n"
            + "import matplotlib.pyplot as plt\n"
            + "data = {'Jan': 10, 'Feb': 20, 'Mar': 30}\n"
            + "plt.plot(list(data.keys()), list(data.values()), marker='o')\n"
            + "plt.title('Monthly Sales — 2026')\n"
            + "plt.ylabel('Sales')\n"
            + "plt.grid(axis='y', alpha=0.3)\n"
            + "plt.tight_layout()\n"
            + "plt.savefig('/home/user/chart.png', dpi=100, bbox_inches='tight')\n"
            + "\n"
            + "EXAMPLE — horizontal bar chart for {Jan: 10, Feb: 20, Mar: 30}:\n"
            + "import matplotlib\n"
            + "matplotlib.use('Agg')\n"
            + "import matplotlib.pyplot as plt\n"
            + "data = {'Jan': 10, 'Feb': 20, 'Mar': 30}\n"
            + "plt.barh(list(data.keys()), list(data.values()))\n"
            + "plt.title('Monthly Sales — 2026')\n"
            + "plt.xlabel('Sales')\n"
            + "plt.grid(axis='x', alpha=0.3)\n"
            + "plt.tight_layout()\n"
            + "plt.savefig('/home/user/chart.png', dpi=100, bbox_inches='tight')\n"
            + "\n"
            + "Now generate code for the USER REQUEST above using this exact data:\n"
            + dataLines + "\n"
            + "\n"
            + "Output ONLY the Python code. Start with: import matplotlib.\n"
            + "Do NOT call plt.show().\n";
    }

    /**
     * Ensures the matplotlib 'Agg' non-interactive backend is activated before
     * pyplot is imported. Without this, the script crashes in E2B's headless
     * environment because the default TkAgg backend requires a display.
     */
    private String ensureAggBackend(String code) {
        if (code.contains("matplotlib.use(")) {
            return code;
        }
        return "import matplotlib\nmatplotlib.use('Agg')\n" + code;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Log formatting helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static final String BOX_H = "═".repeat(76);

    private static String box(String title) {
        return "\n╔" + BOX_H + "╗\n║  " + String.format("%-74s", title) + "║\n╚" + BOX_H + "╝";
    }

    private static String lbl(String label, Object value) {
        return "\n   " + String.format("%-11s", label) + " : " + value;
    }
}
