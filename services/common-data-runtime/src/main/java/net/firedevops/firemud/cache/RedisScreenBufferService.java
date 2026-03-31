package net.firedevops.firemud.cache;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Redis/ObjectMapper dependencies are shared framework singletons")
public class RedisScreenBufferService implements ScreenBufferService {
  private static final String KEY_TEMPLATE = "screenbuffer:%d:%d:%d";

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  @Value("${firemud.screen-buffer.ttl-ms:1800000}")
  private long ttlMs;

  @Value("${firemud.screen-buffer.min-messages:8}")
  private int minMessages;

  @Value("${firemud.screen-buffer.min-lines:24}")
  private int minLines;

  @Value("${firemud.screen-buffer.soft-max-bytes:16384}")
  private int softMaxBytes;

  @Value("${firemud.screen-buffer.hard-max-bytes:65536}")
  private int hardMaxBytes;

  @Override
  public void append(long tenantId, long gameInstanceId, long characterId, String protocolText) {
    if (!StringUtils.hasText(protocolText)) {
      return;
    }
    BufferedPayload payload =
        readPayload(tenantId, gameInstanceId, characterId).orElseGet(BufferedPayload::new);
    payload.entries.add(EntryPayload.from(protocolText));
    payload.updatedAtMs = System.currentTimeMillis();
    trimPayload(payload);
    writePayload(tenantId, gameInstanceId, characterId, payload);
  }

  @Override
  public Optional<BufferedScreen> get(long tenantId, long gameInstanceId, long characterId) {
    return readPayload(tenantId, gameInstanceId, characterId)
        .filter(payload -> !payload.entries.isEmpty())
        .map(
            payload ->
                new BufferedScreen(
                    joinProtocolText(payload.entries),
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
    try {
      redisTemplate
          .opsForValue()
          .set(
              key(tenantId, gameInstanceId, characterId),
              objectMapper.writeValueAsString(payload),
              Duration.ofMillis(ttlMs));
    } catch (JacksonException ex) {
      throw new IllegalStateException("Failed to serialize screen buffer payload", ex);
    }
  }

  private void trimPayload(BufferedPayload payload) {
    while (payload.entries.size() > 1
        && totalBytes(payload.entries) > softMaxBytes
        && payload.entries.size() > minMessages
        && totalLines(payload.entries) > minLines) {
      payload.entries.remove(0);
    }
    while (payload.entries.size() > 1 && totalBytes(payload.entries) > hardMaxBytes) {
      payload.entries.remove(0);
    }
  }

  private int totalBytes(List<EntryPayload> entries) {
    return entries.stream().mapToInt(entry -> entry.byteSize).sum();
  }

  private int totalLines(List<EntryPayload> entries) {
    return entries.stream().mapToInt(entry -> entry.lineCount).sum();
  }

  private String joinProtocolText(List<EntryPayload> entries) {
    StringBuilder builder = new StringBuilder();
    for (EntryPayload entry : entries) {
      builder.append(entry.protocolText);
    }
    return builder.toString();
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

    static EntryPayload from(String protocolText) {
      EntryPayload payload = new EntryPayload();
      payload.protocolText = protocolText;
      payload.lineCount = countLines(protocolText);
      payload.byteSize = protocolText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      payload.appendedAtMs = System.currentTimeMillis();
      return payload;
    }

    private static int countLines(String protocolText) {
      return (int) protocolText.lines().filter(line -> !line.isBlank()).count();
    }
  }
}
