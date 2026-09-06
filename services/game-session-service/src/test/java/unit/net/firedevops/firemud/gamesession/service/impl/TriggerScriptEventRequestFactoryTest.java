package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import net.firedevops.firemud.automationscripting.v1.TriggerMode;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;

class TriggerScriptEventRequestFactoryTest {
  @Test
  void convertsNullScriptPatchVersionToEmptyProtoValueForUnpinnedTuple() {
    TriggerScriptEventRequestFactory.CommonFields fields = commonFields(null, 0L, null);

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
    TriggerScriptEventRequestFactory.CommonFields fields = commonFields(null, 1L, "request-1");

    assertThatThrownBy(() -> TriggerScriptEventRequestFactory.builder(fields, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present"
                + " together");
  }

  @Test
  void rejectsNegativeScriptPinEpoch() {
    TriggerScriptEventRequestFactory.CommonFields fields =
        commonFields("patch-1", -1L, "request-1");

    assertThatThrownBy(() -> TriggerScriptEventRequestFactory.builder(fields, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("scriptPinEpoch cannot be negative");
  }

  @Test
  void rejectsIncompleteScriptPinTupleBeforeBuildingRequest() {
    TriggerScriptEventRequestFactory.CommonFields fields = commonFields("patch-1", 0L, "");

    assertThatThrownBy(() -> TriggerScriptEventRequestFactory.builder(fields, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present"
                + " together");
  }

  @Test
  void rejectsNullOrBlankOwnerRequestIdWhenEpochAndPatchArePresent() {
    for (String ownerRequestId : new String[] {null, "", " "}) {
      TriggerScriptEventRequestFactory.CommonFields fields =
          commonFields("patch-1", 1L, ownerRequestId);

      assertThatThrownBy(() -> TriggerScriptEventRequestFactory.builder(fields, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present"
                  + " together");
    }
  }

  @Test
  void rejectsPatchAbsentOrZeroEpochEvenWithNonBlankOwnerRequestId() {
    for (PinTuple tuple :
        new PinTuple[] {
          new PinTuple(null, 0L, "request-1"), new PinTuple("patch-1", 0L, "request-1")
        }) {
      TriggerScriptEventRequestFactory.CommonFields fields =
          commonFields(tuple.patchVersion(), tuple.epoch(), tuple.ownerRequestId());

      assertThatThrownBy(() -> TriggerScriptEventRequestFactory.builder(fields, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present"
                  + " together");
    }
  }

  private static TriggerScriptEventRequestFactory.CommonFields commonFields(
      String patchVersion, long epoch, String ownerRequestId) {
    return new TriggerScriptEventRequestFactory.CommonFields(
        "tenant",
        "instance",
        "region",
        1L,
        "entity",
        "onCommand",
        "v1",
        patchVersion,
        epoch,
        ownerRequestId,
        "event-1",
        false,
        TriggerMode.TRIGGER_MODE_NORMAL,
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        "snapshot",
        "{}");
  }

  private record PinTuple(String patchVersion, long epoch, String ownerRequestId) {}
}
