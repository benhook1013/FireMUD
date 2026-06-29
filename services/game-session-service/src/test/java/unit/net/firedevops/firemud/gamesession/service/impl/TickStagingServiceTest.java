package net.firedevops.firemud.gamesession.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.entity.TickBatch;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.RuntimeRegionStatusRepository;
import net.firedevops.firemud.gamesession.repository.TickBatchRepository;
import net.firedevops.firemud.gamesession.repository.TickEffectRepository;
import net.firedevops.firemud.gamesession.service.DurableGameplayCommandExecutionService;
import net.firedevops.firemud.gamesession.service.DurableRemoteFollowupExecutionService;
import net.firedevops.firemud.gamesession.service.RemoteFollowupDrainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;

class TickStagingServiceTest {
  private RedisTemplate<String, Object> redisTemplate;
  private org.springframework.data.redis.core.ListOperations<String, Object> listOps;
  private GameplayCommandRepository gameplayCommandRepository;
  private RemoteFollowupRepository remoteFollowupRepository;
  private TickBatchRepository tickBatchRepository;
  private TickEffectRepository tickEffectRepository;
  private RemoteFollowupDrainService remoteFollowupDrainService;
  private TickStagingService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    redisTemplate = mock(RedisTemplate.class);
    listOps = mock(org.springframework.data.redis.core.ListOperations.class);
    when(redisTemplate.opsForList()).thenReturn(listOps);
    gameplayCommandRepository = mock(GameplayCommandRepository.class);
    AtomicLong commandIds = new AtomicLong();
    when(gameplayCommandRepository.save(any()))
        .thenAnswer(
            invocation -> {
              GameplayCommand command = invocation.getArgument(0);
              if (command.getId() == null) {
                command.setId(commandIds.incrementAndGet());
              }
              return command;
            });
    remoteFollowupRepository = mock(RemoteFollowupRepository.class);
    tickBatchRepository = mock(TickBatchRepository.class);
    when(tickBatchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    tickEffectRepository = mock(TickEffectRepository.class);
    when(tickEffectRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    remoteFollowupDrainService = mock(RemoteFollowupDrainService.class);
    RuntimeRegionStatusRepository runtimeRegionStatusRepository =
        mock(RuntimeRegionStatusRepository.class);
    TickQueueControlService tickQueueControlService =
        new TickQueueControlService(
            redisTemplate,
            mock(GameInstanceRepository.class),
            gameplayCommandRepository,
            runtimeRegionStatusRepository,
            new net.firedevops.firemud.common.runtime.RuntimeIdentity(
                "game-session-service",
                "test-instance",
                "test-host",
                Instant.parse("2026-04-19T00:00:00Z"),
                null,
                null,
                null),
            mock(net.firedevops.firemud.gamesession.service.SessionAuthenticationService.class));
    TickBatchExecutionService tickBatchExecutionService =
        new TickBatchExecutionService(
            new SimpleMeterRegistry(),
            redisTemplate,
            gameplayCommandRepository,
            tickBatchRepository,
            tickEffectRepository,
            mock(DurableGameplayCommandExecutionService.class),
            mock(DurableRemoteFollowupExecutionService.class),
            remoteFollowupDrainService,
            tickQueueControlService);
    service =
        new TickStagingService(
            redisTemplate,
            gameplayCommandRepository,
            remoteFollowupRepository,
            tickBatchRepository,
            tickEffectRepository,
            remoteFollowupDrainService,
            tickQueueControlService,
            tickBatchExecutionService);
    setField(service, "maxRemoteFollowupsPerTick", 16);
    when(runtimeRegionStatusRepository.findByTenantIdAndRegionId(1L, "region-a"))
        .thenReturn(Optional.of(runtimeOwnership(1L, 2L, "region-a", 1L, "fence-a", false)));
    when(runtimeRegionStatusRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(tickEffectRepository.findByTickBatchId(anyString())).thenReturn(List.of());
  }

  @Test
  void createBatchPersistsComparableOrderingAndCanonicalRoutingManifest() {
    GameplayCommand command = gameplayCommand("cmd-1");
    command.setSourceType("AUTOMATION");
    command.setAutomationDispatchId("dispatch-1");
    command.setAutomationWorkItemId("work-1");
    command.setScriptId("script-1");
    command.setScriptPatchVersion("patch-1");
    command.setPluginId("plugin-1");
    command.setPluginVersionId("plugin-v1");
    command.setTargetEntityId("entity-1");
    command.setRegionId("region-1");
    command.setRegionEpoch(4L);
    command.setPlayableStateScope("SHARED");
    command.setWorldSlug("demo");
    command.setRealmSlug("production");
    command.setPointerVersion(17L);
    command.setDueTickId(14L);
    command.setEnqueueSeq(77L);
    command.setOriginSourceKind("SCHEDULE_TIMER");
    command.setOriginSourceState("SCHEDULE_DUE_CLAIMED");
    command.setOriginSourceOrdinal(5000L);
    command.setOriginSourceDueAtMs(5000L);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));

    TickBatch batch =
        service.createBatch(
            "FRESH_STAGE",
            1L,
            2L,
            false,
            new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L),
            List.of(new TickQueuedCommandEnvelope(false, "cmd-1", "say hello")));

    org.junit.jupiter.api.Assertions.assertEquals("region-a", batch.getRegionId());
    org.junit.jupiter.api.Assertions.assertTrue(
        batch.getSelectedWorkManifestJson().contains("\"sourceType\":\"AUTOMATION\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        batch.getSelectedWorkManifestJson().contains("\"sourceKind\":\"SCHEDULE_TIMER\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        batch.getSelectedWorkManifestJson().contains("\"sourceOrdinal\":5000"));
    org.junit.jupiter.api.Assertions.assertTrue(
        batch.getSelectedWorkManifestJson().contains("\"sourceState\":\"SCHEDULE_DUE_CLAIMED\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        batch.getSelectedWorkManifestJson().contains("\"worldSlug\":\"demo\""));
    org.junit.jupiter.api.Assertions.assertTrue(
        batch.getSelectedWorkManifestJson().contains("\"pointerVersion\":17"));
  }

  @Test
  void createBatchDropsPartialRoutingBundleFromGameplayManifest() {
    GameplayCommand command = gameplayCommand("cmd-1");
    command.setWorldSlug("demo");
    command.setRealmSlug("production");
    command.setPointerVersion(null);
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1")))
        .thenReturn(List.of(command));

    TickBatch batch =
        service.createBatch(
            "FRESH_STAGE",
            1L,
            2L,
            false,
            new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L),
            List.of(new TickQueuedCommandEnvelope(false, "cmd-1", "say hello")));

    org.junit.jupiter.api.Assertions.assertFalse(
        batch.getSelectedWorkManifestJson().contains("\"worldSlug\""));
    org.junit.jupiter.api.Assertions.assertFalse(
        batch.getSelectedWorkManifestJson().contains("\"realmSlug\""));
    org.junit.jupiter.api.Assertions.assertFalse(
        batch.getSelectedWorkManifestJson().contains("\"pointerVersion\""));
  }

  @Test
  void resolveReplayBatchRestoresSealedManifestAndRequeuesRedisOnlyEntries() {
    List<Object> pendingRawEntries = List.of("N|cmd-1|look", "N|cmd-2|wave");
    TickBatch existingBatch = new TickBatch();
    existingBatch.setTickBatchId("tb-existing");
    existingBatch.setTenantId(1L);
    existingBatch.setGameInstanceId(2L);
    existingBatch.setRegionEpoch(1L);
    existingBatch.setExecutorFence("fence-a");
    existingBatch.setStatus("STAGED");
    existingBatch.setBatchSource("FRESH_STAGE");
    String sealedManifest = replayManifestJson(service, List.of("N|cmd-1|look"));
    existingBatch.setSelectedWorkManifestJson(sealedManifest);
    existingBatch.setSelectedWorkManifestDigest(
        replayManifestDigest(service, List.of("N|cmd-1|look")));
    existingBatch.setStagedAt(Instant.parse("2026-04-19T00:00:00Z"));
    GameplayCommand first = gameplayCommand("cmd-1");
    first.setEnqueueSeq(5L);
    GameplayCommand second = gameplayCommand("cmd-2");
    second.setCommandText("wave");
    second.setSanitizedCommandText("wave");
    second.setEnqueueSeq(6L);
    second.setSourceType("AUTOMATION");
    List<GameplayCommand> savedSnapshots = new ArrayList<>();
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<GameplayCommand> saved = (List<GameplayCommand>) invocation.getArgument(0);
              saved.stream()
                  .map(TickStagingServiceTest::copyGameplayCommand)
                  .forEach(savedSnapshots::add);
              return saved;
            })
        .when(gameplayCommandRepository)
        .saveAll(any());
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1", "cmd-2")))
        .thenReturn(List.of(first, second));
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-1"))).thenReturn(List.of(first));
    when(gameplayCommandRepository.findByCommandIdIn(List.of("cmd-2"))).thenReturn(List.of(second));
    when(tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            1L, 2L, "STAGED"))
        .thenReturn(Optional.of(existingBatch));

    TickBatch replayBatch =
        service.resolveReplayBatch(
            1L,
            2L,
            parseEntries(service, pendingRawEntries),
            new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L));

    org.junit.jupiter.api.Assertions.assertEquals("PENDING_REPLAY", replayBatch.getBatchSource());
    verify(redisTemplate).delete("gamesession:tick:pending:1:2");
    verify(listOps).rightPush("gamesession:tick:pending:1:2", "N|cmd-1|look");
    verify(listOps).leftPush("gamesession:tick:queue:1:2", "N|cmd-2|wave");
    org.junit.jupiter.api.Assertions.assertTrue(
        savedSnapshots.stream()
            .anyMatch(
                saved ->
                    "cmd-2".equals(saved.getCommandId())
                        && "RETRY_QUEUED".equals(saved.getExecutionOutcome())
                        && "GAMEPLAY_RETRY".equals(saved.getQueueSourceKind())));
  }

  @Test
  void drainRemoteFollowupsDropsPartialRoutingBundleFromManifest() {
    net.firedevops.firemud.gamesession.entity.RemoteFollowup followup =
        new net.firedevops.firemud.gamesession.entity.RemoteFollowup();
    followup.setTenantId(1L);
    followup.setFollowupId("followup-1");
    followup.setTargetGameInstanceId(2L);
    followup.setOriginRegionId("origin-region");
    followup.setOriginRegionEpoch(4L);
    followup.setTargetRegionId("region-a");
    followup.setTargetRegionEpoch(8L);
    followup.setDueTickId(10L);
    followup.setQueueSourceKind("REMOTE_FOLLOWUP");
    followup.setQueueSourceState("REDIS_PENDING_CLAIMED");
    followup.setQueueSourceOrdinal(1L);
    followup.setTargetEntityId("entity-1");
    followup.setClaimTargetAggregate("entity:entity-1");
    followup.setPlayableStateScope("SHARED");
    followup.setWorldSlug("demo");
    followup.setRealmSlug("production");
    followup.setPointerVersion(null);
    followup.setPayloadKind("noop");
    followup.setRequestedCommand("LOOK");
    followup.setRequiresSoloTick(true);
    followup.setPayloadJson("{\"kind\":\"noop\"}");
    when(remoteFollowupDrainService.claimDueFollowups(
            eq(1L), eq("region-a"), eq(1L), anyString(), eq(16)))
        .thenReturn(new RemoteFollowupDrainService.ClaimOutcome(List.of("followup-1"), 1));
    when(remoteFollowupRepository.findByClaimedTickBatchIdOrderByIdAsc(anyString()))
        .thenReturn(List.of(followup));

    service.drainRemoteFollowups(
        1L,
        2L,
        new TickQueueControlService.OwnershipSnapshot("region-a", 1L, "fence-a", false, 0L));

    ArgumentCaptor<TickBatch> batchCaptor = ArgumentCaptor.forClass(TickBatch.class);
    verify(tickBatchRepository, org.mockito.Mockito.atLeastOnce()).save(batchCaptor.capture());
    TickBatch stagedBatch =
        batchCaptor.getAllValues().stream()
            .filter(batch -> "REMOTE_FOLLOWUP_DRAIN".equals(batch.getBatchSource()))
            .findFirst()
            .orElseThrow();
    org.junit.jupiter.api.Assertions.assertFalse(
        stagedBatch.getSelectedWorkManifestJson().contains("\"worldSlug\""));
    org.junit.jupiter.api.Assertions.assertFalse(
        stagedBatch.getSelectedWorkManifestJson().contains("\"realmSlug\""));
    org.junit.jupiter.api.Assertions.assertFalse(
        stagedBatch.getSelectedWorkManifestJson().contains("\"pointerVersion\""));
  }

  private static GameplayCommand gameplayCommand(String commandId) {
    var command = new GameplayCommand();
    command.setCommandId(commandId);
    command.setCommandText("look");
    command.setSanitizedCommandText("look");
    command.setAttemptCount(1);
    command.setExecutionOutcome("STAGED");
    command.setGameplayResult("PENDING");
    return command;
  }

  private static GameplayCommand copyGameplayCommand(GameplayCommand source) {
    var copy = new GameplayCommand();
    copy.setCommandId(source.getCommandId());
    copy.setExecutionOutcome(source.getExecutionOutcome());
    copy.setQueueSourceKind(source.getQueueSourceKind());
    copy.setQueueSourceState(source.getQueueSourceState());
    return copy;
  }

  private static RuntimeRegionStatus runtimeOwnership(
      Long tenantId,
      Long gameInstanceId,
      String regionId,
      long regionEpoch,
      String executorFence,
      boolean paused) {
    var status = new RuntimeRegionStatus();
    status.setTenantId(tenantId);
    status.setGameInstanceId(gameInstanceId);
    status.setRegionId(regionId);
    status.setRegionEpoch(regionEpoch);
    status.setExecutorFence(executorFence);
    status.setPaused(paused);
    status.setUpdatedAt(Instant.parse("2026-04-19T00:00:00Z"));
    return status;
  }

  @SuppressWarnings("unchecked")
  private static List<TickQueuedCommandEnvelope> parseEntries(
      TickStagingService service, List<Object> rawEntries) {
    try {
      var parseMethod =
          TickStagingService.class.getDeclaredMethod("parseQueuedCommand", String.class);
      parseMethod.setAccessible(true);
      List<TickQueuedCommandEnvelope> entries = new ArrayList<>();
      for (Object rawEntry : rawEntries) {
        entries.add((TickQueuedCommandEnvelope) parseMethod.invoke(service, rawEntry.toString()));
      }
      return List.copyOf(entries);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to parse queued entries", e);
    }
  }

  private static String replayManifestDigest(TickStagingService service, List<Object> rawEntries) {
    String manifest = replayManifestJson(service, rawEntries);
    return shortHash(service, manifest);
  }

  private static String replayManifestJson(TickStagingService service, List<Object> rawEntries) {
    try {
      var selectionsMethod =
          TickStagingService.class.getDeclaredMethod("commandSelections", List.class);
      selectionsMethod.setAccessible(true);
      Object selections = selectionsMethod.invoke(service, parseEntries(service, rawEntries));
      var manifestMethod =
          TickStagingService.class.getDeclaredMethod(
              "selectedWorkManifest", String.class, List.class);
      manifestMethod.setAccessible(true);
      return (String) manifestMethod.invoke(service, "region-a", selections);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to compute replay manifest json", e);
    }
  }

  private static String shortHash(TickStagingService service, String value) {
    try {
      var hashMethod = TickStagingService.class.getDeclaredMethod("shortHash", String.class);
      hashMethod.setAccessible(true);
      return (String) hashMethod.invoke(service, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to compute short hash", e);
    }
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to set field " + fieldName, e);
    }
  }
}
