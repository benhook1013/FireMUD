package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Locale;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import net.firedevops.firemud.automationscripting.service.ScriptGameplayCommandHandoffService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueAutomationCommandIfAbsentResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators are framework-managed and retained internally")
public class ScriptGameplayCommandHandoffServiceImpl
    implements ScriptGameplayCommandHandoffService {
  private static final String STATUS_HANDOFF_IN_FLIGHT = "HANDOFF_IN_FLIGHT";
  private static final String STATUS_HANDED_OFF = "HANDED_OFF";
  private static final String STATUS_CANCELED = "CANCELED";
  private static final String STATUS_DEAD_LETTERED = "DEAD_LETTERED";

  private final GameSessionControlPlaneClient gameSessionClient;
  private final ScriptWorkItemRepository workItemRepository;
  private final ScriptEventAuditRepository auditRepository;
  private final AutomationAdmissionStateService automationAdmissionStateService;
  private final ScriptPatchInstanceRolloutProjectionService rolloutProjectionService;

  public ScriptGameplayCommandHandoffServiceImpl(
      GameSessionControlPlaneClient gameSessionClient,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      AutomationAdmissionStateService automationAdmissionStateService,
      ScriptPatchInstanceRolloutProjectionService rolloutProjectionService) {
    this.gameSessionClient = gameSessionClient;
    this.workItemRepository = workItemRepository;
    this.auditRepository = auditRepository;
    this.automationAdmissionStateService = automationAdmissionStateService;
    this.rolloutProjectionService = rolloutProjectionService;
  }

  @Override
  @Transactional
  public HandoffResult handoff(ScriptWorkItem workItem, EmittedCommand command) {
    requireWorkItem(workItem);
    requireCommand(command);
    if (isEpochAdvanced(workItem)) {
      cancelForRollbackEpochAdvance(workItem, Instant.now());
      return new HandoffResult(false, "rollback_epoch_advanced", "", "");
    }
    Instant now = Instant.now();
    workItem.setStatus(STATUS_HANDOFF_IN_FLIGHT);
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);

    EnqueueAutomationCommandIfAbsentResponse response =
        gameSessionClient.enqueueAutomationCommandIfAbsent(toRequest(workItem, command));
    HandoffResult result =
        new HandoffResult(
            response.getAccepted(),
            response.getAdmissionOutcome(),
            response.getCommandId(),
            response.hasError() ? response.getError().getCode() : "");
    applyOutcome(workItem, result, now);
    return result;
  }

  private boolean isEpochAdvanced(ScriptWorkItem workItem) {
    if (workItem.getGameInstanceId() == null || workItem.getGameInstanceId().isBlank()) {
      return false;
    }
    AutomationAdmissionStateService.AdmissionStateSummary state =
        automationAdmissionStateService.getState(
            workItem.getTenantId(), workItem.getGameInstanceId(), workItem.getRegionId());
    return state.admissionEpoch() != workItem.getAdmissionEpoch();
  }

  private void cancelForRollbackEpochAdvance(ScriptWorkItem workItem, Instant now) {
    workItem.setStatus(STATUS_CANCELED);
    workItem.setCancelReason("rollback_epoch_advanced");
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    updateAudit(workItem, "HANDOFF", "canceled", "rollback_epoch_advanced", now);
  }

  private EnqueueAutomationCommandIfAbsentRequest toRequest(
      ScriptWorkItem workItem, EmittedCommand command) {
    return EnqueueAutomationCommandIfAbsentRequest.newBuilder()
        .setTenantId(workItem.getTenantId())
        .setGameInstanceId(workItem.getGameInstanceId())
        .setRegionId(workItem.getRegionId())
        .setRegionEpoch(workItem.getRegionEpoch())
        .setDueTickId(command.dueTickId())
        .setAutomationDispatchId(dispatchId(workItem, command.ordinal()))
        .setAutomationWorkItemId(workItem.getId().toString())
        .setScriptId(workItem.getScriptId())
        .setScriptPatchVersion(workItem.getScriptPatchVersion())
        .setPluginId(normalize(workItem.getPluginId()))
        .setPluginVersionId(normalize(workItem.getPluginVersionId()))
        .setTargetEntityId(workItem.getEntityId())
        .setCommand(command.commandText())
        .setRequiresSoloTick(command.requiresSoloTick())
        .build();
  }

  private void applyOutcome(ScriptWorkItem workItem, HandoffResult result, Instant now) {
    if (result.accepted()) {
      workItem.setStatus(STATUS_HANDED_OFF);
      workItem.setUpdatedAt(now);
      workItemRepository.save(workItem);
      rolloutProjectionService.refreshForWorkItem(workItem);
      updateAudit(
          workItem,
          "HANDOFF",
          result.outcome().toLowerCase(Locale.ROOT),
          "game_session_accepted",
          now);
      return;
    }
    workItem.setStatus(STATUS_DEAD_LETTERED);
    workItem.setCancelReason(result.errorCode().isBlank() ? result.outcome() : result.errorCode());
    workItem.setUpdatedAt(now);
    workItemRepository.save(workItem);
    rolloutProjectionService.refreshForWorkItem(workItem);
    updateAudit(
        workItem,
        "HANDOFF",
        "handoff_failed",
        result.errorCode().isBlank() ? result.outcome() : result.errorCode(),
        now);
  }

  private void updateAudit(
      ScriptWorkItem workItem, String stage, String outcome, String reason, Instant now) {
    auditRepository
        .findByWorkItemId(workItem.getId())
        .ifPresent(
            audit -> {
              audit.setFinalStage(stage);
              audit.setFinalOutcome(outcome);
              audit.setFinalReason(reason);
              audit.setUpdatedAt(now);
              auditRepository.save(audit);
            });
  }

  private static String dispatchId(ScriptWorkItem workItem, int ordinal) {
    return "workItem:" + workItem.getId() + "#" + ordinal;
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }

  private static void requireWorkItem(ScriptWorkItem workItem) {
    if (workItem == null || workItem.getId() == null) {
      throw new IllegalArgumentException("persisted work_item is required");
    }
  }

  private static void requireCommand(EmittedCommand command) {
    if (command == null || command.commandText() == null || command.commandText().isBlank()) {
      throw new IllegalArgumentException("command_text is required");
    }
    if (command.ordinal() < 0) {
      throw new IllegalArgumentException("command ordinal must be non-negative");
    }
  }
}
