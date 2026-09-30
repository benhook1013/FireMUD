package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.config.ScriptOutputProperties;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.PluginRuntimeStateRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.AutomationQueueService;
import net.firedevops.firemud.automationscripting.service.AutomationQueueWorkItemPointer;
import net.firedevops.firemud.automationscripting.service.ScriptGameplayCommandHandoffService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchInstanceRolloutProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptPatchReadinessProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptQuotaClasses;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemExecutionService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.service.quota.ScriptDryRunCapacityService;
import net.firedevops.firemud.automationscripting.service.quota.ScriptReadinessCapacityService;
import net.firedevops.firemud.automationscripting.service.quota.ScriptTenantBudgetService;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class ScriptWorkItemExecutionServiceImplTest {
  @Test
  void requeuesWorkWhenRuntimeAuthorityIsUnavailable() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("UNAVAILABLE").build())
                .build());
    ScriptWorkItem item = workItem();
    item.setScriptPinEpoch(3L);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenReturn(item);

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            definitionRepository,
            handoffService,
            automationQueueService,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(item.getCancelReason()).isEqualTo("script_pin_authority_unavailable");
    verify(definitionRepository, Mockito.never())
        .findByTenantIdAndScriptVersionAndName(
            Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
    verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    verify(automationQueueService, Mockito.never()).enqueueWorkItem(Mockito.any());
  }

  @Test
  void authorityUnavailableRetryRecordsDurableEligibilityAndFirstOutcome() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("UNAVAILABLE").build())
                .build());
    ScriptWorkItem item = workItem();
    item.setScriptPinEpoch(3L);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    Instant before = Instant.now();
    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(item.getAuthorityUnavailableCount()).isEqualTo(1);
    assertThat(item.getAuthorityUnavailableSince()).isNotNull().isBetween(before, Instant.now());
    assertThat(item.getNextEligibleAt()).isNotNull().isAfterOrEqualTo(before.plusSeconds(30));
  }

  @Test
  void processesWorkAndClearsAuthorityUnavailableRetryStateAfterFenceRecoversPastAgeBound() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    ScriptWorkItem item = workItem();
    item.setScriptPinEpoch(3L);
    item.setAuthorityUnavailableSince(Instant.now().minus(Duration.ofMinutes(10).plusSeconds(1)));
    item.setAuthorityUnavailableCount(3);
    item.setNextEligibleAt(Instant.now().minus(Duration.ofSeconds(1)));
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"onCommand\": {\"emitCommands\": []}}");
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(new ScriptEventAudit()));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            definitionRepository,
            handoffService,
            null,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.completedCount()).isEqualTo(1);
    assertThat(item.getAuthorityUnavailableSince()).isNull();
    assertThat(item.getAuthorityUnavailableCount()).isZero();
    assertThat(item.getNextEligibleAt()).isNull();
    assertThat(item.getStatus()).isEqualTo("HANDED_OFF");
    Mockito.verify(definitionRepository)
        .findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1");
    verify(workItemRepository, Mockito.times(2)).save(item);
  }

  @Test
  void retryablePostEvaluationRuntimeFenceLeavesEvaluatedParentUnresolved() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    Instant outageSince = Instant.now().minus(Duration.ofSeconds(1));
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            runtimeStateResponse(),
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("UNAVAILABLE").build())
                .build());
    ScriptWorkItem item = workItem();
    item.setAuthorityUnavailableSince(outageSince);
    item.setAuthorityUnavailableCount(9);
    item.setNextEligibleAt(outageSince.plusSeconds(30));
    item.setStatus("PENDING_EVALUATION");
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[{\"commandText\":\"LOOK\"}]}");
    ScriptEventAudit audit = new ScriptEventAudit();
    Mockito.doAnswer(
            invocation -> {
              if (!"PENDING_EVALUATION".equals(item.getStatus())) {
                return List.of();
              }
              item.setStatus("EVALUATING");
              return List.of(item);
            })
        .when(workItemService)
        .claimPendingForEvaluation(1);
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));
    when(automationQueueService.drainIndexedWorkItemPointers(Mockito.anyInt(), Mockito.anyInt()))
        .thenReturn(List.of());
    item.setUpdatedAt(Instant.EPOCH);

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            definitionRepository,
            handoffService,
            automationQueueService,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);
    ScriptWorkItemExecutionService.ExecutionBatchResult retryScan =
        service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(retryScan.claimedCount()).isZero();
    assertThat(item.getStatus()).isEqualTo("EVALUATING");
    assertThat(item.getCancelReason())
        .isEqualTo("post_evaluation_reconciliation_required:script_pin_authority_unavailable");
    assertThat(item.getUpdatedAt()).isAfter(Instant.EPOCH);
    assertThat(item.getAuthorityUnavailableSince()).isEqualTo(outageSince);
    assertThat(item.getAuthorityUnavailableCount()).isEqualTo(9);
    assertThat(item.getNextEligibleAt()).isEqualTo(outageSince.plusSeconds(30));
    verify(workItemRepository).save(item);
    verify(definitionRepository, Mockito.times(1))
        .findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1");
    verify(automationQueueService, Mockito.never()).enqueueWorkItem(Mockito.any());
    verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    verify(auditRepository, Mockito.never()).save(audit);
    assertThat(audit.getFinalStage()).isNull();
    assertThat(audit.getFinalOutcome()).isNull();
  }

  @Test
  void requeuesUnexpectedGameSessionFailureWithBoundedAuthorityRetry() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenThrow(new IllegalStateException("unexpected client failure"));
    ScriptWorkItem item = workItem();
    item.setScriptPinEpoch(3L);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            definitionRepository,
            handoffService,
            null,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(item.getCancelReason()).isEqualTo("script_pin_authority_unavailable");
    assertThat(item.getAuthorityUnavailableCount()).isEqualTo(1);
    assertThat(item.getNextEligibleAt()).isNotNull();
    verify(workItemRepository).save(item);
    verify(definitionRepository, Mockito.never())
        .findByTenantIdAndScriptVersionAndName(
            Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
    verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
  }

  @Test
  void retriesWhenPluginRepositoryReportsTransientConnectionFailure() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PluginRuntimeStateRepository pluginRuntimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "7", "plugin-1"))
        .thenThrow(
            new DataAccessException(
                "plugin lookup unavailable", new SQLTransientConnectionException("offline")));
    ScriptWorkItem item = replayPluginWorkItem();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            gameSessionClient,
            pluginRuntimeStateRepository);

    Instant startedAt = Instant.now();
    service.processPendingWorkItems(1);
    Instant completedAt = Instant.now();

    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(item.getCancelReason()).isEqualTo("authority_unavailable");
    assertThat(item.getAuthorityUnavailableCount()).isZero();
    assertThat(item.getAuthorityUnavailableRetryCount()).isEqualTo(1);
    assertThat(item.getNextEligibleAt())
        .isNotNull()
        .isBetween(startedAt.plusSeconds(15), completedAt.plusSeconds(15));
    verify(pluginRuntimeStateRepository)
        .findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1");
  }

  @Test
  void retriesWhenSpringTranslatedPluginRepositoryFailureWrapsTransientConnection() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PluginRuntimeStateRepository pluginRuntimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "7", "plugin-1"))
        .thenThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "plugin lookup unavailable", new SQLTransientConnectionException("offline")));
    ScriptWorkItem item = replayPluginWorkItem();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            gameSessionClient,
            pluginRuntimeStateRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(item.getCancelReason()).isEqualTo("authority_unavailable");
    assertThat(item.getAuthorityUnavailableCount()).isZero();
    assertThat(item.getAuthorityUnavailableRetryCount()).isEqualTo(1);
    verify(pluginRuntimeStateRepository)
        .findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"57014", "55P03", "40001", "40P01"})
  void retriesPluginRepositoryTransientSqlStates(String sqlState) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PluginRuntimeStateRepository pluginRuntimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "7", "plugin-1"))
        .thenThrow(
            new DataAccessException(
                "plugin lookup unavailable", new SQLException("transient", sqlState)));
    ScriptWorkItem item = replayPluginWorkItem();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            gameSessionClient,
            pluginRuntimeStateRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(item.getAuthorityUnavailableCount()).isZero();
    assertThat(item.getAuthorityUnavailableRetryCount()).isEqualTo(1);
    verify(pluginRuntimeStateRepository)
        .findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1");
  }

  @Test
  void doesNotRetryUnsupportedTransactionSqlState() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PluginRuntimeStateRepository pluginRuntimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "7", "plugin-1"))
        .thenThrow(
            new DataAccessException(
                "plugin lookup unavailable", new SQLException("unsupported", "40003")));
    ScriptWorkItem item = replayPluginWorkItem();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptDefinitionRepository.class),
            Mockito.mock(ScriptGameplayCommandHandoffService.class),
            null,
            gameSessionClient,
            pluginRuntimeStateRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("plugin_lifecycle_evidence_unavailable");
    assertThat(item.getAuthorityUnavailableRetryCount()).isZero();
    assertThat(item.getAuthorityUnavailableCount()).isZero();
  }

  @Test
  void retriesSpringTransientDataAccessExceptionWithoutUsingGenericBudget() {
    assertPluginRepositoryFailureRetries(
        new TransientDataAccessResourceException("plugin lookup unavailable"));
  }

  @Test
  void retriesSpringRecoverableDataAccessExceptionWithoutUsingGenericBudget() {
    assertPluginRepositoryFailureRetries(
        new RecoverableDataAccessException("plugin lookup unavailable"));
  }

  private static void assertPluginRepositoryFailureRetries(Throwable failure) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PluginRuntimeStateRepository pluginRuntimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "7", "plugin-1"))
        .thenThrow(failure);
    ScriptWorkItem item = replayPluginWorkItem();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            gameSessionClient,
            pluginRuntimeStateRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(item.getAuthorityUnavailableCount()).isZero();
    assertThat(item.getAuthorityUnavailableRetryCount()).isEqualTo(1);
    verify(pluginRuntimeStateRepository)
        .findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1");
  }

  @Test
  void retriesWhenLaterPluginFenceReadReportsTransientConnectionFailure() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PluginRuntimeStateRepository pluginRuntimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "7", "plugin-1"))
        .thenReturn(Optional.of(pluginState(4L, 8L)))
        .thenThrow(
            new DataAccessException(
                "plugin lookup unavailable", new SQLTransientConnectionException("offline")));
    ScriptWorkItem item = replayPluginWorkItem();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            gameSessionClient,
            pluginRuntimeStateRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(item.getAuthorityUnavailableRetryCount()).isEqualTo(1);
    assertThat(item.getNextEligibleAt()).isNotNull();
    verify(pluginRuntimeStateRepository, Mockito.times(2))
        .findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1");
  }

  @Test
  void deniesWhenLaterPluginFenceReadReportsNonTransientFailure() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PluginRuntimeStateRepository pluginRuntimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "7", "plugin-1"))
        .thenReturn(Optional.of(pluginState(4L, 8L)))
        .thenThrow(
            new DataAccessException("invalid plugin query", new SQLException("syntax", "42601")));
    ScriptWorkItem item = replayPluginWorkItem();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            gameSessionClient,
            pluginRuntimeStateRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("plugin_lifecycle_evidence_unavailable");
    assertThat(item.getAuthorityUnavailableCount()).isZero();
    verify(pluginRuntimeStateRepository, Mockito.times(2))
        .findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1");
  }

  @Test
  void retriesLaterPluginFenceReadFailureAfterDurablyCommittedClaim() {
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PluginRuntimeStateRepository pluginRuntimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    PlatformTransactionManager transactionManager = Mockito.mock(PlatformTransactionManager.class);
    TransactionStatus transactionStatus = Mockito.mock(TransactionStatus.class);
    AtomicInteger nextTransactionId = new AtomicInteger();
    List<TransactionDefinition> transactionDefinitions = new ArrayList<>();
    List<String> committedStatuses = new ArrayList<>();
    ScriptWorkItem item = replayPluginWorkItem();
    item.setStatus("PENDING_EVALUATION");

    when(automationQueueService.drainIndexedWorkItemPointers(2, 1)).thenReturn(List.of());
    when(workItemRepository.findByStatusOrderByCreatedAtAscIdAsc(
            Mockito.eq("PENDING_EVALUATION"),
            Mockito.any(Instant.class),
            Mockito.any(Pageable.class)))
        .thenReturn(List.of(item));
    when(transactionManager.getTransaction(Mockito.any(TransactionDefinition.class)))
        .thenAnswer(
            invocation -> {
              transactionDefinitions.add(invocation.getArgument(0));
              nextTransactionId.incrementAndGet();
              return transactionStatus;
            });
    Mockito.doAnswer(
            invocation -> {
              committedStatuses.add(item.getStatus());
              return null;
            })
        .when(transactionManager)
        .commit(transactionStatus);
    Mockito.doAnswer(
            invocation -> {
              item.setStatus("EVALUATING");
              return List.of(item);
            })
        .when(workItemService)
        .claimPendingForEvaluation(Mockito.anyList(), Mockito.eq(1));
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "7", "plugin-1"))
        .thenReturn(Optional.of(pluginState(4L, 8L)))
        .thenThrow(
            new DataAccessException(
                "plugin lookup unavailable", new SQLTransientConnectionException("offline")));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            automationQueueService,
            workItemService,
            Mockito.mock(ScriptDefinitionRepository.class),
            Mockito.mock(ScriptGameplayCommandHandoffService.class),
            workItemRepository,
            auditRepository,
            rolloutProjectionService,
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            null,
            null,
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            gameSessionClient,
            pluginRuntimeStateRepository,
            transactionManager);

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.claimedCount()).isEqualTo(1);
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(committedStatuses).containsExactly("EVALUATING", "EVALUATING", "PENDING_EVALUATION");
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(item.getAuthorityUnavailableRetryCount()).isEqualTo(1);
    verify(pluginRuntimeStateRepository, Mockito.times(2))
        .findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1");
    assertThat(
            transactionDefinitions.stream()
                .filter(
                    definition ->
                        definition.getPropagationBehavior()
                                == TransactionDefinition.PROPAGATION_REQUIRES_NEW
                            && definition.isReadOnly())
                .count())
        .isEqualTo(2);
    assertThat(nextTransactionId).hasValue(4);
  }

  @Test
  void retriesWhenPluginFenceRequiresNewTransactionCannotBeCreated() {
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PluginRuntimeStateRepository pluginRuntimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    PlatformTransactionManager transactionManager = Mockito.mock(PlatformTransactionManager.class);
    TransactionStatus transactionStatus = Mockito.mock(TransactionStatus.class);
    AtomicInteger transactionCount = new AtomicInteger();
    ScriptWorkItem item = replayPluginWorkItem();
    item.setStatus("PENDING_EVALUATION");

    when(automationQueueService.drainIndexedWorkItemPointers(2, 1)).thenReturn(List.of());
    when(workItemRepository.findByStatusOrderByCreatedAtAscIdAsc(
            Mockito.eq("PENDING_EVALUATION"),
            Mockito.any(Instant.class),
            Mockito.any(Pageable.class)))
        .thenReturn(List.of(item));
    when(transactionManager.getTransaction(Mockito.any(TransactionDefinition.class)))
        .thenAnswer(
            invocation -> {
              if (transactionCount.incrementAndGet() == 3) {
                throw new CannotCreateTransactionException(
                    "plugin fence transaction unavailable",
                    new SQLTransientConnectionException("offline"));
              }
              return transactionStatus;
            });
    Mockito.doAnswer(
            invocation -> {
              item.setStatus("EVALUATING");
              return List.of(item);
            })
        .when(workItemService)
        .claimPendingForEvaluation(Mockito.anyList(), Mockito.eq(1));
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            automationQueueService,
            workItemService,
            Mockito.mock(ScriptDefinitionRepository.class),
            Mockito.mock(ScriptGameplayCommandHandoffService.class),
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            null,
            null,
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            gameSessionClient,
            pluginRuntimeStateRepository,
            transactionManager);

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.claimedCount()).isEqualTo(1);
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(item.getCancelReason()).isEqualTo("authority_unavailable");
    assertThat(item.getAuthorityUnavailableRetryCount()).isEqualTo(1);
    assertThat(item.getNextEligibleAt()).isNotNull();
    verify(pluginRuntimeStateRepository, Mockito.never())
        .findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"NOT_A_PLUGIN_STATE", "PLUGIN_STATE_UNSPECIFIED"})
  void retriesMissingUnknownOrUnspecifiedEarlyPluginState(String pluginStateName) {
    PluginRuntimeState state = pluginState(4L, 8L);
    state.setPluginState(pluginStateName);

    assertEarlyPluginAuthorityUnavailable(Optional.of(state));
  }

  @Test
  void retriesWhenEarlyPluginOwnerRowIsAbsent() {
    assertEarlyPluginAuthorityUnavailable(Optional.empty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"active-version", "activation-epoch", "lifecycle-revision"})
  void retriesWhenEarlyPluginOwnerRowIsIncomplete(String missingEvidence) {
    PluginRuntimeState state = pluginState(4L, 8L);
    switch (missingEvidence) {
      case "active-version" -> state.setActivePluginVersionId(" ");
      case "activation-epoch" -> state.setPluginActivationEpoch(0L);
      case "lifecycle-revision" -> state.setLifecycleRevision(0L);
      default -> throw new IllegalArgumentException("unexpected missing evidence");
    }

    assertEarlyPluginAuthorityUnavailable(Optional.of(state));
  }

  @ParameterizedTest
  @ValueSource(strings = {"PLUGIN_STATE_DISABLED", "REVOKED"})
  void terminalizesExplicitlyDisabledOrRevokedEarlyPluginState(String pluginStateName) {
    PluginRuntimeState state = pluginState(4L, 8L);
    state.setPluginState(pluginStateName);

    EarlyPluginFenceExecution result = runEarlyPluginFence(Optional.of(state));

    assertThat(result.item().getStatus()).isEqualTo("CANCELED");
    assertThat(result.item().getCancelReason()).isEqualTo("plugin_disabled");
    assertThat(result.item().getAuthorityUnavailableCount()).isZero();
  }

  @Test
  void rejectsRuntimePinnedBaseMismatchBeforeZeroCommandEvaluation() {
    ScriptWorkItem item = workItem();
    item.setScriptPatchBaseVersionId(41L);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptDefinition emptyDefinition = scriptDefinition();
    emptyDefinition.setDefinition("{\"onCommand\": {\"emitCommands\": []}}");
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(emptyDefinition));
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    when(workItemRepository.save(item)).thenReturn(item);
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            null,
            null,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("script_patch_base_version_mismatch");
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
  }

  @Test
  void rejectsChangedRoutingPointerBeforeZeroCommandEvaluation() {
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptDefinition emptyDefinition = scriptDefinition();
    emptyDefinition.setDefinition("{\"onCommand\": {\"emitCommands\": []}}");
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(emptyDefinition));
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptWorkItem item = workItem();
    when(workItemRepository.save(item)).thenReturn(item);
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    var changedRuntime =
        runtimeStateResponse().getRuntimeState().toBuilder()
            .setPointerVersion(18L)
            .clearCurrentAdmissionPointers()
            .addCurrentAdmissionPointers(currentPointer("SHARED", 18L))
            .build();
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(changedRuntime)
                .build());
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            null,
            null,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("routing_bundle_changed");
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
  }

  @Test
  void cancelsEvaluatedCommandsWhenRoutingPointerChangesBeforeHandoff() {
    ScriptWorkItem item = workItem();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\": [{\"commandText\": \"LOOK\"}]}");
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    var changedRuntime =
        runtimeStateResponse().getRuntimeState().toBuilder()
            .setPointerVersion(18L)
            .clearCurrentAdmissionPointers()
            .addCurrentAdmissionPointers(currentPointer("SHARED", 18L))
            .build();
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            runtimeStateResponse(),
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(changedRuntime)
                .build());
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            null,
            null,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("routing_bundle_changed");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("routing_bundle_changed");
    verify(definitionRepository).findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1");
    verify(gameSessionClient, Mockito.times(2)).getGameInstanceRuntimeState("1", "7", "region-1");
    Mockito.verifyNoInteractions(handoffService);
  }

  @Test
  void terminalizesNonAvailabilityPluginRepositoryFailureWithoutRetry() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PluginRuntimeStateRepository pluginRuntimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "7", "plugin-1"))
        .thenThrow(
            new DataAccessException("invalid plugin query", new SQLException("syntax", "42601")));
    ScriptWorkItem item = replayPluginWorkItem();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            gameSessionClient,
            pluginRuntimeStateRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("plugin_lifecycle_evidence_unavailable");
    assertThat(item.getAuthorityUnavailableCount()).isZero();
  }

  @Test
  void terminalizesSpringTranslatedNonAvailabilityPluginRepositoryFailureWithoutRetry() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PluginRuntimeStateRepository pluginRuntimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "7", "plugin-1"))
        .thenThrow(
            new org.springframework.dao.InvalidDataAccessApiUsageException(
                "invalid plugin query", new SQLException("syntax", "42601")));
    ScriptWorkItem item = replayPluginWorkItem();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            gameSessionClient,
            pluginRuntimeStateRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("plugin_lifecycle_evidence_unavailable");
    assertThat(item.getAuthorityUnavailableCount()).isZero();
  }

  @Test
  void authorityUnavailableRetryDeadLettersAtOutcomeLimitWithDurableEvidence() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("UNAVAILABLE").build())
                .build());
    ScriptWorkItem item = workItem();
    item.setScriptPinEpoch(3L);
    item.setAuthorityUnavailableSince(Instant.now().minus(Duration.ofSeconds(1)));
    item.setAuthorityUnavailableCount(9);
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("authority_unavailable_exhausted");
    assertThat(item.getAuthorityUnavailableCount()).isEqualTo(10);
    assertThat(item.getNextEligibleAt()).isNull();
    assertThat(audit.getFinalStage()).isEqualTo("ADMISSION");
    assertThat(audit.getFinalOutcome()).isEqualTo("authority_unavailable_exhausted");
    assertThat(audit.getFinalReason()).isEqualTo("authority_unavailable_exhausted");
    verify(workItemRepository).save(item);
    verify(auditRepository).save(audit);
  }

  @Test
  void retryablePostEvaluationRuntimeFenceDoesNotExhaustAuthorityBudget() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            runtimeStateResponse(),
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("UNAVAILABLE").build())
                .build());
    ScriptWorkItem item = workItem();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[{\"commandText\":\"LOOK\"}]}");
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenAnswer(
            invocation -> {
              // Model the exhausted outage budget reached during the evaluated handoff fence.
              item.setAuthorityUnavailableSince(Instant.now().minus(Duration.ofMinutes(11)));
              item.setAuthorityUnavailableCount(9);
              return Optional.of(definition);
            });
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            definitionRepository,
            handoffService,
            null,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("EVALUATING");
    assertThat(item.getCancelReason())
        .isEqualTo("post_evaluation_reconciliation_required:script_pin_authority_unavailable");
    assertThat(item.getUpdatedAt()).isAfter(Instant.EPOCH);
    assertThat(item.getAuthorityUnavailableCount()).isEqualTo(9);
    verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    verify(workItemRepository).save(item);
    verify(auditRepository, Mockito.never()).save(audit);
  }

  @Test
  void authorityUnavailableRetryDeadLettersWhenFirstOutcomeExceedsAgeBound() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("UNAVAILABLE").build())
                .build());
    ScriptWorkItem item = workItem();
    item.setScriptPinEpoch(3L);
    item.setAuthorityUnavailableSince(Instant.now().minus(Duration.ofMinutes(10).plusSeconds(1)));
    item.setAuthorityUnavailableCount(1);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("authority_unavailable_exhausted");
    assertThat(item.getAuthorityUnavailableCount()).isEqualTo(2);
    verify(gameSessionClient).getGameInstanceRuntimeState("1", "7", "region-1");
  }

  @Test
  void rejectsRuntimeAuthorityForDifferentTenantBeforeEvaluation() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItem item = workItem();
    item.setScriptPinEpoch(3L);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    when(workItemRepository.save(item)).thenReturn(item);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(
                    net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState.newBuilder()
                        .setTenantId("tenant-2")
                        .setGameInstanceId("7")
                        .setRegionId("region-1")
                        .setRegionEpoch(12L))
                .build());

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            Mockito.mock(ScriptDefinitionRepository.class),
            Mockito.mock(ScriptGameplayCommandHandoffService.class),
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            null,
            null,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    service.processPendingWorkItems(10);

    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("runtime_scope_changed");
  }

  @Test
  void rejectsMissingRuntimeScopeBeforeAuthorityLookup() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItem item = workItem();
    item.setTenantId(null);
    item.setScriptPinEpoch(3L);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    when(workItemRepository.save(item)).thenReturn(item);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            Mockito.mock(ScriptDefinitionRepository.class),
            Mockito.mock(ScriptGameplayCommandHandoffService.class),
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            null,
            null,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    service.processPendingWorkItems(10);

    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("runtime_scope_missing");
    Mockito.verifyNoInteractions(gameSessionClient);
  }

  @Test
  void rejectsMissingScriptPinEpochAsTerminalBeforeAuthorityLookup() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItem item = workItem();
    item.setScriptPinEpoch(0L);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    when(workItemRepository.save(item)).thenReturn(item);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            Mockito.mock(ScriptDefinitionRepository.class),
            Mockito.mock(ScriptGameplayCommandHandoffService.class),
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            null,
            null,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("script_pin_epoch_unavailable");
    verify(workItemRepository).save(item);
    Mockito.verifyNoInteractions(gameSessionClient);
  }

  @Test
  void rejectsMissingPluginLifecycleEvidenceAsTerminalBeforeEvaluation() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItem item = workItem();
    item.setScriptPinEpoch(3L);
    item.setPluginId("plugin-1");
    item.setPluginVersionId("plugin-version-1");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    when(workItemRepository.save(item)).thenReturn(item);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            Mockito.mock(ScriptDefinitionRepository.class),
            Mockito.mock(ScriptGameplayCommandHandoffService.class),
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            null,
            null,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("plugin_lifecycle_evidence_unavailable");
    verify(workItemRepository).save(item);
    verify(gameSessionClient).getGameInstanceRuntimeState("1", "7", "region-1");
  }

  @Test
  void terminalizesCapturedPluginBindingMismatchBeforeEvaluation() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptEventAudit audit = new ScriptEventAudit();
    PluginRuntimeStateRepository pluginRuntimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = workItem();
    item.setScriptPinEpoch(3L);
    item.setPluginId("plugin-1");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenReturn(item);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            gameSessionClient,
            pluginRuntimeStateRepository);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("plugin_binding_mismatch");
    assertThat(audit.getFinalStage()).isEqualTo("ADMISSION");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("plugin_binding_mismatch");
    verify(auditRepository).save(audit);
    Mockito.verifyNoInteractions(pluginRuntimeStateRepository);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"stale-pin-request"})
  void terminalizesMissingOrMismatchedPinOwnerRequestAsStaleFence(String capturedRequestId) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptEventAudit audit = new ScriptEventAudit();
    PluginRuntimeStateRepository pluginRuntimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = workItem();
    item.setScriptPinControlPlaneRequestId(capturedRequestId);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenReturn(item);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            gameSessionClient,
            pluginRuntimeStateRepository);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    String expectedReason =
        capturedRequestId == null
            ? "script_pin_owner_request_unavailable"
            : "script_pin_owner_request_mismatch";
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo(expectedReason);
    assertThat(audit.getFinalStage()).isEqualTo("ADMISSION");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo(expectedReason);
    verify(workItemRepository).save(item);
    verify(auditRepository).save(audit);
    Mockito.verifyNoInteractions(pluginRuntimeStateRepository);
  }

  @Test
  void terminalizesCurrentPluginLifecycleMismatchBeforeEvaluation() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PluginRuntimeStateRepository pluginRuntimeStateRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptWorkItem item = workItem();
    item.setScriptPinEpoch(3L);
    item.setPluginId("plugin-1");
    item.setPluginVersionId("plugin-version-1");
    item.setPluginActivationEpoch(4L);
    item.setLifecycleRevision(8L);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenReturn(item);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    PluginRuntimeState currentState = new PluginRuntimeState();
    currentState.setPluginState(
        net.firedevops.firemud.automationscripting.v1.PluginState.PLUGIN_STATE_ENABLED.name());
    currentState.setActivePluginVersionId("plugin-version-1");
    currentState.setPluginActivationEpoch(4L);
    currentState.setLifecycleRevision(9L);
    when(pluginRuntimeStateRepository.findByTenantIdAndGameInstanceIdAndPluginId(
            "1", "7", "plugin-1"))
        .thenReturn(Optional.of(currentState));

    ScriptWorkItemExecutionService service =
        fenceExecutionService(
            workItemService,
            workItemRepository,
            auditRepository,
            gameSessionClient,
            pluginRuntimeStateRepository);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("plugin_binding_mismatch");
    assertThat(audit.getFinalStage()).isEqualTo("ADMISSION");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("plugin_binding_mismatch");
    verify(auditRepository).save(audit);
  }

  @Test
  void claimsQueueIndexedWorkItemsBeforeFallingBackToDurableScan() {
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItem indexed = workItem();
    indexed.setId(99L);
    ScriptWorkItem fallback = workItem();
    fallback.setId(100L);
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[]}");
    when(automationQueueService.drainIndexedWorkItemPointers(20, 9))
        .thenReturn(
            List.of(
                new AutomationQueueWorkItemPointer(1, 99L, "instance-1", "patch-1", "event-1")));
    when(workItemService.claimPendingForEvaluation(List.of(99L), 9)).thenReturn(List.of(indexed));
    when(workItemService.claimPendingForEvaluation(9)).thenReturn(List.of(fallback));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(Mockito.anyLong()))
        .thenAnswer(invocation -> Optional.of(new ScriptEventAudit()));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            automationQueueService);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.claimedCount()).isEqualTo(2);
    assertThat(result.completedCount()).isEqualTo(2);
    ArgumentCaptor<ScriptEventAudit> auditCaptor = ArgumentCaptor.forClass(ScriptEventAudit.class);
    verify(auditRepository, Mockito.times(2)).save(auditCaptor.capture());
    assertThat(auditCaptor.getAllValues())
        .allSatisfy(
            audit -> {
              assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
              assertThat(audit.getFinalOutcome()).isEqualTo("completed_no_commands");
              assertThat(audit.getFinalReason()).isEqualTo("script_emitted_no_commands");
            });
    Mockito.verifyNoInteractions(handoffService);
    verify(workItemService).claimPendingForEvaluation(List.of(99L), 9);
    verify(workItemService).claimPendingForEvaluation(9);
  }

  @Test
  void reservesDurableScanSlotAcrossSustainedFullIndexedBatches() {
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItem indexed = workItem();
    indexed.setId(99L);
    ScriptWorkItem fallback = workItem();
    fallback.setId(100L);
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[]}");
    when(automationQueueService.drainIndexedWorkItemPointers(4, 1))
        .thenReturn(
            List.of(
                new AutomationQueueWorkItemPointer(1, 99L, "instance-1", "patch-1", "event-1")));
    when(workItemService.claimPendingForEvaluation(List.of(99L), 1)).thenReturn(List.of(indexed));
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(fallback));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(Mockito.anyLong()))
        .thenAnswer(invocation -> Optional.of(new ScriptEventAudit()));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            automationQueueService);

    for (int invocation = 0; invocation < 3; invocation++) {
      ScriptWorkItemExecutionService.ExecutionBatchResult result =
          service.processPendingWorkItems(2);
      assertThat(result.claimedCount()).isEqualTo(2);
      assertThat(result.completedCount()).isEqualTo(2);
    }

    verify(automationQueueService, Mockito.times(3)).drainIndexedWorkItemPointers(4, 1);
    verify(workItemService, Mockito.times(3)).claimPendingForEvaluation(List.of(99L), 1);
    verify(workItemService, Mockito.times(3)).claimPendingForEvaluation(1);
    Mockito.verifyNoInteractions(handoffService);
  }

  @Test
  void transactionalExecutorScansDurableWorkAcrossSustainedFullIndexedBatches() {
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PlatformTransactionManager transactionManager = Mockito.mock(PlatformTransactionManager.class);
    TransactionStatus transactionStatus = Mockito.mock(TransactionStatus.class);
    ScriptWorkItem indexed = workItem();
    indexed.setId(99L);
    indexed.setStatus("PENDING_EVALUATION");
    ScriptWorkItem fallback = workItem();
    fallback.setId(100L);
    fallback.setStatus("PENDING_EVALUATION");
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[]}");

    when(transactionManager.getTransaction(Mockito.any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
    when(automationQueueService.drainIndexedWorkItemPointers(4, 1))
        .thenReturn(
            List.of(
                new AutomationQueueWorkItemPointer(1, 99L, "instance-1", "patch-1", "event-1")));
    when(workItemRepository.findByIdInAndStatusOrderByCreatedAtAscIdAsc(
            Mockito.eq(List.of(99L)),
            Mockito.eq("PENDING_EVALUATION"),
            Mockito.any(Instant.class),
            Mockito.any(Pageable.class)))
        .thenReturn(List.of(indexed));
    when(workItemRepository.findByStatusOrderByCreatedAtAscIdAsc(
            Mockito.eq("PENDING_EVALUATION"),
            Mockito.any(Instant.class),
            Mockito.any(Pageable.class)))
        .thenReturn(List.of(indexed, fallback));
    Mockito.doAnswer(
            invocation -> {
              List<Long> ids = invocation.getArgument(0);
              ScriptWorkItem candidate = ids.contains(99L) ? indexed : fallback;
              candidate.setStatus("EVALUATING");
              return List.of(candidate);
            })
        .when(workItemService)
        .claimPendingForEvaluation(Mockito.anyList(), Mockito.eq(1));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(Mockito.anyLong()))
        .thenAnswer(invocation -> Optional.of(new ScriptEventAudit()));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            automationQueueService,
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            null,
            null,
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            Mockito.mock(PluginRuntimeStateRepository.class),
            transactionManager);

    for (int invocation = 0; invocation < 3; invocation++) {
      ScriptWorkItemExecutionService.ExecutionBatchResult result =
          service.processPendingWorkItems(2);
      assertThat(result.claimedCount()).isEqualTo(2);
      assertThat(result.completedCount()).isEqualTo(2);
    }

    verify(automationQueueService, Mockito.times(3)).drainIndexedWorkItemPointers(4, 1);
    verify(workItemRepository, Mockito.times(3))
        .findByStatusOrderByCreatedAtAscIdAsc(
            Mockito.eq("PENDING_EVALUATION"),
            Mockito.any(Instant.class),
            Mockito.any(Pageable.class));
    verify(workItemService, Mockito.times(3)).claimPendingForEvaluation(List.of(99L), 1);
    verify(workItemService, Mockito.times(3)).claimPendingForEvaluation(List.of(100L), 1);
    Mockito.verifyNoInteractions(handoffService);
  }

  @Test
  void commitsEachClaimBeforeEvaluationAndLeavesItUnresolvedAfterEvaluationRollback() {
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PlatformTransactionManager transactionManager = Mockito.mock(PlatformTransactionManager.class);
    TransactionStatus transactionStatus = Mockito.mock(TransactionStatus.class);
    AtomicBoolean transactionActive = new AtomicBoolean();
    AtomicInteger currentTransactionId = new AtomicInteger();
    AtomicInteger nextTransactionId = new AtomicInteger();
    List<Integer> claimTransactionIds = new ArrayList<>();
    List<Integer> evaluationTransactionIds = new ArrayList<>();
    Map<Long, String> committedStatuses = new HashMap<>();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    AtomicBoolean remoteEffectAccepted = new AtomicBoolean();

    ScriptWorkItem indexed = workItem();
    indexed.setStatus("PENDING_EVALUATION");
    ScriptWorkItem fallback = workItem();
    fallback.setId(100L);
    fallback.setScriptId("script-2");
    fallback.setScriptEventId("event-2");
    fallback.setStatus("PENDING_EVALUATION");

    when(transactionManager.getTransaction(Mockito.any(TransactionDefinition.class)))
        .thenAnswer(
            invocation -> {
              assertThat(transactionActive.compareAndSet(false, true)).isTrue();
              currentTransactionId.set(nextTransactionId.incrementAndGet());
              return transactionStatus;
            });
    Mockito.doAnswer(
            invocation -> {
              int committedTransactionId = currentTransactionId.get();
              assertThat(transactionActive.compareAndSet(true, false)).isTrue();
              currentTransactionId.set(0);
              if (committedTransactionId == 2) {
                throw new UnexpectedRollbackException("simulated rollback-only processing tx");
              }
              if (committedTransactionId == 1) {
                committedStatuses.put(indexed.getId(), indexed.getStatus());
              } else if (committedTransactionId == 3) {
                committedStatuses.put(fallback.getId(), fallback.getStatus());
              } else if (committedTransactionId == 4) {
                committedStatuses.put(fallback.getId(), fallback.getStatus());
              }
              return null;
            })
        .when(transactionManager)
        .commit(transactionStatus);
    when(automationQueueService.drainIndexedWorkItemPointers(4, 1))
        .thenReturn(List.of(new AutomationQueueWorkItemPointer(1, 99L, "7", "patch-1", "event-1")));
    when(workItemRepository.findByIdInAndStatusOrderByCreatedAtAscIdAsc(
            Mockito.eq(List.of(99L)),
            Mockito.eq("PENDING_EVALUATION"),
            Mockito.any(Instant.class),
            Mockito.any(Pageable.class)))
        .thenReturn(List.of(indexed));
    when(workItemRepository.findByStatusOrderByCreatedAtAscIdAsc(
            Mockito.eq("PENDING_EVALUATION"),
            Mockito.any(Instant.class),
            Mockito.any(Pageable.class)))
        .thenAnswer(
            invocation -> {
              assertThat(transactionActive).isFalse();
              // The durable scan returns both rows. Claiming rejects the indexed row because it
              // is no longer PENDING_EVALUATION, while its sibling remains eligible.
              return List.of(indexed, fallback);
            });
    Mockito.doAnswer(
            invocation -> {
              assertThat(transactionActive).isTrue();
              claimTransactionIds.add(currentTransactionId.get());
              ScriptWorkItem candidate =
                  invocation.<List<Long>>getArgument(0).contains(99L) ? indexed : fallback;
              if (!"PENDING_EVALUATION".equals(candidate.getStatus())) {
                return List.of();
              }
              candidate.setStatus("EVALUATING");
              return List.of(candidate);
            })
        .when(workItemService)
        .claimPendingForEvaluation(Mockito.anyList(), Mockito.eq(1));
    Mockito.doAnswer(
            invocation -> {
              assertThat(transactionActive).isTrue();
              evaluationTransactionIds.add(currentTransactionId.get());
              return Optional.of(scriptDefinitionForWorkItem(invocation.getArgument(2)));
            })
        .when(definitionRepository)
        .findByTenantIdAndScriptVersionAndName(
            Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    when(handoffService.handoff(Mockito.eq(indexed), Mockito.any()))
        .thenAnswer(
            invocation -> {
              assertThat(transactionActive).isFalse();
              remoteEffectAccepted.set(true);
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  true, "ACCEPTED", "command-1", "", "", "");
            });
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(auditRepository.findByWorkItemId(100L)).thenReturn(Optional.of(new ScriptEventAudit()));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            automationQueueService,
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            rolloutProjectionService,
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            null,
            null,
            new ObjectMapper(),
            meterRegistry,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class),
            transactionManager);

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(2);

    assertThat(result.claimedCount()).isEqualTo(2);
    assertThat(result.completedCount()).isEqualTo(1);
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(claimTransactionIds).containsExactly(1, 3);
    assertThat(evaluationTransactionIds).containsExactly(2, 4);
    assertThat(nextTransactionId).hasValue(4);
    assertThat(remoteEffectAccepted).isFalse();
    assertThat(committedStatuses)
        .containsEntry(indexed.getId(), "EVALUATING")
        .containsEntry(fallback.getId(), "HANDED_OFF");
    assertThat(fallback.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(meterRegistry.counter("script_outbox_processing_failure_after_claim_total").count())
        .isEqualTo(1.0);
    verify(workItemService, Mockito.times(1)).claimPendingForEvaluation(List.of(99L), 1);
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.eq(indexed), Mockito.any());
  }

  @Test
  void finalizationRollbackAfterRemoteHandoffLeavesParentUnresolvedAndContinuesBatch() {
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PlatformTransactionManager transactionManager = Mockito.mock(PlatformTransactionManager.class);
    TransactionStatus transactionStatus = Mockito.mock(TransactionStatus.class);
    AtomicBoolean transactionActive = new AtomicBoolean();
    AtomicInteger currentTransactionId = new AtomicInteger();
    AtomicInteger nextTransactionId = new AtomicInteger();
    List<Integer> claimTransactionIds = new ArrayList<>();
    List<Integer> evaluationTransactionIds = new ArrayList<>();
    Map<Long, String> committedStatuses = new HashMap<>();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    AtomicBoolean remoteEffectAccepted = new AtomicBoolean();

    ScriptWorkItem indexed = workItem();
    indexed.setStatus("PENDING_EVALUATION");
    ScriptWorkItem fallback = workItem();
    fallback.setId(100L);
    fallback.setScriptId("script-2");
    fallback.setScriptEventId("event-2");
    fallback.setStatus("PENDING_EVALUATION");

    when(transactionManager.getTransaction(Mockito.any(TransactionDefinition.class)))
        .thenAnswer(
            invocation -> {
              assertThat(transactionActive.compareAndSet(false, true)).isTrue();
              currentTransactionId.set(nextTransactionId.incrementAndGet());
              return transactionStatus;
            });
    Mockito.doAnswer(
            invocation -> {
              int completedTransactionId = currentTransactionId.get();
              assertThat(transactionActive.compareAndSet(true, false)).isTrue();
              currentTransactionId.set(0);
              if (completedTransactionId == 4) {
                throw new UnexpectedRollbackException("simulated finalization rollback");
              }
              if (completedTransactionId == 1) {
                committedStatuses.put(indexed.getId(), indexed.getStatus());
              } else if (completedTransactionId == 5 || completedTransactionId == 6) {
                committedStatuses.put(fallback.getId(), fallback.getStatus());
              }
              return null;
            })
        .when(transactionManager)
        .commit(transactionStatus);
    when(automationQueueService.drainIndexedWorkItemPointers(4, 1))
        .thenReturn(List.of(new AutomationQueueWorkItemPointer(1, 99L, "7", "patch-1", "event-1")));
    when(workItemRepository.findByIdInAndStatusOrderByCreatedAtAscIdAsc(
            Mockito.eq(List.of(99L)),
            Mockito.eq("PENDING_EVALUATION"),
            Mockito.any(Instant.class),
            Mockito.any(Pageable.class)))
        .thenReturn(List.of(indexed));
    when(workItemRepository.findByStatusOrderByCreatedAtAscIdAsc(
            Mockito.eq("PENDING_EVALUATION"),
            Mockito.any(Instant.class),
            Mockito.any(Pageable.class)))
        .thenAnswer(
            invocation -> {
              assertThat(transactionActive).isFalse();
              return List.of(indexed, fallback);
            });
    Mockito.doAnswer(
            invocation -> {
              assertThat(transactionActive).isTrue();
              claimTransactionIds.add(currentTransactionId.get());
              ScriptWorkItem candidate =
                  invocation.<List<Long>>getArgument(0).contains(99L) ? indexed : fallback;
              if (!"PENDING_EVALUATION".equals(candidate.getStatus())) {
                return List.of();
              }
              candidate.setStatus("EVALUATING");
              return List.of(candidate);
            })
        .when(workItemService)
        .claimPendingForEvaluation(Mockito.anyList(), Mockito.eq(1));
    Mockito.doAnswer(
            invocation -> {
              assertThat(transactionActive).isTrue();
              evaluationTransactionIds.add(currentTransactionId.get());
              return Optional.of(scriptDefinitionForWorkItem(invocation.getArgument(2)));
            })
        .when(definitionRepository)
        .findByTenantIdAndScriptVersionAndName(
            Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    when(handoffService.handoff(Mockito.eq(indexed), Mockito.any()))
        .thenAnswer(
            invocation -> {
              assertThat(transactionActive).isFalse();
              remoteEffectAccepted.set(true);
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  true, "ACCEPTED", "command-1", "", "", "");
            });
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(auditRepository.findByWorkItemId(Mockito.anyLong()))
        .thenAnswer(invocation -> Optional.of(new ScriptEventAudit()));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            automationQueueService,
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            null,
            null,
            new ObjectMapper(),
            meterRegistry,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class),
            transactionManager);

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(2);

    assertThat(result.claimedCount()).isEqualTo(2);
    assertThat(result.completedCount()).isEqualTo(1);
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(claimTransactionIds).containsExactly(1, 5);
    assertThat(evaluationTransactionIds).containsExactly(2, 6);
    assertThat(nextTransactionId).hasValue(6);
    assertThat(remoteEffectAccepted).isTrue();
    assertThat(committedStatuses)
        .containsEntry(indexed.getId(), "EVALUATING")
        .containsEntry(fallback.getId(), "HANDED_OFF");
    assertThat(fallback.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(meterRegistry.counter("script_outbox_processing_failure_after_claim_total").count())
        .isEqualTo(1.0);
    verify(handoffService).handoff(Mockito.eq(indexed), Mockito.any());
  }

  private static ScriptDefinition scriptDefinitionForWorkItem(String scriptId) {
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        "script-1".equals(scriptId)
            ? "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":\"entity-2\"}]}"
            : "{\"emitCommands\":[]}");
    return definition;
  }

  @Test
  void processesClaimedWorkItemAndHandsOffRenderedCommands() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        """
        {
          "eventHandlers": {
            "onCommand": {
              "emitCommands": [
                {
                  "commandText": "say {{payload.commandName}} from {{entityId}}"
                }
              ]
            }
          }
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              item.setStatus("HANDED_OFF");
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  true, "ENQUEUED", "auto-1", "", "", "");
            });
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.claimedCount()).isEqualTo(1);
    assertThat(result.completedCount()).isEqualTo(1);
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getValue().commandText()).isEqualTo("say LOOK from entity-1");
    assertThat(commandCaptor.getValue().targetEntityId()).isEqualTo("entity-1");
    assertThat(commandCaptor.getValue().targetGameInstanceId()).isEqualTo("7");
    assertThat(commandCaptor.getValue().targetRegionId()).isEqualTo("region-1");
    assertThat(commandCaptor.getValue().targetRegionEpoch()).isEqualTo(12L);
    assertThat(item.getScriptPatchBaseVersionId()).isEqualTo(definition.getBaseVersionId());
    assertThat(item.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(audit.getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("handoff_accepted");
    assertThat(audit.getFinalReason()).isEqualTo("commands_handed_off");
  }

  @Test
  void freshAdmissionFenceFailureWinsOverExpiredUnavailableBudget() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    var changedRuntime =
        runtimeStateResponse().getRuntimeState().toBuilder().setScriptPinEpoch(4L).build();
    Instant outageSince = Instant.now().minus(Duration.ofMinutes(11));
    Instant nextEligibleAt = Instant.now().plus(Duration.ofSeconds(30));
    item.setAuthorityUnavailableSince(outageSince);
    item.setAuthorityUnavailableCount(9);
    item.setNextEligibleAt(nextEligibleAt);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(
            GetGameInstanceRuntimeStateResponse.newBuilder()
                .setRuntimeState(changedRuntime)
                .build());
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            null,
            null,
            gameSessionClient,
            Mockito.mock(PluginRuntimeStateRepository.class));

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("script_pin_epoch_mismatch");
    assertThat(item.getAuthorityUnavailableSince()).isEqualTo(outageSince);
    assertThat(item.getAuthorityUnavailableCount()).isEqualTo(9);
    assertThat(item.getNextEligibleAt()).isNull();
    assertThat(audit.getFinalStage()).isEqualTo("ADMISSION");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("script_pin_epoch_mismatch");
    verify(gameSessionClient).getGameInstanceRuntimeState("1", "7", "region-1");
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
  }

  @Test
  void deadLettersWorkItemWithMissingBaseBeforeDslOrHandoff() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItem item = workItem();
    item.setScriptPatchBaseVersionId(null);
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("not-json");
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("script_patch_base_version_unavailable");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("definition_invalid");
    Mockito.verifyNoInteractions(handoffService);
  }

  @Test
  void deadLettersWorkItemWithMismatchedBaseBeforeDslOrHandoff() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItem item = workItem();
    item.setScriptPatchBaseVersionId(7L);
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("not-json");
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("script_patch_base_version_mismatch");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("definition_invalid");
    Mockito.verifyNoInteractions(handoffService);
  }

  @ParameterizedTest
  @CsvSource({"2, 1, plugin_activation_epoch_mismatch", "1, 2, plugin_binding_mismatch"})
  void rejectsStalePluginActivationOrLifecycleFenceBeforeQuotaAndDsl(
      long currentActivationEpoch, long currentLifecycleRevision, String reason) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptTenantBudgetService tenantBudgetService = allowingTenantBudgetService();
    ScriptWorkItem item = pluginWorkItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(Optional.of(pluginState(currentActivationEpoch, currentLifecycleRevision)));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            tenantBudgetService,
            pluginRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo(reason);
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    verify(tenantBudgetService, Mockito.never()).tryReserve(Mockito.any(), Mockito.any());
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "PLUGIN_STATE_DISABLED",
        "PLUGIN_STATE_DRAINING",
        "PLUGIN_STATE_RELOADING",
        "PLUGIN_STATE_FAILED"
      })
  void rejectsNonEnabledPluginBeforeQuotaAndDsl(String pluginStateName) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptTenantBudgetService tenantBudgetService = allowingTenantBudgetService();
    ScriptWorkItem item = pluginWorkItem();
    PluginRuntimeState state = pluginState(1L, 1L);
    state.setPluginState(pluginStateName);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(Optional.of(state));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            tenantBudgetService,
            pluginRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("plugin_disabled");
    verify(tenantBudgetService, Mockito.never()).tryReserve(Mockito.any(), Mockito.any());
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
  }

  @Test
  void rechecksPluginFenceBeforeDefinitionLookupAndZeroCommandSuccess() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = pluginWorkItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    PluginRuntimeState enabled = pluginState(1L, 1L);
    PluginRuntimeState disabled = pluginState(1L, 1L);
    disabled.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(Optional.of(enabled), Optional.of(disabled));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            allowingTenantBudgetService(),
            pluginRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("plugin_disabled");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    verify(pluginRepository, Mockito.times(2))
        .findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1");
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
  }

  @Test
  void missingCurrentPluginAuthorityRequeuesAndContinuesWithSibling() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = pluginWorkItem();
    ScriptWorkItem sibling = workItem();
    sibling.setId(100L);
    sibling.setScriptId("script-2");
    ScriptDefinition siblingDefinition = scriptDefinition();
    siblingDefinition.setDefinition("{\"emitCommands\":[]}");
    when(workItemService.claimPendingForEvaluation(2)).thenReturn(List.of(item, sibling));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-2"))
        .thenReturn(Optional.of(siblingDefinition));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(Optional.empty());
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            allowingTenantBudgetService(),
            pluginRepository);

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(2);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(result.completedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(sibling.getStatus()).isEqualTo("HANDED_OFF");
    verify(workItemRepository, Mockito.times(2)).save(Mockito.any());
    verify(auditRepository, Mockito.never()).findByWorkItemId(99L);
    verify(definitionRepository).findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-2");
    Mockito.verifyNoInteractions(handoffService);
  }

  @ParameterizedTest
  @CsvSource({"0, 15", "1, 30", "2, 60"})
  void retryablePluginFenceBeforeDefinitionLookupSchedulesBoundedRetry(
      int priorRetryCount, int expectedDelaySeconds) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    ScriptWorkItem item = pluginWorkItem();
    item.setAuthorityUnavailableRetryCount(priorRetryCount);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(automationQueueService.drainIndexedWorkItemPointers(2, 1)).thenReturn(List.of());
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(Optional.empty());
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            automationQueueService,
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            null,
            null,
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            pluginRepository);

    Instant startedAt = Instant.now();
    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);
    Instant completedAt = Instant.now();

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(item.getAuthorityUnavailableRetryCount()).isEqualTo(priorRetryCount + 1);
    assertThat(item.getNextEligibleAt())
        .isBetween(
            startedAt.plusSeconds(expectedDelaySeconds),
            completedAt.plusSeconds(expectedDelaySeconds));
    verify(workItemRepository).save(item);
    verify(automationQueueService, Mockito.never()).enqueueWorkItem(Mockito.any());
    verify(auditRepository, Mockito.never()).findByWorkItemId(Mockito.anyLong());
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void successfulPluginFenceValidationResetsBudgetBeforeLaterAuthorityGap(int priorRetryCount) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = pluginWorkItem();
    item.setAuthorityUnavailableRetryCount(priorRetryCount);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(Optional.of(pluginState(1L, 1L)), Optional.empty());
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            allowingTenantBudgetService(),
            pluginRepository);

    Instant startedAt = Instant.now();
    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);
    Instant completedAt = Instant.now();

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(item.getAuthorityUnavailableRetryCount()).isEqualTo(1);
    assertThat(item.getNextEligibleAt())
        .isBetween(startedAt.plusSeconds(15), completedAt.plusSeconds(15));
    verify(workItemRepository).save(item);
    verify(auditRepository, Mockito.never()).findByWorkItemId(Mockito.anyLong());
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
  }

  @Test
  void authorityUnavailableExhaustionDeadLettersWithDurableReason() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = pluginWorkItem();
    item.setAuthorityUnavailableRetryCount(3);
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(Optional.empty());
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            allowingTenantBudgetService(),
            pluginRepository);

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getAuthorityUnavailableRetryCount()).isEqualTo(3);
    assertThat(item.getCancelReason()).isEqualTo("authority_unavailable");
    assertThat(audit.getFinalOutcome()).isEqualTo("infrastructure_error");
    assertThat(audit.getFinalReason()).isEqualTo("authority_unavailable");
    verify(workItemRepository).save(item);
    verify(auditRepository).save(audit);
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "NOT_A_PLUGIN_STATE")
  void malformedCurrentPluginAuthorityRequeuesWithoutTerminalWrites(String pluginStateName) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = pluginWorkItem();
    PluginRuntimeState malformed = pluginState(1L, 1L);
    malformed.setPluginState(pluginStateName);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(Optional.of(malformed));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            allowingTenantBudgetService(),
            pluginRepository);

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    verify(workItemRepository).save(item);
    verify(auditRepository, Mockito.never()).findByWorkItemId(Mockito.anyLong());
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
  }

  @Test
  void invalidCurrentPluginEvidenceRequeuesWithoutTerminalWrites() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = pluginWorkItem();
    PluginRuntimeState invalid = pluginState(1L, 1L);
    invalid.setActivePluginVersionId("");
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(Optional.of(invalid));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            allowingTenantBudgetService(),
            pluginRepository);

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    verify(workItemRepository).save(item);
    verify(auditRepository, Mockito.never()).findByWorkItemId(Mockito.anyLong());
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
  }

  @Test
  void missingInjectedPluginAuthorityRequeuesWithoutTerminalWrites() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItem item = pluginWorkItem();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("PENDING_EVALUATION");
    verify(workItemRepository).save(item);
    verify(auditRepository, Mockito.never()).findByWorkItemId(Mockito.anyLong());
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
  }

  @Test
  void rejectsMissingPluginEvidenceBeforeRepositoryQuotaAndDsl() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptTenantBudgetService tenantBudgetService = allowingTenantBudgetService();
    ScriptWorkItem item = pluginWorkItem();
    item.setPluginActivationEpoch(0L);
    item.setLifecycleRevision(0L);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            tenantBudgetService,
            pluginRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("plugin_lifecycle_evidence_unavailable");
    verify(tenantBudgetService, Mockito.never()).tryReserve(Mockito.any(), Mockito.any());
    Mockito.verifyNoInteractions(pluginRepository, definitionRepository, handoffService);
  }

  @Test
  void evaluatesAndHandsOffPluginWithExactCurrentFence() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptTenantBudgetService tenantBudgetService = allowingTenantBudgetService();
    ScriptWorkItem item = pluginWorkItem();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[{\"commandText\":\"LOOK\"}]}");
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(Optional.of(pluginState(1L, 1L)));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenReturn(
            new ScriptGameplayCommandHandoffService.HandoffResult(
                true, "ENQUEUED", "auto-1", "", "", ""));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            tenantBudgetService,
            pluginRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("HANDED_OFF");
    verify(pluginRepository, Mockito.times(3))
        .findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1");
    verify(handoffService).handoff(Mockito.eq(item), Mockito.any());
  }

  @Test
  void evaluationCommitsBeforeHandoffLoopStarts() {
    List<String> operations = new ArrayList<>();
    RecordingExecutionTransactionManager transactionManager =
        new RecordingExecutionTransactionManager(operations);
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptWorkItem item = workItem();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[{\"commandText\":\"LOOK\"}]}");
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenAnswer(
            invocation -> {
              operations.add(
                  "runtime:" + TransactionSynchronizationManager.isActualTransactionActive());
              return runtimeStateResponse();
            });
    item.setStatus("PENDING_EVALUATION");
    when(workItemRepository.findByStatusOrderByCreatedAtAscIdAsc(
            Mockito.eq("PENDING_EVALUATION"),
            Mockito.any(Instant.class),
            Mockito.any(Pageable.class)))
        .thenReturn(List.of(item));
    Mockito.doAnswer(
            invocation -> {
              ScriptWorkItem candidate =
                  invocation.<List<Long>>getArgument(0).contains(item.getId()) ? item : null;
              if (candidate == null || !"PENDING_EVALUATION".equals(candidate.getStatus())) {
                return List.of();
              }
              candidate.setStatus("EVALUATING");
              return List.of(candidate);
            })
        .when(workItemService)
        .claimPendingForEvaluation(Mockito.anyList(), Mockito.eq(1));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Mockito.doAnswer(
            invocation -> {
              operations.add(
                  "begin-fanout:" + TransactionSynchronizationManager.isActualTransactionActive());
              return null;
            })
        .when(handoffService)
        .beginAggregateFanout(Mockito.eq(item));
    Mockito.doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              operations.add(
                  "handoff:" + TransactionSynchronizationManager.isActualTransactionActive());
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  true, "ENQUEUED", "command-1", "", "", "");
            })
        .when(handoffService)
        .handoff(Mockito.eq(item), Mockito.any());
    Mockito.doAnswer(
            invocation -> {
              operations.add(
                  "end-fanout:" + TransactionSynchronizationManager.isActualTransactionActive());
              return null;
            })
        .when(handoffService)
        .endAggregateFanout(Mockito.eq(item));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            null,
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            null,
            null,
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            gameSessionClient,
            null,
            transactionManager);

    TransactionSynchronizationManager.setActualTransactionActive(false);
    try {
      ScriptWorkItemExecutionService.ExecutionBatchResult result =
          service.processPendingWorkItems(1);

      assertThat(result.completedCount()).isEqualTo(1);
      assertThat(operations)
          .containsExactly(
              "tx-begin:0",
              "tx-commit-1",
              "runtime:false",
              "tx-begin:0",
              "tx-commit-2",
              "runtime:false",
              "begin-fanout:false",
              "tx-begin:0",
              "tx-commit-3",
              "handoff:false",
              "end-fanout:false",
              "tx-begin:0",
              "tx-commit-4");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  @Test
  void coreWorkDoesNotConsultPluginLifecycleFence() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = workItem();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[]}");
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            allowingTenantBudgetService(),
            pluginRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("HANDED_OFF");
    Mockito.verifyNoInteractions(pluginRepository);
  }

  @Test
  void rejectsPluginStateChangedBetweenDslEvaluationAndHandoff() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptTenantBudgetService tenantBudgetService = allowingTenantBudgetService();
    ScriptWorkItem item = pluginWorkItem();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[{\"commandText\":\"LOOK\"}]}");
    PluginRuntimeState current = pluginState(1L, 1L);
    PluginRuntimeState changed = pluginState(2L, 1L);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(Optional.of(current), Optional.of(current), Optional.of(changed));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            tenantBudgetService,
            pluginRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("plugin_activation_epoch_mismatch");
    verify(definitionRepository).findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1");
    verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
  }

  @Test
  void aggregatePreflightFailureLeavesEvaluatedParentUnresolvedAndEndsFanout() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptWorkItem item = workItem();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[{\"commandText\":\"LOOK\"}]}");
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Mockito.doThrow(new IllegalStateException("admission store unavailable"))
        .when(handoffService)
        .beginAggregateFanout(item);

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.claimedCount()).isEqualTo(1);
    assertThat(result.completedCount()).isZero();
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("EVALUATING");
    assertThat(item.getCancelReason())
        .isEqualTo("post_evaluation_reconciliation_required:authority_unavailable");
    verify(handoffService).beginAggregateFanout(item);
    verify(handoffService).endAggregateFanout(item);
    verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    verify(workItemRepository).save(item);
  }

  @Test
  void rechecksPluginFenceBeforeEveryHandoff() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = pluginWorkItem();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        "{\"emitCommands\":[{\"commandText\":\"LOOK\"},{\"commandText\":\"WAIT\"}]}");
    PluginRuntimeState current = pluginState(1L, 1L);
    PluginRuntimeState changed = pluginState(2L, 1L);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(
            Optional.of(current), Optional.of(current), Optional.of(current), Optional.of(changed));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenReturn(
            new ScriptGameplayCommandHandoffService.HandoffResult(
                true, "ENQUEUED", "auto-1", "", "", ""));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            allowingTenantBudgetService(),
            pluginRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("plugin_activation_epoch_mismatch");
    verify(pluginRepository, Mockito.times(4))
        .findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1");
    verify(handoffService, Mockito.times(1)).handoff(Mockito.eq(item), Mockito.any());
  }

  @Test
  void partialFanoutPreservesAcceptedChildAndStopsAfterAmbiguousChild() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = pluginWorkItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        "{\"emitCommands\":[{\"commandText\":\"LOOK\"},{\"commandText\":\"WAIT\"},"
            + "{\"commandText\":\"SAY\"}]}");
    PluginRuntimeState current = pluginState(1L, 1L);
    List<Integer> attemptedOrdinals = new ArrayList<>();
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(Optional.of(current));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              ScriptGameplayCommandHandoffService.EmittedCommand command =
                  invocation.getArgument(1);
              attemptedOrdinals.add(command.ordinal());
              item.setStatus("HANDOFF_IN_FLIGHT");
              if (command.ordinal() == 0) {
                return new ScriptGameplayCommandHandoffService.HandoffResult(
                    true, "ENQUEUED", "auto-1", "", "", "");
              }
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  false, "HANDOFF_IN_FLIGHT", "", "", "", "HANDOFF_IN_FLIGHT");
            });

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            allowingTenantBudgetService(),
            pluginRepository);

    service.processPendingWorkItems(1);

    assertThat(item.getStatus()).isEqualTo("HANDOFF_IN_FLIGHT");
    assertThat(item.getCancelReason()).isNull();
    assertThat(audit.getFinalStage()).isNull();
    assertThat(audit.getFinalOutcome()).isNull();
    assertThat(attemptedOrdinals).containsExactly(0, 1);
    verify(handoffService, Mockito.times(2)).handoff(Mockito.eq(item), Mockito.any());
    verify(handoffService, Mockito.never())
        .recordUnattempted(Mockito.eq(item), Mockito.any(), Mockito.anyString());
    verify(workItemRepository, Mockito.never()).save(item);
  }

  @Test
  void unexpectedItemFailureDoesNotAbortLaterClaimedItems() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptWorkItem first = workItem();
    ScriptWorkItem second = workItem();
    second.setId(100L);
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[{\"commandText\":\"LOOK\"}]}");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(first, second));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(handoffService.handoff(Mockito.any(), Mockito.any()))
        .thenAnswer(
            invocation -> {
              ScriptWorkItem item = invocation.getArgument(0);
              if (item == first) {
                throw new IllegalStateException("unexpected handoff failure");
              }
              second.setStatus("HANDED_OFF");
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  true, "ENQUEUED", "auto-2", "", "", "");
            });

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.claimedCount()).isEqualTo(2);
    assertThat(result.completedCount()).isEqualTo(1);
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(first.getStatus()).isEqualTo("EVALUATING");
    assertThat(second.getStatus()).isEqualTo("HANDED_OFF");
    verify(workItemRepository).save(second);
    verify(workItemRepository, Mockito.never()).save(first);
    verify(handoffService, Mockito.times(2)).handoff(Mockito.any(), Mockito.any());
  }

  @Test
  void retryableHandoffAttemptLeavesEvaluatedParentUnresolved() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchInstanceRolloutProjectionService rolloutProjectionService =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class);
    AutomationQueueService automationQueueService = Mockito.mock(AutomationQueueService.class);
    ScriptWorkItem item = workItem();
    Instant eligibleAtBeforeRetry = item.getNextEligibleAt();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":\"entity-2\"}]}");
    List<String> operations = new ArrayList<>();
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenReturn(
            new ScriptGameplayCommandHandoffService.HandoffResult(
                false, "REMOTE_REJECTED", "", "", "", "QUEUE_UNAVAILABLE"));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(
            invocation -> {
              ScriptWorkItem saved = invocation.getArgument(0);
              operations.add("save:" + saved.getStatus());
              return saved;
            });
    Mockito.doAnswer(
            invocation -> {
              operations.add("refresh");
              return null;
            })
        .when(rolloutProjectionService)
        .refreshForWorkItem(Mockito.any());
    Mockito.doAnswer(
            invocation -> {
              operations.add("enqueue");
              return null;
            })
        .when(automationQueueService)
        .enqueueWorkItem(Mockito.any());
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            rolloutProjectionService,
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            automationQueueService);

    TransactionSynchronizationManager.initSynchronization();
    try {
      ScriptWorkItemExecutionService.ExecutionBatchResult result =
          service.processPendingWorkItems(10);

      assertThat(result.failedCount()).isEqualTo(1);
      assertThat(item.getStatus()).isEqualTo("EVALUATING");
      assertThat(item.getAuthorityUnavailableRetryCount()).isZero();
      assertThat(item.getNextEligibleAt()).isEqualTo(eligibleAtBeforeRetry);
      assertThat(item.getCancelReason())
          .isEqualTo("post_evaluation_reconciliation_required:authority_unavailable");
      assertThat(item.getUpdatedAt()).isNotNull();
      assertThat(operations).containsExactly("save:EVALUATING");
      assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
      verify(automationQueueService, Mockito.never()).enqueueWorkItem(Mockito.any());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void retryableHandoffRollbackLeavesDetachedParentUnresolvedWithoutReadback() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptWorkItem item = workItem();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[{\"commandText\":\"LOOK\"}]}");
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              item.setStatus("CANCELED");
              item.setRowVersion(8);
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  false, "REMOTE_REJECTED", "", "", "", "QUEUE_UNAVAILABLE");
            });
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getRowVersion()).isEqualTo(8);
    assertThat(item.getCancelReason()).isNull();
    verify(workItemRepository, Mockito.never()).findById(item.getId());
    verify(workItemRepository, Mockito.never()).save(Mockito.any());
  }

  @Test
  void retryableHandoffDispositionMarksInFlightParentWithoutChangingItsStatus() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptWorkItem item = workItem();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[{\"commandText\":\"LOOK\"}]}");
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              item.setStatus("HANDOFF_IN_FLIGHT");
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  false, "REMOTE_REJECTED", "", "", "", "QUEUE_UNAVAILABLE");
            });

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("HANDOFF_IN_FLIGHT");
    assertThat(item.getCancelReason())
        .isEqualTo("post_evaluation_reconciliation_required:authority_unavailable");
    verify(workItemRepository, Mockito.never()).findById(item.getId());
    verify(workItemRepository).save(item);
    verify(handoffService).handoff(Mockito.eq(item), Mockito.any());
  }

  @Test
  void retryableHandoffPreflightLeavesEvaluatedParentUnresolved() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptWorkItem item = workItem();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[{\"commandText\":\"LOOK\"}]}");
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    Mockito.doThrow(new IllegalStateException("handoff owner unavailable"))
        .when(handoffService)
        .beginAggregateFanout(item);

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            Mockito.mock(ScriptEventAuditRepository.class),
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("EVALUATING");
    assertThat(item.getCancelReason())
        .isEqualTo("post_evaluation_reconciliation_required:authority_unavailable");
    verify(handoffService).beginAggregateFanout(item);
    verify(handoffService).endAggregateFanout(item);
    verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    verify(workItemRepository).save(item);
  }

  @ParameterizedTest
  @CsvSource({"0", "1"})
  void retryablePluginFenceDuringFanoutLeavesWorkUnresolved(int acceptedChildren) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = pluginWorkItem();
    ScriptWorkItem sibling = workItem();
    sibling.setId(100L);
    sibling.setScriptId("script-2");
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        "{\"emitCommands\":[{\"commandText\":\"LOOK\"},{\"commandText\":\"WAIT\"}]}");
    ScriptDefinition siblingDefinition = scriptDefinition();
    siblingDefinition.setDefinition("{\"emitCommands\":[]}");
    PluginRuntimeState current = pluginState(1L, 1L);
    AtomicInteger pluginFenceReadCount = new AtomicInteger();
    when(workItemService.claimPendingForEvaluation(2)).thenReturn(List.of(item, sibling));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-2"))
        .thenReturn(Optional.of(siblingDefinition));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenAnswer(
            invocation ->
                pluginFenceReadCount.getAndIncrement() < 2 + acceptedChildren
                    ? Optional.of(current)
                    : Optional.empty());
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              if (acceptedChildren > 0) {
                item.setStatus("HANDOFF_IN_FLIGHT");
              }
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  true, "ENQUEUED", "auto-1", "", "", "");
            });
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            allowingTenantBudgetService(),
            pluginRepository);

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(2);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(result.completedCount()).isEqualTo(1);
    assertThat(item.getStatus())
        .isEqualTo(acceptedChildren == 0 ? "EVALUATING" : "HANDOFF_IN_FLIGHT");
    assertThat(item.getCancelReason())
        .isEqualTo("post_evaluation_reconciliation_required:authority_unavailable");
    assertThat(sibling.getStatus()).isEqualTo("HANDED_OFF");
    verify(handoffService, Mockito.times(acceptedChildren))
        .handoff(Mockito.eq(item), Mockito.any());
    verify(handoffService, Mockito.times(2 - acceptedChildren))
        .recordUnattempted(Mockito.eq(item), Mockito.any(), Mockito.eq("authority_unavailable"));
    verify(handoffService).beginAggregateFanout(item);
    verify(handoffService).endAggregateFanout(item);
    verify(workItemRepository).save(sibling);
    verify(workItemRepository).save(item);
    verify(workItemRepository, Mockito.never()).findById(item.getId());
  }

  @Test
  void priorTerminalHandoffDispositionWinsOverRetryablePluginFenceDuringFanout() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = pluginWorkItem();
    ScriptWorkItem sibling = workItem();
    sibling.setId(100L);
    sibling.setScriptId("script-2");
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        "{\"emitCommands\":[{\"commandText\":\"LOOK\"},{\"commandText\":\"WAIT\"}]}");
    ScriptDefinition siblingDefinition = scriptDefinition();
    siblingDefinition.setDefinition("{\"emitCommands\":[]}");
    PluginRuntimeState current = pluginState(1L, 1L);
    when(workItemService.claimPendingForEvaluation(2)).thenReturn(List.of(item, sibling));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-2"))
        .thenReturn(Optional.of(siblingDefinition));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(
            Optional.of(current), Optional.of(current), Optional.of(current), Optional.empty());
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenReturn(
            new ScriptGameplayCommandHandoffService.HandoffResult(
                false, "RUNTIME_SCOPE_CHANGED", "", "", "", "RUNTIME_SCOPE_CHANGED"));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            allowingTenantBudgetService(),
            pluginRepository);

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(2);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(result.completedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("runtime_scope_changed");
    assertThat(sibling.getStatus()).isEqualTo("HANDED_OFF");
    verify(handoffService, Mockito.times(1)).handoff(Mockito.eq(item), Mockito.any());
    verify(handoffService).beginAggregateFanout(item);
    verify(handoffService).endAggregateFanout(item);
  }

  @Test
  void priorTerminalHandoffDispositionWinsOverTerminalPluginFenceAndClosesFanout() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = pluginWorkItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        "{\"emitCommands\":[{\"commandText\":\"LOOK\"},{\"commandText\":\"WAIT\"},"
            + "{\"commandText\":\"SAY\"}]}");
    PluginRuntimeState enabled = pluginState(1L, 1L);
    PluginRuntimeState disabled = pluginState(1L, 1L);
    disabled.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(
            Optional.of(enabled),
            Optional.of(enabled),
            Optional.of(enabled),
            Optional.of(enabled),
            Optional.of(disabled));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              ScriptGameplayCommandHandoffService.EmittedCommand command =
                  invocation.getArgument(1);
              if (command.ordinal() == 0) {
                return new ScriptGameplayCommandHandoffService.HandoffResult(
                    true, "ENQUEUED", "auto-1", "", "", "");
              }
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  false, "REJECTED", "", "", "", "REMOTE_RESPONSE_INVALID");
            });
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            allowingTenantBudgetService(),
            pluginRepository);

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("remote_response_invalid");
    assertThat(audit.getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("infrastructure_error");
    assertThat(audit.getFinalReason()).isEqualTo("remote_response_invalid");
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService, Mockito.times(2)).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::ordinal)
        .containsExactly(0, 1);
    verify(handoffService).beginAggregateFanout(item);
    verify(handoffService).endAggregateFanout(item);
  }

  @Test
  void recordsRemainingCommandsAsUnattemptedWhenPluginFenceChangesMidFanout() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    ScriptWorkItem item = pluginWorkItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        "{\"emitCommands\":[{\"commandText\":\"LOOK\"},{\"commandText\":\"WAIT\"},"
            + "{\"commandText\":\"SAY\"}]}");
    PluginRuntimeState enabled = pluginState(1L, 1L);
    PluginRuntimeState disabled = pluginState(1L, 1L);
    disabled.setPluginState(PluginState.PLUGIN_STATE_DISABLED.name());
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(
            Optional.of(enabled),
            Optional.of(enabled),
            Optional.of(enabled),
            Optional.of(disabled));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenReturn(
            new ScriptGameplayCommandHandoffService.HandoffResult(
                true, "ENQUEUED", "auto-1", "", "", ""));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        pluginFenceService(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            allowingTenantBudgetService(),
            pluginRepository);

    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("plugin_disabled");
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> attemptedCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService).handoff(Mockito.eq(item), attemptedCaptor.capture());
    assertThat(attemptedCaptor.getValue().ordinal()).isZero();
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> unattemptedCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService, Mockito.times(2))
        .recordUnattempted(
            Mockito.eq(item), unattemptedCaptor.capture(), Mockito.eq("plugin_disabled"));
    assertThat(unattemptedCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::ordinal)
        .containsExactly(1, 2);
    verify(handoffService, Mockito.times(1)).handoff(Mockito.eq(item), Mockito.any());
    verify(handoffService).beginAggregateFanout(item);
    verify(handoffService).endAggregateFanout(item);
  }

  @ParameterizedTest(name = "accepts command metadata {0}")
  @CsvSource({
    "'{}', false, 0",
    "'{\"requiresSoloTick\":false}', false, 0",
    "'{\"requiresSoloTick\":true}', true, 0",
    "'{\"dueTickId\":0}', false, 0",
    "'{\"dueTickId\":42}', false, 42"
  })
  void acceptsTypedOptionalCommandMetadata(
      String metadataJson, boolean expectedRequiresSoloTick, long expectedDueTickId) {
    String metadata = metadataJson.substring(1, metadataJson.length() - 1);
    String definitionJson =
        "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":\"entity-2\""
            + (metadata.isBlank() ? "" : "," + metadata)
            + "}]}";
    ExecutionFixture fixture = executeDefinition(definitionJson, new ScriptOutputProperties());

    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(fixture.handoffService()).handoff(Mockito.eq(fixture.item()), commandCaptor.capture());
    assertThat(commandCaptor.getValue().requiresSoloTick()).isEqualTo(expectedRequiresSoloTick);
    assertThat(commandCaptor.getValue().dueTickId()).isEqualTo(expectedDueTickId);
  }

  @Test
  void legacyHandoffAuditTagsAreNormalizedBeforeTerminalMetric() {
    ExecutionFixture fixture =
        executeDefinitionWithRejectedHandoff("DEAD_LETTERED", "handoff_failed", "REMOTE_REJECTED");

    assertThat(fixture.result().failedCount()).isEqualTo(1);
    assertThat(fixture.item().getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(fixture.audit().getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(fixture.audit().getFinalOutcome()).isEqualTo("infrastructure_error");
    assertThat(
            fixture
                .meterRegistry()
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "TICK_HANDOFF")
                .tag("outcome", "infrastructure_error")
                .tag("priority", "normal")
                .tag("source_class", "gameplay")
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
    assertThat(
            fixture
                .meterRegistry()
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "HANDOFF")
                .tag("outcome", "handoff_failed")
                .counter())
        .isNull();
  }

  @Test
  void rejectedHandoffWithNonTerminalWorkItemFailsClosed() {
    ExecutionFixture fixture =
        executeDefinitionWithRejectedHandoff("EVALUATING", "REJECTED", "REMOTE_RESPONSE_INVALID");

    assertThat(fixture.result().failedCount()).isEqualTo(1);
    assertThat(fixture.item().getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(fixture.item().getFailureGeneration())
        .isEqualTo(fixture.initialFailureGeneration() + 1L);
    assertThat(fixture.item().getCancelReason()).isEqualTo("remote_response_invalid");
    assertThat(fixture.audit().getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(fixture.audit().getFinalOutcome()).isEqualTo("infrastructure_error");
    assertThat(fixture.audit().getFinalReason()).isEqualTo("remote_response_invalid");
  }

  @Test
  void deadLetterGenerationAdvancesOncePerDistinctTransition() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(1))
        .thenReturn(List.of(item), List.of(item), List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.empty());
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry());

    service.processPendingWorkItems(1);
    assertThat(item.getFailureGeneration()).isEqualTo(1L);

    item.setStatus("PENDING_EVALUATION");
    service.processPendingWorkItems(1);
    assertThat(item.getFailureGeneration()).isEqualTo(2L);

    service.processPendingWorkItems(1);
    assertThat(item.getFailureGeneration()).isEqualTo(2L);
  }

  @Test
  void defersOutcomeMetricUntilTransactionCommit() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
      executeDefinition("{\"emitCommands\":[]}", new ScriptOutputProperties(), meterRegistry);

      assertThat(meterRegistry.find("automation_script_work_item_outcomes_total").counter())
          .isNull();
      completeSynchronizations(TransactionSynchronization.STATUS_COMMITTED);
      assertThat(outcomeCounter(meterRegistry).count()).isEqualTo(1.0);
    } finally {
      clearSynchronizations();
    }
  }

  @Test
  void doesNotEmitOutcomeMetricAfterTransactionRollback() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
      executeDefinition("{\"emitCommands\":[]}", new ScriptOutputProperties(), meterRegistry);

      completeSynchronizations(TransactionSynchronization.STATUS_ROLLED_BACK);
      assertThat(meterRegistry.find("automation_script_work_item_outcomes_total").counter())
          .isNull();
    } finally {
      clearSynchronizations();
    }
  }

  @Test
  void retryAfterRollbackEmitsOnlyCommittedOutcomeMetric() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TransactionSynchronizationManager.initSynchronization();
    try {
      executeDefinition("{\"emitCommands\":[]}", new ScriptOutputProperties(), meterRegistry);
      completeSynchronizations(TransactionSynchronization.STATUS_ROLLED_BACK);
    } finally {
      clearSynchronizations();
    }

    TransactionSynchronizationManager.initSynchronization();
    try {
      executeDefinition("{\"emitCommands\":[]}", new ScriptOutputProperties(), meterRegistry);
      completeSynchronizations(TransactionSynchronization.STATUS_COMMITTED);
    } finally {
      clearSynchronizations();
    }

    assertThat(outcomeCounter(meterRegistry).count()).isEqualTo(1.0);
  }

  @Test
  void rollbackFenceCancellationRecordsCanceledTerminalMetric() {
    ExecutionFixture fixture =
        executeDefinitionWithRejectedHandoff("CANCELED", "canceled", "rollback_epoch_advanced");

    assertThat(fixture.result().failedCount()).isEqualTo(1);
    assertThat(fixture.item().getStatus()).isEqualTo("CANCELED");
    assertThat(fixture.item().getCancelReason()).isEqualTo("rollback_epoch_advanced");
    assertThat(fixture.audit().getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(fixture.audit().getFinalOutcome()).isEqualTo("canceled");
    assertThat(
            fixture
                .meterRegistry()
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "TICK_HANDOFF")
                .tag("outcome", "canceled")
                .tag("priority", "normal")
                .tag("source_class", "gameplay")
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
  }

  @Test
  void acceptedMultiCommandWorkItemRecordsOneTerminalMetric() {
    ExecutionFixture fixture =
        executeDefinition(
            """
            {
              "emitCommands": [
                {"commandText": "LOOK", "targetEntityId": "entity-2"},
                {"commandText": "LOOK", "targetEntityId": "entity-3"}
              ]
            }
            """,
            new ScriptOutputProperties());

    assertThat(fixture.result().completedCount()).isEqualTo(1);
    verify(fixture.handoffService(), Mockito.times(2))
        .handoff(Mockito.eq(fixture.item()), Mockito.any());
    assertThat(
            fixture
                .meterRegistry()
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "TICK_HANDOFF")
                .tag("outcome", "handoff_accepted")
                .tag("priority", "normal")
                .tag("source_class", "gameplay")
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
  }

  @Test
  void terminalAggregateRejectionWinsOverLaterRetryableSibling() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {"commandText": "A", "targetEntityId": "entity-a"},
            {"commandText": "B", "targetEntityId": "entity-b"},
            {"commandText": "C", "targetEntityId": "entity-c"}
          ]
        }
        """);

    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              ScriptGameplayCommandHandoffService.EmittedCommand command =
                  invocation.getArgument(1);
              if (command.ordinal() == 0) {
                return new ScriptGameplayCommandHandoffService.HandoffResult(
                    false, "RUNTIME_SCOPE_CHANGED", "", "", "", "RUNTIME_SCOPE_CHANGED");
              }
              if (command.ordinal() == 1) {
                return new ScriptGameplayCommandHandoffService.HandoffResult(
                    false, "REMOTE_REJECTED", "", "", "", "AUTHORITY_UNAVAILABLE");
              }
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  true, "ENQUEUED", "auto-" + command.ordinal(), "", "", "");
            });
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isZero();
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("runtime_scope_changed");
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService, Mockito.times(3)).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::ordinal)
        .containsExactly(0, 1, 2);
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::commandText)
        .containsExactly("A", "B", "C");
    verify(handoffService).beginAggregateFanout(item);
    verify(handoffService).endAggregateFanout(item);
    assertThat(audit.getFinalStage()).isEqualTo("TICK_HANDOFF");
    assertThat(audit.getFinalOutcome()).isEqualTo("canceled");
    assertThat(audit.getFinalReason()).isEqualTo("runtime_scope_changed");
  }

  @Test
  void supportsCommandSpecificTargetEntityTemplates() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setPayloadJson("{\"commandName\":\"LOOK\",\"target\":\"entity-2\"}");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        """
        {
          "eventHandlers": {
            "onCommand": {
              "emitCommands": [
                {
                  "targetEntityId": "target-{{payload.target}}",
                  "commandText": "say {{payload.commandName}} for {{entityId}}"
                }
              ]
            }
          }
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              item.setStatus("HANDED_OFF");
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  true, "ENQUEUED", "auto-1", "", "", "");
            });
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getValue().commandText()).isEqualTo("say LOOK for entity-1");
    assertThat(commandCaptor.getValue().targetEntityId()).isEqualTo("target-entity-2");
    assertThat(commandCaptor.getValue().targetGameInstanceId()).isEqualTo("7");
    assertThat(commandCaptor.getValue().targetRegionId()).isEqualTo("region-1");
    assertThat(commandCaptor.getValue().targetRegionEpoch()).isEqualTo(12L);
    assertThat(audit.getFinalOutcome()).isEqualTo("handoff_accepted");
  }

  @Test
  void supportsExplicitTargetRuntimeScopeTemplates() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setPayloadJson(
        "{\"targetInstance\":\"remote-7\",\"targetRegion\":\"region-9\",\"targetEpoch\":44}");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "commandText": "say remote hello",
              "targetGameInstanceId": "{{payload.targetInstance}}",
              "targetRegionId": "{{payload.targetRegion}}",
              "targetRegionEpoch": "{{payload.targetEpoch}}"
            }
          ]
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              item.setStatus("HANDED_OFF");
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  true, "REMOTE_SCHEDULED", "", "coord-1", "followup-1", "");
            });
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getValue().targetGameInstanceId()).isEqualTo("remote-7");
    assertThat(commandCaptor.getValue().targetRegionId()).isEqualTo("region-9");
    assertThat(commandCaptor.getValue().targetRegionEpoch()).isEqualTo(44L);
  }

  @Test
  void supportsStructuredCommandAliasAndArguments() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setPayloadJson("{\"direction\":\"north\",\"thing\":\"old chest\"}");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "commandAlias": "MOVE",
              "arguments": ["{{payload.direction}}"]
            },
            {
              "commandAlias": "LOOK",
              "arguments": "AT {{payload.thing}}"
            }
          ]
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation ->
                new ScriptGameplayCommandHandoffService.HandoffResult(
                    true,
                    "ENQUEUED",
                    "auto-"
                        + invocation
                            .<ScriptGameplayCommandHandoffService.EmittedCommand>getArgument(1)
                            .ordinal(),
                    "",
                    "",
                    ""));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService, Mockito.times(2)).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::commandText)
        .containsExactly("MOVE north", "LOOK AT old chest");
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::ordinal)
        .containsExactly(0, 1);
    assertThat(audit.getFinalOutcome()).isEqualTo("handoff_accepted");
  }

  @Test
  void supportsMultiTargetCommandFanOut() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setPayloadJson("{\"targetA\":\"entity-2\",\"targetB\":\"entity-3\"}");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "commandAlias": "LOOK",
              "arguments": ["AT", "idol"],
              "targetEntityIds": ["{{payload.targetA}}", "{{payload.targetB}}"]
            }
          ]
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation ->
                new ScriptGameplayCommandHandoffService.HandoffResult(
                    true,
                    "ENQUEUED",
                    "auto-"
                        + invocation
                            .<ScriptGameplayCommandHandoffService.EmittedCommand>getArgument(1)
                            .ordinal(),
                    "",
                    "",
                    ""));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService, Mockito.times(2)).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::commandText)
        .containsExactly("LOOK AT idol", "LOOK AT idol");
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::targetEntityId)
        .containsExactly("entity-2", "entity-3");
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::ordinal)
        .containsExactly(0, 1);
    assertThat(audit.getFinalOutcome()).isEqualTo("handoff_accepted");
  }

  @Test
  void rejectsOversizedMultiTargetExpansionBeforeReadingBeyondLimitAndContinuesBatch() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    outputProperties.setMaxCommandsPerRun(2);

    ScriptWorkItem oversized = workItem();
    ScriptWorkItem valid = workItem();
    valid.setId(100L);
    valid.setScriptId("valid-script");
    ScriptEventAudit oversizedAudit = new ScriptEventAudit();
    ScriptEventAudit validAudit = new ScriptEventAudit();
    ScriptDefinition oversizedDefinition = scriptDefinition();
    oversizedDefinition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "commandText": "LOOK",
              "targetEntityIds": ["entity-1", "entity-2", {"unexpected": "object"}]
            }
          ]
        }
        """);
    ScriptDefinition validDefinition = scriptDefinition();
    validDefinition.setDefinition("{\"emitCommands\":[]}");

    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(oversized, valid));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(oversizedDefinition));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "valid-script"))
        .thenReturn(Optional.of(validDefinition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(oversizedAudit));
    when(auditRepository.findByWorkItemId(100L)).thenReturn(Optional.of(validAudit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.claimedCount()).isEqualTo(2);
    assertThat(result.completedCount()).isEqualTo(1);
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(oversized.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(oversized.getCancelReason()).isEqualTo("command_count_exceeded");
    assertThat(oversizedAudit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(oversizedAudit.getFinalOutcome()).isEqualTo("sandbox_error");
    assertThat(oversizedAudit.getFinalReason()).isEqualTo("command_count_exceeded");
    assertThat(valid.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(validAudit.getFinalOutcome()).isEqualTo("completed_no_commands");
    Mockito.verifyNoInteractions(handoffService);
  }

  @Test
  void countsDuplicateIdsWithinOneMultiTargetExpansionPerEntity() {
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    outputProperties.setMaxCommandsPerRun(10);
    outputProperties.setMaxCommandsPerEntityPerTrigger(2);

    ExecutionFixture fixture =
        executeDefinition(
            """
            {
              "emitCommands": [
                {
                  "commandText": "LOOK",
                  "targetEntityIds": ["entity-2", "entity-2", "entity-2"]
                }
              ]
            }
            """,
            outputProperties);

    assertThat(fixture.result().failedCount()).isEqualTo(1);
    assertThat(fixture.item().getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(fixture.item().getCancelReason()).isEqualTo("per_entity_command_limit_exceeded");
    assertThat(fixture.audit().getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(fixture.audit().getFinalOutcome()).isEqualTo("sandbox_error");
    assertThat(fixture.audit().getFinalReason()).isEqualTo("per_entity_command_limit_exceeded");
    Mockito.verifyNoInteractions(fixture.handoffService());
  }

  @Test
  void acceptsExactGlobalAndPerEntityBoundaryThenRejectsLaterCommandCount() {
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    outputProperties.setMaxCommandsPerRun(2);
    outputProperties.setMaxCommandsPerEntityPerTrigger(2);

    ExecutionFixture fixture =
        executeDefinition(
            """
            {
              "emitCommands": [
                {"commandText": "LOOK", "targetEntityId": "entity-2"},
                {"commandText": "LOOK", "targetEntityId": "entity-2"},
                {"commandText": "LOOK", "targetEntityId": "entity-2"}
              ]
            }
            """,
            outputProperties);

    assertThat(fixture.result().failedCount()).isEqualTo(1);
    assertThat(fixture.item().getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(fixture.item().getCancelReason()).isEqualTo("command_count_exceeded");
    assertThat(fixture.audit().getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(fixture.audit().getFinalOutcome()).isEqualTo("sandbox_error");
    assertThat(fixture.audit().getFinalReason()).isEqualTo("command_count_exceeded");
    Mockito.verifyNoInteractions(fixture.handoffService());
  }

  @Test
  void accumulatesPerEntityLimitAcrossSingleAndMultiTargetCommands() {
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    outputProperties.setMaxCommandsPerRun(10);
    outputProperties.setMaxCommandsPerEntityPerTrigger(2);

    ExecutionFixture fixture =
        executeDefinition(
            """
            {
              "emitCommands": [
                {"commandText": "LOOK", "targetEntityId": "entity-2"},
                {
                  "commandText": "LOOK",
                  "targetEntityIds": ["entity-2", "entity-3"]
                },
                {"commandText": "LOOK", "targetEntityId": "entity-2"}
              ]
            }
            """,
            outputProperties);

    assertThat(fixture.result().failedCount()).isEqualTo(1);
    assertThat(fixture.item().getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(fixture.item().getCancelReason()).isEqualTo("per_entity_command_limit_exceeded");
    assertThat(fixture.audit().getFinalOutcome()).isEqualTo("sandbox_error");
    assertThat(fixture.audit().getFinalReason()).isEqualTo("per_entity_command_limit_exceeded");
    Mockito.verifyNoInteractions(fixture.handoffService());
  }

  @ParameterizedTest(name = "rejects malformed target fields: {1}")
  @MethodSource("malformedTargetDefinitions")
  void rejectsExplicitMalformedTargetFieldsWithoutFallbackAndContinuesBatch(
      String definition, String expectedReason) {
    assertMalformedDefinitionAndContinuesBatch(definition, "onCommand", expectedReason);
  }

  private static Stream<Arguments> malformedTargetDefinitions() {
    return Stream.of(
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":null}]}",
            "target_entity_id_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":{\"id\":\"entity-2\"}}]}",
            "target_entity_id_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":\"entity-2\",\"targetGameInstanceId\":null}]}",
            "target_game_instance_id_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":\"entity-2\",\"targetGameInstanceId\":\"game-2\",\"targetRegionId\":{\"id\":\"region-2\"}}]}",
            "target_region_id_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityIds\":null}]}",
            "target_entity_ids_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityIds\":\"entity-2\"}]}",
            "target_entity_ids_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityIds\":[42]}]}",
            "target_entity_ids_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityIds\":[true]}]}",
            "target_entity_ids_invalid"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityIds\":[\"  \"]}]}",
            "target_entity_id_blank"),
        Arguments.of(
            "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityIds\":[]}]}",
            "target_entity_ids_empty"));
  }

  @Test
  void supportsConditionalCommandEmissionWithoutOrdinalGaps() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setPayloadJson("{\"shouldGreet\":true,\"muted\":false}");
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "commandText": "say hello",
              "when": {"payload.shouldGreet": "true"}
            },
            {
              "commandText": "say hidden",
              "when": {"payload.shouldGreet": "false"}
            },
            {
              "commandText": "say audible",
              "unless": {"payload.muted": "true"}
            }
          ]
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation ->
                new ScriptGameplayCommandHandoffService.HandoffResult(
                    true,
                    "ENQUEUED",
                    "auto-"
                        + invocation
                            .<ScriptGameplayCommandHandoffService.EmittedCommand>getArgument(1)
                            .ordinal(),
                    "",
                    "",
                    ""));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    ArgumentCaptor<ScriptGameplayCommandHandoffService.EmittedCommand> commandCaptor =
        ArgumentCaptor.forClass(ScriptGameplayCommandHandoffService.EmittedCommand.class);
    verify(handoffService, Mockito.times(2)).handoff(Mockito.eq(item), commandCaptor.capture());
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::commandText)
        .containsExactly("say hello", "say audible");
    assertThat(commandCaptor.getAllValues())
        .extracting(ScriptGameplayCommandHandoffService.EmittedCommand::ordinal)
        .containsExactly(0, 1);
    assertThat(audit.getFinalOutcome()).isEqualTo("handoff_accepted");
  }

  @Test
  void deadLettersWhenStructuredCommandArgumentRendersBlank() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "commandAlias": "MOVE",
              "arguments": [""]
            }
          ]
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("command_argument_blank");
    assertThat(audit.getFinalOutcome()).isEqualTo("definition_invalid");
    assertThat(audit.getFinalReason()).isEqualTo("command_argument_blank");
  }

  @Test
  void deadLettersWhenPerTargetCommandLimitIsExceeded() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    outputProperties.setMaxCommandsPerRun(10);
    outputProperties.setMaxCommandsPerEntityPerTrigger(1);
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "targetEntityId": "entity-2",
              "commandText": "say first"
            },
            {
              "targetEntityId": "entity-2",
              "commandText": "say second"
            }
          ]
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("per_entity_command_limit_exceeded");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("sandbox_error");
    assertThat(audit.getFinalReason()).isEqualTo("per_entity_command_limit_exceeded");
  }

  @Test
  void deadLettersWhenDefinitionIsMissing() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.empty());
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("script_definition_missing");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("definition_missing");
  }

  @Test
  void deadLettersWorkItemWithNonPositiveTenantIdBeforeDefinitionLookup() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItem item = workItem();
    item.setTenantId("0");
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("tenant_id_invalid");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("definition_invalid");
    assertThat(audit.getFinalReason()).isEqualTo("tenant_id_invalid");
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
  }

  @Test
  void marksOnLoadWithoutCommandsAsCompletedNoCommands() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setGameInstanceId("");
    item.setRegionId("");
    item.setEntityId("");
    item.setEventType("onLoad");
    item.setQuotaClass(ScriptQuotaClasses.PUBLISH_READINESS);
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"eventHandlers\":{\"onLoad\":{}}}");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptReadinessCapacityService readinessCapacityService = allowingReadinessCapacityService();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            denyingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            meterRegistry,
            null,
            null,
            readinessCapacityService);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    Mockito.verify(definitionRepository)
        .findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1");
    assertThat(item.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("completed_no_commands");
    assertThat(audit.getFinalReason()).isEqualTo("ready_for_tenant");
    var outcomeCounter =
        meterRegistry
            .find("automation_script_work_item_outcomes_total")
            .tag("service", "automation-scripting-service")
            .tag("stage", "DSL_EVAL")
            .tag("outcome", "completed_no_commands")
            .tag("priority", "normal")
            .tag("source_class", "readiness")
            .counter();
    assertThat(outcomeCounter).isNotNull().extracting(counter -> counter.count()).isEqualTo(1.0);
    assertThat(outcomeCounter.getId().getTags())
        .extracting(Tag::getKey)
        .containsExactlyInAnyOrder("service", "stage", "outcome", "priority", "source_class");
    Mockito.verify(readinessCapacityService)
        .release(Mockito.any(ScriptReadinessCapacityService.Reservation.class));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "{",
        "",
        "   ",
        "null",
        "[]",
        "\"scalar\"",
        "{\"emitCommands\":{}}",
        "{\"emitCommands\":null}",
        "{\"eventHandlers\":[]}",
        "{\"eventHandlers\":{\"onLoad\":[]}}",
        "{\"eventHandlers\":{\"onLoad\":{\"emitCommands\":{}}}}"
      })
  void deadLettersMalformedOnLoadAndContinuesBatch(String malformedDefinitionValue) {
    assertMalformedDefinitionAndContinuesBatch(
        malformedDefinitionValue, "onLoad", "definition_json_invalid");
  }

  @ParameterizedTest(name = "{0} rejects {1} and continues the batch")
  @CsvSource({
    "onCommand, '{', definition_json_invalid",
    "onCommand, '[]', definition_json_invalid",
    "onCommand, '{\"eventHandlers\":[]}', definition_json_invalid",
    "onCommand, '{\"emitCommands\":[{\"when\":[]}]}', command_condition_invalid",
    "onTimerExpire, '{', definition_json_invalid",
    "onTimerExpire, '[]', definition_json_invalid",
    "onTimerExpire, '{\"eventHandlers\":[]}', definition_json_invalid",
    "onTimerExpire, '{\"emitCommands\":[{\"when\":[]}]}', command_condition_invalid"
  })
  void deadLettersMalformedNonOnLoadAndContinuesBatch(
      String eventType, String malformedDefinitionValue, String expectedReason) {
    assertMalformedDefinitionAndContinuesBatch(malformedDefinitionValue, eventType, expectedReason);
  }

  @ParameterizedTest(name = "rejects malformed optional command metadata: {1}")
  @CsvSource({
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"requiresSoloTick\":null}]}', requires_solo_tick_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"requiresSoloTick\":\"true\"}]}', requires_solo_tick_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"requiresSoloTick\":1}]}', requires_solo_tick_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"requiresSoloTick\":{}}]}', requires_solo_tick_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"requiresSoloTick\":[]}]}', requires_solo_tick_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":null}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":\"1\"}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":{}}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":[]}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":true}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":-1}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":1.5}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":9223372036854775807}]}', due_tick_id_invalid",
    "'{\"emitCommands\":[{\"commandText\":\"LOOK\",\"dueTickId\":9223372036854775808}]}', due_tick_id_invalid"
  })
  void rejectsMalformedOptionalCommandMetadataAndContinuesBatch(
      String malformedDefinitionValue, String expectedReason) {
    assertMalformedDefinitionAndContinuesBatch(
        malformedDefinitionValue, "onCommand", expectedReason);
  }

  private void assertMalformedDefinitionAndContinuesBatch(
      String malformedDefinitionValue, String eventType, String expectedReason) {
    boolean onLoad = "onLoad".equals(eventType);
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptPatchReadinessProjectionService readinessProjectionService =
        Mockito.mock(ScriptPatchReadinessProjectionService.class);
    ScriptWorkItem malformed = workItem();
    malformed.setEntityId("");
    malformed.setEventType(eventType);
    malformed.setScriptId("malformed-script");
    malformed.setQuotaClass(
        onLoad ? ScriptQuotaClasses.PUBLISH_READINESS : ScriptQuotaClasses.STANDARD_RUNTIME);
    ScriptWorkItem valid = workItem();
    valid.setId(100L);
    valid.setEntityId("");
    valid.setEventType(eventType);
    valid.setScriptId("valid-script");
    valid.setQuotaClass(
        onLoad ? ScriptQuotaClasses.PUBLISH_READINESS : ScriptQuotaClasses.STANDARD_RUNTIME);
    ScriptEventAudit malformedAudit = new ScriptEventAudit();
    ScriptEventAudit validAudit = new ScriptEventAudit();
    ScriptDefinition malformedDefinition = scriptDefinition();
    malformedDefinition.setDefinition(malformedDefinitionValue);
    ScriptDefinition validDefinition = scriptDefinition();
    validDefinition.setDefinition("{\"eventHandlers\":{\"" + eventType + "\":{}}}");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(malformed, valid));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(
            1L, "patch-1", "malformed-script"))
        .thenReturn(Optional.of(malformedDefinition));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "valid-script"))
        .thenReturn(Optional.of(validDefinition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(malformedAudit));
    when(auditRepository.findByWorkItemId(100L)).thenReturn(Optional.of(validAudit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptReadinessCapacityService readinessCapacityService =
        Mockito.mock(ScriptReadinessCapacityService.class);
    when(readinessCapacityService.tryReserve("1", 99L))
        .thenReturn(
            Optional.of(
                new ScriptReadinessCapacityService.Reservation(
                    "1", 99L, "readiness-tenant-token-99", "readiness-cluster-token-99")));
    when(readinessCapacityService.tryReserve("1", 100L))
        .thenReturn(
            Optional.of(
                new ScriptReadinessCapacityService.Reservation(
                    "1", 100L, "readiness-tenant-token-100", "readiness-cluster-token-100")));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            readinessProjectionService,
            readinessCapacityService);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.claimedCount()).isEqualTo(2);
    assertThat(result.completedCount()).isEqualTo(1);
    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(malformed.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(malformed.getCancelReason()).isEqualTo(expectedReason);
    assertThat(malformedAudit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(malformedAudit.getFinalOutcome()).isEqualTo("definition_invalid");
    assertThat(malformedAudit.getFinalReason()).isEqualTo(expectedReason);
    assertThat(valid.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(validAudit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(validAudit.getFinalOutcome()).isEqualTo("completed_no_commands");
    assertThat(validAudit.getFinalReason())
        .isEqualTo(onLoad ? "ready_for_tenant" : "script_emitted_no_commands");
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    if (onLoad) {
      Mockito.verify(readinessProjectionService, Mockito.times(2))
          .refreshFromOnLoadWorkItems("1", "patch-1");
      ArgumentCaptor<ScriptReadinessCapacityService.Reservation> releaseCaptor =
          ArgumentCaptor.forClass(ScriptReadinessCapacityService.Reservation.class);
      Mockito.verify(readinessCapacityService, Mockito.times(2)).release(releaseCaptor.capture());
      assertThat(releaseCaptor.getAllValues())
          .extracting(ScriptReadinessCapacityService.Reservation::workItemId)
          .containsExactly(99L, 100L);
    } else {
      Mockito.verifyNoInteractions(readinessProjectionService, readinessCapacityService);
    }
  }

  @Test
  void cancelsOnLoadWhenPublishReadinessCapacityIsExhausted() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setGameInstanceId("");
    item.setRegionId("");
    item.setEntityId("");
    item.setEventType("onLoad");
    item.setQuotaClass(ScriptQuotaClasses.PUBLISH_READINESS);
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptTenantBudgetService tenantBudgetService = Mockito.mock(ScriptTenantBudgetService.class);
    ScriptReadinessCapacityService readinessCapacityService = denyingReadinessCapacityService();
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            tenantBudgetService,
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            null,
            readinessCapacityService);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("onload_budget_exceeded");
    assertThat(audit.getFinalStage()).isEqualTo("ADMISSION");
    assertThat(audit.getFinalOutcome()).isEqualTo("quota_denied");
    assertThat(audit.getFinalReason()).isEqualTo("onload_budget_exceeded");
    Mockito.verify(tenantBudgetService, Mockito.never()).tryReserve(Mockito.any(), Mockito.any());
    Mockito.verify(readinessCapacityService, Mockito.never())
        .release(Mockito.any(ScriptReadinessCapacityService.Reservation.class));
    Mockito.verify(definitionRepository, Mockito.never())
        .findByTenantIdAndScriptVersionAndName(Mockito.anyLong(), Mockito.any(), Mockito.any());
  }

  @Test
  void rejectsOnLoadThatEmitsCommands() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setGameInstanceId("");
    item.setRegionId("");
    item.setEntityId("");
    item.setEventType("onLoad");
    item.setQuotaClass(ScriptQuotaClasses.PUBLISH_READINESS);
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        "{\"eventHandlers\":{\"onLoad\":{\"emitCommands\":[{\"commandText\":\"say nope\",\"targetEntityId\":\"entity-9\"}]}}}");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(item.getCancelReason()).isEqualTo("onload_commands_not_allowed");
    assertThat(audit.getFinalOutcome()).isEqualTo("definition_invalid");
    assertThat(audit.getFinalReason()).isEqualTo("onload_commands_not_allowed");
  }

  @Test
  void dryRunDoesNotAcquireLiveScriptQuotaOrHandoffCommands() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setDryRun(true);
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        """
        {
          "emitCommands": [
            {
              "commandText": "say dry run"
            }
          ]
        }
        """);
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            denyingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("dry_run_completed");
    assertThat(audit.getFinalReason()).isEqualTo("dry_run_no_handoff");
  }

  @Test
  void dryRunDoesNotReserveLiveTenantBudget() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptTenantBudgetService tenantBudgetService = Mockito.mock(ScriptTenantBudgetService.class);
    ScriptDryRunCapacityService dryRunCapacityService = allowingDryRunCapacityService();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItem item = workItem();
    item.setDryRun(true);
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[{\"commandText\":\"say dry\"}]}");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            tenantBudgetService,
            dryRunCapacityService,
            new ObjectMapper(),
            meterRegistry);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.completedCount()).isEqualTo(1);
    Mockito.verify(tenantBudgetService, Mockito.never()).tryReserve(Mockito.any(), Mockito.any());
    Mockito.verify(dryRunCapacityService)
        .release(Mockito.any(ScriptDryRunCapacityService.Reservation.class));
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("HANDED_OFF");
    assertThat(audit.getFinalStage()).isEqualTo("DSL_EVAL");
    assertThat(audit.getFinalOutcome()).isEqualTo("dry_run_completed");
    assertThat(audit.getFinalReason()).isEqualTo("dry_run_no_handoff");
    assertThat(meterRegistry.find("automation_script_work_item_outcomes_total").counter()).isNull();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   ", "untrusted", "normal", "high", "background"})
  void metricNormalizesMissingOrInvalidPriorityWithoutChangingAcceptedNormal(String priorityTag) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptTenantBudgetService tenantBudgetService = allowingTenantBudgetService();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItem item = workItem();
    item.setPriorityTag(priorityTag);
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[]}");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            tenantBudgetService,
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            meterRegistry);

    service.processPendingWorkItems(10);

    String expectedPriority =
        switch (priorityTag == null ? "" : priorityTag) {
          case "high", "normal", "background" -> priorityTag;
          default -> "unknown";
        };
    String expectedReservationTier =
        "high".equals(priorityTag)
                || "background".equals(priorityTag)
                || "normal".equals(priorityTag)
            ? priorityTag
            : "normal";
    verify(tenantBudgetService).tryReserve("1", expectedReservationTier);
    assertThat(
            meterRegistry
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "DSL_EVAL")
                .tag("outcome", "completed_no_commands")
                .tag("priority", expectedPriority)
                .tag("source_class", "gameplay")
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
  }

  @ParameterizedTest
  @CsvSource({
    "onCommand, gameplay",
    "onTimerExpire, scheduler",
    "onLoad, readiness",
    "future-event, unknown"
  })
  void metricKeepsSourceClassBoundedToCurrentBuiltInRegistry(
      String eventType, String expectedSourceClass) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItem item = workItem();
    item.setEventType(eventType);
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition("{\"emitCommands\":[]}");
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            meterRegistry);

    service.processPendingWorkItems(10);

    assertThat(
            meterRegistry
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "DSL_EVAL")
                .tag("outcome", "completed_no_commands")
                .tag("priority", "normal")
                .tag("source_class", expectedSourceClass)
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
  }

  @Test
  void tenantBudgetDenialCancelsBeforeDefinitionEvaluation() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptTenantBudgetService tenantBudgetService = Mockito.mock(ScriptTenantBudgetService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItem item = workItem();
    item.setPriorityTag("high");
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(tenantBudgetService.tryReserve("1", "high")).thenReturn(false);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            tenantBudgetService,
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            meterRegistry);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    Mockito.verify(tenantBudgetService).tryReserve("1", "high");
    Mockito.verify(definitionRepository, Mockito.never())
        .findByTenantIdAndScriptVersionAndName(Mockito.anyLong(), Mockito.any(), Mockito.any());
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("tenant_budget_exceeded");
    assertThat(audit.getFinalStage()).isEqualTo("ADMISSION");
    assertThat(audit.getFinalOutcome()).isEqualTo("tenant_budget_exceeded");
    assertThat(audit.getFinalReason()).isEqualTo("tenant_budget_exceeded");
    assertThat(
            meterRegistry
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "ADMISSION")
                .tag("outcome", "tenant_budget_exceeded")
                .tag("priority", "high")
                .tag("source_class", "gameplay")
                .counter())
        .isNotNull();
  }

  @Test
  void dryRunCapacityDenialCancelsBeforeDefinitionEvaluation() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptWorkItem item = workItem();
    item.setDryRun(true);
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            denyingDryRunCapacityService(),
            new ObjectMapper());

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    Mockito.verify(definitionRepository, Mockito.never())
        .findByTenantIdAndScriptVersionAndName(Mockito.anyLong(), Mockito.any(), Mockito.any());
    Mockito.verify(handoffService, Mockito.never()).handoff(Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("dry_run_capacity_exhausted");
    assertThat(audit.getFinalStage()).isEqualTo("ADMISSION");
    assertThat(audit.getFinalOutcome()).isEqualTo("quota_denied");
    assertThat(audit.getFinalReason()).isEqualTo("dry_run_capacity_exhausted");
  }

  @Test
  void tenantBudgetDenialUsesPersistedScheduleTimerSourceAndPriorityTags() {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    ScriptTenantBudgetService tenantBudgetService = Mockito.mock(ScriptTenantBudgetService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItem item = workItem();
    item.setEventType("onTimerExpire");
    item.setSourceKind("SCHEDULE_TIMER");
    item.setSourceService("automation-scripting-service");
    item.setPriorityTag("background");
    ScriptEventAudit audit = new ScriptEventAudit();
    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(tenantBudgetService.tryReserve("1", "background")).thenReturn(false);
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            tenantBudgetService,
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            meterRegistry);

    ScriptWorkItemExecutionService.ExecutionBatchResult result =
        service.processPendingWorkItems(10);

    assertThat(result.failedCount()).isEqualTo(1);
    Mockito.verify(tenantBudgetService).tryReserve("1", "background");
    Mockito.verify(definitionRepository, Mockito.never())
        .findByTenantIdAndScriptVersionAndName(Mockito.anyLong(), Mockito.any(), Mockito.any());
    assertThat(item.getStatus()).isEqualTo("CANCELED");
    assertThat(item.getCancelReason()).isEqualTo("tenant_budget_exceeded");
    assertThat(audit.getFinalOutcome()).isEqualTo("tenant_budget_exceeded");
    assertThat(
            meterRegistry
                .find("automation_script_work_item_outcomes_total")
                .tag("service", "automation-scripting-service")
                .tag("stage", "ADMISSION")
                .tag("outcome", "tenant_budget_exceeded")
                .tag("priority", "background")
                .tag("source_class", "scheduler")
                .counter())
        .isNotNull();
  }

  private static ExecutionFixture executeDefinition(
      String definitionJson, ScriptOutputProperties outputProperties) {
    return executeDefinition(definitionJson, outputProperties, new SimpleMeterRegistry());
  }

  private static ExecutionFixture executeDefinition(
      String definitionJson,
      ScriptOutputProperties outputProperties,
      SimpleMeterRegistry meterRegistry) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(definitionJson);

    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenReturn(
            new ScriptGameplayCommandHandoffService.HandoffResult(
                true, "ENQUEUED", "auto-1", "", "", ""));
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            meterRegistry);
    long initialFailureGeneration = item.getFailureGeneration();
    return new ExecutionFixture(
        item,
        audit,
        handoffService,
        meterRegistry,
        initialFailureGeneration,
        service.processPendingWorkItems(10));
  }

  private static io.micrometer.core.instrument.Counter outcomeCounter(
      SimpleMeterRegistry meterRegistry) {
    return meterRegistry
        .find("automation_script_work_item_outcomes_total")
        .tag("service", "automation-scripting-service")
        .tag("stage", "DSL_EVAL")
        .tag("outcome", "completed_no_commands")
        .tag("priority", "normal")
        .tag("source_class", "gameplay")
        .counter();
  }

  private static void completeSynchronizations(int completionStatus) {
    if (completionStatus == TransactionSynchronization.STATUS_COMMITTED) {
      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);
    }
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(synchronization -> synchronization.afterCompletion(completionStatus));
  }

  private static void clearSynchronizations() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private static ExecutionFixture executeDefinitionWithRejectedHandoff(
      String terminalStatus, String finalOutcome, String reason) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptOutputProperties outputProperties = new ScriptOutputProperties();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ScriptWorkItem item = workItem();
    ScriptEventAudit audit = new ScriptEventAudit();
    ScriptDefinition definition = scriptDefinition();
    definition.setDefinition(
        "{\"emitCommands\":[{\"commandText\":\"LOOK\",\"targetEntityId\":\"entity-2\"}]}");

    when(workItemService.claimPendingForEvaluation(10)).thenReturn(List.of(item));
    when(definitionRepository.findByTenantIdAndScriptVersionAndName(1L, "patch-1", "script-1"))
        .thenReturn(Optional.of(definition));
    when(handoffService.handoff(Mockito.eq(item), Mockito.any()))
        .thenAnswer(
            invocation -> {
              item.setStatus(terminalStatus);
              item.setCancelReason(reason);
              audit.setFinalStage("HANDOFF");
              audit.setFinalOutcome(finalOutcome);
              audit.setFinalReason(reason);
              return new ScriptGameplayCommandHandoffService.HandoffResult(
                  false, reason, "", "", "", reason);
            });
    when(auditRepository.findByWorkItemId(99L)).thenReturn(Optional.of(audit));
    when(workItemRepository.save(Mockito.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            outputProperties,
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            meterRegistry);
    long initialFailureGeneration = item.getFailureGeneration();
    return new ExecutionFixture(
        item,
        audit,
        handoffService,
        meterRegistry,
        initialFailureGeneration,
        service.processPendingWorkItems(10));
  }

  private static ScriptWorkItemExecutionService pluginFenceService(
      ScriptWorkItemService workItemService,
      ScriptDefinitionRepository definitionRepository,
      ScriptGameplayCommandHandoffService handoffService,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptTenantBudgetService tenantBudgetService,
      PluginRuntimeStateRepository pluginRepository) {
    return new ScriptWorkItemExecutionServiceImpl(
        workItemService,
        definitionRepository,
        handoffService,
        workItemRepository,
        auditRepository,
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
        new ScriptOutputProperties(),
        tenantBudgetService,
        allowingDryRunCapacityService(),
        new ObjectMapper(),
        new SimpleMeterRegistry(),
        pluginRepository);
  }

  private static ScriptWorkItem pluginWorkItem() {
    ScriptWorkItem item = workItem();
    item.setPluginId("plugin-1");
    item.setPluginVersionId("plugin-version-1");
    item.setPluginActivationEpoch(1L);
    item.setLifecycleRevision(1L);
    return item;
  }

  private static void assertEarlyPluginAuthorityUnavailable(
      Optional<PluginRuntimeState> ownerState) {
    EarlyPluginFenceExecution result = runEarlyPluginFence(ownerState);

    assertThat(result.result().failedCount()).isEqualTo(1);
    assertThat(result.item().getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(result.item().getCancelReason()).isEqualTo("authority_unavailable");
    assertThat(result.item().getAuthorityUnavailableCount()).isZero();
    assertThat(result.item().getAuthorityUnavailableRetryCount()).isEqualTo(1);
    verify(result.auditRepository(), Mockito.never()).findByWorkItemId(Mockito.anyLong());
  }

  @Test
  void pluginAuthorityOutageDoesNotClearPriorRuntimeOutageBudget() {
    Instant outageSince = Instant.now().minus(Duration.ofMinutes(5));
    EarlyPluginFenceExecution result = runEarlyPluginFence(Optional.empty(), outageSince, 4);

    assertThat(result.item().getStatus()).isEqualTo("PENDING_EVALUATION");
    assertThat(result.item().getAuthorityUnavailableSince()).isEqualTo(outageSince);
    assertThat(result.item().getAuthorityUnavailableCount()).isEqualTo(4);
    assertThat(result.item().getAuthorityUnavailableRetryCount()).isEqualTo(1);
  }

  private static EarlyPluginFenceExecution runEarlyPluginFence(
      Optional<PluginRuntimeState> ownerState) {
    return runEarlyPluginFence(ownerState, null, 0);
  }

  private static EarlyPluginFenceExecution runEarlyPluginFence(
      Optional<PluginRuntimeState> ownerState, Instant priorOutageSince, int priorOutageCount) {
    ScriptWorkItemService workItemService = Mockito.mock(ScriptWorkItemService.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptEventAuditRepository auditRepository = Mockito.mock(ScriptEventAuditRepository.class);
    ScriptDefinitionRepository definitionRepository =
        Mockito.mock(ScriptDefinitionRepository.class);
    ScriptGameplayCommandHandoffService handoffService =
        Mockito.mock(ScriptGameplayCommandHandoffService.class);
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    PluginRuntimeStateRepository pluginRepository =
        Mockito.mock(PluginRuntimeStateRepository.class);
    when(gameSessionClient.getGameInstanceRuntimeState("1", "7", "region-1"))
        .thenReturn(runtimeStateResponse());
    when(pluginRepository.findByTenantIdAndGameInstanceIdAndPluginId("1", "7", "plugin-1"))
        .thenReturn(ownerState);
    ScriptWorkItem item = replayPluginWorkItem();
    item.setAuthorityUnavailableSince(priorOutageSince);
    item.setAuthorityUnavailableCount(priorOutageCount);
    when(workItemService.claimPendingForEvaluation(1)).thenReturn(List.of(item));
    when(workItemRepository.save(item)).thenReturn(item);

    ScriptWorkItemExecutionService service =
        new ScriptWorkItemExecutionServiceImpl(
            workItemService,
            definitionRepository,
            handoffService,
            workItemRepository,
            auditRepository,
            Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
            new ScriptOutputProperties(),
            allowingTenantBudgetService(),
            allowingDryRunCapacityService(),
            new ObjectMapper(),
            new SimpleMeterRegistry(),
            null,
            null,
            null,
            gameSessionClient,
            pluginRepository);
    ScriptWorkItemExecutionService.ExecutionBatchResult result = service.processPendingWorkItems(1);
    Mockito.verifyNoInteractions(definitionRepository, handoffService);
    return new EarlyPluginFenceExecution(item, auditRepository, result);
  }

  private record EarlyPluginFenceExecution(
      ScriptWorkItem item,
      ScriptEventAuditRepository auditRepository,
      ScriptWorkItemExecutionService.ExecutionBatchResult result) {}

  private static PluginRuntimeState pluginState(
      long pluginActivationEpoch, long lifecycleRevision) {
    PluginRuntimeState state = new PluginRuntimeState();
    state.setTenantId("1");
    state.setGameInstanceId("7");
    state.setPluginId("plugin-1");
    state.setActivePluginVersionId("plugin-version-1");
    state.setPluginActivationEpoch(pluginActivationEpoch);
    state.setLifecycleRevision(lifecycleRevision);
    state.setPluginState(PluginState.PLUGIN_STATE_ENABLED.name());
    return state;
  }

  private record ExecutionFixture(
      ScriptWorkItem item,
      ScriptEventAudit audit,
      ScriptGameplayCommandHandoffService handoffService,
      SimpleMeterRegistry meterRegistry,
      long initialFailureGeneration,
      ScriptWorkItemExecutionService.ExecutionBatchResult result) {}

  private static final class RecordingExecutionTransactionManager
      implements PlatformTransactionManager {
    private final List<String> operations;
    private int commitCount;

    private RecordingExecutionTransactionManager(List<String> operations) {
      this.operations = operations;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      int propagation =
          definition == null
              ? TransactionDefinition.PROPAGATION_REQUIRED
              : definition.getPropagationBehavior();
      operations.add("tx-begin:" + propagation);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      operations.add("tx-commit-" + ++commitCount);
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Override
    public void rollback(TransactionStatus status) {
      operations.add("tx-rollback");
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  private static ScriptTenantBudgetService allowingTenantBudgetService() {
    ScriptTenantBudgetService service = Mockito.mock(ScriptTenantBudgetService.class);
    when(service.tryReserve(Mockito.any(), Mockito.any())).thenReturn(true);
    return service;
  }

  private static ScriptTenantBudgetService denyingTenantBudgetService() {
    ScriptTenantBudgetService service = Mockito.mock(ScriptTenantBudgetService.class);
    when(service.tryReserve(Mockito.any(), Mockito.any())).thenReturn(false);
    return service;
  }

  private static ScriptDryRunCapacityService allowingDryRunCapacityService() {
    ScriptDryRunCapacityService service = Mockito.mock(ScriptDryRunCapacityService.class);
    when(service.tryReserve(Mockito.any(), Mockito.anyLong()))
        .thenReturn(
            Optional.of(
                new ScriptDryRunCapacityService.Reservation(
                    "1", 99L, "tenant-lease-token", "cluster-lease-token")));
    return service;
  }

  private static ScriptDryRunCapacityService denyingDryRunCapacityService() {
    ScriptDryRunCapacityService service = Mockito.mock(ScriptDryRunCapacityService.class);
    when(service.tryReserve(Mockito.any(), Mockito.anyLong())).thenReturn(Optional.empty());
    return service;
  }

  private static ScriptReadinessCapacityService allowingReadinessCapacityService() {
    ScriptReadinessCapacityService service = Mockito.mock(ScriptReadinessCapacityService.class);
    when(service.tryReserve(Mockito.any(), Mockito.anyLong()))
        .thenReturn(
            Optional.of(
                new ScriptReadinessCapacityService.Reservation(
                    "1", 99L, "readiness-tenant-token", "readiness-cluster-token")));
    return service;
  }

  private static ScriptReadinessCapacityService denyingReadinessCapacityService() {
    ScriptReadinessCapacityService service = Mockito.mock(ScriptReadinessCapacityService.class);
    when(service.tryReserve(Mockito.any(), Mockito.anyLong())).thenReturn(Optional.empty());
    return service;
  }

  private static ScriptWorkItemExecutionService fenceExecutionService(
      ScriptWorkItemService workItemService,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      GameSessionControlPlaneClient gameSessionClient,
      PluginRuntimeStateRepository pluginRuntimeStateRepository) {
    return fenceExecutionService(
        workItemService,
        workItemRepository,
        auditRepository,
        Mockito.mock(ScriptDefinitionRepository.class),
        Mockito.mock(ScriptGameplayCommandHandoffService.class),
        null,
        gameSessionClient,
        pluginRuntimeStateRepository);
  }

  private static ScriptWorkItemExecutionService fenceExecutionService(
      ScriptWorkItemService workItemService,
      ScriptWorkItemRepository workItemRepository,
      ScriptEventAuditRepository auditRepository,
      ScriptDefinitionRepository definitionRepository,
      ScriptGameplayCommandHandoffService handoffService,
      AutomationQueueService automationQueueService,
      GameSessionControlPlaneClient gameSessionClient,
      PluginRuntimeStateRepository pluginRuntimeStateRepository) {
    return new ScriptWorkItemExecutionServiceImpl(
        workItemService,
        definitionRepository,
        handoffService,
        workItemRepository,
        auditRepository,
        Mockito.mock(ScriptPatchInstanceRolloutProjectionService.class),
        new ScriptOutputProperties(),
        allowingTenantBudgetService(),
        allowingDryRunCapacityService(),
        new ObjectMapper(),
        new SimpleMeterRegistry(),
        automationQueueService,
        null,
        null,
        gameSessionClient,
        pluginRuntimeStateRepository);
  }

  private static GetGameInstanceRuntimeStateResponse runtimeStateResponse() {
    return GetGameInstanceRuntimeStateResponse.newBuilder()
        .setRuntimeState(
            net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("7")
                .setRegionId("region-1")
                .setRegionEpoch(12L)
                .setPinnedScriptPatchVersion("patch-1")
                .setPinnedScriptPatchBaseVersionId(42L)
                .setScriptPinEpoch(3L)
                .setScriptPatchPinnedControlPlaneRequestId("pin-1")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setWorldSlug("demo")
                .setRealmSlug("production")
                .setPointerVersion(17L)
                .addCurrentAdmissionPointers(currentPointer("SHARED", 17L)))
        .build();
  }

  private static AdmissionPointerControlPlaneEntry currentPointer(
      String stateScope, long pointerVersion) {
    return AdmissionPointerControlPlaneEntry.newBuilder()
        .setTenantId("1")
        .setGameInstanceId("7")
        .setWorldSlug("demo")
        .setRealmSlug("production")
        .setPointerVersion(pointerVersion)
        .setStateScope(stateScope)
        .build();
  }

  private static ScriptWorkItem workItem() {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setId(99L);
    item.setTenantId("1");
    item.setGameInstanceId("7");
    item.setRegionId("region-1");
    item.setRegionEpoch(12L);
    item.setEntityId("entity-1");
    item.setScriptId("script-1");
    item.setEventType("onCommand");
    item.setEventSchemaVersion("v1");
    item.setQuotaClass(ScriptQuotaClasses.STANDARD_RUNTIME);
    item.setScriptPatchVersion("patch-1");
    item.setScriptPatchBaseVersionId(42L);
    item.setPlayableStateScope("SHARED");
    item.setWorldSlug("demo");
    item.setRealmSlug("production");
    item.setPointerVersion("17");
    item.setScriptEventId("event-1");
    item.setSourceKind("GAMEPLAY_EVENT");
    item.setSourceService("game-session-service");
    item.setPayloadJson("{\"commandName\":\"LOOK\"}");
    item.setScriptPinEpoch(3L);
    item.setScriptPinControlPlaneRequestId("pin-1");
    item.setStatus("EVALUATING");
    // This fixture represents work with no prior authority outage or retry delay.
    item.setNextEligibleAt(null);
    item.setCreatedAt(Instant.EPOCH);
    item.setUpdatedAt(Instant.EPOCH);
    return item;
  }

  private static ScriptDefinition scriptDefinition() {
    ScriptDefinition definition = new ScriptDefinition();
    definition.setBaseVersionId(42L);
    return definition;
  }

  private static ScriptWorkItem replayPluginWorkItem() {
    ScriptWorkItem item = workItem();
    item.setScriptPinEpoch(3L);
    item.setPluginId("plugin-1");
    item.setPluginVersionId("plugin-version-1");
    item.setPluginActivationEpoch(4L);
    item.setLifecycleRevision(8L);
    return item;
  }
}
