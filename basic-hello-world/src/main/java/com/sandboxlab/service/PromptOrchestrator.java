/**
 * PromptOrchestrator.java
 *
 * Glues the entire pipeline together. This is the single method the controller
 * calls. It coordinates all five services in sequence:
 *
 *   DateRangeParser     → parse the user's natural-language prompt
 *   SalesDataGenerator  → generate random sales data for the parsed range
 *   OllamaClient        → call the local LLM with a code-generation prompt
 *   CodeExtractor       → strip markdown/preamble from the LLM response
 *   E2BSandboxClient    → run the Python code in E2B and return PNG bytes
 *
 * Why a separate orchestrator instead of logic in the controller?
 *   Controllers should only handle HTTP concerns (request/response mapping,
 *   error codes). The pipeline logic lives here so it can evolve independently
 *   and be tested or replaced without touching the HTTP layer.
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

    // Constructor injection — all dependencies are mandatory
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
     * Runs the full pipeline for a given user prompt and returns PNG bytes.
     *
     * @param userPrompt natural-language prompt, e.g. "Plot bar chart from Jan 2026 to March 2026"
     * @return raw PNG bytes of the generated chart
     * @throws IllegalArgumentException if the prompt has no recognisable date range
     * @throws RuntimeException if any pipeline step fails (Ollama, E2B, etc.)
     */
    public byte[] handle(String userPrompt) {
        long pipelineStart = System.currentTimeMillis();
        log.info(box("PIPELINE START") + lbl("Input", "\"" + userPrompt + "\""));

        // ── Step 1: DateRangeParser ───────────────────────────────────────────
        long t1 = System.currentTimeMillis();
        DateRange dateRange = dateRangeParser.parse(userPrompt);
        log.info(box("STEP 1 : DateRangeParser")
            + lbl("Input",       "\"" + userPrompt + "\"")
            + lbl("Description", "Parse natural-language date range into structured DateRange")
            + lbl("Output",      "granularity=" + dateRange.granularity()
                                 + ", year=" + dateRange.year()
                                 + ", periods=" + dateRange.periods())
            + lbl("Duration",    (System.currentTimeMillis() - t1) + "ms"));

        // ── Step 2: SalesDataGenerator ────────────────────────────────────────
        long t2 = System.currentTimeMillis();
        Map<String, Integer> salesData = salesDataGenerator.generate(dateRange);
        log.info(box("STEP 2 : SalesDataGenerator")
            + lbl("Input",       dateRange)
            + lbl("Description", "Generate random sales values (50-300) for each period")
            + lbl("Output",      salesData)
            + lbl("Duration",    (System.currentTimeMillis() - t2) + "ms"));

        // ── Step 3: Build LLM prompt ──────────────────────────────────────────
        long t3 = System.currentTimeMillis();
        String llmPrompt = buildLlmPrompt(userPrompt, dateRange, salesData);
        log.info(box("STEP 3 : Build LLM prompt")
            + lbl("Input",       "userPrompt=\"" + userPrompt + "\", salesData=" + salesData)
            + lbl("Description", "Compose code-generation prompt with inlined sales data")
            + lbl("Output",      "<length=" + llmPrompt.length() + " chars>")
            + "\n   Full LLM prompt:\n" + llmPrompt
            + lbl("Duration",    (System.currentTimeMillis() - t3) + "ms"));

        // ── Step 4: OllamaClient (header now; close-out after response) ────────
        log.info(box("STEP 4 : OllamaClient")
            + lbl("Input",       "<length=" + llmPrompt.length() + " chars>")
            + lbl("Description", "Call local Ollama LLM to generate Python chart code"));
        long t4 = System.currentTimeMillis();
        String rawLlmResponse = ollamaClient.generateCode(llmPrompt);
        log.info("\n   Output      : <length={} chars>\n   Duration    : {}ms",
            rawLlmResponse.length(), System.currentTimeMillis() - t4);

        // ── Step 5: CodeExtractor (header now; close-out after extract) ────────
        log.info(box("STEP 5 : CodeExtractor")
            + lbl("Input",       "<length=" + rawLlmResponse.length() + " chars>")
            + lbl("Description", "Strip markdown fences and preamble; extract clean Python"));
        long t5 = System.currentTimeMillis();
        String cleanCode = codeExtractor.extract(rawLlmResponse);
        log.info("\n   Output      : <length={} chars>\n   Duration    : {}ms",
            cleanCode.length(), System.currentTimeMillis() - t5);

        // Safety net: ensure the matplotlib non-interactive backend is set.
        // In a headless sandbox there is no display; without 'Agg', plt.show()
        // or the default TkAgg backend will crash the script.
        String finalCode = ensureAggBackend(cleanCode);
        log.debug("Final Python code:\n{}", finalCode);

        // ── Step 6: E2BSandboxClient (header now; close-out after PNG) ─────────
        log.info(box("STEP 6 : E2BSandboxClient")
            + lbl("Input",       "<length=" + finalCode.length() + " chars>")
            + lbl("Description", "Execute Python in E2B cloud sandbox; retrieve PNG chart"));
        long t6 = System.currentTimeMillis();
        byte[] png = e2bSandboxClient.executePythonAndGetChart(finalCode);
        log.info("\n   Output      : <length={} bytes PNG>\n   Duration    : {}ms",
            png.length, System.currentTimeMillis() - t6);

        log.info(box("PIPELINE END")
            + lbl("Duration", (System.currentTimeMillis() - pipelineStart) + "ms"));
        return png;
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
        // Actual sales data block — "  Jan: 182\n  Feb: 247\n  Mar: 91"
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
     * pyplot is imported. Without this, the script will crash in E2B's headless
     * Ubuntu environment because the default backend (TkAgg) requires a display.
     *
     * If the LLM already included the call, we leave the code as-is.
     * If not, we prepend the two necessary lines.
     */
    private String ensureAggBackend(String code) {
        if (code.contains("matplotlib.use(")) {
            return code; // LLM already handled it
        }
        // Prepend Agg setup before any existing matplotlib/pyplot imports
        return "import matplotlib\nmatplotlib.use('Agg')\n" + code;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Log formatting helpers — bundle step content into a single log call
    // ─────────────────────────────────────────────────────────────────────────

    private static final String BOX_H = "═".repeat(76);

    private static String box(String title) {
        return "\n╔" + BOX_H + "╗\n║  " + String.format("%-74s", title) + "║\n╚" + BOX_H + "╝";
    }

    private static String lbl(String label, Object value) {
        return "\n   " + String.format("%-11s", label) + " : " + value;
    }
}
