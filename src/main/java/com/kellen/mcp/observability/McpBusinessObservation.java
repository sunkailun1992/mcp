package com.kellen.mcp.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/** 只记录有限业务结果；不读取请求、正文、业务 ID 或原始异常。 */
@Aspect
@Component
public class McpBusinessObservation {
    private final MeterRegistry registry;
    public McpBusinessObservation(MeterRegistry registry) { this.registry = registry; }

    @Around("execution(* com.kellen.mcp.service.McpProtocolService.handle(..))")
    public Object observe(ProceedingJoinPoint invocation) throws Throwable {
        long started = System.nanoTime();
        var span = GlobalOpenTelemetry.getTracer("fleet.business").spanBuilder("mcp.protocol").startSpan();
        String outcome = "error";
        try (var scope = span.makeCurrent()) {
            Object result = invocation.proceed();
            var optional = (java.util.Optional<?>) result;
            if (optional.isEmpty()) outcome = "notification";
            else {
                var response = (java.util.Map<?, ?>) optional.get();
                Object value = response.get("result");
                outcome = response.containsKey("error") ? "protocol_error"
                        : value instanceof java.util.Map<?, ?> map && Boolean.TRUE.equals(map.get("isError"))
                        ? "business_error" : "success";
            }
            return result;
        } finally {
            span.setAttribute("business.outcome", outcome);
            if (java.util.Set.of("error", "unavailable", "business_error", "protocol_error").contains(outcome))
                span.setStatus(StatusCode.ERROR);
            span.end();
            registry.counter("mcp.protocol.count", "outcome", outcome).increment();
            registry.timer("mcp.protocol.duration", "outcome", outcome)
                    .record(Math.max(0, System.nanoTime() - started), TimeUnit.NANOSECONDS);
        }
    }
}
