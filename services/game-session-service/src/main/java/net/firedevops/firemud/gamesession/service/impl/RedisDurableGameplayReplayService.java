package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import net.firedevops.firemud.gamesession.presentation.ErrorOutput;
import net.firedevops.firemud.gamesession.presentation.NoticeOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.presentation.TextMessageOutput;
import net.firedevops.firemud.gamesession.service.DurableGameplayReplayService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public final class RedisDurableGameplayReplayService implements DurableGameplayReplayService {
  private final RedisTemplate<String, Object> redisTemplate;
  private final Duration sessionTtl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "RedisTemplate is a shared Spring bean used only internally")
  public RedisDurableGameplayReplayService(
      RedisTemplate<String, Object> redisTemplate,
      @Value("${FIREMUD_AUTH_SESSION_EXPIRATION_MS:3600000}") long sessionExpirationMs) {
    this.redisTemplate = redisTemplate;
    this.sessionTtl = Duration.ofMillis(sessionExpirationMs);
  }

  @Override
  public Optional<ReplayRecord> find(long tenantId, long sessionId, String effectId) {
    StoredReplayRecord stored =
        (StoredReplayRecord)
            redisTemplate
                .opsForValue()
                .get(SessionContextRedisKeys.durableEffectKey(tenantId, sessionId, effectId));
    if (stored == null) {
      return Optional.empty();
    }
    return Optional.of(
        new ReplayRecord(
            stored.accepted(),
            stored.failureCode(),
            stored.failureMessage(),
            stored.actorOutputs().stream().map(StoredPlayerOutput::toPlayerOutput).toList()));
  }

  @Override
  public void save(
      long tenantId,
      long sessionId,
      String effectId,
      boolean accepted,
      String failureCode,
      String failureMessage,
      List<PlayerOutput> actorOutputs) {
    Objects.requireNonNull(effectId, "effectId must not be null");
    List<StoredPlayerOutput> storedOutputs =
        actorOutputs == null
            ? List.of()
            : actorOutputs.stream().map(StoredPlayerOutput::from).collect(Collectors.toList());
    redisTemplate
        .opsForValue()
        .set(
            SessionContextRedisKeys.durableEffectKey(tenantId, sessionId, effectId),
            new StoredReplayRecord(
                accepted, blankToNull(failureCode), blankToNull(failureMessage), storedOutputs),
            sessionTtl);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private record StoredReplayRecord(
      boolean accepted,
      String failureCode,
      String failureMessage,
      List<StoredPlayerOutput> actorOutputs)
      implements Serializable {
    private static final long serialVersionUID = 1L;

    StoredReplayRecord {
      actorOutputs = actorOutputs == null ? List.of() : List.copyOf(actorOutputs);
    }
  }

  private record StoredPlayerOutput(
      PlayerOutputKind kind,
      String text,
      String messageKey,
      Map<String, String> arguments,
      String errorCode)
      implements Serializable {
    private static final long serialVersionUID = 1L;

    StoredPlayerOutput {
      arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    private static StoredPlayerOutput from(PlayerOutput output) {
      return switch (output.payload()) {
        case TextMessageOutput message ->
            new StoredPlayerOutput(
                output.kind(), message.text(), message.messageKey(), message.arguments(), null);
        case NoticeOutput notice ->
            new StoredPlayerOutput(
                output.kind(), notice.text(), notice.messageKey(), notice.arguments(), null);
        case ErrorOutput error ->
            new StoredPlayerOutput(
                output.kind(),
                error.message(),
                error.messageKey(),
                error.arguments(),
                error.code());
        default ->
            throw new IllegalArgumentException(
                "Unsupported durable replay output payload: "
                    + output.payload().getClass().getSimpleName());
      };
    }

    private PlayerOutput toPlayerOutput() {
      return switch (kind) {
        case MESSAGE -> PlayerOutput.message(text, messageKeyOrDefault(), arguments);
        case NOTICE -> PlayerOutput.notice(text, messageKeyOrDefault(), arguments);
        case ERROR ->
            PlayerOutput.error(errorCodeOrDefault(), text, messageKeyOrDefault(), arguments);
        default ->
            throw new IllegalArgumentException("Unsupported durable replay output kind: " + kind);
      };
    }

    private String messageKeyOrDefault() {
      return messageKey == null ? "" : messageKey;
    }

    private String errorCodeOrDefault() {
      return errorCode == null ? "UNKNOWN" : errorCode;
    }
  }
}
