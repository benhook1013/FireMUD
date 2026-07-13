package net.firedevops.firemud.cache;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.common.config.FiremudReconnectionProperties;
import net.firedevops.firemud.common.config.ReconnectionSettingsResolver;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Redis/ObjectMapper dependencies are shared framework singletons")
public class RedisScreenBufferService implements ScreenBufferService {
  private static final String KEY_TEMPLATE = "screenbuffer:%d:%d:%d";
  private static final int MAX_APPEND_RETRIES = 8;

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final ReconnectionSettingsResolver settingsResolver;

  public RedisScreenBufferService(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      ReconnectionSettingsResolver settingsResolver) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.settingsResolver = settingsResolver;
  }

  @Override
  public void append(
      long tenantId, long gameInstanceId, long characterId, List<BufferedEntry> entries) {
    List<BufferedEntry> filtered =
        entries == null
            ? List.of()
            : entries.stream()
                .filter(entry -> StringUtils.hasText(entry.text()))
                .map(entry -> entry.withCanonicalByteSize(tenantId, gameInstanceId, characterId))
                .toList();
    if (filtered.isEmpty()) {
      return;
    }
    FiremudReconnectionProperties properties = settingsResolver.resolve(tenantId, gameInstanceId);
    Duration ttl = Duration.ofMillis(properties.buffer().ttlMs());
    String redisKey = key(tenantId, gameInstanceId, characterId);
    for (int attempt = 0; attempt < MAX_APPEND_RETRIES; attempt++) {
      Boolean updated =
          redisTemplate.execute(
              new SessionCallback<>() {
                @Override
                public Boolean execute(RedisOperations operations) {
                  operations.watch(redisKey);
                  BufferedPayload payload =
                      readPayload((RedisOperations<String, ?>) operations, redisKey)
                          .orElseGet(BufferedPayload::new);
                  filtered.stream().map(EntryPayload::from).forEach(payload.entries::add);
                  payload.updatedAtMs = System.currentTimeMillis();
                  trimPayload(payload, properties.buffer());
                  try {
                    operations.multi();
                    if (ttl.isZero()) {
                      operations
                          .opsForValue()
                          .set(redisKey, objectMapper.writeValueAsString(payload));
                    } else {
                      operations
                          .opsForValue()
                          .set(redisKey, objectMapper.writeValueAsString(payload), ttl);
                    }
                  } catch (JacksonException ex) {
                    operations.unwatch();
                    throw new IllegalStateException(
                        "Failed to serialize screen buffer payload", ex);
                  }
                  return operations.exec() != null;
                }
              });
      if (Boolean.TRUE.equals(updated)) {
        return;
      }
    }
    throw new IllegalStateException("Failed to append screen buffer after concurrent retries");
  }

  @Override
  public Optional<BufferedScreen> get(long tenantId, long gameInstanceId, long characterId) {
    return readPayload(tenantId, gameInstanceId, characterId)
        .filter(payload -> !payload.entries.isEmpty())
        .map(
            payload ->
                new BufferedScreen(
                    payload.entries.stream().map(EntryPayload::toPublicEntry).toList(),
                    payload.entries.size(),
                    payload.entries.stream().mapToInt(entry -> entry.lineCount).sum(),
                    payload.updatedAtMs));
  }

  @Override
  public void clear(long tenantId, long gameInstanceId, long characterId) {
    redisTemplate.delete(key(tenantId, gameInstanceId, characterId));
  }

  private Optional<BufferedPayload> readPayload(
      long tenantId, long gameInstanceId, long characterId) {
    return readPayload(redisTemplate, key(tenantId, gameInstanceId, characterId));
  }

  private Optional<BufferedPayload> readPayload(
      RedisOperations<String, ?> operations, String redisKey) {
    String payload = (String) operations.opsForValue().get(redisKey);
    if (payload == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readValue(payload, BufferedPayload.class));
    } catch (JacksonException ex) {
      operations.unwatch();
      redisTemplate.delete(redisKey);
      return Optional.empty();
    }
  }

  private void trimPayload(BufferedPayload payload, FiremudReconnectionProperties.Buffer buffer) {
    payload.entries.removeIf(entry -> entry.byteSize > buffer.hardMaxBytes());
    while (payload.entries.size() > buffer.maxEntries()) {
      payload.entries.removeFirst();
    }
    Map<EntryPayload, Integer> entryByteSizes = new IdentityHashMap<>();
    int currentBytes = 0;
    int currentLines = 0;
    for (EntryPayload entry : payload.entries) {
      int entryByteSize = entry.byteSize;
      entryByteSizes.put(entry, entryByteSize);
      currentBytes += entryByteSize;
      currentLines += entry.lineCount;
    }
    while (payload.entries.size() > 1
        && currentBytes > buffer.softMaxBytes()
        && payload.entries.size() > buffer.minMessages()
        && currentLines > buffer.minLines()) {
      EntryPayload removed = payload.entries.removeFirst();
      currentBytes -= entryByteSizes.get(removed);
      currentLines -= removed.lineCount;
    }
    while (payload.entries.size() > 1 && currentBytes > buffer.hardMaxBytes()) {
      EntryPayload removed = payload.entries.removeFirst();
      currentBytes -= entryByteSizes.get(removed);
    }
  }

  private String key(long tenantId, long gameInstanceId, long characterId) {
    return String.format(KEY_TEMPLATE, tenantId, gameInstanceId, characterId);
  }

  private static final class BufferedPayload {
    public Deque<EntryPayload> entries = new ArrayDeque<>();
    public long updatedAtMs;
  }

  private static final class EntryPayload {
    public String protocolText;
    public int lineCount;
    public int byteSize;
    public long appendedAtMs;
    public String outputKind;
    public String replayPolicy;
    public String briefRenderPolicy;
    public String payloadType;
    public String payloadJson;
    public long orderingToken;

    static EntryPayload from(BufferedEntry entry) {
      EntryPayload payload = new EntryPayload();
      payload.protocolText = entry.text();
      payload.lineCount = entry.lineCount();
      payload.byteSize = entry.byteSize();
      payload.appendedAtMs = entry.appendedAtMs();
      payload.outputKind = entry.outputKind();
      payload.replayPolicy = entry.replayPolicy();
      payload.briefRenderPolicy = entry.briefRenderPolicy();
      payload.payloadType = entry.payloadType();
      payload.payloadJson = entry.payloadJson();
      payload.orderingToken = entry.orderingToken();
      return payload;
    }

    BufferedEntry toPublicEntry() {
      return new BufferedEntry(
          protocolText,
          lineCount,
          byteSize,
          appendedAtMs,
          outputKind,
          replayPolicy,
          briefRenderPolicy,
          payloadType,
          payloadJson,
          orderingToken);
    }
  }
}
