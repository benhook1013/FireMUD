package net.firedevops.firemud.gamesession.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.cache.RedisScreenBufferService;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.config.FiremudReconnectionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

class RedisScreenBufferServiceTest {
  private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
  private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

  private RedisScreenBufferService cacheService;

  @BeforeEach
  void setUp() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    cacheService =
        new RedisScreenBufferService(
            redisTemplate,
            new ObjectMapper(),
            (tenantId, gameInstanceId) ->
                new FiremudReconnectionProperties(
                    null,
                    new FiremudReconnectionProperties.Buffer(1_800_000L, 8, 24, 16_384, 65_536)));
  }

  @Test
  void appendsAndReadsBufferedTranscript() {
    cacheService.append(
        22L,
        1L,
        123L,
        java.util.List.of(
            ScreenBufferService.BufferedEntry.fromText("OK SAY\nYou say, \"hello\"\n\n")));

    ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
    verify(valueOperations)
        .set(
            eq("screenbuffer:22:1:123"), valueCaptor.capture(), org.mockito.ArgumentMatchers.any());
    when(redisTemplate.opsForValue().get("screenbuffer:22:1:123"))
        .thenReturn(valueCaptor.getValue());

    Optional<ScreenBufferService.BufferedScreen> result = cacheService.get(22L, 1L, 123L);

    assertThat(result).isPresent();
    assertThat(result.orElseThrow().protocolText()).contains("OK SAY");
    assertThat(result.orElseThrow().messageCount()).isEqualTo(1);
    assertThat(result.orElseThrow().lineCount()).isEqualTo(2);
  }
}
