/**
 * OllamaClient.java
 *
 * Thin HTTP wrapper around Ollama's /api/generate endpoint.
 *
 * Sends a code-generation prompt to the local Ollama server and returns
 * the raw LLM response text (which may still contain markdown fences or
 * preamble — stripping is handled downstream by CodeExtractor).
 *
 * Ollama generate API contract:
 *   POST {ollama.base-url}/api/generate
 *   Body: { "model": "<name>", "prompt": "...", "stream": false }
 *   Response: { "model": "...", "response": "...", "done": true, ... }
 *
 * Why stream=false?
 *   With stream=false, Ollama waits for the full response before returning a
 *   single JSON object. With stream=true, it sends multiple newline-delimited
 *   JSON chunks. We use false to keep the client simple and synchronous.
 *
 * Timeout: 60 seconds — Gemma models can be slow on CPU.
 *
 * Configuration (application.properties):
 *   ollama.base-url  — default http://localhost:11434
 *   ollama.model     — default gemma4:e2b
 *
 * Pipeline position: PromptOrchestrator → OllamaClient → CodeExtractor
 */
package com.sandboxlab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper;

    // ---------- Internal DTOs for Ollama JSON (Jackson maps fields by name) ----------

    /** Request body sent to Ollama. stream=false = wait for complete response. */
    private record GenerateRequest(String model, String prompt, boolean stream) {}

    /** Subset of Ollama's response JSON — we only need the generated text. */
    private record GenerateResponse(String response, boolean done) {}

    // ---------- Construction ----------

    public OllamaClient(
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.model}") String model,
            ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;

        // SimpleClientHttpRequestFactory wraps Java's HttpURLConnection.
        // It is synchronous, lightweight, and supports read/connect timeouts.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60)); // Gemma can be slow

        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .build();
        log.info("OllamaClient initialized — build marker: EXCHANGE_RAW_BYTES_v2 @ {}", java.time.Instant.now());
    }

    // ---------- Public API ----------

    /**
     * Sends a prompt to Ollama and returns the raw generated text.
     * The caller is responsible for cleaning up the response (see CodeExtractor).
     *
     * @param prompt the fully-constructed code-generation prompt
     * @return raw LLM output (may contain ```python fences, thinking text, etc.)
     * @throws RuntimeException if Ollama is unreachable or returns an empty response
     */
    public String generateCode(String prompt) {
        log.info(box("OUT → Ollama")
            + lbl("Model",  model)
            + lbl("Prompt", "<length=" + prompt.length() + " chars>")
            + "\n   Full prompt:\n" + prompt);

        long callStart = System.currentTimeMillis();

        // Use .exchange() to bypass Spring's content-type-based converter selection.
        // We read the raw response stream into bytes ourselves, then decode UTF-8.
        String rawJson = restClient.post()
            .uri("/api/generate")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(new GenerateRequest(model, prompt, false))
            .exchange((request, response) -> {
                try (java.io.InputStream is = response.getBody()) {
                    byte[] bytes = is.readAllBytes();
                    String body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    log.info(box("IN ← Ollama")
                        + lbl("HTTP status", response.getStatusCode())
                        + lbl("Body length", bytes.length + " bytes")
                        + lbl("Duration",    (System.currentTimeMillis() - callStart) + "ms")
                        + "\n   Full response body:\n" + body);
                    return body;
                }
            });

        GenerateResponse response;
        try {
            response = objectMapper.readValue(rawJson, GenerateResponse.class);
        } catch (Exception e) {
            String preview = rawJson == null ? "null" : rawJson.substring(0, Math.min(rawJson.length(), 500));
            throw new RuntimeException("Failed to parse Ollama JSON response. Body preview: " + preview, e);
        }

        if (response == null || response.response() == null) {
            throw new RuntimeException("Ollama returned an empty response — is the model loaded?");
        }

        return response.response();
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
