package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FixtureInitialAdmissionFramesTest {
  private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID INSTANCE = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID REQUEST = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Test
  void bindsTheExactAttemptAndActivationWithoutChangingHumanBytes() {
    var selector = selector(2L);
    byte[] humanBytes = selector.canonicalRequestBytes();
    var request =
        new WorldFixtureInitialAdmissionHoldRequest(selector, activation(REQUEST, TENANT));
    assertThat(request.canonicalBytes()).isEqualTo(request.canonicalBytes());
    assertThat(new String(request.canonicalBytes(), StandardCharsets.UTF_8))
        .contains("RUN_OWNED_FIXTURE", WorldFixtureInitialAdmissionHoldRequest.SCHEMA);
    assertThat(selector.canonicalRequestBytes()).isEqualTo(humanBytes);
    assertThatThrownBy(
            () -> WorldCanonicalInitialAdmissionHold.Request.fromStored(request.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    var differentActivation =
        new WorldFixtureInitialAdmissionHoldRequest(
            selector, activation(UUID.fromString("44444444-4444-4444-8444-444444444444"), TENANT));
    assertThat(differentActivation.canonicalBytes()).isNotEqualTo(request.canonicalBytes());
  }

  @Test
  void rejectsDifferentTenantAndNonActiveEpoch() {
    assertThatThrownBy(
            () ->
                new WorldFixtureInitialAdmissionHoldRequest(
                    selector(2L),
                    activation(REQUEST, UUID.fromString("55555555-5555-4555-8555-555555555555"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new WorldFixtureInitialAdmissionHoldRequest(
                    selector(1L), activation(REQUEST, TENANT)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void terminalFramesRequireExactPositiveOutcomeAndRejectPendingClaims() {
    var request =
        new WorldFixtureInitialAdmissionHoldRequest(selector(2L), activation(REQUEST, TENANT));
    var hold = UUID.randomUUID();
    var fence = UUID.randomUUID();
    var now = java.time.Instant.parse("2026-11-15T13:00:00Z");
    var committed =
        new WorldFixtureInitialAdmissionTerminalProof(
            request,
            hold,
            fence,
            WorldFixtureInitialAdmissionTerminalProof.Outcome.COMMITTED,
            1L,
            10L,
            false,
            now);
    assertThat(new String(committed.canonicalBytes(), StandardCharsets.UTF_8))
        .contains("world-fixture-initial-admission-terminal/v1");
    assertThatThrownBy(
            () ->
                new WorldFixtureInitialAdmissionTerminalProof(
                    request,
                    hold,
                    fence,
                    WorldFixtureInitialAdmissionTerminalProof.Outcome.COMMITTED,
                    2L,
                    10L,
                    false,
                    now))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new WorldFixtureInitialAdmissionTerminalProof(
                    request,
                    hold,
                    fence,
                    WorldFixtureInitialAdmissionTerminalProof.Outcome.ABORTED,
                    null,
                    null,
                    false,
                    now))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new WorldFixtureInitialAdmissionTerminalProof(
                    request,
                    hold,
                    fence,
                    WorldFixtureInitialAdmissionTerminalProof.Outcome.PENDING,
                    null,
                    null,
                    false,
                    now))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void roundTripsAllTerminalStatesAndPreservesExactOwnerBytes() {
    var request =
        new WorldFixtureInitialAdmissionHoldRequest(selector(2L), activation(REQUEST, TENANT));
    assertThat(
            WorldCanonicalInstanceFixtureActivationRequest.fromStored(
                request.activation().canonicalBytes()))
        .isEqualTo(request.activation());
    assertThat(WorldFixtureInitialAdmissionHoldRequest.fromStored(request.canonicalBytes()))
        .isEqualTo(request);
    for (var outcome : WorldFixtureInitialAdmissionTerminalProof.Outcome.values()) {
      boolean committed = outcome == WorldFixtureInitialAdmissionTerminalProof.Outcome.COMMITTED;
      boolean aborted = outcome == WorldFixtureInitialAdmissionTerminalProof.Outcome.ABORTED;
      var proof =
          new WorldFixtureInitialAdmissionTerminalProof(
              request,
              REQUEST,
              INSTANCE,
              outcome,
              committed ? 1L : null,
              committed ? 9L : null,
              aborted,
              committed || aborted ? java.time.Instant.parse("2026-11-15T13:00:00Z") : null);
      assertThat(WorldFixtureInitialAdmissionTerminalProof.fromStored(proof.canonicalBytes()))
          .isEqualTo(proof);
    }
  }

  @Test
  void refusesSchemaAuthorityUnknownFieldsAndNoncanonicalBytes() {
    var request =
        new WorldFixtureInitialAdmissionHoldRequest(selector(2L), activation(REQUEST, TENANT));
    String encoded = new String(request.canonicalBytes(), StandardCharsets.UTF_8);
    for (String bad :
        java.util.List.of(
            encoded + " ",
            encoded.replace("RUN_OWNED_FIXTURE", "HUMAN"),
            encoded.replace("/v1", "/v2"),
            "{\"extra\":1," + encoded.substring(1),
            "{\"schema\":\"ignored\"," + encoded.substring(1))) {
      assertThatThrownBy(
              () ->
                  WorldFixtureInitialAdmissionHoldRequest.fromStored(
                      bad.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                WorldFixtureInitialAdmissionHoldRequest.fromStored(
                    selector(2L).canonicalRequestBytes()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WorldFixtureInitialAdmissionHoldRequest.fromStored(new byte[65537]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static WorldCanonicalInstanceFixtureActivationRequest activation(
      UUID requestId, UUID tenant) {
    return new WorldCanonicalInstanceFixtureActivationRequest(
        requestId, tenant, INSTANCE, "sha256:" + "a".repeat(64), 1L, 0L);
  }

  private static WorldCanonicalInitialAdmissionHold.Request selector(long epoch) {
    return new WorldCanonicalInitialAdmissionHold.Request(
        "test",
        TENANT,
        "green-hollow",
        UUID.fromString("66666666-6666-4666-8666-666666666666"),
        UUID.fromString("77777777-7777-4777-8777-777777777777"),
        "SHARED",
        INSTANCE,
        UUID.fromString("88888888-8888-4888-8888-888888888888"),
        epoch,
        "initial-attempt-1",
        "b".repeat(64),
        WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
        1L,
        null);
  }
}
