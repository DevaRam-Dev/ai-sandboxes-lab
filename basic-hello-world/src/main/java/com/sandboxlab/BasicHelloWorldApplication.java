/**
 * BasicHelloWorldApplication.java
 *
 * Entry point for the AI Sandboxes POC (POC #2 in the ai-sandboxes-lab portfolio).
 *
 * What this application does (end-to-end):
 *   1. User sends a natural-language prompt via POST /ask
 *      e.g. {"prompt": "Plot bar chart from Jan 2026 to March 2026"}
 *
 *   2. DateRangeParser extracts the date range (monthly or quarterly)
 *   3. SalesDataGenerator creates random sales figures (50–300) per period
 *   4. PromptOrchestrator builds a code-generation prompt with the data
 *   5. OllamaClient sends the prompt to the local gemma4:e2b model
 *   6. CodeExtractor strips markdown/preamble from the LLM response
 *   7. E2BSandboxClient uploads the Python code to an E2B cloud sandbox,
 *      executes it, and retrieves the generated chart.png
 *   8. The PNG bytes are returned to the caller as image/png
 *
 * Quick start (after env setup):
 *   mvn clean package -DskipTests
 *   export E2B_API_KEY=e2b_xxx
 *   java -jar target/basic-hello-world-0.0.1-SNAPSHOT.jar
 *
 *   curl -X POST http://localhost:8080/ask \
 *        -H "Content-Type: application/json" \
 *        -d '{"prompt": "Plot bar chart from Jan 2026 to March 2026"}' \
 *        --output chart.png
 */
package com.sandboxlab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BasicHelloWorldApplication {

    public static void main(String[] args) {
        SpringApplication.run(BasicHelloWorldApplication.class, args);
    }
}
