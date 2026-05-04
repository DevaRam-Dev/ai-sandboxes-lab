/**
 * SalesDataGenerator.java
 *
 * Generates random sales figures (50–300 inclusive) for each period in a DateRange.
 *
 * Uses java.util.Random with no fixed seed, so every request produces different numbers.
 * Returns a LinkedHashMap to preserve the insertion order of period labels —
 * important because the LLM prompt and chart x-axis both rely on this order.
 *
 * Example output for periods ["Jan", "Feb", "Mar"]:
 *   {"Jan": 152, "Feb": 289, "Mar": 74}
 *
 * Pipeline position: PromptOrchestrator calls this after DateRangeParser,
 *                    before building the LLM prompt.
 */
package com.sandboxlab.service;

import com.sandboxlab.dto.DateRange;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

@Component
public class SalesDataGenerator {

    // Single Random instance — no fixed seed so each call yields different values
    private final Random random = new Random();

    /**
     * Generates one random sales integer (50–300) for each period in the DateRange.
     *
     * @param dateRange the parsed range from DateRangeParser
     * @return ordered map of period label → sales value
     */
    public Map<String, Integer> generate(DateRange dateRange) {
        Map<String, Integer> sales = new LinkedHashMap<>();
        for (String period : dateRange.periods()) {
            // nextInt(251) → [0, 250]; +50 shifts the range to [50, 300]
            sales.put(period, random.nextInt(251) + 50);
        }
        return sales;
    }
}
