package net.firedevops.firemud.gamesession.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import net.firedevops.firemud.cache.RedisScreenBufferService;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.config.ReconnectionSettingsResolver;
import net.firedevops.firemud.gamesession.repository.ResumeTranscriptEntryRepository;
import net.firedevops.firemud.gamesession.service.DurableScreenBufferService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Supplies the durable transcript implementation while keeping Redis as a best-effort hot cache.
 */
@Configuration
public class DurableResumeTranscriptConfiguration {
  @Bean
  public ScreenBufferService durableScreenBufferService(
      ResumeTranscriptEntryRepository repository,
      ReconnectionSettingsResolver settingsResolver,
      ObjectProvider<StringRedisTemplate> redisTemplateProvider,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    ScreenBufferService hotCache =
        Optional.ofNullable(redisTemplateProvider.getIfAvailable())
            .<ScreenBufferService>map(
                redisTemplate ->
                    new RedisScreenBufferService(redisTemplate, objectMapper, settingsResolver))
            .orElseGet(NoopScreenBufferService::new);
    return new DurableScreenBufferService(repository, settingsResolver, hotCache, meterRegistry);
  }

  private static final class NoopScreenBufferService implements ScreenBufferService {
    @Override
    public void append(
        long tenantId,
        long gameInstanceId,
        long characterId,
        java.util.List<BufferedEntry> entries) {}

    @Override
    public void replace(
        long tenantId,
        long gameInstanceId,
        long characterId,
        java.util.List<BufferedEntry> entries) {}

    @Override
    public Optional<BufferedScreen> get(long tenantId, long gameInstanceId, long characterId) {
      return Optional.empty();
    }

    @Override
    public void clear(long tenantId, long gameInstanceId, long characterId) {}
  }
}
