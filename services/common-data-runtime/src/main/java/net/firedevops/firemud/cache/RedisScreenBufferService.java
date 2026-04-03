package net.firedevops.firemud.cache;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.config.FiremudReconnectionProperties;
import net.firedevops.firemud.common.config.ReconnectionSettingsResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Redis/ObjectMapper dependencies are shared framework singletons")
public class RedisScreenBufferService implements ScreenBufferService {
  private static final String KEY_TEMPLATE = "screenbuffer:%d:%d:%d";

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
            : entries.stream().filter(entry -> StringUtils.hasText(entry.text())).toList();
    if (filtered.isEmpty()) {
      return;
    }
    BufferedPayload payload =
        readPayload(tenantId, gameInstanceId, characterId).orElseGet(BufferedPayload::new);
    filtered.stream().map(EntryPayload::from).forEach(payload.entries::add);
    payload.updatedAtMs = System.currentTimeMillis();
    trimPayload(payload, tenantId, gameInstanceId);
    writePayload(tenantId, gameInstanceId, characterId, payload);
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
    String payload = redisTemplate.opsForValue().get(key(tenantId, gameInstanceId, characterId));
    if (payload == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readValue(payload, BufferedPayload.class));
    } catch (JacksonException ex) {
      redisTemplate.delete(key(tenantId, gameInstanceId, characterId));
      return Optional.empty();
    }
  }

  private void writePayload(
      long tenantId, long gameInstanceId, long characterId, BufferedPayload payload) {
    FiremudReconnectionProperties properties = settingsResolver.resolve(tenantId, gameInstanceId);
    try {
      redisTemplate
          .opsForValue()
          .set(
              key(tenantId, gameInstanceId, characterId),
              objectMapper.writeValueAsString(payload),
              Duration.ofMillis(properties.buffer().ttlMs()));
    } catch (JacksonException ex) {
      throw new IllegalStateException("Failed to serialize screen buffer payload", ex);
    }
  }

  private void trimPayload(BufferedPayload payload, long tenantId, long gameInstanceId) {
    FiremudReconnectionProperties.Buffer buffer =
        settingsResolver.resolve(tenantId, gameInstanceId).buffer();
    while (payload.entries.size() > 1
        && totalBytes(payload.entries) > buffer.softMaxBytes()
        && payload.entries.size() > buffer.minMessages()
        && totalLines(payload.entries) > buffer.minLines()) {
      payload.entries.remove(0);
    }
    while (payload.entries.size() > 1 && totalBytes(payload.entries) > buffer.hardMaxBytes()) {
      payload.entries.remove(0);
    }
  }

  private int totalBytes(List<EntryPayload> entries) {
    return entries.stream().mapToInt(entry -> entry.byteSize).sum();
  }

  private int totalLines(List<EntryPayload> entries) {
    return entries.stream().mapToInt(entry -> entry.lineCount).sum();
  }

  private String key(long tenantId, long gameInstanceId, long characterId) {
    return String.format(KEY_TEMPLATE, tenantId, gameInstanceId, characterId);
  }

  private static final class BufferedPayload {
    public List<EntryPayload> entries = new ArrayList<>();
    public long updatedAtMs;
  }

  private static final class EntryPayload {
    public String protocolText;
    public int lineCount;
    public int byteSize;
    public long appendedAtMs;

    static EntryPayload from(BufferedEntry entry) {
      EntryPayload payload = new EntryPayload();
      payload.protocolText = entry.text();
      payload.lineCount = entry.lineCount();
      payload.byteSize = entry.byteSize();
      payload.appendedAtMs = entry.appendedAtMs();
      return payload;
    }

    BufferedEntry toPublicEntry() {
      return new BufferedEntry(protocolText, lineCount, byteSize, appendedAtMs);
    }
  }
}
