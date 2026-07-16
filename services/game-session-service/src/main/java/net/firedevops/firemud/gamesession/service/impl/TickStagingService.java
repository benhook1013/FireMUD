package net.firedevops.firemud.gamesession.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.entity.TickBatch;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import net.firedevops.firemud.gamesession.repository.GameplayCommandRepository;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.repository.TickBatchRepository;
import net.firedevops.firemud.gamesession.repository.TickEffectRepository;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.RemoteFollowupDrainService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
final class TickStagingService {
  private static final Logger logger = LoggingUtil.getLogger(TickStagingService.class);

  private final RedisTemplate<String, Object> redisTemplate;
  private final GameplayCommandRepository gameplayCommandRepository;
  private final RemoteFollowupRepository remoteFollowupRepository;
  private final TickBatchRepository tickBatchRepository;
  private final TickEffectRepository tickEffectRepository;
  private final RemoteFollowupDrainService remoteFollowupDrainService;
  private final TickQueueControlService tickQueueControlService;
  private final TickBatchExecutionService tickBatchExecutionService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Value("${game.remote-followups.max-per-tick:16}")
  private int maxRemoteFollowupsPerTick;

  TickStagingService(
      RedisTemplate<String, Object> redisTemplate,
      GameplayCommandRepository gameplayCommandRepository,
      RemoteFollowupRepository remoteFollowupRepository,
      TickBatchRepository tickBatchRepository,
      TickEffectRepository tickEffectRepository,
      RemoteFollowupDrainService remoteFollowupDrainService,
      TickQueueControlService tickQueueControlService,
      TickBatchExecutionService tickBatchExecutionService) {
    this.redisTemplate = redisTemplate;
    this.gameplayCommandRepository = gameplayCommandRepository;
    this.remoteFollowupRepository = remoteFollowupRepository;
    this.tickBatchRepository = tickBatchRepository;
    this.tickEffectRepository = tickEffectRepository;
    this.remoteFollowupDrainService = remoteFollowupDrainService;
    this.tickQueueControlService = tickQueueControlService;
    this.tickBatchExecutionService = tickBatchExecutionService;
  }

  void drainRemoteFollowups(
      Long tenantId, Long gameInstanceId, TickQueueControlService.OwnershipSnapshot ownership) {
    String tickBatchId = "tb-" + UUID.randomUUID();
    RemoteFollowupDrainService.ClaimOutcome claimOutcome =
        remoteFollowupDrainService.claimDueFollowups(
            tenantId,
            ownership.regionId(),
            ownership.lastCommittedTickId() + 1L,
            tickBatchId,
            maxRemoteFollowupsPerTick);
    if (claimOutcome.claimedCount() <= 0) {
      return;
    }
    List<net.firedevops.firemud.gamesession.entity.RemoteFollowup> claimedFollowups =
        remoteFollowupRepository.findByClaimedTickBatchIdOrderByIdAsc(tickBatchId);
    TickBatch batch = null;
    try {
      batch =
          createRemoteFollowupBatch(
              tickBatchId, tenantId, gameInstanceId, ownership, claimedFollowups);
      tickBatchExecutionService.requireCurrentOwnership(batch, false);
      tickBatchExecutionService.markRemoteFollowupBatchDrained(batch);
      tickBatchExecutionService.executeDurableEffects(tenantId, gameInstanceId);
    } catch (Exception ex) {
      if (batch != null) {
        tickBatchExecutionService.markRemoteFollowupBatchAbandoned(
            batch, failureCode(ex), ex.getMessage());
      } else {
        remoteFollowupDrainService.releaseClaimedFollowups(
            tickBatchId, failureCode(ex), ex.getMessage());
      }
      throw ex;
    }
  }

  List<TickQueuedCommandEnvelope> readPendingEntries(Long tenantId, Long queueTargetId) {
    List<Object> rawEntries =
        redisTemplate
            .opsForList()
            .range(tickQueueControlService.pendingKey(tenantId, queueTargetId), 0, -1);
    if (rawEntries == null || rawEntries.isEmpty()) {
      return List.of();
    }
    List<TickQueuedCommandEnvelope> entries = new ArrayList<>(rawEntries.size());
    for (Object rawEntry : rawEntries) {
      if (rawEntry == null) {
        continue;
      }
      entries.add(parseQueuedCommand(rawEntry.toString()));
    }
    return List.copyOf(entries);
  }

  TickBatch resolveReplayBatch(
      Long tenantId,
      Long gameInstanceId,
      List<TickQueuedCommandEnvelope> replayEntries,
      TickQueueControlService.OwnershipSnapshot ownership) {
    Optional<TickBatch> existing =
        tickBatchRepository.findFirstByTenantIdAndGameInstanceIdAndStatusOrderByStagedAtDesc(
            tenantId, gameInstanceId, "STAGED");
    if (existing.isEmpty()) {
      return createBatch(
          "PENDING_REPLAY", tenantId, gameInstanceId, false, ownership, replayEntries);
    }
    TickBatch batch = existing.orElseThrow();
    String replayManifest =
        selectedWorkManifest(ownership.regionId(), commandSelections(replayEntries));
    String replayDigest = shortHash(replayManifest);
    if (replayDigest.equals(batch.getSelectedWorkManifestDigest())) {
      return batch;
    }
    logger.warn(
        "Replay manifest mismatch for staged batch tickBatchId={} tenantId={} gameInstanceId={} expectedDigest={} actualDigest={}",
        batch.getTickBatchId(),
        tenantId,
        gameInstanceId,
        batch.getSelectedWorkManifestDigest(),
        replayDigest);
    List<TickQueuedCommandEnvelope> sealedEntries = loadSealedReplayEntries(batch);
    tickBatchExecutionService.restorePendingProjection(
        tenantId, gameInstanceId, replayEntries, sealedEntries);
    tickBatchExecutionService.markBatchManifestMismatch(batch, sealedEntries, replayDigest);
    return createBatch("PENDING_REPLAY", tenantId, gameInstanceId, false, ownership, sealedEntries);
  }

  TickBatch createBatch(
      String batchSource,
      Long tenantId,
      Long gameInstanceId,
      boolean requiresSoloTick,
      TickQueueControlService.OwnershipSnapshot ownership,
      List<TickQueuedCommandEnvelope> entries) {
    Instant now = Instant.now();
    requireDurableCommandIdentifiers(entries);
    List<CommandSelection> selections = commandSelections(entries);
    TickBatch batch = new TickBatch();
    batch.setTickBatchId("tb-" + UUID.randomUUID());
    batch.setTenantId(tenantId);
    batch.setGameInstanceId(gameInstanceId);
    batch.setRegionId(ownership.regionId());
    batch.setRegionEpoch(ownership.regionEpoch());
    batch.setExecutorFence(ownership.executorFence());
    batch.setBatchSource(batchSource);
    batch.setStatus("STAGED");
    batch.setRequiresSoloTick(requiresSoloTick);
    batch.setCommandCount(entries.size());
    batch.setExpectedEffectCount(entries.size());
    String selectedWorkManifest = selectedWorkManifest(ownership.regionId(), selections);
    batch.setSelectedWorkManifestJson(selectedWorkManifest);
    batch.setSelectedWorkManifestDigest(shortHash(selectedWorkManifest));
    batch.setStagedAt(now);
    TickBatch savedBatch = tickBatchRepository.save(batch);
    persistEffects(savedBatch, gameInstanceId, now, selections);
    bumpGameplayCommandAttempts(entries, now);
    logger.info(
        "Staged durable tick batch tickBatchId={} tenantId={} gameInstanceId={} source={} commandCount={}",
        savedBatch.getTickBatchId(),
        tenantId,
        gameInstanceId,
        batchSource,
        entries.size());
    return savedBatch;
  }

  private TickQueuedCommandEnvelope parseQueuedCommand(String payload) {
    String[] parts = payload.split("\\|", 3);
    if (parts.length < 3) {
      return new TickQueuedCommandEnvelope(false, null, payload);
    }
    boolean requiresSoloTick = "S".equals(parts[0]);
    String commandId = "-".equals(parts[1]) || parts[1].isBlank() ? null : parts[1];
    return new TickQueuedCommandEnvelope(requiresSoloTick, commandId, parts[2]);
  }

  private List<TickQueuedCommandEnvelope> loadSealedReplayEntries(TickBatch batch) {
    try {
      JsonNode root = objectMapper.readTree(batch.getSelectedWorkManifestJson());
      JsonNode items = root.path("items");
      if (!items.isArray() || items.isEmpty()) {
        throw new IllegalStateException(
            "Sealed replay manifest is missing item entries for tickBatchId="
                + batch.getTickBatchId());
      }
      List<SealedReplayCommand> sealedCommands = new ArrayList<>();
      for (JsonNode item : items) {
        String commandId = item.path("commandId").asText("").trim();
        if (commandId.isBlank()) {
          throw new IllegalStateException(
              "Sealed replay manifest requires commandId for tickBatchId="
                  + batch.getTickBatchId());
        }
        sealedCommands.add(
            new SealedReplayCommand(
                commandId,
                item.path("requiresSoloTick").asBoolean(false),
                sealedQueueSource(item, batch.getTickBatchId())));
      }
      Map<String, GameplayCommand> commandsById =
          gameplayCommandRepository
              .findByCommandIdIn(
                  sealedCommands.stream().map(SealedReplayCommand::commandId).toList())
              .stream()
              .collect(
                  java.util.stream.Collectors.toMap(GameplayCommand::getCommandId, cmd -> cmd));
      List<TickQueuedCommandEnvelope> entries = new ArrayList<>(sealedCommands.size());
      for (SealedReplayCommand sealedCommand : sealedCommands) {
        String commandId = sealedCommand.commandId();
        GameplayCommand command = commandsById.get(commandId);
        if (command == null) {
          throw new IllegalStateException(
              "Sealed replay manifest references missing gameplay command "
                  + commandId
                  + " for tickBatchId="
                  + batch.getTickBatchId());
        }
        entries.add(
            new TickQueuedCommandEnvelope(
                sealedCommand.requiresSoloTick(),
                commandId,
                command.getCommandText(),
                sealedCommand.sealedQueueSource()));
      }
      return List.copyOf(entries);
    } catch (java.io.IOException ex) {
      throw new IllegalStateException(
          "Failed to restore sealed replay manifest for tickBatchId=" + batch.getTickBatchId(), ex);
    }
  }

  private void persistEffects(
      TickBatch batch, Long gameInstanceId, Instant stagedAt, List<CommandSelection> selections) {
    if (selections.isEmpty()) {
      return;
    }
    List<TickEffect> effects = new ArrayList<>(selections.size());
    for (CommandSelection selection : selections) {
      TickQueuedCommandEnvelope entry = selection.entry();
      TickEffect effect = new TickEffect();
      effect.setEffectId(effectId(batch.getTickBatchId(), selection.effectKey()));
      effect.setTickBatchId(batch.getTickBatchId());
      effect.setCommandId(entry.commandId());
      effect.setEffectKey(selection.effectKey());
      effect.setEffectType("GAMEPLAY_COMMAND");
      effect.setTargetAggregate(effectTargetAggregate(gameInstanceId, selection.command()));
      effect.setStatus("STAGED");
      effect.setStagedAt(stagedAt);
      effects.add(effect);
    }
    tickEffectRepository.saveAll(effects);
  }

  private TickBatch createRemoteFollowupBatch(
      String tickBatchId,
      Long tenantId,
      Long gameInstanceId,
      TickQueueControlService.OwnershipSnapshot ownership,
      List<net.firedevops.firemud.gamesession.entity.RemoteFollowup> followups) {
    Instant now = Instant.now();
    TickBatch batch = new TickBatch();
    batch.setTickBatchId(tickBatchId);
    batch.setTenantId(tenantId);
    batch.setGameInstanceId(gameInstanceId);
    batch.setRegionId(ownership.regionId());
    batch.setRegionEpoch(ownership.regionEpoch());
    batch.setExecutorFence(ownership.executorFence());
    batch.setBatchSource("REMOTE_FOLLOWUP_DRAIN");
    batch.setStatus("STAGED");
    batch.setRequiresSoloTick(false);
    batch.setCommandCount(0);
    batch.setExpectedEffectCount(followups.size());
    String selectedWorkManifest = selectedRemoteFollowupManifest(ownership.regionId(), followups);
    batch.setSelectedWorkManifestJson(selectedWorkManifest);
    batch.setSelectedWorkManifestDigest(shortHash(selectedWorkManifest));
    batch.setStagedAt(now);
    TickBatch savedBatch = tickBatchRepository.save(batch);
    persistRemoteFollowupEffects(savedBatch, now, followups);
    logger.info(
        "Staged durable remote followup batch tickBatchId={} tenantId={} gameInstanceId={} followupCount={}",
        savedBatch.getTickBatchId(),
        tenantId,
        gameInstanceId,
        followups.size());
    return savedBatch;
  }

  private void persistRemoteFollowupEffects(
      TickBatch batch,
      Instant stagedAt,
      List<net.firedevops.firemud.gamesession.entity.RemoteFollowup> followups) {
    if (followups.isEmpty()) {
      return;
    }
    List<TickEffect> effects = new ArrayList<>(followups.size());
    for (net.firedevops.firemud.gamesession.entity.RemoteFollowup followup : followups) {
      TickEffect effect = new TickEffect();
      effect.setEffectId(effectId(batch.getTickBatchId(), followup.getFollowupId()));
      effect.setTickBatchId(batch.getTickBatchId());
      effect.setCommandId(null);
      effect.setEffectKey(followup.getFollowupId());
      effect.setEffectType("REMOTE_FOLLOWUP");
      effect.setTargetAggregate(remoteFollowupTargetAggregate(followup));
      effect.setStatus("STAGED");
      effect.setStagedAt(stagedAt);
      effects.add(effect);
    }
    tickEffectRepository.saveAll(effects);
  }

  private static String remoteFollowupTargetAggregate(
      net.firedevops.firemud.gamesession.entity.RemoteFollowup followup) {
    if (followup.getClaimTargetAggregate() != null
        && !followup.getClaimTargetAggregate().isBlank()) {
      return followup.getClaimTargetAggregate();
    }
    if (followup.getTargetEntityId() != null && !followup.getTargetEntityId().isBlank()) {
      return "entity:" + followup.getTargetEntityId();
    }
    return "game-instance:" + followup.getTargetGameInstanceId();
  }

  private static String effectTargetAggregate(Long gameInstanceId, GameplayCommand command) {
    if (command != null) {
      if (command.getCharacterId() != null && command.getCharacterId() > 0) {
        return "character:" + command.getCharacterId();
      }
      if (command.getTargetEntityId() != null && !command.getTargetEntityId().isBlank()) {
        return "entity:" + command.getTargetEntityId();
      }
    }
    return "game-instance:" + gameInstanceId;
  }

  private void bumpGameplayCommandAttempts(
      List<TickQueuedCommandEnvelope> entries, Instant attemptedAt) {
    List<GameplayCommand> commands = loadCommands(entries);
    if (commands.isEmpty()) {
      return;
    }
    for (GameplayCommand command : commands) {
      command.setAttemptCount(command.getAttemptCount() + 1);
      command.setLastAttemptAt(attemptedAt);
    }
    gameplayCommandRepository.saveAll(commands);
  }

  private List<CommandSelection> commandSelections(List<TickQueuedCommandEnvelope> entries) {
    if (entries.isEmpty()) {
      return List.of();
    }
    requireDurableCommandIdentifiers(entries);
    Map<String, GameplayCommand> commandsById =
        loadCommands(entries).stream()
            .collect(java.util.stream.Collectors.toMap(GameplayCommand::getCommandId, cmd -> cmd));
    List<CommandSelection> selections = new ArrayList<>(entries.size());
    for (int index = 0; index < entries.size(); index++) {
      TickQueuedCommandEnvelope entry = entries.get(index);
      GameplayCommand command =
          entry.commandId() == null || entry.commandId().isBlank()
              ? null
              : commandsById.get(entry.commandId());
      String effectKey = effectKey(entry, index);
      selections.add(
          new CommandSelection(
              entry,
              command,
              selectionSourceKind(command, entry),
              selectionSourceState(command, entry),
              selectionSourceOrdinal(command, entry, index),
              selectionSourceDueTickId(command, entry),
              selectionSourceDueAtMs(command, entry),
              effectKey,
              shortHash(entry.command())));
    }
    return List.copyOf(selections);
  }

  private long selectionSourceOrdinal(
      GameplayCommand command, TickQueuedCommandEnvelope entry, int fallbackIndex) {
    if (entry.sealedQueueSource() != null && entry.sealedQueueSource().sourceOrdinal() > 0) {
      return entry.sealedQueueSource().sourceOrdinal();
    }
    if (command != null
        && command.getQueueSourceOrdinal() != null
        && command.getQueueSourceOrdinal() > 0) {
      return command.getQueueSourceOrdinal();
    }
    if (timerOriginSelection(command)
        && command.getOriginSourceOrdinal() != null
        && command.getOriginSourceOrdinal() > 0) {
      return command.getOriginSourceOrdinal();
    }
    if (command != null && command.getEnqueueSeq() != null && command.getEnqueueSeq() > 0) {
      return command.getEnqueueSeq();
    }
    return fallbackIndex;
  }

  private List<GameplayCommand> loadCommands(List<TickQueuedCommandEnvelope> entries) {
    List<String> commandIds =
        entries.stream()
            .map(TickQueuedCommandEnvelope::commandId)
            .filter(commandId -> commandId != null && !commandId.isBlank())
            .distinct()
            .toList();
    if (commandIds.isEmpty()) {
      return List.of();
    }
    return gameplayCommandRepository.findByCommandIdIn(commandIds);
  }

  private void requireDurableCommandIdentifiers(List<TickQueuedCommandEnvelope> entries) {
    for (TickQueuedCommandEnvelope entry : entries) {
      if (entry.commandId() == null || entry.commandId().isBlank()) {
        throw new IllegalStateException(
            "Durable tick batching requires linked command ids for all queued commands");
      }
    }
  }

  private String failureCode(Exception ex) {
    return ex instanceof TickQueueControlService.StaleOwnershipException
        ? "STALE_EXECUTOR_FENCE"
        : "ROLLBACK_REQUEUED";
  }

  private String effectKey(TickQueuedCommandEnvelope entry, int index) {
    if (entry.commandId() != null && !entry.commandId().isBlank()) {
      return "command:" + entry.commandId();
    }
    return "command-text:" + shortHash(entry.command()) + ":slot:" + index;
  }

  private String effectId(String tickBatchId, String effectKey) {
    return "tfx-" + shortHash(tickBatchId + "|" + effectKey);
  }

  private String selectedWorkManifest(String regionId, List<CommandSelection> selections) {
    StringBuilder builder = new StringBuilder();
    builder
        .append("{\"version\":1,\"source\":\"GAMEPLAY_COMMAND_QUEUE\",\"regionId\":\"")
        .append(jsonEscape(regionId))
        .append("\",\"items\":[");
    for (int index = 0; index < selections.size(); index++) {
      if (index > 0) {
        builder.append(',');
      }
      CommandSelection selection = selections.get(index);
      TickQueuedCommandEnvelope entry = selection.entry();
      GameplayCommand command = selection.command();
      builder
          .append("{\"sourceKind\":\"")
          .append(selection.sourceKind())
          .append("\",\"sourceOrdinal\":")
          .append(selection.sourceOrdinal())
          .append(",\"sourceState\":\"")
          .append(selection.sourceState())
          .append("\"")
          .append(",\"effectKey\":\"")
          .append(jsonEscape(selection.effectKey()))
          .append("\",\"commandId\":");
      if (entry.commandId() == null || entry.commandId().isBlank()) {
        builder.append("null");
      } else {
        builder.append('"').append(jsonEscape(entry.commandId())).append('"');
      }
      appendJsonStringField(
          builder, "sourceType", command == null ? null : command.getSourceType());
      appendJsonStringField(
          builder,
          "automationDispatchId",
          command == null ? null : command.getAutomationDispatchId());
      appendJsonStringField(
          builder,
          "automationWorkItemId",
          command == null ? null : command.getAutomationWorkItemId());
      appendJsonStringField(builder, "scriptId", command == null ? null : command.getScriptId());
      appendJsonStringField(
          builder, "scriptPatchVersion", command == null ? null : command.getScriptPatchVersion());
      appendJsonStringField(builder, "pluginId", command == null ? null : command.getPluginId());
      appendJsonStringField(
          builder, "pluginVersionId", command == null ? null : command.getPluginVersionId());
      appendJsonStringField(
          builder, "targetEntityId", command == null ? null : command.getTargetEntityId());
      appendJsonStringField(builder, "regionId", command == null ? null : command.getRegionId());
      appendJsonNumberField(
          builder, "regionEpoch", command == null ? null : command.getRegionEpoch());
      appendJsonNumberField(
          builder, "enqueueSeq", command == null ? null : command.getEnqueueSeq());
      appendJsonNumberField(builder, "dueTickId", command == null ? null : command.getDueTickId());
      appendJsonNumberField(builder, "queueSourceDueTickId", selection.sourceDueTickId());
      appendJsonNumberField(builder, "queueSourceDueAtMs", selection.sourceDueAtMs());
      appendJsonStringField(
          builder, "originSourceKind", command == null ? null : command.getOriginSourceKind());
      appendJsonStringField(
          builder, "originSourceState", command == null ? null : command.getOriginSourceState());
      appendJsonNumberField(
          builder,
          "originSourceOrdinal",
          command == null ? null : command.getOriginSourceOrdinal());
      appendJsonNumberField(
          builder,
          "originSourceDueTickId",
          command == null ? null : command.getOriginSourceDueTickId());
      appendJsonNumberField(
          builder,
          "originSourceDueAtMs",
          command == null ? null : command.getOriginSourceDueAtMs());
      appendJsonStringField(
          builder, "playableStateScope", command == null ? null : command.getPlayableStateScope());
      appendRoutingBundleFields(
          builder,
          command == null ? null : command.getWorldSlug(),
          command == null ? null : command.getRealmSlug(),
          command == null ? null : command.getPointerVersion());
      builder
          .append(",\"requiresSoloTick\":")
          .append(entry.requiresSoloTick())
          .append(",\"commandDigest\":\"")
          .append(selection.commandDigest())
          .append("\"}");
    }
    builder.append("]}");
    return builder.toString();
  }

  private String selectedRemoteFollowupManifest(
      String regionId, List<net.firedevops.firemud.gamesession.entity.RemoteFollowup> followups) {
    StringBuilder builder = new StringBuilder();
    builder
        .append("{\"version\":1,\"source\":\"REMOTE_FOLLOWUP_QUEUE\",\"regionId\":\"")
        .append(jsonEscape(regionId))
        .append("\",\"items\":[");
    for (int index = 0; index < followups.size(); index++) {
      if (index > 0) {
        builder.append(',');
      }
      net.firedevops.firemud.gamesession.entity.RemoteFollowup followup = followups.get(index);
      builder
          .append("{\"sourceKind\":\"")
          .append(jsonEscape(remoteFollowupQueueSourceKind(followup)))
          .append("\"")
          .append(",\"sourceOrdinal\":")
          .append(remoteFollowupSourceOrdinal(followup))
          .append(",\"sourceState\":\"")
          .append(jsonEscape(remoteFollowupQueueSourceState(followup)))
          .append("\"")
          .append(",\"effectKey\":\"")
          .append(jsonEscape(followup.getFollowupId()))
          .append("\"");
      appendJsonNumberField(
          builder, "sourceDueTickId", remoteFollowupQueueSourceDueTickId(followup));
      appendJsonNumberField(builder, "sourceDueAtMs", followup.getQueueSourceDueAtMs());
      appendJsonStringField(builder, "followupId", followup.getFollowupId());
      appendJsonStringField(builder, "originRegionId", followup.getOriginRegionId());
      appendJsonNumberField(builder, "originRegionEpoch", followup.getOriginRegionEpoch());
      appendJsonStringField(builder, "targetRegionId", followup.getTargetRegionId());
      appendJsonNumberField(builder, "targetRegionEpoch", followup.getTargetRegionEpoch());
      appendJsonNumberField(builder, "dueTickId", followup.getDueTickId());
      appendJsonStringField(builder, "targetEntityId", followup.getTargetEntityId());
      appendJsonStringField(builder, "claimTargetAggregate", followup.getClaimTargetAggregate());
      appendJsonStringField(builder, "commandId", followup.getCommandId());
      appendJsonStringField(builder, "automationDispatchId", followup.getAutomationDispatchId());
      appendJsonStringField(builder, "automationWorkItemId", followup.getAutomationWorkItemId());
      appendJsonStringField(builder, "scriptId", followup.getScriptId());
      appendJsonStringField(builder, "scriptPatchVersion", followup.getScriptPatchVersion());
      appendJsonStringField(builder, "pluginId", followup.getPluginId());
      appendJsonStringField(builder, "pluginVersionId", followup.getPluginVersionId());
      appendJsonStringField(builder, "playableStateScope", followup.getPlayableStateScope());
      appendRoutingBundleFields(
          builder, followup.getWorldSlug(), followup.getRealmSlug(), followup.getPointerVersion());
      appendJsonStringField(builder, "originSourceKind", followup.getOriginSourceKind());
      appendJsonStringField(builder, "originSourceState", followup.getOriginSourceState());
      appendJsonNumberField(builder, "originSourceOrdinal", followup.getOriginSourceOrdinal());
      appendJsonNumberField(builder, "originSourceDueTickId", followup.getOriginSourceDueTickId());
      appendJsonNumberField(builder, "originSourceDueAtMs", followup.getOriginSourceDueAtMs());
      appendJsonStringField(builder, "payloadKind", followup.getPayloadKind());
      appendJsonStringField(builder, "requestedCommand", followup.getRequestedCommand());
      appendJsonBooleanField(builder, "requiresSoloTick", followup.isRequiresSoloTick());
      appendJsonStringField(builder, "payloadJson", followup.getPayloadJson());
      builder.append('}');
    }
    builder.append("]}");
    return builder.toString();
  }

  private long remoteFollowupSourceOrdinal(
      net.firedevops.firemud.gamesession.entity.RemoteFollowup followup) {
    return followup.getQueueSourceOrdinal() != null && followup.getQueueSourceOrdinal() > 0L
        ? followup.getQueueSourceOrdinal()
        : followup.getClaimOrdinal() == null || followup.getClaimOrdinal() <= 0L
            ? followup.getDueTickId()
            : followup.getClaimOrdinal();
  }

  private String remoteFollowupQueueSourceKind(
      net.firedevops.firemud.gamesession.entity.RemoteFollowup followup) {
    return followup.getQueueSourceKind() == null || followup.getQueueSourceKind().isBlank()
        ? RemoteFollowupRuntimeServiceImpl.FOLLOWUP_QUEUE_SOURCE_KIND
        : followup.getQueueSourceKind();
  }

  private static void appendRoutingBundleFields(
      StringBuilder builder, String worldSlug, String realmSlug, Long pointerVersion) {
    GameplayAdmissionPointerSnapshots.RoutingBundle routingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            worldSlug, realmSlug, pointerVersion);
    if (routingBundle == null) {
      return;
    }
    appendJsonStringField(builder, "worldSlug", routingBundle.worldSlug());
    appendJsonStringField(builder, "realmSlug", routingBundle.realmSlug());
    appendJsonNumberField(builder, "pointerVersion", routingBundle.pointerVersion());
  }

  private String remoteFollowupQueueSourceState(
      net.firedevops.firemud.gamesession.entity.RemoteFollowup followup) {
    return followup.getQueueSourceState() == null || followup.getQueueSourceState().isBlank()
        ? RemoteFollowupRuntimeServiceImpl.FOLLOWUP_QUEUE_SOURCE_STATE_CLAIMED
        : followup.getQueueSourceState();
  }

  private Long remoteFollowupQueueSourceDueTickId(
      net.firedevops.firemud.gamesession.entity.RemoteFollowup followup) {
    Long queueSourceDueTickId = followup.getQueueSourceDueTickId();
    return queueSourceDueTickId != null
        ? queueSourceDueTickId
        : Long.valueOf(followup.getDueTickId());
  }

  private String selectionSourceKind(GameplayCommand command, TickQueuedCommandEnvelope entry) {
    if (command != null && "RETRY_QUEUED".equals(command.getExecutionOutcome())) {
      return "GAMEPLAY_RETRY";
    }
    if (entry.sealedQueueSource() != null) {
      return entry.sealedQueueSource().sourceKind();
    }
    if (timerOriginSelection(command)) {
      return "SCHEDULE_TIMER";
    }
    return "GAMEPLAY_COMMAND";
  }

  private String selectionSourceState(GameplayCommand command, TickQueuedCommandEnvelope entry) {
    if (command != null && "RETRY_QUEUED".equals(command.getExecutionOutcome())) {
      return "REDIS_RETRY_CLAIMED";
    }
    if (entry.sealedQueueSource() != null) {
      return entry.sealedQueueSource().sourceState();
    }
    if (timerOriginSelection(command)
        && command.getOriginSourceState() != null
        && !command.getOriginSourceState().isBlank()) {
      return command.getOriginSourceState();
    }
    return "REDIS_PENDING_CLAIMED";
  }

  private Long selectionSourceDueTickId(GameplayCommand command, TickQueuedCommandEnvelope entry) {
    if (entry.sealedQueueSource() != null
        && entry.sealedQueueSource().sourceDueTickId() != null
        && entry.sealedQueueSource().sourceDueTickId() > 0) {
      return entry.sealedQueueSource().sourceDueTickId();
    }
    if (timerOriginSelection(command)
        && command.getOriginSourceDueTickId() != null
        && command.getOriginSourceDueTickId() > 0) {
      return command.getOriginSourceDueTickId();
    }
    return command == null ? null : command.getDueTickId();
  }

  private Long selectionSourceDueAtMs(GameplayCommand command, TickQueuedCommandEnvelope entry) {
    if (entry.sealedQueueSource() != null
        && entry.sealedQueueSource().sourceDueAtMs() != null
        && entry.sealedQueueSource().sourceDueAtMs() > 0) {
      return entry.sealedQueueSource().sourceDueAtMs();
    }
    if (timerOriginSelection(command)
        && command.getOriginSourceDueAtMs() != null
        && command.getOriginSourceDueAtMs() > 0) {
      return command.getOriginSourceDueAtMs();
    }
    return null;
  }

  private boolean timerOriginSelection(GameplayCommand command) {
    return command != null
        && !"RETRY_QUEUED".equals(command.getExecutionOutcome())
        && "SCHEDULE_TIMER".equals(command.getOriginSourceKind());
  }

  private TickQueuedCommandEnvelope.SealedQueueSource sealedQueueSource(
      JsonNode item, String tickBatchId) {
    String sourceKind = item.path("sourceKind").asText("").trim();
    String sourceState = item.path("sourceState").asText("").trim();
    long sourceOrdinal = item.path("sourceOrdinal").asLong(0L);
    if (sourceKind.isBlank() || sourceState.isBlank() || sourceOrdinal <= 0) {
      throw new IllegalStateException(
          "Sealed replay manifest is missing queue-source truth for tickBatchId=" + tickBatchId);
    }
    return new TickQueuedCommandEnvelope.SealedQueueSource(
        sourceKind,
        sourceState,
        sourceOrdinal,
        positiveLongOrNull(item, "queueSourceDueTickId"),
        positiveLongOrNull(item, "queueSourceDueAtMs"));
  }

  private static Long positiveLongOrNull(JsonNode item, String fieldName) {
    long value = item.path(fieldName).asLong(0L);
    return value > 0 ? value : null;
  }

  private static void appendJsonStringField(StringBuilder builder, String fieldName, String value) {
    builder.append(",\"").append(fieldName).append("\":");
    if (value == null || value.isBlank()) {
      builder.append("null");
      return;
    }
    builder.append('"').append(jsonEscape(value)).append('"');
  }

  private static void appendJsonNumberField(StringBuilder builder, String fieldName, Number value) {
    builder.append(",\"").append(fieldName).append("\":");
    if (value == null) {
      builder.append("null");
      return;
    }
    builder.append(value);
  }

  private static void appendJsonBooleanField(
      StringBuilder builder, String fieldName, boolean value) {
    builder.append(",\"").append(fieldName).append("\":").append(value);
  }

  private static String jsonEscape(String value) {
    StringBuilder builder = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      switch (current) {
        case '"' -> builder.append("\\\"");
        case '\\' -> builder.append("\\\\");
        case '\b' -> builder.append("\\b");
        case '\f' -> builder.append("\\f");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        default -> {
          if (current < 0x20) {
            builder.append(String.format("\\u%04x", (int) current));
          } else {
            builder.append(current);
          }
        }
      }
    }
    return builder.toString();
  }

  private String shortHash(String value) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte current : bytes) {
        builder.append(String.format("%02x", current));
      }
      return builder.substring(0, 60);
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  private record CommandSelection(
      TickQueuedCommandEnvelope entry,
      GameplayCommand command,
      String sourceKind,
      String sourceState,
      long sourceOrdinal,
      Long sourceDueTickId,
      Long sourceDueAtMs,
      String effectKey,
      String commandDigest) {}

  private record SealedReplayCommand(
      String commandId,
      boolean requiresSoloTick,
      TickQueuedCommandEnvelope.SealedQueueSource sealedQueueSource) {}
}
