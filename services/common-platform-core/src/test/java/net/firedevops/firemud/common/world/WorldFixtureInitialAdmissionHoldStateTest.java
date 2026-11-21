package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState.HoldStatus;
import org.junit.jupiter.api.Test;

class WorldFixtureInitialAdmissionHoldStateTest {
  @Test
  void exactPendingActiveStateRoundTripsWithoutChangingRequestOrHoldBytes() {
    var request = FixtureInitialAdmissionGrpcCodecTest.request(false);
    var identity = FixtureInitialAdmissionGrpcCodecTest.identity(request);
    byte[] requestBytes = request.canonicalBytes();
    byte[] identityBytes = identity.canonicalBytes();
    var state =
        new WorldFixtureInitialAdmissionHoldState(
            request, identity, HoldStatus.PENDING, "ACTIVE", 2, 1);
    state.requirePendingActive();
    var decoded = WorldFixtureInitialAdmissionHoldState.fromStored(state.canonicalBytes());
    assertThat(decoded).isEqualTo(state);
    decoded.requirePendingActive();
    assertThat(decoded.request().canonicalBytes()).containsExactly(requestBytes);
    assertThat(decoded.holdIdentity().canonicalBytes()).containsExactly(identityBytes);
  }

  @Test
  void completeRequestMismatchAndInvalidLifecycleCoordinatesCannotConstructState() {
    var request = FixtureInitialAdmissionGrpcCodecTest.request(false);
    var otherIdentity =
        FixtureInitialAdmissionGrpcCodecTest.identity(
            FixtureInitialAdmissionGrpcCodecTest.request(true));
    assertThatThrownBy(
            () ->
                new WorldFixtureInitialAdmissionHoldState(
                    request, otherIdentity, HoldStatus.PENDING, "ACTIVE", 2, 1))
        .isInstanceOf(IllegalArgumentException.class);
    var identity = FixtureInitialAdmissionGrpcCodecTest.identity(request);
    assertThatThrownBy(
            () ->
                new WorldFixtureInitialAdmissionHoldState(
                    request, identity, HoldStatus.PENDING, "UNKNOWN", 2, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new WorldFixtureInitialAdmissionHoldState(
                    request, identity, HoldStatus.PENDING, "ACTIVE", 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new WorldFixtureInitialAdmissionHoldState(
                    request, identity, HoldStatus.PENDING, "ACTIVE", 2, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void historicalStatesRemainReadableButCannotServeAsPendingActiveFirstOpenEvidence() {
    var request = FixtureInitialAdmissionGrpcCodecTest.request(false);
    var identity = FixtureInitialAdmissionGrpcCodecTest.identity(request);
    for (var status : HoldStatus.values()) {
      if (status == HoldStatus.PENDING) continue;
      var state =
          new WorldFixtureInitialAdmissionHoldState(request, identity, status, "ACTIVE", 2, 1);
      assertThat(WorldFixtureInitialAdmissionHoldState.fromStored(state.canonicalBytes()))
          .isEqualTo(state);
      assertThatThrownBy(state::requirePendingActive).isInstanceOf(IllegalStateException.class);
    }
    for (String lifecycle :
        List.of("PREPARING", "FAILED_PRE_ACTIVATION", "TERMINATING", "TERMINATED")) {
      var state =
          new WorldFixtureInitialAdmissionHoldState(
              request, identity, HoldStatus.PENDING, lifecycle, 2, 1);
      assertThatThrownBy(state::requirePendingActive).isInstanceOf(IllegalStateException.class);
    }
    for (long[] coordinates :
        List.of(new long[] {1, 1}, new long[] {3, 1}, new long[] {2, 0}, new long[] {2, 2})) {
      var state =
          new WorldFixtureInitialAdmissionHoldState(
              request, identity, HoldStatus.PENDING, "ACTIVE", coordinates[0], coordinates[1]);
      assertThatThrownBy(state::requirePendingActive).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void storedFrameRejectsUnknownMissingNoncanonicalDuplicateAndOversizedValues() {
    var request = FixtureInitialAdmissionGrpcCodecTest.request(false);
    var state =
        new WorldFixtureInitialAdmissionHoldState(
            request,
            FixtureInitialAdmissionGrpcCodecTest.identity(request),
            HoldStatus.PENDING,
            "ACTIVE",
            2,
            1);
    String json = new String(state.canonicalBytes(), StandardCharsets.UTF_8);
    for (String bad :
        List.of(
            json + " ",
            "{}",
            "{\"extra\":1," + json.substring(1),
            "{\"schema\":\"ignored\"," + json.substring(1),
            json.replace(WorldFixtureInitialAdmissionHoldState.SCHEMA, "wrong/v1"),
            json.replace("\"lifecycleEpoch\":\"2\"", "\"lifecycleEpoch\":\"02\""),
            json.replace("\"PENDING\"", "\"UNKNOWN\""))) {
      assertThatThrownBy(
              () ->
                  WorldFixtureInitialAdmissionHoldState.fromStored(
                      bad.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> WorldFixtureInitialAdmissionHoldState.fromStored(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WorldFixtureInitialAdmissionHoldState.fromStored(new byte[65537]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void expectClosedValueStateStillDecodesWithoutAssertingRuntimeAdmissionAuthority() {
    var request = FixtureInitialAdmissionGrpcCodecTest.request(true);
    var state =
        new WorldFixtureInitialAdmissionHoldState(
            request,
            FixtureInitialAdmissionGrpcCodecTest.identity(request),
            HoldStatus.COMMITTED,
            "ACTIVE",
            2,
            1);
    assertThat(WorldFixtureInitialAdmissionHoldState.fromStored(state.canonicalBytes()))
        .isEqualTo(state);
    assertThatThrownBy(state::requirePendingActive).isInstanceOf(IllegalStateException.class);
  }
}
