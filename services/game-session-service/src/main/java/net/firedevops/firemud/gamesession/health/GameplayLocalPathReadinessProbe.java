package net.firedevops.firemud.gamesession.health;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.health.DependencyReadinessSupport;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Bounded local canaries for the Game Session front-door state path.
 *
 * <p>These probes intentionally use reserved readiness-only keys and delete them immediately so
 * readiness can verify Redis-backed state primitives without mutating real gameplay state.
 */
@Component
public final class GameplayLocalPathReadinessProbe {
  private static final long PROBE_TENANT_ID = 0L;
  private static final long PROBE_SESSION_ID = 9_223_372_036_854_770_000L;
  private static final long PROBE_ACCOUNT_ID = 9_223_372_036_854_770_001L;
  private static final long PROBE_CHARACTER_ID = 9_223_372_036_854_770_002L;
  private static final long PROBE_GAME_INSTANCE_ID = 0L;
  private static final String PROBE_ROOM_INSTANCE_ID_PREFIX = "readiness-room-";
  private static final String PROBE_JWT = "readiness-probe";
  private static final String QUEUE_PREFIX = "gamesession:tick:queue:";
  private static final AtomicLong PROBE_SEQUENCE = new AtomicLong();

  private final SessionContextService sessionContextService;
  private final RedisTemplate<String, Object> redisTemplate;

  public GameplayLocalPathReadinessProbe(
      SessionContextService sessionContextService, RedisTemplate<String, Object> redisTemplate) {
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
  }

  public ProbeResult probeSessionContextStore() {
    long probeSequence = PROBE_SEQUENCE.incrementAndGet();
    long probeSessionId = PROBE_SESSION_ID + probeSequence;
    String probeRoomInstanceId = PROBE_ROOM_INSTANCE_ID_PREFIX + probeSequence;
    SessionContext context =
        new SessionContext(
            probeSessionId,
            PROBE_TENANT_ID,
            PROBE_ACCOUNT_ID,
            PROBE_CHARACTER_ID,
            PROBE_GAME_INSTANCE_ID,
            probeRoomInstanceId,
            PROBE_JWT);
    try {
      sessionContextService.save(context);
      SessionContext stored =
          sessionContextService
              .findByTenantAndSessionId(PROBE_TENANT_ID, probeSessionId)
              .orElseThrow(() -> new IllegalStateException("session context was not persisted"));
      if (!stored.equals(context)) {
        return ProbeResult.down("stored session context did not round-trip");
      }
      return ProbeResult.up("ROUND_TRIP_OK");
    } catch (RuntimeException ex) {
      return ProbeResult.down(DependencyReadinessSupport.message(ex));
    } finally {
      cleanupSessionContext(probeSessionId);
    }
  }

  public ProbeResult probeCommandQueueStore() {
    String key = queueKey(PROBE_SEQUENCE.incrementAndGet());
    try {
      redisTemplate.opsForList().rightPush(key, "N|READINESS_LOOK");
      Object queuedValue = redisTemplate.opsForList().index(key, 0);
      if (!"N|READINESS_LOOK".equals(queuedValue)) {
        return ProbeResult.down("command queue write did not round-trip");
      }
      return ProbeResult.up("QUEUE_WRITE_OK");
    } catch (RuntimeException ex) {
      return ProbeResult.down(DependencyReadinessSupport.message(ex));
    } finally {
      redisTemplate.delete(key);
    }
  }

  private void cleanupSessionContext(long probeSessionId) {
    try {
      sessionContextService.deleteBySessionId(PROBE_TENANT_ID, probeSessionId);
    } catch (RuntimeException ignored) {
      // Cleanup failures should not hide the original probe result.
    }
  }

  private String queueKey(long probeSequence) {
    return QUEUE_PREFIX + PROBE_TENANT_ID + ":" + (PROBE_SESSION_ID + probeSequence);
  }

  public record ProbeResult(boolean ready, String detail) {
    static ProbeResult up(String detail) {
      return new ProbeResult(true, detail);
    }

    static ProbeResult down(String detail) {
      return new ProbeResult(false, detail);
    }
  }
}
