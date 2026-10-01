package com.ledgerline.payments.ratelimit;

import com.ledgerline.payments.config.LedgerlineProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Distributed token-bucket rate limiter backed by Redis.
 *
 * <p>The whole read-refill-take step runs as one Lua script, so it is atomic even with many
 * service replicas hitting the same bucket. It uses Redis's own clock (TIME) so replicas with
 * slightly different clocks still agree.
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    // Returns {allowed (1|0), tokens remaining, retry-after in ms}
    private static final String TOKEN_BUCKET_LUA = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_per_sec = tonumber(ARGV[2])
            local requested = tonumber(ARGV[3])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end
            local elapsed = math.max(0, now - ts)
            tokens = math.min(capacity, tokens + (elapsed * refill_per_sec / 1000))
            local allowed = 0
            local retry_ms = 0
            if tokens >= requested then
              tokens = tokens - requested
              allowed = 1
            else
              retry_ms = math.ceil((requested - tokens) * 1000 / refill_per_sec)
            end
            redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', key, math.ceil(capacity * 1000 / refill_per_sec) + 1000)
            return {allowed, math.floor(tokens), retry_ms}
            """;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>(TOKEN_BUCKET_LUA, List.class);

    private final StringRedisTemplate redis;
    private final LedgerlineProperties.RateLimit config;

    public RedisRateLimiter(StringRedisTemplate redis, LedgerlineProperties props) {
        this.redis = redis;
        this.config = props.rateLimit();
    }

    public record Decision(boolean allowed, long remaining, long retryAfterMs) {}

    public Decision tryAcquire(String clientId) {
        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(SCRIPT, List.of("ratelimit:" + clientId),
                    String.valueOf(config.capacity()), String.valueOf(config.refillPerSecond()), "1");
            return new Decision(result.get(0) == 1L, result.get(1), result.get(2));
        } catch (RuntimeException e) {
            // Fail open: if Redis is down we would rather accept traffic than reject every payment.
            // The trade-off is explained in the README.
            log.warn("Rate limiter unavailable, allowing request: {}", e.toString());
            return new Decision(true, -1, 0);
        }
    }
}
