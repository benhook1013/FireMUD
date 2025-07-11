package net.firedevops.firemud.service.quota;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed implementation of {@link ScriptQuotaService}. */
@Service
@RequiredArgsConstructor
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
  public boolean tryAcquire(Long tenantId, Long scriptId) {
    String key = quotaKey(tenantId, scriptId);
    Long count = redisTemplate.opsForValue().increment(key);
    if (count != null && count == 1L) {
      redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
    }
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
