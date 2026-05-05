package com.sandboxlab.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Assigns a unique transactionId to every incoming HTTP request via SLF4J MDC,
 * so all log lines emitted during that request carry the same ID automatically.
 *
 * Format: yyyyMMddHHmmssSSS (e.g. 20260505143022847)
 * MDC key: "transactionId" — referenced in the logback pattern as %X{transactionId:-NO-TXN}
 *
 * The MDC entry is cleared in the finally block to prevent leaking into
 * thread-pool-reused threads on subsequent unrelated requests.
 */
@Component
public class TransactionIdFilter extends OncePerRequestFilter {

    private static final String TXN_KEY = "transactionId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String transactionId = new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date());
        MDC.put(TXN_KEY, transactionId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TXN_KEY);
        }
    }
}
