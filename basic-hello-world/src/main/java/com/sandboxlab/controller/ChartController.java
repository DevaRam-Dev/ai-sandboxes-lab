/**
 * ChartController.java
 *
 * Exposes two endpoints:
 *
 *   POST /ask         — synchronous, returns image/png (curl / backwards-compatible)
 *   POST /ask/stream  — SSE stream, emits step-start/step-end/complete/error events
 *
 * The controller's only responsibilities are:
 *   1. Accept and validate the incoming JSON request
 *   2. Delegate all business logic to PromptOrchestrator
 *   3. Return the result or an appropriate error
 *
 * Pipeline position: HTTP → ChartController → PromptOrchestrator
 */
package com.sandboxlab.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandboxlab.dto.AskRequest;
import com.sandboxlab.service.PromptOrchestrator;
import com.sandboxlab.service.SseStepEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;

@RestController
@CrossOrigin(origins = "*")
public class ChartController {

    private static final Logger log = LoggerFactory.getLogger(ChartController.class);

    private final PromptOrchestrator promptOrchestrator;
    private final ExecutorService    pipelineExecutor;
    private final ObjectMapper       objectMapper;

    public ChartController(
            PromptOrchestrator promptOrchestrator,
            ExecutorService    pipelineExecutor,
            ObjectMapper       objectMapper) {
        this.promptOrchestrator = promptOrchestrator;
        this.pipelineExecutor   = pipelineExecutor;
        this.objectMapper       = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  POST /ask  — synchronous image/png (curl-compatible, unchanged)
    // ─────────────────────────────────────────────────────────────────────────

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
    //  POST /ask/stream  — SSE, browser fetch + ReadableStream
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping(
        value    = "/ask/stream",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter askStream(@RequestBody AskRequest request) {
        SseEmitter emitter = new SseEmitter(90_000L);

        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            SseStepEmitter sse = new SseStepEmitter(emitter, objectMapper);
            sse.emitError(0, "", "prompt must not be blank");
            return emitter;
        }

        String prompt = request.prompt();
        log.info(heavyBox("REQUEST START (SSE)")
            + lbl("Input",       "\"" + prompt + "\"")
            + lbl("Description", "POST /ask/stream — dispatching pipeline to background thread"));

        // Copy MDC so transactionId from TransactionIdFilter propagates to the async thread
        Map<String, String> mdcCopy = MDC.getCopyOfContextMap();

        pipelineExecutor.submit(() -> {
            if (mdcCopy != null) MDC.setContextMap(mdcCopy);
            try {
                SseStepEmitter sse = new SseStepEmitter(emitter, objectMapper);
                promptOrchestrator.handleWithSse(prompt, sse);
            } catch (Exception e) {
                log.error("Unexpected error in SSE pipeline task: {}", e.getMessage());
            } finally {
                MDC.clear();
            }
        });

        return emitter;
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
