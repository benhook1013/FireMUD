package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;

class TriggerScriptEventRequestFactoryTest {
  @Test
  void convertsNullScriptPatchVersionToEmptyProtoValueForUnpinnedTuple() {
    TriggerScriptEventRequestFactory.CommonFields fields =
        new TriggerScriptEventRequestFactory.CommonFields(
            "tenant",
            "instance",
            "region",
            1L,
            "entity",
            "onCommand",
            "v1",
            null,
            0L,
            null,
            "event-1",
            false,
            TriggerMode.TRIGGER_MODE_NORMAL,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "snapshot",
            "{}");

    assertEquals(
        "", TriggerScriptEventRequestFactory.builder(fields, null).getScriptPatchVersion());
  }

  @Test
  void preservesCompleteScriptPinTupleAndRoutingEvidence() {
    TriggerScriptEventRequestFactory.CommonFields fields =
        new TriggerScriptEventRequestFactory.CommonFields(
            "tenant",
            "instance",
            "region",
            4L,
            "entity",
            "onCommand",
            "v1",
            "patch-7",
            9L,
            "pin-request-9",
            "event-9",
            false,
            TriggerMode.TRIGGER_MODE_NORMAL,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED,
            "snapshot-9",
            "{\"action\":\"say\"}");

    var request =
        TriggerScriptEventRequestFactory.builder(
                fields, new TriggerScriptEventRequestFactory.RoutingBundle("world", "realm", "v3"))
            .build();

    assertEquals("patch-7", request.getScriptPatchVersion());
    assertEquals(9L, request.getScriptPinEpoch());
    assertEquals("pin-request-9", request.getScriptPinControlPlaneRequestId());
    assertEquals("world", request.getWorldSlug());
    assertEquals("realm", request.getRealmSlug());
    assertEquals("v3", request.getPointerVersion());
  }

  @Test
  void rejectsNullScriptPatchVersionWhenPinEpochIsPositive() {
    TriggerScriptEventRequestFactory.CommonFields fields =
        new TriggerScriptEventRequestFactory.CommonFields(
            "tenant",
            "instance",
            "region",
            1L,
            "entity",
            "onCommand",
            "v1",
            null,
            1L,
            "request-1",
            "event-1",
            false,
            TriggerMode.TRIGGER_MODE_NORMAL,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "snapshot",
            "{}");

    assertThrows(
        IllegalArgumentException.class,
        () -> TriggerScriptEventRequestFactory.builder(fields, null));
  }

  @Test
  void rejectsIncompleteScriptPinTupleBeforeBuildingRequest() {
    TriggerScriptEventRequestFactory.CommonFields fields =
        new TriggerScriptEventRequestFactory.CommonFields(
            "tenant",
            "instance",
            "region",
            1L,
            "entity",
            "onCommand",
            "v1",
            "patch-1",
            0L,
            "",
            "event-1",
            false,
            TriggerMode.TRIGGER_MODE_NORMAL,
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            "snapshot",
            "{}");

    assertThatThrownBy(() -> TriggerScriptEventRequestFactory.builder(fields, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SCRIPT_PIN_STATE_INVALID");
  }
}
