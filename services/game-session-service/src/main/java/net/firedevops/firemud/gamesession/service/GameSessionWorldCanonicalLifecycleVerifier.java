package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;

/**
 * Validates complete authenticated World lifecycle reads against their exact launch binding.
 *
 * <p>Manually composed evidence for a point-in-time World owner read. The caller supplies the
 * independently retained request and complete launch binding for each authenticated response.
 */
public final class GameSessionWorldCanonicalLifecycleVerifier {
  private static final String PREPARING = "PREPARING";
  private static final String ACTIVE = "ACTIVE";

  /** Full request and complete source/release pair expected from this owner read. */
  public record Expected(
      WorldCanonicalInstanceLifecycleEvidence.Request request,
      CompleteLaunchBindingEvidence launchBinding) {
    public Expected {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(launchBinding, "launchBinding");
    }
  }

  /** A PREPARING snapshot that has passed the exact request and complete-binding checks. */
  public static final class VerifiedPreparing {
    private final Expected expected;
    private final WorldCanonicalInstanceLifecycleEvidence evidence;

    private VerifiedPreparing(Expected expected, WorldCanonicalInstanceLifecycleEvidence evidence) {
      this.expected = expected;
      this.evidence = evidence;
    }

    public Expected expected() {
      return expected;
    }

    public WorldCanonicalInstanceLifecycleEvidence evidence() {
      return evidence;
    }

    public RoomTemplateRef startLocation() {
      return evidence.startLocation();
    }

    public long runtimeRoomInstanceId() {
      return evidence.runtimeRoomInstanceId();
    }

    public long lifecycleEpoch() {
      return evidence.lifecycleEpoch();
    }

    public long rowVersion() {
      return evidence.rowVersion();
    }
  }

  /** An ACTIVE snapshot tied to its verified PREPARING origin and World capture. */
  public static final class VerifiedActive {
    private final Expected expected;
    private final WorldCanonicalInstanceLifecycleEvidence evidence;

    private VerifiedActive(Expected expected, WorldCanonicalInstanceLifecycleEvidence evidence) {
      this.expected = expected;
      this.evidence = evidence;
    }

    public Expected expected() {
      return expected;
    }

    public WorldCanonicalInstanceLifecycleEvidence evidence() {
      return evidence;
    }

    public RoomTemplateRef startLocation() {
      return evidence.startLocation();
    }

    public long runtimeRoomInstanceId() {
      return evidence.runtimeRoomInstanceId();
    }

    public long lifecycleEpoch() {
      return evidence.lifecycleEpoch();
    }

    public long rowVersion() {
      return evidence.rowVersion();
    }
  }

  /** Verifies an exact World PREPARING read and returns typed evidence for later owner reads. */
  public VerifiedPreparing verifyPreparing(
      Expected expected, WorldCanonicalInstanceLifecycleEvidence observed) {
    requireObserved(expected, observed);
    if (!PREPARING.equals(observed.lifecycleStatus())) {
      throw new IllegalArgumentException("World lifecycle read is not PREPARING");
    }
    return new VerifiedPreparing(expected, observed);
  }

  /**
   * Rechecks a PREPARING read against a later owner read. Exact response replay is accepted, and
   * the mutable current row version may advance but cannot move backwards.
   */
  public VerifiedPreparing verifyCurrentPreparing(
      VerifiedPreparing previous,
      Expected currentRead,
      WorldCanonicalInstanceLifecycleEvidence observed) {
    Objects.requireNonNull(previous, "previous");
    requireObserved(currentRead, observed);
    requireSameRequestBinding(previous.expected.request(), currentRead.request());
    if (!PREPARING.equals(observed.lifecycleStatus())) {
      throw new IllegalArgumentException("Current World lifecycle read is not PREPARING");
    }
    requireSameCapture(previous.evidence, observed);
    if (observed.lifecycleEpoch() != previous.lifecycleEpoch()) {
      throw new IllegalArgumentException("Current World PREPARING lifecycle epoch changed");
    }
    requireNondecreasingRowVersion(previous.rowVersion(), observed.rowVersion());
    return new VerifiedPreparing(currentRead, observed);
  }

  /**
   * Verifies a current ACTIVE read for the same complete World capture after PREPARING. The World
   * epoch advances by exactly one, and the mutable row may have advanced more than once since
   * PREPARING.
   */
  public VerifiedActive verifyActiveAfterPreparing(
      VerifiedPreparing preparing,
      Expected activeRead,
      WorldCanonicalInstanceLifecycleEvidence observed) {
    Objects.requireNonNull(preparing, "preparing");
    requireObserved(activeRead, observed);
    requireSameRequestBinding(preparing.expected.request(), activeRead.request());
    if (!ACTIVE.equals(observed.lifecycleStatus())) {
      throw new IllegalArgumentException("World lifecycle read is not ACTIVE");
    }
    long nextPreparingEpoch;
    try {
      nextPreparingEpoch = Math.addExact(preparing.lifecycleEpoch(), 1L);
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "World PREPARING lifecycle epoch cannot advance", overflow);
    }
    if (observed.lifecycleEpoch() != nextPreparingEpoch) {
      throw new IllegalArgumentException(
          "World ACTIVE lifecycle epoch does not immediately follow PREPARING");
    }
    if (observed.rowVersion() <= preparing.rowVersion()) {
      throw new IllegalArgumentException(
          "World ACTIVE row version did not advance beyond PREPARING");
    }
    requireSameCapture(preparing.evidence, observed);
    return new VerifiedActive(activeRead, observed);
  }

  /**
   * Validates a later current ACTIVE read or exact replay against prior verified ACTIVE evidence.
   * Row version is mutable current-state metadata: equality is a valid replay, advancement is
   * valid, and regression is stale evidence.
   */
  public VerifiedActive verifyCurrentActive(
      VerifiedActive previous,
      Expected currentRead,
      WorldCanonicalInstanceLifecycleEvidence observed) {
    Objects.requireNonNull(previous, "previous");
    requireObserved(currentRead, observed);
    requireSameRequestBinding(previous.expected.request(), currentRead.request());
    if (!ACTIVE.equals(observed.lifecycleStatus())) {
      throw new IllegalArgumentException("Current World lifecycle read is not ACTIVE");
    }
    if (observed.lifecycleEpoch() != previous.lifecycleEpoch()) {
      throw new IllegalArgumentException("Current World ACTIVE lifecycle epoch changed");
    }
    requireSameCapture(previous.evidence, observed);
    requireNondecreasingRowVersion(previous.rowVersion(), observed.rowVersion());
    return new VerifiedActive(currentRead, observed);
  }

  private static void requireObserved(
      Expected expected, WorldCanonicalInstanceLifecycleEvidence observed) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(observed, "observed");
    if (!expected.request().equals(observed.request())) {
      throw new IllegalArgumentException("World lifecycle response changed the exact read request");
    }
    if (!expected.launchBinding().equals(observed.launchBinding())) {
      throw new IllegalArgumentException(
          "World lifecycle response changed the complete launch binding");
    }
  }

  private static void requireSameCapture(
      WorldCanonicalInstanceLifecycleEvidence previous,
      WorldCanonicalInstanceLifecycleEvidence current) {
    if (!previous.launchBinding().equals(current.launchBinding())
        || !previous.startLocation().equals(current.startLocation())
        || previous.runtimeRoomInstanceId() != current.runtimeRoomInstanceId()
        || !previous.captureId().equals(current.captureId())
        || !previous.graphSha256().equals(current.graphSha256())
        || !previous.preparationInputDigest().equals(current.preparationInputDigest())
        || !previous
            .operationalRegionAssignments()
            .equals(current.operationalRegionAssignments())) {
      throw new IllegalArgumentException(
          "World lifecycle read changed its original preparation capture or selector");
    }
  }

  private static void requireSameRequestBinding(
      WorldCanonicalInstanceLifecycleEvidence.Request previous,
      WorldCanonicalInstanceLifecycleEvidence.Request current) {
    if (previous.schemaVersion() != current.schemaVersion()
        || !previous.targetNamespace().equals(current.targetNamespace())
        || !previous.canonicalTenantId().equals(current.canonicalTenantId())
        || !previous.worldSlug().equals(current.worldSlug())
        || !previous.canonicalGameInstanceId().equals(current.canonicalGameInstanceId())
        || !previous.playableStateNamespaceId().equals(current.playableStateNamespaceId())
        || !previous.playableStateScope().equals(current.playableStateScope())
        || previous.publicProduction() != current.publicProduction()
        || !previous.controlPlaneRequestId().equals(current.controlPlaneRequestId())
        || !previous.canonicalVersionId().equals(current.canonicalVersionId())
        || !previous
            .expectedDescriptorRequestDigest()
            .equals(current.expectedDescriptorRequestDigest())
        || !previous
            .expectedDescriptorResultDigest()
            .equals(current.expectedDescriptorResultDigest())
        || !previous
            .expectedReleaseAttestationDigest()
            .equals(current.expectedReleaseAttestationDigest())) {
      throw new IllegalArgumentException(
          "World lifecycle read changed the complete canonical launch request binding");
    }
  }

  private static void requireNondecreasingRowVersion(long previous, long current) {
    if (current < previous) {
      throw new IllegalArgumentException("World current row version moved backwards");
    }
  }
}
