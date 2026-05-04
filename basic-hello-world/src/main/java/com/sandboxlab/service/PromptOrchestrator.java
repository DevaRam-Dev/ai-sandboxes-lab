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
        log.info("=== Pipeline start: prompt='{}'", userPrompt);

        // ── Step 1: parse date range ──────────────────────────────────────────
        DateRange dateRange = dateRangeParser.parse(userPrompt);
        log.info("Step 1 ✓ DateRange: {} {} — periods={}", dateRange.granularity(), dateRange.year(), dateRange.periods());

        // ── Step 2: generate random sales data ───────────────────────────────
        Map<String, Integer> salesData = salesDataGenerator.generate(dateRange);
        log.info("Step 2 ✓ Sales data generated: {}", salesData);

        // ── Step 3: build the code-generation prompt ──────────────────────────
        String llmPrompt = buildLlmPrompt(dateRange, salesData);
        log.info("Step 3 ✓ LLM prompt built ({} chars)", llmPrompt.length());

        // ── Step 4: call Ollama ───────────────────────────────────────────────
        String rawLlmResponse = ollamaClient.generateCode(llmPrompt);
        log.info("Step 4 ✓ Ollama responded ({} chars)", rawLlmResponse.length());

        // ── Step 5: extract clean Python code from the LLM response ──────────
        String cleanCode = codeExtractor.extract(rawLlmResponse);
        log.info("Step 5 ✓ Clean code extracted ({} chars)", cleanCode.length());

        // Safety net: ensure the matplotlib non-interactive backend is set.
        // In a headless sandbox there is no display; without 'Agg', plt.show()
        // or the default TkAgg backend will crash the script.
        String finalCode = ensureAggBackend(cleanCode);

        log.info("Step 5b ✓ Agg backend ensured");
        log.debug("Final Python code:\n{}", finalCode);

        // ── Step 6: run in E2B, retrieve PNG ─────────────────────────────────
        byte[] png = e2bSandboxClient.executePythonAndGetChart(finalCode);
        log.info("Step 6 ✓ Chart PNG retrieved ({} bytes)", png.length);

        log.info("=== Pipeline complete ===");
        return png;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the prompt we send to the LLM. The prompt instructs the model to
     * generate pure executable Python — no explanations, no markdown. It also
     * inlines the sales data so the LLM doesn't have to guess the numbers.
     */
    private String buildLlmPrompt(DateRange dateRange, Map<String, Integer> salesData) {
        String granularityLabel = switch (dateRange.granularity()) {
            case MONTHLY   -> "Monthly";
            case QUARTERLY -> "Quarterly";
        };
        String chartTitle = granularityLabel + " Sales — " + dateRange.year();

        // Convert the sales map to indented "Period: Value" lines for clarity
        String dataLines = salesData.entrySet().stream()
            .map(e -> "  " + e.getKey() + ": " + e.getValue())
            .collect(Collectors.joining("\n"));

        return """
            You are a Python code generator. Output ONLY executable Python code.
            Do NOT include any explanation, markdown, code fences, or comments.
            The first line of your response must start with: import matplotlib

            Task: generate a bar chart using matplotlib and save it to /home/user/chart.png.

            Requirements:
            - Line 1: import matplotlib
            - Line 2: matplotlib.use('Agg')   ← REQUIRED for headless execution
            - Line 3: import matplotlib.pyplot as plt
            - Use plt.bar() to draw a bar chart
            - Set x-axis tick labels to the period names (see data below)
            - Rotate x-axis labels 45 degrees for readability if more than 4 periods
            - Set y-axis label: "Sales"
            - Set chart title: "%s"
            - Add a grid on the y-axis with alpha=0.3
            - Call plt.tight_layout() before saving
            - Save with: plt.savefig('/home/user/chart.png', dpi=100, bbox_inches='tight')
            - Do NOT call plt.show()

            Sales data (%s, year %d):
            %s

            Output ONLY the Python code. Start immediately with: import matplotlib
            """.formatted(chartTitle, granularityLabel.toLowerCase(), dateRange.year(), dataLines);
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
}
