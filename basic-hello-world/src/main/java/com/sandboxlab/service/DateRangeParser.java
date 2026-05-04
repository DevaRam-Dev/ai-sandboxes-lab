/**
 * DateRangeParser.java
 *
 * Extracts a structured DateRange from a free-text user prompt using regex.
 * No NLP libraries — just two compiled patterns.
 *
 * Supported input formats:
 *
 *   Monthly (year after start month, or at end):
 *     "Jan 2026 to March 2026"
 *     "January 2026 to March 2026"
 *     "Jan to March 2026"
 *
 *   Quarterly:
 *     "Q1 to Q3 2026"
 *     "Q1 2026 to Q3 2026"
 *
 * Throws IllegalArgumentException if neither pattern matches.
 *
 * Pipeline position: PromptOrchestrator → DateRangeParser
 */
package com.sandboxlab.service;

import com.sandboxlab.dto.DateRange;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DateRangeParser {

    // Maps any month name variant (lower-cased) → 1-based month number
    private static final Map<String, Integer> MONTH_TO_NUM = Map.ofEntries(
        Map.entry("january", 1),   Map.entry("jan", 1),
        Map.entry("february", 2),  Map.entry("feb", 2),
        Map.entry("march", 3),     Map.entry("mar", 3),
        Map.entry("april", 4),     Map.entry("apr", 4),
        Map.entry("may", 5),
        Map.entry("june", 6),      Map.entry("jun", 6),
        Map.entry("july", 7),      Map.entry("jul", 7),
        Map.entry("august", 8),    Map.entry("aug", 8),
        Map.entry("september", 9), Map.entry("sep", 9),
        Map.entry("october", 10),  Map.entry("oct", 10),
        Map.entry("november", 11), Map.entry("nov", 11),
        Map.entry("december", 12), Map.entry("dec", 12)
    );

    // Standard 3-letter abbreviations used as period labels in chart data
    private static final String[] MONTH_LABELS = {
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    // Matches any full or abbreviated English month name (case-insensitive)
    private static final String MONTH_PAT =
        "(?:january|february|march|april|may|june|july|august|" +
        "september|october|november|december|jan|feb|mar|apr|" +
        "jun|jul|aug|sep|oct|nov|dec)";

    /**
     * "Jan 2026 to March 2026" — year after start month, optional year after end month.
     * Groups: (1) startMonth, (2) year, (3) endMonth
     */
    private static final Pattern MONTHLY_YEAR_FIRST = Pattern.compile(
        "(" + MONTH_PAT + ")\\s+(\\d{4})\\s+to\\s+(" + MONTH_PAT + ")(?:\\s+\\d{4})?",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * "Jan to March 2026" — year only at the end.
     * Groups: (1) startMonth, (2) endMonth, (3) year
     */
    private static final Pattern MONTHLY_YEAR_LAST = Pattern.compile(
        "(" + MONTH_PAT + ")\\s+to\\s+(" + MONTH_PAT + ")\\s+(\\d{4})",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * "Q1 to Q3 2026" or "Q1 2026 to Q3 2026".
     * The (?:\s+\d{4})? after Q1 eats the year if it appears before "to".
     * Groups: (1) startQ, (2) endQ, (3) year
     */
    private static final Pattern QUARTERLY = Pattern.compile(
        "(?i)Q(\\d)(?:\\s+\\d{4})?\\s+to\\s+Q(\\d)\\s+(\\d{4})"
    );

    /**
     * Parses the user's prompt and returns a DateRange.
     *
     * @param prompt raw user prompt (e.g. "Plot bar chart from Jan 2026 to March 2026")
     * @return DateRange with granularity, ordered period labels, and the year
     * @throws IllegalArgumentException if the prompt contains no recognisable date range
     */
    public DateRange parse(String prompt) {
        // Try quarterly first — the "Q" prefix makes it unambiguous
        Matcher qm = QUARTERLY.matcher(prompt);
        if (qm.find()) {
            int startQ = Integer.parseInt(qm.group(1));
            int endQ   = Integer.parseInt(qm.group(2));
            int year   = Integer.parseInt(qm.group(3));
            return new DateRange(DateRange.Granularity.QUARTERLY, buildQuarters(startQ, endQ), year);
        }

        // Try monthly with year written right after the start month
        Matcher m1 = MONTHLY_YEAR_FIRST.matcher(prompt);
        if (m1.find()) {
            int start = resolveMonth(m1.group(1));
            int year  = Integer.parseInt(m1.group(2));
            int end   = resolveMonth(m1.group(3));
            return new DateRange(DateRange.Granularity.MONTHLY, buildMonths(start, end), year);
        }

        // Try monthly with year only at the tail
        Matcher m2 = MONTHLY_YEAR_LAST.matcher(prompt);
        if (m2.find()) {
            int start = resolveMonth(m2.group(1));
            int end   = resolveMonth(m2.group(2));
            int year  = Integer.parseInt(m2.group(3));
            return new DateRange(DateRange.Granularity.MONTHLY, buildMonths(start, end), year);
        }

        throw new IllegalArgumentException(
            "No recognisable date range found in prompt. " +
            "Expected 'Jan 2026 to March 2026' or 'Q1 to Q3 2026'. Got: " + prompt
        );
    }

    /** Returns the 1-based month number for names like "jan", "January", "MARCH". */
    private int resolveMonth(String name) {
        Integer num = MONTH_TO_NUM.get(name.toLowerCase());
        if (num == null) {
            throw new IllegalArgumentException("Unrecognised month name: " + name);
        }
        return num;
    }

    /**
     * Builds an ordered list of 3-letter month abbreviations from startMonth to endMonth (inclusive).
     * Both values are 1-based (Jan=1, Dec=12).
     */
    private List<String> buildMonths(int startMonth, int endMonth) {
        if (startMonth > endMonth) {
            throw new IllegalArgumentException(
                "Start month (" + startMonth + ") must not exceed end month (" + endMonth + ")"
            );
        }
        List<String> periods = new ArrayList<>();
        for (int m = startMonth; m <= endMonth; m++) {
            periods.add(MONTH_LABELS[m - 1]);
        }
        return periods;
    }

    /** Builds an ordered list like ["Q1","Q2","Q3"] from startQ to endQ (inclusive). */
    private List<String> buildQuarters(int startQ, int endQ) {
        if (startQ > endQ) {
            throw new IllegalArgumentException(
                "Start quarter (" + startQ + ") must not exceed end quarter (" + endQ + ")"
            );
        }
        List<String> periods = new ArrayList<>();
        for (int q = startQ; q <= endQ; q++) {
            periods.add("Q" + q);
        }
        return periods;
    }
}
