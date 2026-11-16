package io.scent.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.slf4j.LoggerFactory;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MDC.put("traceId", UUID.randomUUID().toString());
        response.setHeader("X-Trace-Id", MDC.get("traceId"));
        long started=System.nanoTime();
        try { chain.doFilter(request, response); }
        finally {
            Object route=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            LoggerFactory.getLogger(TraceFilter.class).info("HTTP method={} route={} status={} elapsedMs={}",request.getMethod(),route==null?"unmapped":route,response.getStatus(),(System.nanoTime()-started)/1000000);
            MDC.remove("traceId");
        }
    }
}
