package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import org.junit.jupiter.api.Test;

class WorldCanonicalInitialAdmissionHoldStateTest {
  private static final UUID REALM = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID HOLD_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID HOLD_FENCE = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

  @Test
  void completePendingActiveStateRoundTripsExactNestedOwnerBytes() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence lifecycle =
        AuthoringFixtures.lifecycleEvidence("ACTIVE", 3L);
    Request request = holdRequest(lifecycle);
    var identity = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);
    var state =
        new WorldCanonicalInitialAdmissionHoldState(
            identity, WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING, lifecycle);

    byte[] encoded = state.canonicalBytes();
    var decoded = WorldCanonicalInitialAdmissionHoldState.fromStored(encoded);

    assertThat(decoded).isEqualTo(state);
    assertThat(decoded.isPendingAtExpectedActiveEpoch()).isTrue();
    String encodedText = new String(encoded, StandardCharsets.UTF_8);
    assertThat(encodedText)
        .contains(java.util.Base64.getEncoder().encodeToString(identity.canonicalBytes()));
    assertThat(encodedText)
        .contains(java.util.Base64.getEncoder().encodeToString(lifecycle.canonicalBytes()));
  }

  @Test
  void nonterminalAndAdvancedLifecycleSamplesRemainHonestButDoNotQualify() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence active =
        AuthoringFixtures.lifecycleEvidence("ACTIVE", 3L);
    Request request = holdRequest(active);
    HoldIdentity identity = new HoldIdentity(request, HOLD_ID, HOLD_FENCE);

    for (var status :
        List.of(
            WorldCanonicalInitialAdmissionHoldState.HoldStatus.RECONCILIATION_REQUIRED,
            WorldCanonicalInitialAdmissionHoldState.HoldStatus.COMMITTED,
            WorldCanonicalInitialAdmissionHoldState.HoldStatus.ABORTED)) {
      var state = new WorldCanonicalInitialAdmissionHoldState(identity, status, active);
      assertThat(state.isPendingAtExpectedActiveEpoch()).isFalse();
      assertThat(WorldCanonicalInitialAdmissionHoldState.fromStored(state.canonicalBytes()))
          .isEqualTo(state);
    }

    var advanced = AuthoringFixtures.lifecycleEvidence("TERMINATING", 4L);
    var observed =
        new WorldCanonicalInitialAdmissionHoldState(
            identity, WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING, advanced);
    assertThat(observed.lifecycleEvidence().lifecycleStatus()).isEqualTo("TERMINATING");
    assertThat(observed.lifecycleEvidence().lifecycleEpoch()).isEqualTo(4L);
    assertThat(observed.isPendingAtExpectedActiveEpoch()).isFalse();
    assertThat(WorldCanonicalInitialAdmissionHoldState.fromStored(observed.canonicalBytes()))
        .isEqualTo(observed);
  }

  @Test
  void rejectsChangedTargetScopeWithoutEquatingIndependentRequestIds() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence lifecycle =
        AuthoringFixtures.lifecycleEvidence("ACTIVE", 3L);
    Request original = holdRequest(lifecycle);
    Request mismatched =
        new Request(
            original.targetNamespace(),
            original.canonicalTenantId(),
            original.worldSlug(),
            REALM,
            uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            original.playableStateScope(),
            original.canonicalGameInstanceId(),
            original.canonicalVersionId(),
            original.activeLifecycleEpoch(),
            original.initialAdmissionRequestId(),
            original.initialAdmissionRequestDigest(),
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1L,
            null);

    assertThatThrownBy(
            () ->
                new WorldCanonicalInitialAdmissionHoldState(
                    new HoldIdentity(mismatched, HOLD_ID, HOLD_FENCE),
                    WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING,
                    lifecycle))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("target scope");

    Request independentRequestId =
        new Request(
            original.targetNamespace(),
            original.canonicalTenantId(),
            original.worldSlug(),
            original.realmId(),
            original.playableStateNamespaceId(),
            original.playableStateScope(),
            original.canonicalGameInstanceId(),
            original.canonicalVersionId(),
            original.activeLifecycleEpoch(),
            "a-distinct-initial-admission-request-id",
            original.initialAdmissionRequestDigest(),
            InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1L,
            null);
    assertThat(
            new WorldCanonicalInitialAdmissionHoldState(
                    new HoldIdentity(independentRequestId, HOLD_ID, HOLD_FENCE),
                    WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING,
                    lifecycle)
                .lifecycleEvidence()
                .request()
                .controlPlaneRequestId())
        .isEqualTo(lifecycle.request().controlPlaneRequestId());
    assertThat(independentRequestId.initialAdmissionRequestId())
        .isNotEqualTo(lifecycle.request().controlPlaneRequestId());
  }

  @Test
  void rejectsUnknownDuplicateNoncanonicalMalformedAndOversizedState() throws Exception {
    var lifecycle = AuthoringFixtures.lifecycleEvidence("ACTIVE", 3L);
    var state =
        new WorldCanonicalInitialAdmissionHoldState(
            new HoldIdentity(holdRequest(lifecycle), HOLD_ID, HOLD_FENCE),
            WorldCanonicalInitialAdmissionHoldState.HoldStatus.PENDING,
            lifecycle);
    String canonical = new String(state.canonicalBytes(), StandardCharsets.UTF_8);

    assertInvalid(canonical.replace("\"schema\":", "\"extra\":true,\"schema\":"));
    String duplicateSchema =
        "\"schema\":\""
            + WorldCanonicalInitialAdmissionHoldState.SCHEMA
            + "\",\"schema\":\""
            + WorldCanonicalInitialAdmissionHoldState.SCHEMA
            + "\"";
    assertInvalid(
        canonical.replace(
            "\"schema\":\"" + WorldCanonicalInitialAdmissionHoldState.SCHEMA + "\"",
            duplicateSchema));
    assertInvalid(canonical.replace("PENDING", "UNKNOWN"));
    assertInvalid(canonical.replace(",\"holdStatus\":\"PENDING\"", ""));
    assertInvalid(
        canonical.replace(
            java.util.Base64.getEncoder().encodeToString(state.holdIdentity().canonicalBytes()),
            "!"));
    assertInvalid(canonical + " ");
    assertThatThrownBy(
            () ->
                WorldCanonicalInitialAdmissionHoldState.fromStored(new byte[] {(byte) 0xc3, 0x28}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strict UTF-8");
    assertThatThrownBy(
            () ->
                WorldCanonicalInitialAdmissionHoldState.fromStored(
                    new byte[WorldCanonicalInitialAdmissionHoldState.MAX_CANONICAL_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit");
  }

  private static void assertInvalid(String value) {
    assertThatThrownBy(
            () ->
                WorldCanonicalInitialAdmissionHoldState.fromStored(
                    value.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Request holdRequest(WorldCanonicalInstanceLifecycleEvidence lifecycle) {
    var request = lifecycle.request();
    return new Request(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        REALM,
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        request.canonicalGameInstanceId(),
        request.canonicalVersionId(),
        3L,
        "initial-admission-independent-from-preparation-request",
        "a".repeat(64),
        InitialAdmissionOrigin.NO_PRIOR_POINTER,
        1L,
        null);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
