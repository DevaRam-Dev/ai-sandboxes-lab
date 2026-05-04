/**
 * PASSIVE / DEPRECATED — Original raw HTTP implementation of E2B integration.
 *
 * Kept for reference and educational comparison only. NOT a Spring bean.
 *
 * Why deprecated: E2B's envd daemon listens on a port that varies by sandbox
 * version, behind subdomain routing (https://{port}-{sandboxId}.e2b.app),
 * and uses a Connect-RPC protocol that is undocumented and version-fragile.
 * Direct HTTP integration was not viable.
 *
 * Replaced by E2BSandboxClient.java which uses ProcessBuilder to invoke
 * e2b_runner.py (Python wrapper around E2B's official Python SDK).
 *
 * To re-enable this implementation: add @Component back, remove @Component
 * from the new E2BSandboxClient, then debug the port/protocol issues.
 */
package com.sandboxlab.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class E2BSandboxClientHttpRaw {

    private static final Logger log = LoggerFactory.getLogger(E2BSandboxClientHttpRaw.class);

    // Paths used inside the sandbox (must match what the LLM generates in the Python code)
    private static final String SCRIPT_PATH = "/home/user/script.py";
    private static final String CHART_PATH  = "/home/user/chart.png";

    private final String apiKey;
    private final String baseUrl;       // E2B control-plane URL
    private final int    envdPort;      // port for sandbox envd daemon
    private final String processPath;  // Connect RPC path for process execution
    private final String templateId;

    private final RestClient  restClient;
    private final ObjectMapper objectMapper;

    // ---------- Construction ----------

    public E2BSandboxClientHttpRaw(
            @Value("${e2b.api.key}")                              String apiKey,
            @Value("${e2b.base-url}")                             String baseUrl,
            @Value("${e2b.envd.port}")                            int envdPort,
            @Value("${e2b.envd.process.path}")                    String processPath,
            @Value("${e2b.template-id:code-interpreter-v1}")      String templateId) {
        this.apiKey      = apiKey;
        this.baseUrl     = baseUrl;
        this.envdPort    = envdPort;
        this.processPath = processPath;
        this.templateId  = templateId;
        this.objectMapper = new ObjectMapper();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(120)); // sandbox spin-up + matplotlib install + run

        this.restClient = RestClient.builder()
            .requestFactory(factory)
            .build();
        log.info("E2BSandboxClientHttpRaw initialized — build marker: TEMPLATE_ID_FIX_v1, templateId={} @ {}",
            templateId, java.time.Instant.now());
    }

    // ═══════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Full pipeline:
     *   1. Create a fresh E2B sandbox
     *   2. Upload the Python script
     *   3. Run:  pip install matplotlib && python3 /home/user/script.py
     *   4. Download the generated chart.png
     *   5. Kill the sandbox (always, even on error)
     *
     * @param pythonCode clean, executable Python that saves a chart to /home/user/chart.png
     * @return PNG bytes of the generated chart
     * @throws RuntimeException on any step failure (E2B API error, non-zero exit code, etc.)
     */
    public byte[] executePythonAndGetChart(String pythonCode) {
        String sandboxId = null;
        try {
            // Step 1: create sandbox
            Map<String, Object> sandboxInfo = createSandbox();
            sandboxId = (String) sandboxInfo.get("sandboxID");
            String domain = (String) sandboxInfo.getOrDefault("domain", "e2b.app");

            if (sandboxId == null) {
                throw new RuntimeException("E2B did not return a sandboxID in the create response");
            }

            // Construct the envd base URL: https://{port}-{sandboxId}.{domain}
            String envdBase = "https://" + envdPort + "-" + sandboxId + "." + domain;
            log.info("Created sandbox id={}, envd={}", sandboxId, envdBase);

            // Step 2: upload the Python script
            uploadFile(envdBase, SCRIPT_PATH, pythonCode.getBytes(StandardCharsets.UTF_8));
            log.info("Uploaded script to sandbox at {}", SCRIPT_PATH);

            // Step 3: install matplotlib (if not present) and run the script.
            // A single shell command keeps this to one Connect RPC call.
            String shellCmd = "pip install -q matplotlib 2>&1 && python3 " + SCRIPT_PATH;
            int exitCode = runConnectRpcCommand(envdBase, shellCmd);

            if (exitCode != 0) {
                throw new RuntimeException(
                    "Python script execution failed with exit code " + exitCode +
                    ". Check that the generated code saves to " + CHART_PATH
                );
            }
            log.info("Python script completed successfully (exit code 0)");

            // Step 4: download the chart
            byte[] png = downloadFile(envdBase, CHART_PATH);
            log.info("Downloaded chart.png from sandbox ({} bytes)", png.length);
            return png;

        } finally {
            // Step 5: always clean up — leave no dangling sandboxes (costs money)
            if (sandboxId != null) {
                killSandbox(sandboxId);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONTROL PLANE  (REST API at baseUrl)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates a new E2B sandbox using the "base" template.
     * Returns the raw response JSON as a Map so we can extract sandboxID and domain.
     *
     * POST {baseUrl}/sandboxes
     * Headers: X-API-Key, Content-Type: application/json
     * Body:    {"templateID": "base", "timeout": 300}
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> createSandbox() {
        log.info("Creating E2B sandbox via {}/sandboxes", baseUrl);

        Map<String, Object> body = Map.of(
            "templateID", templateId,
            "timeout", 300         // seconds before E2B auto-kills the sandbox
        );

        Map<String, Object> response = restClient.post()
            .uri(baseUrl + "/sandboxes")
            .header("X-API-Key", apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(Map.class);

        if (response == null) {
            throw new RuntimeException("E2B sandbox create returned null — check your API key and base URL");
        }
        return response;
    }

    /**
     * Terminates a running sandbox.
     *
     * DELETE {baseUrl}/sandboxes/{sandboxId}
     * Headers: X-API-Key
     *
     * Errors here are logged but not re-thrown so they don't mask the real result.
     */
    private void killSandbox(String sandboxId) {
        try {
            restClient.delete()
                .uri(baseUrl + "/sandboxes/" + sandboxId)
                .header("X-API-Key", apiKey)
                .retrieve()
                .toBodilessEntity();
            log.info("Killed sandbox {}", sandboxId);
        } catch (Exception e) {
            log.warn("Failed to kill sandbox {} (it will auto-expire): {}", sandboxId, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  DATA PLANE  (envd HTTP file API)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Uploads a file to the sandbox via the envd HTTP files endpoint.
     *
     * POST {envdBase}/files?path={remotePath}
     * Content-Type: application/octet-stream
     * Body: raw bytes
     */
    private void uploadFile(String envdBase, String remotePath, byte[] content) {
        restClient.post()
            .uri(envdBase + "/files?path=" + remotePath)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(content)
            .retrieve()
            .toBodilessEntity();
    }

    /**
     * Downloads a file from the sandbox via the envd HTTP files endpoint.
     *
     * GET {envdBase}/files?path={remotePath}
     * Response: raw bytes
     */
    private byte[] downloadFile(String envdBase, String remotePath) {
        byte[] data = restClient.get()
            .uri(envdBase + "/files?path=" + remotePath)
            .retrieve()
            .body(byte[].class);

        if (data == null || data.length == 0) {
            throw new RuntimeException("Downloaded empty or null file from E2B path: " + remotePath);
        }
        return data;
    }

    // ═══════════════════════════════════════════════════════════════
    //  DATA PLANE  (Connect RPC — command execution)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Runs a shell command inside the sandbox using the Connect RPC protocol.
     *
     * The envd daemon implements gRPC services. Connect RPC is a modern protocol
     * that is gRPC-compatible but also supports HTTP/1.1 with JSON bodies, which
     * lets us use Spring's RestClient instead of a gRPC client library.
     *
     * Connect streaming response body = a series of length-prefixed frames.
     * This method reads all frames, parses ProcessEvent messages, and returns
     * the final exit code from the "end" event.
     *
     * @param envdBase the envd base URL (https://{port}-{sandboxId}.e2b.app)
     * @param shellCmd the shell command to run (passed to /bin/sh -c)
     * @return exit code (0 = success)
     */
    private int runConnectRpcCommand(String envdBase, String shellCmd) {
        String requestJson = buildStartRequestJson(shellCmd);
        String connectUrl  = envdBase + processPath;

        log.info("Connect RPC command: POST {}", connectUrl);
        log.info("Shell command: {}", shellCmd);

        return restClient.post()
            .uri(connectUrl)
            // Connect RPC JSON protocol — tells the server to respond with JSON frames
            .contentType(MediaType.parseMediaType("application/connect+json"))
            .body(requestJson)
            .exchange((request, response) -> {
                int statusCode = response.getStatusCode().value();
                if (statusCode != 200) {
                    throw new RuntimeException(
                        "E2B Connect RPC returned HTTP " + statusCode +
                        " — verify e2b.envd.process.path in application.properties"
                    );
                }
                byte[] frames = response.getBody().readAllBytes();
                return parseConnectFrames(frames);
            });
    }

    /**
     * Builds the StartRequest JSON for the Connect RPC Process.Start method.
     *
     * The proto3 message structure (ProcessConfig):
     *   cmd  = "/bin/sh"       — the executable
     *   args = ["-c", "..."]   — arguments (shell flag + the actual command string)
     *
     * Jackson serialises the Map to proper JSON with correct string escaping.
     */
    private String buildStartRequestJson(String shellCmd) {
        try {
            Map<String, Object> processConfig = Map.of(
                "cmd",  "/bin/sh",
                "args", List.of("-c", shellCmd)
            );
            Map<String, Object> startRequest = Map.of("process", processConfig);
            return objectMapper.writeValueAsString(startRequest);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise Connect RPC StartRequest", e);
        }
    }

    /**
     * Parses a byte array containing one or more Connect RPC response frames.
     *
     * Frame format:
     *   [flags: 1 byte][length: 4 bytes big-endian][message: length bytes]
     *
     *   flags 0x00 → regular ProcessEvent (JSON)
     *   flags 0x02 → end-of-stream trailers (JSON, signals RPC completion)
     *
     * ProcessEvent JSON (proto3 JSON encoding, oneof field at top level):
     *   {"start": {"pid": 12345}}
     *   {"data":  {"stdout": "..."}}   ← stdout data from the process
     *   {"data":  {"stderr": "..."}}   ← stderr data from the process
     *   {"end":   {"exitCode": 0}}     ← process finished
     *
     * @param frames raw bytes from the Connect RPC streaming response body
     * @return the process exit code extracted from the "end" event
     */
    @SuppressWarnings("unchecked")
    private int parseConnectFrames(byte[] frames) throws IOException {
        ByteArrayInputStream stream = new ByteArrayInputStream(frames);
        StringBuilder stdout  = new StringBuilder();
        StringBuilder stderr  = new StringBuilder();
        int exitCode = 0;

        while (true) {
            byte[] header = stream.readNBytes(5);
            if (header.length < 5) break; // stream exhausted

            int flags  = header[0] & 0xFF;
            int msgLen = ((header[1] & 0xFF) << 24)
                       | ((header[2] & 0xFF) << 16)
                       | ((header[3] & 0xFF) << 8)
                       |  (header[4] & 0xFF);

            if (msgLen < 0 || msgLen > 10_000_000) {
                log.warn("Suspicious Connect frame length {}; stopping frame parse", msgLen);
                break;
            }

            byte[] msgBytes = stream.readNBytes(msgLen);
            String json = new String(msgBytes, StandardCharsets.UTF_8);

            if (flags == 0x02) {
                // End-of-stream trailers — check for Connect protocol-level errors
                // e.g. {"code": "not_found", "message": "process path not found"}
                if (json.contains("\"code\"")) {
                    Map<String, Object> trailer = objectMapper.readValue(
                        json, new TypeReference<Map<String, Object>>() {}
                    );
                    Object code = trailer.get("code");
                    if (code != null && !"ok".equals(code.toString())) {
                        throw new RuntimeException(
                            "Connect RPC error in trailer — " + json +
                            "\nHint: verify e2b.envd.process.path in application.properties"
                        );
                    }
                }
                break; // normal end of stream
            }

            // flags 0x00: regular ProcessEvent message
            Map<String, Object> event = objectMapper.readValue(
                json, new TypeReference<Map<String, Object>>() {}
            );

            if (event.containsKey("data")) {
                Map<String, Object> data = (Map<String, Object>) event.get("data");
                if (data.containsKey("stdout")) stdout.append(data.get("stdout").toString());
                if (data.containsKey("stderr")) stderr.append(data.get("stderr").toString());
            }

            if (event.containsKey("end")) {
                Map<String, Object> end = (Map<String, Object>) event.get("end");
                if (end.containsKey("exitCode")) {
                    exitCode = ((Number) end.get("exitCode")).intValue();
                }
            }
        }

        // Log collected output for debugging (visible at INFO level)
        if (!stdout.isEmpty()) {
            log.info("Command stdout:\n{}", stdout.toString().trim());
        }
        if (!stderr.isEmpty()) {
            log.info("Command stderr:\n{}", stderr.toString().trim());
        }

        return exitCode;
    }
}
