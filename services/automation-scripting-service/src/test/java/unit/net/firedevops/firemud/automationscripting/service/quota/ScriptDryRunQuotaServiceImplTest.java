package net.firedevops.firemud.automationscripting.service.quota;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.automationscripting.config.ScriptDryRunQuotaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

@SuppressWarnings("unchecked")
class ScriptDryRunQuotaServiceImplTest {
  private ScriptDryRunQuotaServiceImpl service;

  @BeforeEach
  void setup() {
    Map<String, Long> counts = new HashMap<>();
    RedisTemplate<String, Object> redisTemplate =
        mock(
            RedisTemplate.class,
            invocation -> {
              if ("execute".equals(invocation.getMethod().getName())) {
                @SuppressWarnings("unchecked")
                List<String> keys = invocation.getArgument(1);
                return counts.merge(keys.get(0), 1L, Long::sum);
              }
              return null;
            });
    ScriptDryRunQuotaProperties properties = new ScriptDryRunQuotaProperties();
    properties.setMaxRunsPerMinute(3);
    properties.setMaxRunsPerMinutePerPrincipal(1);
    service =
        new ScriptDryRunQuotaServiceImpl(redisTemplate, new SimpleMeterRegistry(), properties);
    service.init();
  }

  @Test
  void enforcesTenantAndPrincipalLimits() {
    assertTrue(service.tryAcquire("tenant-1", "script-1", "account:1"));
    assertFalse(service.tryAcquire("tenant-1", "script-1", "account:1"));
  }
}
