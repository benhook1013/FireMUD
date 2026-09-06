package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class TickQueueControlServiceRedisIntegrationTest {
  private static final long TENANT_ID = 42L;
  private static final long GAME_INSTANCE_ID = 9001L;
  private static final String QUEUE_KEY =
      "gamesession:tick:queue:" + TENANT_ID + ":" + GAME_INSTANCE_ID;
  private static final String PENDING_KEY =
      "gamesession:tick:pending:" + TENANT_ID + ":" + GAME_INSTANCE_ID;
  private static final String COMMAND_INDEX_KEY =
      "gamesession:tick:command-index:" + TENANT_ID + ":" + GAME_INSTANCE_ID;
  private static final String COMMAND_INDEX_MARKER_KEY =
      "gamesession:tick:command-index-ready:" + TENANT_ID + ":" + GAME_INSTANCE_ID;
  private static final String MUTATION_LOCK_KEY =
      "gamesession:tick:mutation-lock:" + TENANT_ID + ":" + GAME_INSTANCE_ID;
  private static final String TICK_LOCK_KEY =
      "gamesession:tick:lock:" + TENANT_ID + ":" + GAME_INSTANCE_ID;

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  private LettuceConnectionFactory connectionFactory;
  private RedisTemplate<String, Object> redisTemplate;
  private StringRedisTemplate lockRedisTemplate;
  private ScheduledExecutorService queueLockRenewalExecutor;
  private GameplayCommandRepository gameplayCommandRepository;
  private TickQueueControlService service;

  @BeforeEach
  void setUpRedisAndService() {
    connectionFactory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
    connectionFactory.afterPropertiesSet();

    redisTemplate = new RedisTemplate<>();
    redisTemplate.setConnectionFactory(connectionFactory);
    redisTemplate.afterPropertiesSet();

    lockRedisTemplate = new StringRedisTemplate(connectionFactory);
    lockRedisTemplate.afterPropertiesSet();
    redisTemplate.delete(
        List.of(QUEUE_KEY, PENDING_KEY, COMMAND_INDEX_KEY, COMMAND_INDEX_MARKER_KEY));
    lockRedisTemplate.delete(List.of(MUTATION_LOCK_KEY, TICK_LOCK_KEY));

    gameplayCommandRepository = mock(GameplayCommandRepository.class);
    when(gameplayCommandRepository.lockAcceptedCommandForStaging(
            any(Long.class), any(Long.class), any(String.class), any(String.class), anyBoolean()))
        .thenReturn(true);
    when(gameplayCommandRepository.markAcceptedCommandStaged(any(String.class), any(Instant.class)))
        .thenReturn(true);

    queueLockRenewalExecutor = Executors.newSingleThreadScheduledExecutor();
    service =
        new TickQueueControlService(
            redisTemplate,
            lockRedisTemplate,
            mock(GameInstanceRepository.class),
            gameplayCommandRepository,
            mock(RuntimeRegionStatusRepository.class),
            new RuntimeIdentity(
                "game-session-service",
                "redis-integration-test",
                "test-host",
                Instant.parse("2026-04-19T00:00:00Z"),
                null,
                null,
                null),
            mock(SessionAuthenticationService.class),
            queueLockRenewalExecutor);
  }

  @AfterEach
  void tearDownRedisAndService() {
    queueLockRenewalExecutor.shutdownNow();
    connectionFactory.destroy();
  }

  @Test
  void freshEnqueueUsesProductionSerializersAndMaterializesQueuePayload() {
    service.enqueueCommand(TENANT_ID, GAME_INSTANCE_ID, "cmd-fresh", "look", false);

    assertThat(values(QUEUE_KEY)).containsExactly("N|cmd-fresh|look");
    assertThat(values(PENDING_KEY)).isEmpty();
    assertThat(redisTemplate.opsForHash().get(COMMAND_INDEX_KEY, "cmd-fresh").toString())
        .contains("cmd-fresh|look");
    verify(gameplayCommandRepository)
        .markAcceptedCommandStaged(
            org.mockito.ArgumentMatchers.eq("cmd-fresh"), any(Instant.class));
  }

  @Test
  void exactReplayInQueueProjectionDoesNotDuplicate() {
    String payload = "N|cmd-replay|look";
    redisTemplate.opsForList().rightPush(QUEUE_KEY, payload);

    service.enqueueCommand(TENANT_ID, GAME_INSTANCE_ID, "cmd-replay", "look", false);

    assertThat(values(QUEUE_KEY)).containsExactly(payload);
    assertThat(values(PENDING_KEY)).isEmpty();
    assertThat(redisTemplate.opsForHash().get(COMMAND_INDEX_KEY, "cmd-replay").toString())
        .contains("cmd-replay|look");
    verify(gameplayCommandRepository)
        .markAcceptedCommandStaged(
            org.mockito.ArgumentMatchers.eq("cmd-replay"), any(Instant.class));
  }

  @Test
  void exactReplayInPendingProjectionDoesNotDuplicate() {
    String payload = "N|cmd-replay-pending|look";
    redisTemplate.opsForList().rightPush(PENDING_KEY, payload);

    service.enqueueCommand(TENANT_ID, GAME_INSTANCE_ID, "cmd-replay-pending", "look", false);

    assertThat(values(QUEUE_KEY)).isEmpty();
    assertThat(values(PENDING_KEY)).containsExactly(payload);
    assertThat(redisTemplate.opsForHash().get(COMMAND_INDEX_KEY, "cmd-replay-pending").toString())
        .contains("cmd-replay-pending|look");
  }

  @Test
  void conflictingSameCommandIdAcrossProjectionsFailsClosedWithoutMutation() {
    String existingPayload = "N|cmd-conflict|look";
    String conflictingPayload = "N|cmd-conflict|say hello";
    redisTemplate.opsForList().rightPush(QUEUE_KEY, existingPayload);
    redisTemplate.opsForList().rightPush(PENDING_KEY, conflictingPayload);

    assertThatThrownBy(
            () ->
                service.enqueueCommand(TENANT_ID, GAME_INSTANCE_ID, "cmd-conflict", "look", false))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(values(QUEUE_KEY)).containsExactly(existingPayload);
    assertThat(values(PENDING_KEY)).containsExactly(conflictingPayload);
    assertThat(redisTemplate.opsForHash().get(COMMAND_INDEX_KEY, "cmd-conflict")).isNull();
    verify(gameplayCommandRepository, never())
        .markAcceptedCommandStaged(any(String.class), any(Instant.class));
  }

  @Test
  void duplicateIdentityAcrossQueueAndPendingFailsClosedDuringIndexRebuild() {
    String payload = "N|cmd-duplicate|look";
    redisTemplate.opsForList().rightPush(QUEUE_KEY, payload);
    redisTemplate.opsForList().rightPush(PENDING_KEY, payload);

    assertThatThrownBy(
            () ->
                service.enqueueCommand(TENANT_ID, GAME_INSTANCE_ID, "cmd-duplicate", "look", false))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(values(QUEUE_KEY)).containsExactly(payload);
    assertThat(values(PENDING_KEY)).containsExactly(payload);
    assertThat(redisTemplate.opsForHash().get(COMMAND_INDEX_KEY, "cmd-duplicate")).isNull();
  }

  @Test
  void firstEnqueueRebuildsLegacyQueueAndPendingProjectionsBeforeAddingNewCommand() {
    redisTemplate.opsForList().rightPush(QUEUE_KEY, "N|cmd-legacy-queue|look");
    redisTemplate.opsForList().rightPush(PENDING_KEY, "S|cmd-legacy-pending|wave");

    service.enqueueCommand(TENANT_ID, GAME_INSTANCE_ID, "cmd-new", "say hello", false);

    assertThat(redisTemplate.opsForHash().get(COMMAND_INDEX_KEY, "cmd-legacy-queue").toString())
        .contains("cmd-legacy-queue|look");
    assertThat(redisTemplate.opsForHash().get(COMMAND_INDEX_KEY, "cmd-legacy-pending").toString())
        .contains("cmd-legacy-pending|wave");
    assertThat(redisTemplate.opsForHash().get(COMMAND_INDEX_KEY, "cmd-new").toString())
        .contains("cmd-new|say hello");
  }

  private List<String> values(String key) {
    List<Object> values = redisTemplate.opsForList().range(key, 0, -1);
    return values == null ? List.of() : values.stream().map(Object::toString).toList();
  }
}
