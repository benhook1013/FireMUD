package net.firedevops.firemud.automationscripting.service.quota;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.config.ScriptQuotaProperties;
import net.firedevops.firemud.automationscripting.service.redis.AutomationRedisKeys;
import net.firedevops.firemud.common.redis.RedisAtomicOperations;
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
  private final ScriptQuotaProperties quotaProperties;

  private Counter allowedCounter;
  private Counter deniedCounter;

  @PostConstruct
  void init() {
    allowedCounter = meterRegistry.counter("script_quota_allowed_total");
    deniedCounter = meterRegistry.counter("script_quota_denied_total");
  }

  @Override
  @Timed(value = "script.quota.tryAcquire")
  public boolean tryAcquire(String tenantId, String scriptId) {
    String key = AutomationRedisKeys.automationQuota(tenantId, scriptId);
    Long count =
        RedisAtomicOperations.incrementWithTtl(
            redisTemplate, key, java.time.Duration.ofSeconds(quotaProperties.getWindowSeconds()));
    boolean allowed = count != null && count <= quotaProperties.getLimit();
    if (allowed) {
      allowedCounter.increment();
    } else {
      deniedCounter.increment();
    }
    return allowed;
  }
}
