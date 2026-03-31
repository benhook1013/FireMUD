package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.List;
import net.firedevops.firemud.gamesession.service.DisconnectDeduplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public final class RedisDisconnectDeduplicationService implements DisconnectDeduplicationService {
  private static final Logger logger =
      LoggerFactory.getLogger(RedisDisconnectDeduplicationService.class);
  private static final String KEY_TEMPLATE = "gamesession:notifydisconnect:dedup:%s";
  private static final DefaultRedisScript<Long> DEDUP_SCRIPT = buildScript();

  private final StringRedisTemplate redisTemplate;
  private final Duration ttl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "StringRedisTemplate is injected and used internally only")
  public RedisDisconnectDeduplicationService(
      StringRedisTemplate redisTemplate,
      @Value("${GAME_SESSION_NOTIFY_DISCONNECT_DEDUP_TTL_MS:3600000}") long ttlMs) {
    this.redisTemplate = redisTemplate;
    this.ttl = Duration.ofMillis(Math.max(ttlMs, 1L));
  }

  @Override
  public boolean shouldProcess(String proxyConnectionId, long disconnectSequence) {
    if (!StringUtils.hasText(proxyConnectionId) || disconnectSequence <= 0L) {
      return true;
    }
    try {
      Long accepted =
          redisTemplate.execute(
              DEDUP_SCRIPT,
              List.of(key(proxyConnectionId)),
              String.valueOf(disconnectSequence),
              String.valueOf(ttl.toMillis()));
      return Long.valueOf(1L).equals(accepted);
    } catch (RuntimeException ex) {
      logger.warn(
          "Failed to apply Redis disconnect deduplication for proxyConnectionId={}",
          proxyConnectionId,
          ex);
      return true;
    }
  }

  private static DefaultRedisScript<Long> buildScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setResultType(Long.class);
    script.setScriptText(
        """
        local current = redis.call('GET', KEYS[1])
        if not current then
          redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
          return 1
        end
        local currentSeq = tonumber(current)
        local newSeq = tonumber(ARGV[1])
        if newSeq > currentSeq then
          redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
          return 1
        end
        return 0
        """);
    return script;
  }

  private String key(String proxyConnectionId) {
    return String.format(KEY_TEMPLATE, proxyConnectionId);
  }
}
