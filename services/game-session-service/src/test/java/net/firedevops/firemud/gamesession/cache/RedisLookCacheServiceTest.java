package net.firedevops.firemud.gamesession.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.cache.RedisLookCacheService;
import net.firedevops.firemud.common.config.FiremudReconnectionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

@SuppressWarnings("unchecked")
class RedisLookCacheServiceTest {
  private StringRedisTemplate redisTemplate;
  private ValueOperations<String, String> valueOperations;
  private RedisLookCacheService cacheService;

  @BeforeEach
  void setUp() {
    redisTemplate = Mockito.mock(StringRedisTemplate.class);
    valueOperations = Mockito.mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    cacheService =
        new RedisLookCacheService(
            redisTemplate,
            new ObjectMapper(),
            new FiremudReconnectionProperties(
                null, null, new FiremudReconnectionProperties.ViewCache(600_000L), null));
  }

  @Test
  void cacheWritesSerializedPayload() {
    cacheService.cache(22L, 1L, "R-1021", "OK LOOK text", "OK LOOK\nOK LOOK text\n\n");

    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
    verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture(), any(Duration.class));
    assertThat(keyCaptor.getValue()).isEqualTo("lookcache:22:1");
    assertThat(valueCaptor.getValue()).contains("OK LOOK text");
  }

  @Test
  void getReturnsCachedLook() {
    when(valueOperations.get(any(String.class)))
        .thenReturn(
            "{\"roomId\":\"R-1021\",\"renderedText\":\"OK LOOK\",\"protocolText\":\"OK LOOK\\nOK LOOK\\n\\n\",\"cachedAtMs\":123}");
    Optional<LookCacheService.CachedLook> result = cacheService.get(22L, 1L);
    assertThat(result).isPresent();
    assertThat(result.get().roomId()).isEqualTo("R-1021");
    assertThat(result.get().renderedText()).isEqualTo("OK LOOK");
    assertThat(result.get().protocolText()).isEqualTo("OK LOOK\nOK LOOK\n\n");
  }

  @Test
  void getHandlesMissingPayload() {
    when(valueOperations.get(any(String.class))).thenReturn(null);
    assertThat(cacheService.get(22L, 1L)).isEmpty();
  }
}
