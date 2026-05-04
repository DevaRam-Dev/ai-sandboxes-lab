/**
 * AskRequest.java
 *
 * Request body DTO for POST /ask.
 *
 * The caller sends a natural-language prompt describing the chart they want:
 *   {"prompt": "Plot bar chart from Jan 2026 to March 2026"}
 *   {"prompt": "Show me Q1 to Q3 2026 sales"}
 *
 * Java records give us an immutable, Jackson-compatible DTO with zero boilerplate.
 * Jackson 2.12+ deserializes records automatically from their component names.
 *
 * Pipeline position: HTTP request body → ChartController → PromptOrchestrator
 */
package com.sandboxlab.dto;

public record AskRequest(String prompt) {
}
