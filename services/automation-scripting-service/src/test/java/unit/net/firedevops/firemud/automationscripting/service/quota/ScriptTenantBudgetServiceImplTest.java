package net.firedevops.firemud.automationscripting.service.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.automationscripting.config.ScriptTenantBudgetProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

@SuppressWarnings("unchecked")
class ScriptTenantBudgetServiceImplTest {
  private ScriptTenantBudgetServiceImpl service;

  @BeforeEach
  void setup() {
    AtomicInteger calls = new AtomicInteger();
    RedisTemplate<String, Object> redisTemplate =
        mock(
            RedisTemplate.class,
            invocation -> {
              if ("execute".equals(invocation.getMethod().getName())) {
                return (long) calls.incrementAndGet();
              }
              return null;
            });
    ScriptTenantBudgetProperties properties = new ScriptTenantBudgetProperties();
    properties.setHighRunsPerMinute(3L);
    properties.setNormalRunsPerMinute(2L);
    properties.setBackgroundRunsPerMinute(1L);
    service =
        new ScriptTenantBudgetServiceImpl(redisTemplate, new SimpleMeterRegistry(), properties);
  }

  @Test
  void tryReserveAppliesNormalTierLimitByDefault() {
    assertThat(service.tryReserve("tenant-1", "")).isTrue();
    assertThat(service.tryReserve("tenant-1", "unknown")).isTrue();
    assertThat(service.tryReserve("tenant-1", "normal")).isFalse();
  }

  @Test
  void tryReserveUsesExplicitTierLimits() {
    assertThat(service.tryReserve("tenant-1", "background")).isTrue();
    assertThat(service.tryReserve("tenant-1", "background")).isFalse();
  }
}
