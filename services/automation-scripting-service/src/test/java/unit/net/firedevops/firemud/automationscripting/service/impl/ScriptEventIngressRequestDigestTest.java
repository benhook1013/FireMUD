package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import org.junit.jupiter.api.Test;

class ScriptEventIngressRequestDigestTest {
  @Test
  void canonicalizesEquivalentPayloadJson() {
    TriggerScriptEventRequest first = request("{\"b\":2, \"a\": 1}");
    TriggerScriptEventRequest second = request("{\"a\":1,\"b\":2}");

    assertThat(ScriptEventIngressRequestDigest.compute(first, "v1", "game-session-service"))
        .isEqualTo(ScriptEventIngressRequestDigest.compute(second, "v1", "game-session-service"));
  }

  @Test
  void treatsAbsentBlankAndJsonNullPayloadsAsTheSameSemanticPayload() {
    TriggerScriptEventRequest absent = request("").toBuilder().clearPayloadJson().build();
    TriggerScriptEventRequest blank = request("   ");
    TriggerScriptEventRequest jsonNull = request("null");

    String absentDigest =
        ScriptEventIngressRequestDigest.compute(absent, "v1", "game-session-service");
    assertThat(ScriptEventIngressRequestDigest.compute(blank, "v1", "game-session-service"))
        .isEqualTo(absentDigest);
    assertThat(ScriptEventIngressRequestDigest.compute(jsonNull, "v1", "game-session-service"))
        .isEqualTo(absentDigest);
  }

  @Test
  void bindsOwnerRequestEvidenceSoChangedIdCannotReplayTheClaim() {
    TriggerScriptEventRequest original = request("{\"a\":1}");
    TriggerScriptEventRequest changedOwner =
        original.toBuilder().setScriptPinControlPlaneRequestId("pin-request-2").build();

    assertThat(ScriptEventIngressRequestDigest.compute(original, "v1", "game-session-service"))
        .isNotEqualTo(
            ScriptEventIngressRequestDigest.compute(changedOwner, "v1", "game-session-service"));
  }

  @Test
  void bindsScriptPinEpochSoChangingOnlyEpochCannotReplayTheClaim() {
    TriggerScriptEventRequest original = request("{\"a\":1}");
    TriggerScriptEventRequest changedEpoch = original.toBuilder().setScriptPinEpoch(2L).build();

    assertThat(ScriptEventIngressRequestDigest.compute(original, "v1", "game-session-service"))
        .isNotEqualTo(
            ScriptEventIngressRequestDigest.compute(changedEpoch, "v1", "game-session-service"));
    assertThat(changedEpoch.getScriptPinControlPlaneRequestId())
        .isEqualTo(original.getScriptPinControlPlaneRequestId());
  }

  @Test
  void bindsEventSchemaVersionSoChangingOnlySchemaCannotReplayTheClaim() {
    TriggerScriptEventRequest original = request("{\"a\":1}");

    assertThat(ScriptEventIngressRequestDigest.compute(original, "v1", "game-session-service"))
        .isNotEqualTo(
            ScriptEventIngressRequestDigest.compute(original, "v2", "game-session-service"));
  }

  @Test
  void bindsSourceServiceSoChangingOnlyProducerCannotReplayTheClaim() {
    TriggerScriptEventRequest original = request("{\"a\":1}");

    assertThat(ScriptEventIngressRequestDigest.compute(original, "v1", "game-session-service"))
        .isNotEqualTo(
            ScriptEventIngressRequestDigest.compute(
                original, "v1", "automation-scripting-service"));
  }

  @Test
  void rejectsDuplicateJsonObjectKeys() {
    TriggerScriptEventRequest duplicateKeys = request("{\"a\":1,\"a\":2}");

    assertThatThrownBy(
            () ->
                ScriptEventIngressRequestDigest.compute(
                    duplicateKeys, "v1", "game-session-service"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("JSON payload contains duplicate keys");
  }

  @Test
  void rejectsDuplicateKeysAfterNfcNormalization() {
    TriggerScriptEventRequest duplicateKeys = request("{\"\\u00e9\":1,\"e\\u0301\":2}");

    assertThatThrownBy(
            () ->
                ScriptEventIngressRequestDigest.compute(
                    duplicateKeys, "v1", "game-session-service"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate keys after NFC normalization");
  }

  @Test
  void preservesDistinctMalformedPayloadsInDigest() {
    TriggerScriptEventRequest first = request("{\"a\":}");
    TriggerScriptEventRequest second = request("[1,");

    assertThat(ScriptEventIngressRequestDigest.compute(first, "v1", "game-session-service"))
        .isNotEqualTo(
            ScriptEventIngressRequestDigest.compute(second, "v1", "game-session-service"));
  }

  @Test
  void treatsEquivalentNfcPayloadValuesAsOneDigest() {
    TriggerScriptEventRequest composed = request("{\"value\":\"\\u00e9\"}");
    TriggerScriptEventRequest decomposed = request("{\"value\":\"e\\u0301\"}");

    assertThat(ScriptEventIngressRequestDigest.compute(composed, "v1", "game-session-service"))
        .isEqualTo(
            ScriptEventIngressRequestDigest.compute(decomposed, "v1", "game-session-service"));
  }

  private static TriggerScriptEventRequest request(String payload) {
    return TriggerScriptEventRequest.newBuilder()
        .setTenantId("1")
        .setGameInstanceId("game-1")
        .setRegionId("region-1")
        .setRegionEpoch(7L)
        .setEntityId("entity-1")
        .setEventType("onCommand")
        .setScriptPatchVersion("patch-1")
        .setScriptPinEpoch(1L)
        .setScriptPinControlPlaneRequestId("pin-request-1")
        .setScriptEventId("event-1")
        .setPayloadJson(payload)
        .build();
  }
}
