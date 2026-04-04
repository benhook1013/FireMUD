package net.firedevops.firemud.automationscripting.service.quota;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.redis.RedisAtomicOperations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link ScriptQuotaService}. */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Dependencies are injected and not exposed")
public class ScriptQuotaServiceImpl implements ScriptQuotaService {
  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;

  @Value("${script.quota.limit:50}")
  private long limit;

  @Value("${script.quota.windowSeconds:60}")
  private long windowSeconds;

  private Counter allowedCounter;
  private Counter deniedCounter;

  @PostConstruct
  void init() {
    allowedCounter = meterRegistry.counter("script_quota_allowed_total");
    deniedCounter = meterRegistry.counter("script_quota_denied_total");
  }

  @Override
  @Timed(value = "script.quota.tryAcquire")
  public boolean tryAcquire(Long tenantId, Long scriptId) {
    String key = quotaKey(tenantId, scriptId);
    Long count =
        RedisAtomicOperations.incrementWithTtl(
            redisTemplate, key, java.time.Duration.ofSeconds(windowSeconds));
    boolean allowed = count != null && count <= limit;
    if (allowed) {
      allowedCounter.increment();
    } else {
      deniedCounter.increment();
    }
    return allowed;
  }

  private String quotaKey(Long tenantId, Long scriptId) {
    return "script_quota:" + tenantId + ":" + scriptId;
  }
}
