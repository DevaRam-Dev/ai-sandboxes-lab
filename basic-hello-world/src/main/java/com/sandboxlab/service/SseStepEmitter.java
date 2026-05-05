package com.sandboxlab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Base64;
import java.util.Map;

/**
 * Per-request SSE wrapper that emits structured pipeline events to the browser.
 *
 * Not a Spring bean — instantiated per request in ChartController.askStream().
 *
 * Event format (SSE wire):
 *   event: step-start
 *   data: {"step":4,"totalSteps":6,"name":"Calling LLM (Ollama)"}
 */
public class SseStepEmitter {

    private static final Logger log = LoggerFactory.getLogger(SseStepEmitter.class);

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;

    public SseStepEmitter(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    public void emitStepStart(int step, String name) {
        log.info("SSE → step-start step={} name='{}'", step, name);
        send("step-start", Map.of("step", step, "totalSteps", 6, "name", name));
    }

    public void emitStepEnd(int step, String name, long durationMs) {
        log.info("SSE → step-end   step={} name='{}' durationMs={}", step, name, durationMs);
        send("step-end", Map.of("step", step, "totalSteps", 6, "name", name, "durationMs", durationMs));
    }

    public void emitComplete(byte[] png, long totalDurationMs) {
        log.info("SSE → complete totalDurationMs={} pngBytes={}", totalDurationMs, png.length);
        String base64 = Base64.getEncoder().encodeToString(png);
        send("complete", Map.of("chartBase64", base64, "totalDurationMs", totalDurationMs));
        try { emitter.complete(); } catch (Exception ignored) {}
    }

    public void emitError(int step, String name, String message) {
        log.warn("SSE → error step={} name='{}' message='{}'", step, name, message);
        send("error", Map.of("step", step, "name", name, "message", message));
        try { emitter.complete(); } catch (Exception ignored) {}
    }

    private void send(String eventName, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (Exception e) {
            log.warn("Failed to send SSE event '{}': {}", eventName, e.getMessage());
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
        }
    }
}
