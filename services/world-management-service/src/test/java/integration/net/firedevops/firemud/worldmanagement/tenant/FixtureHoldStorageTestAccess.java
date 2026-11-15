package net.firedevops.firemud.worldmanagement.tenant;

import java.time.Instant;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;

/** Test-only access to private storage; stipulated terminal data is not authenticated transport. */
public final class FixtureHoldStorageTestAccess {
  private FixtureHoldStorageTestAccess() {}

  public static void abort(
      WorldFixtureInitialAdmissionHoldRepository storage,
      WorldFixtureInitialAdmissionHoldRequest request,
      HoldIdentity identity) {
    var proof =
        new WorldFixtureInitialAdmissionTerminalProof(
            request,
            identity.holdId(),
            identity.holdFence(),
            WorldFixtureInitialAdmissionTerminalProof.Outcome.ABORTED,
            null,
            null,
            true,
            Instant.parse("2026-11-15T13:00:00Z"));
    storage.settle(identity, proof);
    storage.settle(identity, proof);
  }

  public static void commitAndRejectMalformedProof(
      org.jooq.DSLContext dsl,
      WorldFixtureInitialAdmissionHoldRepository storage,
      WorldFixtureInitialAdmissionHoldRequest request,
      HoldIdentity identity) {
    var now = Instant.parse("2026-11-15T13:00:00Z");
    var pending =
        new WorldFixtureInitialAdmissionTerminalProof(
            request,
            identity.holdId(),
            identity.holdFence(),
            WorldFixtureInitialAdmissionTerminalProof.Outcome.PENDING,
            null,
            null,
            false,
            null);
    storage.settle(identity, pending);
    org.assertj.core.api.Assertions.assertThat(storage.readState(request, identity))
        .contains(
            net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState.HoldStatus
                .PENDING);
    var wrongFence =
        new WorldFixtureInitialAdmissionTerminalProof(
            request,
            identity.holdId(),
            java.util.UUID.randomUUID(),
            WorldFixtureInitialAdmissionTerminalProof.Outcome.COMMITTED,
            1L,
            73L,
            false,
            now);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.settle(identity, wrongFence))
        .isInstanceOf(IllegalStateException.class);
    var proof =
        new WorldFixtureInitialAdmissionTerminalProof(
            request,
            identity.holdId(),
            identity.holdFence(),
            WorldFixtureInitialAdmissionTerminalProof.Outcome.COMMITTED,
            1L,
            73L,
            false,
            now);
    byte[] malformed =
        new String(proof.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8)
            .replace(
                "world-fixture-initial-admission-terminal/v1",
                "world-fixture-initial-admission-terminal/wrong")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final String digest;
    try {
      digest =
          java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(malformed));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    // Use a correctly rehashed but semantically malformed frame so SQL must validate authority,
    // not merely reject a bad digest. No authenticated transport is claimed by this fixture.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                dsl.fetchOne(
                    "SELECT world_finalize_canonical_initial_admission_hold(?::uuid,?::uuid,?::text,?::text,?::text,?::text,?::bigint,?::timestamptz,?::boolean,?::bytea,?::text)",
                    identity.holdId(),
                    identity.holdFence(),
                    "COMMITTED",
                    request.selector().initialAdmissionRequestId(),
                    digest,
                    "73",
                    1L,
                    java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC),
                    false,
                    malformed,
                    "sha256:" + digest))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Fixture hold terminal proof differs");
    org.assertj.core.api.Assertions.assertThat(storage.readState(request, identity))
        .contains(
            net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState.HoldStatus
                .PENDING);
    storage.settle(identity, proof);
    storage.settle(identity, proof);
    var otherOutcome =
        new WorldFixtureInitialAdmissionTerminalProof(
            request,
            identity.holdId(),
            identity.holdFence(),
            WorldFixtureInitialAdmissionTerminalProof.Outcome.ABORTED,
            null,
            null,
            true,
            now);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.settle(identity, otherOutcome))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already settled");
  }
}
