package io.scent.account;

import io.scent.common.BusinessException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bounded, single-instance fixed-window protection. Does not trust X-Forwarded-For. */
@Component
public class AttemptLimiter {
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;

    public AttemptLimiter() { this(Clock.systemUTC()); }
    AttemptLimiter(Clock clock) { this.clock = clock; }

    public synchronized void check(String key, int limit, long seconds) {
        long now = clock.millis();
        Window window = windows.get(key);
        if (window == null || now >= window.until) {
            if (windows.size() >= 4096) { windows.entrySet().removeIf(e -> e.getValue().until <= now); }
            if (windows.size() >= 4096) { limited(); }
            window = new Window(now + seconds * 1000);
            windows.put(key, window);
        }
        if (window.count >= limit) { limited(); }
        window.count++;
    }

    public synchronized void clear(String key) { windows.remove(key); }

    private static void limited() { throw new BusinessException(429, "RATE_LIMITED", "操作过于频繁，请稍后重试"); }

    private static final class Window {
        private final long until;
        private int count;
        private Window(long until) { this.until = until; }
    }
}
