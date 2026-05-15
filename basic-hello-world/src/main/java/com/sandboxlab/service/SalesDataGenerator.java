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
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

@Component
public class SalesDataGenerator {

    private static final Logger log = LoggerFactory.getLogger(SalesDataGenerator.class);

    // Single Random instance — no fixed seed so each call yields different values
    private final Random random = new Random();

    @PostConstruct
    public void init() {
        log.info("[SERVICE → SalesDataGenerator] initialized and ready | ANALOGY: Data fabricator loaded with random number tables");
    }

    /**
     * Generates one random sales integer (50–300) for each period in the DateRange.
     *
     * @param dateRange the parsed range from DateRangeParser
     * @return ordered map of period label → sales value
     */
    public Map<String, Integer> generate(DateRange dateRange) {
        log.info("[SERVICE → SalesDataGenerator] INPUT: generate | granularity={}, periods={}, year={} | action=GENERATE_SALES_DATA | ANALOGY: Fabricator picks random sales figures for each period",
                dateRange.granularity(), dateRange.periods().size(), dateRange.year());
        Map<String, Integer> sales = new LinkedHashMap<>();
        for (String period : dateRange.periods()) {
            // nextInt(251) → [0, 250]; +50 shifts the range to [50, 300]
            sales.put(period, random.nextInt(251) + 50);
        }
        log.info("[SERVICE → SalesDataGenerator] OUTPUT: generate | periods={}, salesData={} | ANALOGY: Fabricator hands over the sales table", sales.size(), sales);
        return sales;
    }
}
