package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.config.ScriptRuntimeProperties;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchInstanceRolloutEvent;
import net.firedevops.firemud.automationscripting.entity.ScriptPatchInstanceRolloutProjection;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchInstanceRolloutEventRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchInstanceRolloutProjectionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.ScriptPatchPinProjectionService;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ScriptPatchInstanceRolloutProjectionServiceImplTest {
  @Test
  void exactEpochLookupRejectsSameEpochDifferentControlPlaneRequest() {
    ScriptPatchInstanceRolloutProjectionRepository repository =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionRepository.class);
    ScriptPatchInstanceRolloutEventRepository eventRepository =
        Mockito.mock(ScriptPatchInstanceRolloutEventRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptPatchInstanceRolloutProjection existing = new ScriptPatchInstanceRolloutProjection();
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setScriptPatchVersion("patch-1");
    existing.setScriptPinEpoch(4L);
    existing.setLastObservedControlPlaneRequestId("req-1");
    existing.setRolloutStatus(
        ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED.name());
    existing.setStatusReason("runtime_pin_matches_patch");
    existing.setLastChangedAt(Instant.ofEpochMilli(100));
    existing.setProjectionRefreshedAt(Instant.ofEpochMilli(100));
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(Optional.empty(), "", ""));
    when(repository.findByTenantIdAndGameInstanceIdAndScriptPatchVersionAndScriptPinEpoch(
            "1", "game-1", "patch-1", 4L))
        .thenReturn(Optional.of(existing));
    ScriptPatchInstanceRolloutProjectionServiceImpl service =
        new ScriptPatchInstanceRolloutProjectionServiceImpl(
            repository,
            eventRepository,
            workItemRepository,
            pinProjectionService,
            new ScriptRuntimeProperties());

    Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> summary =
        service.getProjection("1", "game-1", "patch-1", 4L, "req-2");

    assertThat(summary).isEmpty();
    Mockito.verify(repository)
        .findByTenantIdAndGameInstanceIdAndScriptPatchVersionAndScriptPinEpoch(
            "1", "game-1", "patch-1", 4L);
  }

  @Test
  void rejectsPositiveEpochWithoutOwnerRequestIdOnFullReads() {
    ScriptPatchInstanceRolloutProjectionRepository repository =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionRepository.class);
    ScriptPatchInstanceRolloutEventRepository eventRepository =
        Mockito.mock(ScriptPatchInstanceRolloutEventRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptPatchInstanceRolloutProjectionServiceImpl service =
        new ScriptPatchInstanceRolloutProjectionServiceImpl(
            repository,
            eventRepository,
            workItemRepository,
            pinProjectionService,
            new ScriptRuntimeProperties());

    assertThatThrownBy(() -> service.getProjection("1", "game-1", "patch-1", 2L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
    assertThatThrownBy(
            () ->
                service.listProjections(
                    "1",
                    "game-1",
                    "patch-1",
                    2L,
                    "",
                    ScriptPatchInstanceRolloutStatus
                        .SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_UNSPECIFIED,
                    0L,
                    0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
    assertThatThrownBy(
            () ->
                service.listEvents(
                    "1",
                    "game-1",
                    "patch-1",
                    2L,
                    null,
                    ScriptPatchInstanceRolloutStatus
                        .SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_UNSPECIFIED,
                    0L,
                    0L,
                    25))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
    verifyNoInteractions(repository, eventRepository, workItemRepository, pinProjectionService);
  }

  @Test
  void rejectsZeroEpochWithOwnerRequestIdOnFullReads() {
    ScriptPatchInstanceRolloutProjectionRepository repository =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionRepository.class);
    ScriptPatchInstanceRolloutEventRepository eventRepository =
        Mockito.mock(ScriptPatchInstanceRolloutEventRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptPatchInstanceRolloutProjectionServiceImpl service =
        new ScriptPatchInstanceRolloutProjectionServiceImpl(
            repository,
            eventRepository,
            workItemRepository,
            pinProjectionService,
            new ScriptRuntimeProperties());

    assertThatThrownBy(() -> service.getProjection("1", "game-1", "patch-1", 0L, "request-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
    assertThatThrownBy(
            () ->
                service.listProjections(
                    "1",
                    "game-1",
                    "patch-1",
                    0L,
                    "request-1",
                    ScriptPatchInstanceRolloutStatus
                        .SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_UNSPECIFIED,
                    0L,
                    0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
    assertThatThrownBy(
            () ->
                service.listEvents(
                    "1",
                    "game-1",
                    "patch-1",
                    0L,
                    "request-1",
                    ScriptPatchInstanceRolloutStatus
                        .SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_UNSPECIFIED,
                    0L,
                    0L,
                    25))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "script_pin_control_plane_request_id must be present exactly when script_pin_epoch is positive");
    verifyNoInteractions(repository, eventRepository, workItemRepository, pinProjectionService);
  }

  @Test
  void marksProjectionRepinnedWhenPatchReturnsAfterRollback() {
    ScriptPatchInstanceRolloutProjectionRepository repository =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionRepository.class);
    ScriptPatchInstanceRolloutEventRepository eventRepository =
        Mockito.mock(ScriptPatchInstanceRolloutEventRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptPatchInstanceRolloutProjection existing = new ScriptPatchInstanceRolloutProjection();
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setScriptPatchVersion("patch-1");
    existing.setScriptPinEpoch(1L);
    existing.setRolloutStatus(
        ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK.name());
    existing.setStatusReason("runtime_pin_differs_from_patch");
    existing.setLastChangedAt(Instant.ofEpochMilli(100));
    existing.setProjectionRefreshedAt(Instant.ofEpochMilli(100));
    ScriptWorkItem workItem = new ScriptWorkItem();
    workItem.setTenantId("1");
    workItem.setGameInstanceId("game-1");
    workItem.setScriptPatchVersion("patch-1");
    workItem.setScriptPinEpoch(1L);
    workItem.setUpdatedAt(Instant.ofEpochMilli(120));
    when(workItemRepository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
            "1", "game-1", "patch-1"))
        .thenReturn(List.of(workItem));
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(
                Optional.of(
                    new ScriptPatchPinProjectionService.PinConvergenceSummary(
                        "1", "game-1", "patch-1", 2L, "req-2", 200L, 205L, 0L, false, "", 0L, "",
                        "", "")),
                "",
                ""));
    when(repository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion("1", "game-1", "patch-1"))
        .thenReturn(Optional.of(existing), Optional.of(existing));
    when(repository.save(Mockito.any(ScriptPatchInstanceRolloutProjection.class)))
        .thenAnswer(
            invocation -> {
              ScriptPatchInstanceRolloutProjection saved = invocation.getArgument(0);
              existing.setRolloutStatus(saved.getRolloutStatus());
              existing.setStatusReason(saved.getStatusReason());
              existing.setLastChangedAt(saved.getLastChangedAt());
              existing.setProjectionRefreshedAt(saved.getProjectionRefreshedAt());
              return existing;
            });
    ScriptPatchInstanceRolloutProjectionServiceImpl service =
        new ScriptPatchInstanceRolloutProjectionServiceImpl(
            repository,
            eventRepository,
            workItemRepository,
            pinProjectionService,
            new ScriptRuntimeProperties());

    Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> summary =
        service.getProjection("1", "game-1", "patch-1", 0L, null);

    assertThat(summary).isPresent();
    assertThat(summary.get().rolloutStatus())
        .isEqualTo(ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_REPINNED);
    assertThat(summary.get().statusReason()).isEqualTo("runtime_pin_restored_after_rollback");
    assertThat(summary.get().lastChangedAtMs()).isEqualTo(200L);
    org.mockito.ArgumentCaptor<ScriptPatchInstanceRolloutProjection> projectionCaptor =
        org.mockito.ArgumentCaptor.forClass(ScriptPatchInstanceRolloutProjection.class);
    Mockito.verify(repository).save(projectionCaptor.capture());
    assertThat(projectionCaptor.getValue().getScriptPinEpoch()).isEqualTo(2L);
    assertThat(projectionCaptor.getValue().getLastObservedControlPlaneRequestId())
        .isEqualTo("req-2");
    org.mockito.ArgumentCaptor<ScriptPatchInstanceRolloutEvent> eventCaptor =
        org.mockito.ArgumentCaptor.forClass(ScriptPatchInstanceRolloutEvent.class);
    Mockito.verify(eventRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getRolloutStatus())
        .isEqualTo(
            ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_REPINNED.name());
    assertThat(eventCaptor.getValue().getStatusReason())
        .isEqualTo("runtime_pin_restored_after_rollback");
    assertThat(eventCaptor.getValue().getScriptPinEpoch()).isEqualTo(2L);
    assertThat(eventCaptor.getValue().getLastObservedControlPlaneRequestId()).isEqualTo("req-2");
    assertThat(eventCaptor.getValue().getObservedAt()).isEqualTo(Instant.ofEpochMilli(200));
  }

  @Test
  void currentDiagnosticProjectionRollsBackWhenPositiveEpochProvesDifferentPatch() {
    ScriptPatchInstanceRolloutProjectionRepository repository =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionRepository.class);
    ScriptPatchInstanceRolloutEventRepository eventRepository =
        Mockito.mock(ScriptPatchInstanceRolloutEventRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptPatchInstanceRolloutProjection existing = new ScriptPatchInstanceRolloutProjection();
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setScriptPatchVersion("patch-1");
    existing.setScriptPinEpoch(1L);
    existing.setLastObservedControlPlaneRequestId("req-1");
    existing.setRolloutStatus(
        ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED.name());
    existing.setStatusReason("runtime_pin_matches_patch");
    existing.setLastChangedAt(Instant.ofEpochMilli(100));
    existing.setProjectionRefreshedAt(Instant.ofEpochMilli(100));
    ScriptWorkItem workItem = new ScriptWorkItem();
    workItem.setTenantId("1");
    workItem.setGameInstanceId("game-1");
    workItem.setScriptPatchVersion("patch-1");
    workItem.setUpdatedAt(Instant.ofEpochMilli(120));
    when(workItemRepository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion(
            "1", "game-1", "patch-1"))
        .thenReturn(List.of(workItem));
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(
                Optional.of(
                    new ScriptPatchPinProjectionService.PinConvergenceSummary(
                        "1", "game-1", "patch-2", 2L, "req-2", 200L, 205L, 0L, false, "", 0L, "",
                        "", "")),
                "",
                ""));
    when(repository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion("1", "game-1", "patch-1"))
        .thenReturn(Optional.of(existing), Optional.of(existing));
    when(repository.save(Mockito.any(ScriptPatchInstanceRolloutProjection.class)))
        .thenAnswer(
            invocation -> {
              ScriptPatchInstanceRolloutProjection saved = invocation.getArgument(0);
              existing.setScriptPinEpoch(saved.getScriptPinEpoch());
              existing.setLastObservedControlPlaneRequestId(
                  saved.getLastObservedControlPlaneRequestId());
              existing.setRolloutStatus(saved.getRolloutStatus());
              existing.setStatusReason(saved.getStatusReason());
              existing.setLastChangedAt(saved.getLastChangedAt());
              existing.setProjectionRefreshedAt(saved.getProjectionRefreshedAt());
              return existing;
            });
    ScriptPatchInstanceRolloutProjectionServiceImpl service =
        new ScriptPatchInstanceRolloutProjectionServiceImpl(
            repository,
            eventRepository,
            workItemRepository,
            pinProjectionService,
            new ScriptRuntimeProperties());

    Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> summary =
        service.getProjection("1", "game-1", "patch-1", 0L, null);

    // This proves current local diagnostic projection behavior, not target rollout-history
    // authority.
    assertThat(summary).isPresent();
    assertThat(summary.get().rolloutStatus())
        .isEqualTo(
            ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK);
    assertThat(summary.get().statusReason()).isEqualTo("runtime_pin_differs_from_patch");
    assertThat(summary.get().scriptPinEpoch()).isEqualTo(2L);
    assertThat(summary.get().lastObservedControlPlaneRequestId()).isEqualTo("req-2");
    org.mockito.ArgumentCaptor<ScriptPatchInstanceRolloutProjection> projectionCaptor =
        org.mockito.ArgumentCaptor.forClass(ScriptPatchInstanceRolloutProjection.class);
    Mockito.verify(repository).save(projectionCaptor.capture());
    assertThat(projectionCaptor.getValue().getRolloutStatus())
        .isEqualTo(
            ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK
                .name());
    assertThat(projectionCaptor.getValue().getStatusReason())
        .isEqualTo("runtime_pin_differs_from_patch");
    org.mockito.ArgumentCaptor<ScriptPatchInstanceRolloutEvent> eventCaptor =
        org.mockito.ArgumentCaptor.forClass(ScriptPatchInstanceRolloutEvent.class);
    Mockito.verify(eventRepository).save(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getRolloutStatus())
        .isEqualTo(
            ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_ROLLED_BACK
                .name());
    assertThat(eventCaptor.getValue().getStatusReason())
        .isEqualTo("runtime_pin_differs_from_patch");
    assertThat(eventCaptor.getValue().getScriptPinEpoch()).isEqualTo(2L);
    assertThat(eventCaptor.getValue().getLastObservedControlPlaneRequestId()).isEqualTo("req-2");
  }

  @Test
  void preservesExistingProjectionWhenRuntimePinProjectionIsStale() {
    ScriptPatchInstanceRolloutProjectionRepository repository =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionRepository.class);
    ScriptPatchInstanceRolloutEventRepository eventRepository =
        Mockito.mock(ScriptPatchInstanceRolloutEventRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptPatchInstanceRolloutProjection existing = new ScriptPatchInstanceRolloutProjection();
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setScriptPatchVersion("patch-1");
    existing.setScriptPinEpoch(1L);
    existing.setLastObservedControlPlaneRequestId("req-1");
    existing.setRolloutStatus(
        ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED.name());
    existing.setStatusReason("runtime_pin_matches_patch");
    Instant now = Instant.now();
    existing.setLastChangedAt(now);
    existing.setProjectionRefreshedAt(now);
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(
                Optional.of(
                    new ScriptPatchPinProjectionService.PinConvergenceSummary(
                        "1", "game-1", "patch-1", 2L, "req-2", 200L, 100L, 100L, true, "", 0L, "",
                        "", "")),
                "",
                ""));
    when(repository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion("1", "game-1", "patch-1"))
        .thenReturn(Optional.of(existing));
    ScriptPatchInstanceRolloutProjectionServiceImpl service =
        new ScriptPatchInstanceRolloutProjectionServiceImpl(
            repository,
            eventRepository,
            workItemRepository,
            pinProjectionService,
            new ScriptRuntimeProperties());

    Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> summary =
        service.getProjection("1", "game-1", "patch-1", 0L, null);

    assertThat(summary).isPresent();
    assertThat(summary.get().rolloutStatus())
        .isEqualTo(ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED);
    assertThat(summary.get().statusReason()).isEqualTo("runtime_pin_matches_patch");
    assertThat(existing.getScriptPinEpoch()).isEqualTo(1L);
    assertThat(existing.getLastObservedControlPlaneRequestId()).isEqualTo("req-1");
    verifyNoInteractions(workItemRepository, eventRepository);
    Mockito.verify(repository)
        .findByTenantIdAndGameInstanceIdAndScriptPatchVersion("1", "game-1", "patch-1");
    Mockito.verify(repository, Mockito.never())
        .save(Mockito.any(ScriptPatchInstanceRolloutProjection.class));
  }

  @Test
  void preservesExistingProjectionWhenOwnerPinIsUnavailable() {
    ScriptPatchInstanceRolloutProjectionRepository repository =
        Mockito.mock(ScriptPatchInstanceRolloutProjectionRepository.class);
    ScriptPatchInstanceRolloutEventRepository eventRepository =
        Mockito.mock(ScriptPatchInstanceRolloutEventRepository.class);
    ScriptWorkItemRepository workItemRepository = Mockito.mock(ScriptWorkItemRepository.class);
    ScriptPatchPinProjectionService pinProjectionService =
        Mockito.mock(ScriptPatchPinProjectionService.class);
    ScriptPatchInstanceRolloutProjection existing = new ScriptPatchInstanceRolloutProjection();
    existing.setTenantId("1");
    existing.setGameInstanceId("game-1");
    existing.setScriptPatchVersion("patch-1");
    existing.setRolloutStatus(
        ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED.name());
    existing.setStatusReason("runtime_pin_matches_patch");
    existing.setLastChangedAt(Instant.ofEpochMilli(100));
    existing.setProjectionRefreshedAt(Instant.ofEpochMilli(100));
    when(pinProjectionService.getPinConvergence("1", "game-1"))
        .thenReturn(
            new ScriptPatchPinProjectionService.PinConvergenceLookup(Optional.empty(), "", ""));
    when(repository.findByTenantIdAndGameInstanceIdAndScriptPatchVersion("1", "game-1", "patch-1"))
        .thenReturn(Optional.of(existing));
    ScriptPatchInstanceRolloutProjectionServiceImpl service =
        new ScriptPatchInstanceRolloutProjectionServiceImpl(
            repository,
            eventRepository,
            workItemRepository,
            pinProjectionService,
            new ScriptRuntimeProperties());

    Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> summary =
        service.getProjection("1", "game-1", "patch-1", 0L, null);

    assertThat(summary).isPresent();
    assertThat(summary.get().rolloutStatus())
        .isEqualTo(ScriptPatchInstanceRolloutStatus.SCRIPT_PATCH_INSTANCE_ROLLOUT_STATUS_PINNED);
    verifyNoInteractions(workItemRepository);
    Mockito.verify(repository)
        .findByTenantIdAndGameInstanceIdAndScriptPatchVersion("1", "game-1", "patch-1");
    verifyNoInteractions(eventRepository);
  }
}
