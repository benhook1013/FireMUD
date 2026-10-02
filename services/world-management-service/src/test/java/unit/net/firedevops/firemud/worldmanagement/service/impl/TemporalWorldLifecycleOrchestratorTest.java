package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.temporal.client.WorkflowClient;
import net.firedevops.firemud.common.temporal.TemporalTaskQueueResolver;
import net.firedevops.firemud.worldmanagement.dto.WorldInstanceLifecycleSnapshotDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TemporalWorldLifecycleOrchestratorTest {
  private static final long TENANT_ID = 42L;
  private static final long GAME_INSTANCE_ID = 101L;
  private static final String WORKFLOW_ID = "world-lifecycle:42:game-instance:101";

  private WorkflowClient workflowClient;
  private TemporalWorldLifecycleWorkflow workflow;
  private TemporalWorldLifecycleWorkflowMetadataResolver metadataResolver;
  private TemporalWorldLifecycleOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    workflowClient = mock(WorkflowClient.class);
    workflow = mock(TemporalWorldLifecycleWorkflow.class);
    metadataResolver = mock(TemporalWorldLifecycleWorkflowMetadataResolver.class);
    TemporalTaskQueueResolver taskQueues = mock(TemporalTaskQueueResolver.class);
    when(workflowClient.newWorkflowStub(TemporalWorldLifecycleWorkflow.class, WORKFLOW_ID))
        .thenReturn(workflow);
    orchestrator =
        new TemporalWorldLifecycleOrchestrator(workflowClient, taskQueues, metadataResolver);
  }

  @AfterEach
  void clearInterruptFlag() {
    Thread.interrupted();
  }

  @Test
  void terminationRetryAtCurrentTerminatingEpochAcceptsPersistedTerminalSnapshot() {
    WorldInstanceLifecycleSnapshotDto terminalSnapshot = snapshot(4L, "TERMINATED");
    when(workflow.currentSnapshot()).thenReturn(terminalSnapshot);
    when(metadataResolver.attach(terminalSnapshot)).thenReturn(terminalSnapshot);
    Thread.currentThread().interrupt();

    WorldInstanceLifecycleSnapshotDto result =
        orchestrator.terminateWorldInstance(TENANT_ID, GAME_INSTANCE_ID, 3L, "term-1", "stop");

    assertSame(terminalSnapshot, result);
    assertEquals(4L, result.lifecycleEpoch());
    verify(workflow).terminate(3L, "term-1", "stop");
    verify(workflow).currentSnapshot();
  }

  @Test
  void terminationFromActiveEpochAcceptsExpectedTerminalSnapshot() {
    WorldInstanceLifecycleSnapshotDto terminalSnapshot = snapshot(4L, "TERMINATED");
    when(workflow.currentSnapshot()).thenReturn(terminalSnapshot);
    when(metadataResolver.attach(terminalSnapshot)).thenReturn(terminalSnapshot);

    WorldInstanceLifecycleSnapshotDto result =
        orchestrator.terminateWorldInstance(TENANT_ID, GAME_INSTANCE_ID, 2L, "term-1", "stop");

    assertSame(terminalSnapshot, result);
    assertEquals(4L, result.lifecycleEpoch());
    verify(workflow).terminate(2L, "term-1", "stop");
  }

  @Test
  void terminationWaitRefusesNonterminalSnapshotEvenAtHigherEpoch() {
    WorldInstanceLifecycleSnapshotDto terminatingSnapshot = snapshot(100L, "TERMINATING");
    when(workflow.currentSnapshot()).thenReturn(terminatingSnapshot);
    Thread.currentThread().interrupt();

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                orchestrator.terminateWorldInstance(
                    TENANT_ID, GAME_INSTANCE_ID, 3L, "term-1", "stop"));

    assertEquals("Interrupted while waiting for Temporal workflow state", error.getMessage());
    verify(metadataResolver, org.mockito.Mockito.never()).attach(terminatingSnapshot);
  }

  @Test
  void terminationWaitRefusesTerminalSnapshotForAnotherWorldInstance() {
    WorldInstanceLifecycleSnapshotDto otherInstanceSnapshot =
        snapshot(TENANT_ID, GAME_INSTANCE_ID + 1L, 4L, "TERMINATED");
    when(workflow.currentSnapshot()).thenReturn(otherInstanceSnapshot);
    Thread.currentThread().interrupt();

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                orchestrator.terminateWorldInstance(
                    TENANT_ID, GAME_INSTANCE_ID, 3L, "term-1", "stop"));

    assertEquals("Interrupted while waiting for Temporal workflow state", error.getMessage());
    verify(metadataResolver, org.mockito.Mockito.never()).attach(otherInstanceSnapshot);
  }

  @Test
  void terminationWaitRefusesTerminalSnapshotForAnotherTenant() {
    WorldInstanceLifecycleSnapshotDto otherTenantSnapshot =
        snapshot(TENANT_ID + 1L, GAME_INSTANCE_ID, 4L, "TERMINATED");
    when(workflow.currentSnapshot()).thenReturn(otherTenantSnapshot);
    Thread.currentThread().interrupt();

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                orchestrator.terminateWorldInstance(
                    TENANT_ID, GAME_INSTANCE_ID, 3L, "term-1", "stop"));

    assertEquals("Interrupted while waiting for Temporal workflow state", error.getMessage());
    verify(metadataResolver, org.mockito.Mockito.never()).attach(otherTenantSnapshot);
  }

  private WorldInstanceLifecycleSnapshotDto snapshot(long lifecycleEpoch, String status) {
    return snapshot(TENANT_ID, GAME_INSTANCE_ID, lifecycleEpoch, status);
  }

  private WorldInstanceLifecycleSnapshotDto snapshot(
      long tenantId, long gameInstanceId, long lifecycleEpoch, String status) {
    return new WorldInstanceLifecycleSnapshotDto(
        tenantId,
        gameInstanceId,
        7L,
        "cp-1",
        "ld-1",
        11L,
        77L,
        "genrev-11",
        "prb:42:11:77",
        77L,
        lifecycleEpoch,
        status);
  }
}
