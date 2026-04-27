package net.firedevops.firemud.automationscripting.service.quota;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.config.ScriptDryRunQuotaProperties;
import net.firedevops.firemud.automationscripting.service.redis.AutomationRedisKeys;
import net.firedevops.firemud.common.redis.RedisAtomicOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Dependencies are injected and not exposed")
public class ScriptDryRunQuotaServiceImpl implements ScriptDryRunQuotaService {
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;
  private final ScriptDryRunQuotaProperties properties;

  private Counter allowedCounter;
  private Counter deniedCounter;

  @PostConstruct
  void init() {
    allowedCounter = meterRegistry.counter("automation_script_test_runs_allowed_total");
    deniedCounter = meterRegistry.counter("automation_script_test_runs_denied_total");
  }

  @Override
  @Timed(value = "script.dryRunQuota.tryAcquire")
  public boolean tryAcquire(String tenantId, String scriptId, String principalKey) {
    Long tenantCount =
        RedisAtomicOperations.incrementWithTtl(
            redisTemplate,
            AutomationRedisKeys.automationDryRunTenantQuota(tenantId, scriptId),
            WINDOW);
    Long principalCount =
        RedisAtomicOperations.incrementWithTtl(
            redisTemplate,
            AutomationRedisKeys.automationDryRunPrincipalQuota(tenantId, scriptId, principalKey),
            WINDOW);
    boolean allowed =
        tenantCount != null
            && tenantCount <= properties.getMaxRunsPerMinute()
            && principalCount != null
            && principalCount <= properties.getMaxRunsPerMinutePerPrincipal();
    if (allowed) {
      allowedCounter.increment();
    } else {
      deniedCounter.increment();
    }
    return allowed;
  }
}
