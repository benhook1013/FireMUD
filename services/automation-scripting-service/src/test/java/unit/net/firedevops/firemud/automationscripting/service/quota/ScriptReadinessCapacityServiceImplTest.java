package net.firedevops.firemud.automationscripting.service.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.automationscripting.config.ScriptReadinessCapacityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

@SuppressWarnings("unchecked")
class ScriptReadinessCapacityServiceImplTest {
  private RedisTemplate<String, Object> redisTemplate;
  private ScriptReadinessCapacityServiceImpl service;

  @BeforeEach
  void setup() {
    AtomicInteger calls = new AtomicInteger();
    redisTemplate =
        mock(
            RedisTemplate.class,
            invocation -> {
              if ("execute".equals(invocation.getMethod().getName())) {
                return calls.incrementAndGet() <= 2 ? 1L : -1L;
              }
              return null;
            });
    ScriptReadinessCapacityProperties properties = new ScriptReadinessCapacityProperties();
    properties.setMaxConcurrency(1L);
    service =
        new ScriptReadinessCapacityServiceImpl(
            redisTemplate, new SimpleMeterRegistry(), properties);
  }

  @Test
  void tryReserveHonorsBoundedCounterResult() {
    Optional<ScriptReadinessCapacityService.Reservation> first =
        service.tryReserve("tenant-1", 99L);
    Optional<ScriptReadinessCapacityService.Reservation> second =
        service.tryReserve("tenant-1", 100L);

    assertThat(first).isPresent();
    assertThat(first.get().tenantId()).isEqualTo("tenant-1");
    assertThat(first.get().workItemId()).isEqualTo(99L);
    assertThat(first.get().tenantToken()).isNotBlank();
    assertThat(first.get().clusterToken()).isNotBlank();
    assertThat(second).isEmpty();
  }

  @Test
  void releaseExecutesLeaseReleaseScript() {
    ScriptReadinessCapacityService.Reservation reservation =
        new ScriptReadinessCapacityService.Reservation(
            "tenant-1", 99L, "tenant-token-1", "cluster-token-1");

    service.release(reservation);

    verify(redisTemplate)
        .execute(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq("tenant-token-1"));
    verify(redisTemplate)
        .execute(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq("cluster-token-1"));
  }
}
