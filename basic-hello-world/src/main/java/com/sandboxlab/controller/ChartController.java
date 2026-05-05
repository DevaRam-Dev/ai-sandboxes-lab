/**
 * ChartController.java
 *
 * REST controller exposing a single endpoint: POST /ask
 *
 * The controller's only responsibilities are:
 *   1. Accept and validate the incoming JSON request
 *   2. Delegate all business logic to PromptOrchestrator
 *   3. Return the result (PNG bytes) or an appropriate HTTP error code
 *
 * No business logic lives here — that's intentional. The controller is the
 * HTTP boundary layer; the pipeline logic belongs in PromptOrchestrator.
 *
 * Endpoint:
 *   POST /ask
 *   Content-Type: application/json
 *   Body: {"prompt": "Plot bar chart from Jan 2026 to March 2026"}
 *
 *   Success response: 200 OK, Content-Type: image/png, body: PNG bytes
 *   Error responses:
 *     400 Bad Request  — prompt is blank or date range can't be parsed
 *     502 Bad Gateway  — E2B sandbox or Ollama call failed
 *     500 Internal     — unexpected error
 *
 * @CrossOrigin(origins = "*") is set so a future browser-based frontend can
 * call this endpoint without CORS errors.
 *
 * Pipeline position: HTTP → ChartController → PromptOrchestrator
 */
package com.sandboxlab.controller;

import com.sandboxlab.dto.AskRequest;
import com.sandboxlab.service.PromptOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*") // allow future browser-based frontend to call this directly
public class ChartController {

    private static final Logger log = LoggerFactory.getLogger(ChartController.class);

    private final PromptOrchestrator promptOrchestrator;

    public ChartController(PromptOrchestrator promptOrchestrator) {
        this.promptOrchestrator = promptOrchestrator;
    }

    /**
     * Accepts a natural-language prompt and returns a PNG chart.
     *
     * Example:
     *   curl -X POST http://localhost:8080/ask \
     *        -H "Content-Type: application/json" \
     *        -d '{"prompt": "Plot bar chart from Jan 2026 to March 2026"}' \
     *        --output chart.png
     *
     * @param request JSON body containing the user's prompt
     * @return 200 with PNG bytes, or 4xx/5xx with an error message
     */
    @PostMapping(
        value    = "/ask",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.IMAGE_PNG_VALUE
    )
    public ResponseEntity<byte[]> ask(@RequestBody AskRequest request) {
        long start = System.currentTimeMillis();

        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            log.warn("Rejected request — prompt is blank");
            return ResponseEntity
                .badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body("prompt must not be blank".getBytes());
        }

        String prompt = request.prompt();
        log.info(heavyBox("REQUEST START")
            + lbl("Input",       "\"" + prompt + "\"")
            + lbl("Input size",  prompt.length() + " chars")
            + lbl("Description", "POST /ask received — delegating to PromptOrchestrator pipeline"));

        try {
            byte[] png = promptOrchestrator.handle(prompt);
            long duration = System.currentTimeMillis() - start;
            log.info(heavyBox("REQUEST END")
                + lbl("Output",   "HTTP 200, " + png.length + " bytes PNG")
                + lbl("Duration", duration + "ms"));
            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(png);

        } catch (IllegalArgumentException e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("Bad request — could not parse date range: {}", e.getMessage());
            log.info(heavyBox("REQUEST END  [400]")
                + lbl("Output",   "HTTP 400 — " + e.getMessage())
                + lbl("Duration", duration + "ms"));
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_PLAIN)
                .body(e.getMessage().getBytes());

        } catch (RuntimeException e) {
            long duration = System.currentTimeMillis() - start;
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";

            // Distinguish upstream failures (Ollama, E2B) from internal bugs
            if (msg.contains("E2B") || msg.contains("Ollama") || msg.contains("Connect RPC")) {
                log.error("Upstream service error: {}", msg);
                log.info(heavyBox("REQUEST END  [502]")
                    + lbl("Output",   "HTTP 502 — upstream error: " + msg)
                    + lbl("Duration", duration + "ms"));
                return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Upstream error: " + msg).getBytes());
            }

            log.error("Unexpected error processing /ask", e);
            log.info(heavyBox("REQUEST END  [500]")
                + lbl("Output",   "HTTP 500 — internal error: " + msg)
                + lbl("Duration", duration + "ms"));
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body(("Internal error: " + msg).getBytes());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Log formatting helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static final String HEAVY_BAR = "█".repeat(78);

    private static String heavyBox(String title) {
        return "\n" + HEAVY_BAR + "\n█  " + String.format("%-74s", title) + "█\n" + HEAVY_BAR;
    }

    private static String lbl(String label, Object value) {
        return "\n   " + String.format("%-11s", label) + " : " + value;
    }
}
