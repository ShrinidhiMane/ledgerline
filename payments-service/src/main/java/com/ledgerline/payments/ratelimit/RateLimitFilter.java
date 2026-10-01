package com.ledgerline.payments.ratelimit;

import com.ledgerline.payments.config.LedgerlineProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies the per-client rate limit to payment creation only (reads are not limited). */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    static final String CLIENT_HEADER = "X-Client-Id";

    private final RedisRateLimiter limiter;
    private final boolean enabled;
    private final MeterRegistry metrics;

    public RateLimitFilter(RedisRateLimiter limiter, LedgerlineProperties props, MeterRegistry metrics) {
        this.limiter = limiter;
        this.enabled = props.rateLimit().enabled();
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled
                || !"POST".equals(request.getMethod())
                || !"/api/v1/payments".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = request.getHeader(CLIENT_HEADER);
        if (client == null || client.isBlank()) {
            client = "anonymous";
        }
        RedisRateLimiter.Decision decision = limiter.tryAcquire(client);
        if (decision.remaining() >= 0) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        }
        if (!decision.allowed()) {
            metrics.counter("ledgerline.ratelimit.rejected").increment();
            long retrySeconds = Math.max(1, (decision.retryAfterMs() + 999) / 1000);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retrySeconds));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"about:blank","title":"Too Many Requests","status":429,\
                    "detail":"Rate limit exceeded for client; retry later"}""");
            return;
        }
        chain.doFilter(request, response);
    }
}
