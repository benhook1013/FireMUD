package net.firedevops.firemud.automationscripting.service.quota;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.config.ScriptDryRunQuotaProperties;
import net.firedevops.firemud.automationscripting.service.redis.AutomationRedisKeys;
import net.firedevops.firemud.common.redis.RedisAtomicOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/** Redis-backed dry-run capacity reservation implementation. */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Dependencies are injected and not exposed")
public class ScriptDryRunCapacityServiceImpl implements ScriptDryRunCapacityService {
  private static final Duration RESERVATION_TTL = Duration.ofMinutes(10);
  private static final RedisScript<Long> RELEASE_SCRIPT = releaseScript();

  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;
  private final ScriptDryRunQuotaProperties properties;

  @Override
  @Timed(value = "script.dryRunCapacity.tryReserve")
  public Optional<Reservation> tryReserve(String tenantId, long workItemId) {
    String token = UUID.randomUUID().toString();
    boolean reserved =
        RedisAtomicOperations.reserveBoundedCounter(
            redisTemplate,
            AutomationRedisKeys.automationDryRunCapacityCounter(tenantId),
            AutomationRedisKeys.automationDryRunCapacityLease(tenantId, Long.toString(workItemId)),
            properties.getMaxConcurrency(),
            RESERVATION_TTL,
            token);
    meterRegistry
        .counter(
            reserved
                ? "automation_script_test_capacity_reserved_total"
                : "automation_script_test_capacity_denied_total",
            "tenantId",
            tenantId)
        .increment();
    return reserved ? Optional.of(new Reservation(tenantId, workItemId, token)) : Optional.empty();
  }

  @Override
  @Timed(value = "script.dryRunCapacity.release")
  public void release(Reservation reservation) {
    redisTemplate.execute(
        RELEASE_SCRIPT,
        List.of(
            AutomationRedisKeys.automationDryRunCapacityCounter(reservation.tenantId()),
            AutomationRedisKeys.automationDryRunCapacityLease(
                reservation.tenantId(), Long.toString(reservation.workItemId()))),
        reservation.token());
    meterRegistry
        .counter(
            "automation_script_test_capacity_released_total", "tenantId", reservation.tenantId())
        .increment();
  }

  private static RedisScript<Long> releaseScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setResultType(Long.class);
    script.setScriptText(
        """
        if redis.call('GET', KEYS[2]) ~= ARGV[1] then
          return 0
        end
        redis.call('DEL', KEYS[2])
        local remaining = redis.call('DECR', KEYS[1])
        if remaining <= 0 then
          redis.call('DEL', KEYS[1])
        end
        return remaining
        """);
    return script;
  }
}
