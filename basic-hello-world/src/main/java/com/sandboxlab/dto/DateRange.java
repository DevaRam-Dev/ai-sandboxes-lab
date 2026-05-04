/**
 * DateRange.java
 *
 * Internal DTO holding the structured result of parsing a natural-language date range.
 *
 * Examples:
 *   "Jan 2026 to March 2026" → MONTHLY,  periods=["Jan","Feb","Mar"], year=2026
 *   "Q1 to Q3 2026"          → QUARTERLY, periods=["Q1","Q2","Q3"],   year=2026
 *
 * This record is produced by DateRangeParser and consumed by:
 *   - SalesDataGenerator  (iterates periods to create random sales values)
 *   - PromptOrchestrator  (uses granularity + year for the chart title)
 *
 * Using a sealed design with an enum keeps the granularity options explicit
 * and exhaustively checkable with switch expressions later.
 */
package com.sandboxlab.dto;

import java.util.List;

public record DateRange(Granularity granularity, List<String> periods, int year) {

    /** Whether data points represent individual months or calendar quarters. */
    public enum Granularity {
        MONTHLY,
        QUARTERLY
    }
}
