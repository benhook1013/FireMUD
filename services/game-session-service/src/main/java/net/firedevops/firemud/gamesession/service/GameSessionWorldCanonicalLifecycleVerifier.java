package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;

/**
 * Validates complete authenticated World lifecycle reads against their exact launch binding.
 *
 * <p>This readback is owner evidence only. It does not decide current Account authority, create an
 * admission, or make a lifecycle result current beyond the read that was verified.
 */
public final class GameSessionWorldCanonicalLifecycleVerifier {
  private static final String PREPARING = "PREPARING";
  private static final String ACTIVE = "ACTIVE";
  private static final String SHARED = "SHARED";

  /** Full request and complete source/release pair expected from this owner read. */
  public record Expected(
      WorldCanonicalInstanceLifecycleEvidence.Request request,
      CompleteLaunchBindingEvidence launchBinding) {
    public Expected {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(launchBinding, "launchBinding");
      requireExpectedBinding(request, launchBinding);
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

  /** Verifies an exact World PREPARING read and returns typed evidence for the later CAS read. */
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
    requireSameRequestBinding(previous.expected.request(), currentRead.request());
    requireSameLaunchBinding(previous.expected.launchBinding(), currentRead.launchBinding());
    requireExpectedBinding(currentRead.request(), currentRead.launchBinding());
    requireObserved(currentRead, observed);
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
   * Verifies the fenced PREPARING to ACTIVE readback for the same complete World capture. The
   * lifecycle epoch is World-owned and is deliberately distinct from Game Design's version-state
   * epoch carried by the immutable launch descriptor.
   */
  public VerifiedActive verifyActiveAfterPreparing(
      VerifiedPreparing preparing,
      Expected activeRead,
      WorldCanonicalInstanceLifecycleEvidence observed) {
    Objects.requireNonNull(preparing, "preparing");
    requireSameRequestBinding(preparing.expected.request(), activeRead.request());
    requireSameLaunchBinding(preparing.expected.launchBinding(), activeRead.launchBinding());
    requireExpectedBinding(activeRead.request(), activeRead.launchBinding());
    requireObserved(activeRead, observed);
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
    requireNondecreasingRowVersion(preparing.rowVersion(), observed.rowVersion());
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
    requireSameRequestBinding(previous.expected.request(), currentRead.request());
    requireSameLaunchBinding(previous.expected.launchBinding(), currentRead.launchBinding());
    requireExpectedBinding(currentRead.request(), currentRead.launchBinding());
    requireObserved(currentRead, observed);
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
    if (observed.lifecycleEpoch() <= 0L) {
      throw new IllegalArgumentException("World lifecycle epoch must be positive");
    }
    if (observed.rowVersion() < 0L) {
      throw new IllegalArgumentException("World current row version must be nonnegative");
    }
    if (observed.runtimeRoomInstanceId() <= 0L) {
      throw new IllegalArgumentException("World runtime room instance id must be positive");
    }
    if (!expectedSelector(expected.launchBinding()).equals(observed.startLocation())) {
      throw new IllegalArgumentException(
          "World lifecycle selector differs from the complete release attestation");
    }
  }

  private static void requireExpectedBinding(
      WorldCanonicalInstanceLifecycleEvidence.Request request,
      CompleteLaunchBindingEvidence launchBinding) {
    AuthoredWorldLaunchDescriptorEvidence descriptor = launchBinding.descriptor();
    AuthoredWorldReleaseAttestationEvidence release = launchBinding.releaseAttestation();
    descriptor.requireValid();
    release.requireValid(descriptor);
    WorldPublishedStartLocationEvidence worldSelector = release.worldStartLocationEvidence();
    if (release.schemaVersion() != AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION
        || worldSelector == null) {
      throw new IllegalArgumentException(
          "World lifecycle verification requires complete selector/v2 release evidence");
    }
    WorldPublishedStartLocationEvidence.fromStored(worldSelector.canonicalBytes());
    if (!request.targetNamespace().equals(descriptor.targetNamespace())
        || !request.targetNamespace().equals(release.targetNamespace())
        || !request.canonicalTenantId().equals(descriptor.canonicalTenantId())
        || !request.canonicalTenantId().equals(release.canonicalTenantId())
        || !request.worldSlug().equals(descriptor.worldSlug())
        || !request.worldSlug().equals(release.worldSlug())
        || !request.controlPlaneRequestId().equals(descriptor.controlPlaneRequestId())
        || !request.expectedDescriptorRequestDigest().equals(descriptor.requestDigest())
        || !request.expectedDescriptorResultDigest().equals(descriptor.resultDigest())
        || !request.expectedReleaseAttestationDigest().equals(release.evidenceDigest())
        || !request.canonicalVersionId().equals(release.canonicalVersionId())
        || !descriptor
            .authoredWorldSourceOperationId()
            .equals(release.authoredWorldSourceOperationId())
        || !descriptor
            .authoredWorldSourceEvidenceDigest()
            .equals(release.authoredWorldSourceEvidenceDigest())
        || !request.canonicalTenantId().equals(worldSelector.request().canonicalTenantId())
        || !request.canonicalVersionId().equals(worldSelector.request().canonicalVersionId())
        || !request.targetNamespace().equals(worldSelector.request().targetNamespace())
        || !request.publicProduction()
        || !SHARED.equals(request.playableStateScope())) {
      throw new IllegalArgumentException(
          "World lifecycle request differs from the complete public shared launch binding");
    }
    if (request.canonicalGameInstanceId() == null
        || request.playableStateNamespaceId() == null
        || isNil(request.canonicalGameInstanceId())
        || isNil(request.playableStateNamespaceId())) {
      throw new IllegalArgumentException("Canonical World lifecycle request is incomplete");
    }
  }

  private static RoomTemplateRef expectedSelector(CompleteLaunchBindingEvidence binding) {
    WorldPublishedStartLocationEvidence selector =
        binding.releaseAttestation().worldStartLocationEvidence();
    if (selector == null) {
      throw new IllegalArgumentException("Complete release selector evidence is required");
    }
    return WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes())
        .startLocation();
  }

  private static void requireSameCapture(
      WorldCanonicalInstanceLifecycleEvidence previous,
      WorldCanonicalInstanceLifecycleEvidence current) {
    if (!previous.launchBinding().equals(current.launchBinding())
        || !previous.startLocation().equals(current.startLocation())
        || previous.runtimeRoomInstanceId() != current.runtimeRoomInstanceId()
        || !previous.captureId().equals(current.captureId())
        || !previous.graphSha256().equals(current.graphSha256())
        || !previous.preparationInputDigest().equals(current.preparationInputDigest())) {
      throw new IllegalArgumentException(
          "World lifecycle read changed its original preparation capture or selector");
    }
  }

  private static void requireSameRequestBinding(
      WorldCanonicalInstanceLifecycleEvidence.Request previous,
      WorldCanonicalInstanceLifecycleEvidence.Request current) {
    Objects.requireNonNull(current, "currentRead.request");
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

  private static void requireSameLaunchBinding(
      CompleteLaunchBindingEvidence previous, CompleteLaunchBindingEvidence current) {
    if (!previous.equals(current)) {
      throw new IllegalArgumentException(
          "World lifecycle read changed the complete immutable launch binding");
    }
  }

  private static boolean isNil(UUID value) {
    return new UUID(0L, 0L).equals(value);
  }

  private static void requireNondecreasingRowVersion(long previous, long current) {
    if (current < previous) {
      throw new IllegalArgumentException("World current row version moved backwards");
    }
  }
}
