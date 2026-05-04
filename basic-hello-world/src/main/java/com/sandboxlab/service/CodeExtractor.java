/**
 * CodeExtractor.java
 *
 * Strips markdown fences, preamble text, and trailing commentary from a raw
 * LLM response, returning only the executable Python source code.
 *
 * Extraction strategies (tried in order):
 *
 *   1. Look for an explicit  ```python … ```  fence — take the content inside.
 *   2. Look for any plain    ``` … ```        fence — take the content inside.
 *   3. Scan line-by-line for the first line starting with "import" or "from",
 *      then take everything from that line onward (stopping at a bare ``` line).
 *   4. Fallback: return the whole response trimmed and hope for the best.
 *
 * Why three strategies?
 *   LLMs vary: some always wrap code in ```python, some use plain fences,
 *   some (especially instruction-following models like gemma4) output raw Python
 *   with a short preamble like "Here's the code:" before the import lines.
 *
 * Pipeline position: OllamaClient → CodeExtractor → E2BSandboxClient
 */
package com.sandboxlab.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CodeExtractor {

    private static final Logger log = LoggerFactory.getLogger(CodeExtractor.class);

    // Matches ```python\n ... ``` (DOTALL so "." matches newlines)
    private static final Pattern PYTHON_FENCE = Pattern.compile(
        "```python\\s*\\n(.*?)```", Pattern.DOTALL
    );

    // Matches any plain ``` ... ``` block (LLM sometimes omits the language tag)
    private static final Pattern PLAIN_FENCE = Pattern.compile(
        "```\\s*\\n(.*?)```", Pattern.DOTALL
    );

    /**
     * Extracts clean, executable Python code from a raw LLM response string.
     *
     * @param rawResponse the unprocessed text returned by OllamaClient.generateCode()
     * @return executable Python source code, trimmed of whitespace
     */
    public String extract(String rawResponse) {

        // Strategy 1 — explicit ```python fence (most reliable signal)
        Matcher pyFence = PYTHON_FENCE.matcher(rawResponse);
        if (pyFence.find()) {
            String code = pyFence.group(1).trim();
            log.info("Extracted via ```python fence ({} chars)", code.length());
            return code;
        }

        // Strategy 2 — plain ``` fence (no language tag)
        Matcher plainFence = PLAIN_FENCE.matcher(rawResponse);
        if (plainFence.find()) {
            String code = plainFence.group(1).trim();
            log.info("Extracted via plain ``` fence ({} chars)", code.length());
            return code;
        }

        // Strategy 3 — find first "import" / "from" line, collect from there
        String[] lines = rawResponse.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("import ") || trimmed.startsWith("from ")) {
                StringBuilder code = new StringBuilder();
                for (int j = i; j < lines.length; j++) {
                    // Stop at a trailing bare fence — LLMs sometimes append ``` after code
                    if (lines[j].trim().equals("```")) break;
                    code.append(lines[j]).append("\n");
                }
                String result = code.toString().trim();
                log.info("Extracted via first-import heuristic ({} chars)", result.length());
                return result;
            }
        }

        // Strategy 4 — fallback: return everything trimmed
        log.warn("No code markers found; returning full trimmed response ({} chars)", rawResponse.trim().length());
        return rawResponse.trim();
    }
}
