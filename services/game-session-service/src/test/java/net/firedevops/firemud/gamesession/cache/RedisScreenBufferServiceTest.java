package net.firedevops.firemud.gamesession.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.cache.RedisScreenBufferService;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.config.FiremudReconnectionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

@SuppressWarnings("unchecked")
class RedisScreenBufferServiceTest {
  private static final String REDIS_KEY = "screenbuffer:22:1:123";

  private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
  private final RedisOperations<String, Object> redisOperations = mock(RedisOperations.class);
  private final ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
  private final AtomicReference<String> storedPayload = new AtomicReference<>();
  private final AtomicReference<String> pendingPayload = new AtomicReference<>();
  private final AtomicInteger execAttempts = new AtomicInteger();

  private RedisScreenBufferService cacheService;

  @BeforeEach
  void setUp() {
    when(redisTemplate.opsForValue()).thenReturn((ValueOperations) valueOperations);
    when(redisTemplate.execute(any(SessionCallback.class)))
        .thenAnswer(
            invocation -> {
              SessionCallback<?> callback = invocation.getArgument(0);
              return callback.execute(redisOperations);
            });
    when(redisOperations.opsForValue()).thenReturn((ValueOperations) valueOperations);
    doAnswer(
            invocation -> {
              pendingPayload.set((String) invocation.getArgument(1));
              return null;
            })
        .when(valueOperations)
        .set(eq(REDIS_KEY), any(String.class), any(Duration.class));
    doAnswer(
            invocation -> {
              pendingPayload.set((String) invocation.getArgument(1));
              return null;
            })
        .when(valueOperations)
        .set(eq(REDIS_KEY), any(String.class));
    when(valueOperations.get(REDIS_KEY)).thenAnswer(invocation -> storedPayload.get());
    doAnswer(
            invocation -> {
              execAttempts.incrementAndGet();
              if (execAttempts.get() == 1) {
                pendingPayload.set(null);
                return null;
              }
              storedPayload.set(pendingPayload.get());
              pendingPayload.set(null);
              return List.of();
            })
        .when(redisOperations)
        .exec();
    doAnswer(
            invocation -> {
              pendingPayload.set(null);
              return null;
            })
        .when(redisOperations)
        .unwatch();

    cacheService =
        new RedisScreenBufferService(
            redisTemplate,
            new ObjectMapper(),
            (tenantId, gameInstanceId) ->
                new FiremudReconnectionProperties(
                    null,
                    new FiremudReconnectionProperties.Buffer(
                        1_800_000L, 256, 8, 24, 16_384, 65_536)));
  }

  @Test
  void appendsBufferedTranscriptWithRetryPreservingExistingEntries() {
    storedPayload.set(
        """
        {"entries":[{"protocolText":"FIRST\\n","lineCount":1,"byteSize":6,"appendedAtMs":1000}],"updatedAtMs":1000}
        """
            .trim());

    cacheService.append(
        22L,
        1L,
        123L,
        List.of(
            ScreenBufferService.BufferedEntry.fromStructuredOutput(
                "SECOND\n",
                "MESSAGE",
                "BUFFERABLE",
                "DEFAULT",
                "text_message",
                "{\"text\":\"SECOND\"}")));

    ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
    verify(valueOperations, times(2))
        .set(eq(REDIS_KEY), valueCaptor.capture(), any(Duration.class));
    verify(redisOperations, times(2)).watch(REDIS_KEY);
    assertThat(execAttempts.get()).isEqualTo(2);

    Optional<ScreenBufferService.BufferedScreen> result = cacheService.get(22L, 1L, 123L);

    assertThat(result).isPresent();
    assertThat(result.orElseThrow().messageCount()).isEqualTo(2);
    assertThat(result.orElseThrow().lineCount()).isEqualTo(2);
    assertThat(result.orElseThrow().protocolText()).contains("FIRST");
    assertThat(result.orElseThrow().protocolText()).contains("SECOND");
    ScreenBufferService.BufferedEntry second = result.orElseThrow().entries().get(1);
    assertThat(second.hasStructuredOutput()).isTrue();
    assertThat(second.outputKind()).isEqualTo("MESSAGE");
    assertThat(second.payloadType()).isEqualTo("text_message");
    assertThat(second.payloadJson()).contains("SECOND");
    assertThat(second.byteSize()).isGreaterThan("SECOND\n".getBytes(StandardCharsets.UTF_8).length);
    assertThat(valueCaptor.getAllValues()).hasSize(2);
    assertThat(valueCaptor.getAllValues().get(1)).contains("\"SECOND");
    assertThat(valueCaptor.getAllValues().get(1)).contains("\"payloadType\":\"text_message\"");
  }

  @Test
  void zeroTtlKeepsTheBoundedTranscriptWithoutRedisExpiry() {
    RedisScreenBufferService noExpiryCache =
        new RedisScreenBufferService(
            redisTemplate,
            new ObjectMapper(),
            (tenantId, gameInstanceId) ->
                new FiremudReconnectionProperties(
                    null,
                    new FiremudReconnectionProperties.Buffer(0L, 256, 8, 24, 16_384, 65_536)));

    noExpiryCache.append(
        22L, 1L, 123L, List.of(ScreenBufferService.BufferedEntry.fromText("ONE\n")));

    verify(valueOperations, times(2)).set(eq(REDIS_KEY), anyString());
  }

  @Test
  void dropsSingleEntryWhoseCanonicalEnvelopeExceedsHardLimit() {
    ScreenBufferService.BufferedEntry oversized =
        ScreenBufferService.BufferedEntry.fromStructuredOutput(
            "short\n",
            "VIEW",
            "REPLAY",
            "FULL",
            "look-view",
            "{\"description\":\"" + "x".repeat(65_536) + "\"}");

    assertThat(oversized.canonicalByteSize(22L, 1L, 123L)).isGreaterThan(65_536);

    cacheService.append(22L, 1L, 123L, List.of(oversized));

    assertThat(cacheService.get(22L, 1L, 123L)).isEmpty();
  }

  @Test
  void trimsOldestEntryWhenConfiguredEntryLimitIsExceeded() {
    RedisScreenBufferService limitedCache =
        new RedisScreenBufferService(
            redisTemplate,
            new ObjectMapper(),
            (tenantId, gameInstanceId) ->
                new FiremudReconnectionProperties(
                    null,
                    new FiremudReconnectionProperties.Buffer(1_800_000L, 1, 1, 1, 16_384, 65_536)));

    limitedCache.append(
        22L, 1L, 123L, List.of(ScreenBufferService.BufferedEntry.fromText("FIRST\n")));
    limitedCache.append(
        22L, 1L, 123L, List.of(ScreenBufferService.BufferedEntry.fromText("SECOND\n")));

    assertThat(limitedCache.get(22L, 1L, 123L))
        .map(ScreenBufferService.BufferedScreen::protocolText)
        .hasValueSatisfying(
            transcript -> {
              assertThat(transcript).contains("SECOND\n");
              assertThat(transcript).doesNotContain("FIRST\n");
            });
  }

  @Test
  void replaceWritesTheAuthoritativeEntriesWithoutClearingTheExistingCacheFirst() {
    cacheService.replace(
        22L, 1L, 123L, List.of(ScreenBufferService.BufferedEntry.fromText("CURRENT\\n")));

    verify(redisTemplate, times(0)).delete(REDIS_KEY);
    verify(valueOperations).set(eq(REDIS_KEY), anyString(), any(Duration.class));
  }
}
