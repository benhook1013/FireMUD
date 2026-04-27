package net.firedevops.firemud.automationscripting.service.quota;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.config.ScriptTenantBudgetProperties;
import net.firedevops.firemud.automationscripting.service.redis.AutomationRedisKeys;
import net.firedevops.firemud.common.redis.RedisAtomicOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed tenant automation budget implementation. */
@Service
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Dependencies are injected and not exposed")
public class ScriptTenantBudgetServiceImpl implements ScriptTenantBudgetService {
  private static final String TIER_HIGH = "high";
  private static final String TIER_BACKGROUND = "background";
  private static final String TIER_NORMAL = "normal";
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final RedisTemplate<String, Object> redisTemplate;
  private final MeterRegistry meterRegistry;
  private final ScriptTenantBudgetProperties properties;

  @Override
  @Timed(value = "script.tenantBudget.tryReserve")
  public boolean tryReserve(String tenantId, String priorityTier) {
    String tier = normalizeTier(priorityTier);
    String key = AutomationRedisKeys.automationTenantBudget(tenantId, tier);
    Long count = RedisAtomicOperations.incrementWithTtl(redisTemplate, key, WINDOW);
    boolean allowed = count != null && count <= limitFor(tier);
    meterRegistry
        .counter(
            allowed
                ? "automation_script_tenant_budget_allowed_total"
                : "automation_script_tenant_budget_denied_total",
            "tenantId",
            tenantId,
            "tier",
            tier)
        .increment();
    return allowed;
  }

  private long limitFor(String tier) {
    return switch (tier) {
      case TIER_HIGH -> properties.getHighRunsPerMinute();
      case TIER_BACKGROUND -> properties.getBackgroundRunsPerMinute();
      default -> properties.getNormalRunsPerMinute();
    };
  }

  private static String normalizeTier(String priorityTier) {
    if (priorityTier == null || priorityTier.isBlank()) {
      return TIER_NORMAL;
    }
    String normalized = priorityTier.toLowerCase(java.util.Locale.ROOT);
    return switch (normalized) {
      case TIER_HIGH, TIER_BACKGROUND -> normalized;
      default -> TIER_NORMAL;
    };
  }
}
