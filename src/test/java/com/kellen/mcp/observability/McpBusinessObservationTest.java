package com.kellen.mcp.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class McpBusinessObservationTest {
    @Test
    void separatesJsonRpcAndToolErrorsFromSuccessfulHttpResponse() throws Throwable {
        var registry = new SimpleMeterRegistry();
        var observer = new McpBusinessObservation(registry);
        var call = mock(ProceedingJoinPoint.class);
        when(call.proceed()).thenReturn(Optional.of(Map.of("error", Map.of("code", -32601))),
                Optional.of(Map.of("result", Map.of("isError", true))),
                Optional.of(Map.of("result", Map.of("isError", false))), Optional.empty());
        for (int index = 0; index < 4; index++) observer.observe(call);
        for (String outcome : java.util.List.of("protocol_error", "business_error", "success", "notification")) {
            assertThat(registry.get("mcp.protocol.count").tag("outcome", outcome).counter().count()).isEqualTo(1);
        }
        verify(call, never()).getArgs();
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).hasSize(1));
    }

    @Test
    void recordsAuthorizationExceptionWithoutItsMessage() throws Throwable {
        var registry = new SimpleMeterRegistry();
        var observer = new McpBusinessObservation(registry);
        var call = mock(ProceedingJoinPoint.class);
        when(call.proceed()).thenThrow(new IllegalStateException("synthetic secret"));
        assertThatThrownBy(() -> observer.observe(call)).isInstanceOf(IllegalStateException.class);
        assertThat(registry.get("mcp.protocol.count").tag("outcome", "error").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).hasSize(1));
    }
}
