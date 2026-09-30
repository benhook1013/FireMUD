package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.v1.PluginState;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.AdmissionPointerControlPlaneEntry;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;
import org.junit.jupiter.api.Test;

class ScriptWorkItemFenceEvaluationSupportTest {
  @Test
  void acceptsRuntimeStateWhenCapturedPinOwnerRequestMatches() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setScriptPinControlPlaneRequestId("pin-request-1");

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(
                workItem, runtimeState("pin-request-1")))
        .isNull();
  }

  @Test
  void rejectsRuntimeStateWhenStoredPatchBaseIsMissing() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setScriptPinControlPlaneRequestId("pin-request-1");
    workItem.setScriptPatchBaseVersionId(null);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(
                workItem, runtimeState("pin-request-1")))
        .isEqualTo("script_patch_base_version_unavailable");
  }

  @Test
  void rejectsRuntimeStateWhenStoredPatchBaseDiffersFromCurrentOwnerBase() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setScriptPinControlPlaneRequestId("pin-request-1");
    workItem.setScriptPatchBaseVersionId(41L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(
                workItem, runtimeState("pin-request-1")))
        .isEqualTo("script_patch_base_version_mismatch");
  }

  @Test
  void rejectsRuntimeStateWhenCurrentOwnerBaseIsMissing() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setScriptPinControlPlaneRequestId("pin-request-1");
    GameInstanceRuntimeState runtime =
        runtimeState("pin-request-1").getRuntimeState().toBuilder()
            .setPinnedScriptPatchBaseVersionId(0L)
            .build();

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(
                workItem,
                GetGameInstanceRuntimeStateResponse.newBuilder().setRuntimeState(runtime).build()))
        .isEqualTo("script_patch_base_version_unavailable");
  }

  @Test
  void rejectsRuntimeStateWhenPersistedPlayableStateScopeChanged() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setScriptPinControlPlaneRequestId("pin-request-1");
    GameInstanceRuntimeState runtime =
        runtimeState("pin-request-1").getRuntimeState().toBuilder()
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)
            .clearCurrentAdmissionPointers()
            .addCurrentAdmissionPointers(currentPointer("ISOLATED", 17L))
            .build();

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(
                workItem,
                GetGameInstanceRuntimeStateResponse.newBuilder().setRuntimeState(runtime).build()))
        .isEqualTo("playable_state_scope_mismatch");
  }

  @Test
  void rejectsRuntimeStateWhenCurrentAdmissionPointerChanged() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setScriptPinControlPlaneRequestId("pin-request-1");
    GameInstanceRuntimeState runtime =
        runtimeState("pin-request-1").getRuntimeState().toBuilder()
            .setPointerVersion(18L)
            .clearCurrentAdmissionPointers()
            .addCurrentAdmissionPointers(currentPointer("SHARED", 18L))
            .build();

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(
                workItem,
                GetGameInstanceRuntimeStateResponse.newBuilder().setRuntimeState(runtime).build()))
        .isEqualTo("routing_bundle_changed");
  }

  @Test
  void rejectsLegacyWorkItemWhenPersistedRoutingBundleIsMissing() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setScriptPinControlPlaneRequestId("pin-request-1");
    workItem.setWorldSlug("");
    workItem.setRealmSlug("");
    workItem.setPointerVersion("");

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(
                workItem, runtimeState("pin-request-1")))
        .isEqualTo("routing_bundle_changed");
  }

  @Test
  void rejectsRuntimeStateWhenCapturedPinOwnerRequestIsMissing() {
    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(
                runtimeWorkItem(), runtimeState("pin-request-1")))
        .isEqualTo("script_pin_owner_request_unavailable");
  }

  @Test
  void rejectsRuntimeStateWhenAuthoritativePinOwnerRequestIsMissing() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setScriptPinControlPlaneRequestId("pin-request-1");

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(workItem, runtimeState(" ")))
        .isEqualTo("script_pin_owner_request_unavailable");
  }

  @Test
  void rejectsRuntimeStateWhenCapturedPinOwnerRequestDiffers() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setScriptPinControlPlaneRequestId("pin-request-1");

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateRuntimeState(
                workItem, runtimeState("pin-request-2")))
        .isEqualTo("script_pin_owner_request_mismatch");
  }

  @Test
  void acceptsFirstPartyWorkItemWithoutPluginFence() {
    ScriptWorkItem workItem = runtimeWorkItem();

    assertThat(ScriptWorkItemFenceEvaluationSupport.validateCapturedPluginFence(workItem)).isNull();
  }

  @Test
  void rejectsUnboundWorkItemWithCapturedPluginLifecycleEvidence() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginActivationEpoch(1L);
    workItem.setLifecycleRevision(1L);

    assertThat(ScriptWorkItemFenceEvaluationSupport.validateCapturedPluginFence(workItem))
        .isEqualTo("plugin_binding_mismatch");
  }

  @Test
  void rejectsPartialPluginBindingBeforeCurrentAuthorityLookup() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");

    assertThat(ScriptWorkItemFenceEvaluationSupport.validateCapturedPluginFence(workItem))
        .isEqualTo("plugin_binding_mismatch");
  }

  @Test
  void rejectsMissingCapturedLifecycleEvidenceBeforeCurrentPluginStatus() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");

    assertThat(ScriptWorkItemFenceEvaluationSupport.validateCapturedPluginFence(workItem))
        .isEqualTo("plugin_lifecycle_evidence_unavailable");
  }

  @Test
  void usesBindingMismatchForCurrentLifecycleRevisionMismatch() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");
    workItem.setPluginActivationEpoch(4L);
    workItem.setLifecycleRevision(8L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, "plugin-v1", PluginState.PLUGIN_STATE_ENABLED, 4L, 9L))
        .isEqualTo("plugin_binding_mismatch");
  }

  @Test
  void usesActivationEpochMismatchForCurrentActivationEpochMismatch() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");
    workItem.setPluginActivationEpoch(4L);
    workItem.setLifecycleRevision(8L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, "plugin-v1", PluginState.PLUGIN_STATE_ENABLED, 5L, 8L))
        .isEqualTo("plugin_activation_epoch_mismatch");
  }

  @Test
  void acceptsCurrentEnabledPluginFenceWhenAllEvidenceMatches() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");
    workItem.setPluginActivationEpoch(4L);
    workItem.setLifecycleRevision(8L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, " plugin-v1 ", PluginState.PLUGIN_STATE_ENABLED, 4L, 8L))
        .isNull();
  }

  @Test
  void rejectsCapturedPluginVersionWhenAnotherVersionIsCurrentlyEnabled() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");
    workItem.setPluginActivationEpoch(4L);
    workItem.setLifecycleRevision(8L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, "plugin-v2", PluginState.PLUGIN_STATE_ENABLED, 4L, 8L))
        .isEqualTo("plugin_version_mismatch");
  }

  @Test
  void rejectsCurrentDisabledPluginFence() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");
    workItem.setPluginActivationEpoch(4L);
    workItem.setLifecycleRevision(8L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, "plugin-v1", PluginState.PLUGIN_STATE_DISABLED, 4L, 8L))
        .isEqualTo("plugin_disabled");
  }

  @Test
  void rejectsCurrentDrainingPluginFenceAsNonExecutable() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");
    workItem.setPluginActivationEpoch(4L);
    workItem.setLifecycleRevision(8L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, "plugin-v1", PluginState.PLUGIN_STATE_DRAINING, 4L, 8L))
        .isEqualTo("plugin_disabled");
  }

  @Test
  void rejectsPredecessorEnabledRevisionWhenCurrentPluginIsDraining() {
    ScriptWorkItem workItem = runtimeWorkItem();
    workItem.setPluginId("plugin-1");
    workItem.setPluginVersionId("plugin-v1");
    workItem.setPluginActivationEpoch(4L);
    workItem.setLifecycleRevision(8L);

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, "plugin-v1", PluginState.PLUGIN_STATE_DRAINING, 4L, 9L))
        .isEqualTo("plugin_disabled");
  }

  @Test
  void rejectsUnspecifiedCurrentPluginStateWithoutPluginVersion() {
    ScriptWorkItem workItem = runtimeWorkItem();

    assertThat(
            ScriptWorkItemFenceEvaluationSupport.validateCurrentPluginFence(
                workItem, "", PluginState.PLUGIN_STATE_UNSPECIFIED, 0L, 0L))
        .isEqualTo("plugin_disabled");
  }

  private static ScriptWorkItem runtimeWorkItem() {
    ScriptWorkItem workItem = new ScriptWorkItem();
    workItem.setTenantId("1");
    workItem.setGameInstanceId("game-1");
    workItem.setRegionId("region-1");
    workItem.setRegionEpoch(3L);
    workItem.setScriptPatchVersion("patch-1");
    workItem.setScriptPatchBaseVersionId(42L);
    workItem.setScriptPinEpoch(2L);
    workItem.setPlayableStateScope("SHARED");
    workItem.setWorldSlug("demo");
    workItem.setRealmSlug("production");
    workItem.setPointerVersion("17");
    return workItem;
  }

  private static GetGameInstanceRuntimeStateResponse runtimeState(String pinRequestId) {
    return GetGameInstanceRuntimeStateResponse.newBuilder()
        .setRuntimeState(
            GameInstanceRuntimeState.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("game-1")
                .setPinnedScriptPatchVersion("patch-1")
                .setPinnedScriptPatchBaseVersionId(42L)
                .setScriptPinEpoch(2L)
                .setScriptPatchPinnedControlPlaneRequestId(pinRequestId)
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setWorldSlug("demo")
                .setRealmSlug("production")
                .setPointerVersion(17L)
                .addCurrentAdmissionPointers(currentPointer("SHARED", 17L))
                .setRegionId("region-1")
                .setRegionEpoch(3L)
                .build())
        .build();
  }

  private static AdmissionPointerControlPlaneEntry currentPointer(
      String stateScope, long pointerVersion) {
    return AdmissionPointerControlPlaneEntry.newBuilder()
        .setTenantId("1")
        .setGameInstanceId("game-1")
        .setWorldSlug("demo")
        .setRealmSlug("production")
        .setPointerVersion(pointerVersion)
        .setStateScope(stateScope)
        .build();
  }
}
