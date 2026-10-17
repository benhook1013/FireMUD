package net.firedevops.firemud.common.gamesession;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;

/**
 * Exact selector and readback evidence for a Game Session-owned launch association.
 *
 * <p>Value construction and codec validation alone do not authenticate the owner; the explicit mTLS
 * client establishes the Game Session peer before accepting response headers or content.
 */
public final class CanonicalGameInstanceLaunchAssociationReadEvidence {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  private CanonicalGameInstanceLaunchAssociationReadEvidence() {}

  /** Fixed fixture owner selector: wire construction grants no fixture authority. */
  public record FixtureRequest(
      UUID readRequestId,
      String targetNamespace,
      UUID fixtureOperationId,
      String expectedFixturePolicyEvidenceDigest) {
    public FixtureRequest {
      requireNonNil(readRequestId, "readRequestId");
      requireNonNil(fixtureOperationId, "fixtureOperationId");
      if (readRequestId.equals(fixtureOperationId)
          || !GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Independent fixture owner read identity required");
      }
      requireDigest(expectedFixturePolicyEvidenceDigest, "expectedFixturePolicyEvidenceDigest");
    }
  }

  /** Exact fixture allocation and immutable owner readback, never a Human release attestation. */
  public record FixtureResult(
      FixtureRequest request,
      long gameSessionTenantId,
      long gameInstanceId,
      UUID canonicalTenantId,
      UUID gameInstanceUuid,
      UUID canonicalVersionId,
      UUID canonicalRealmId,
      UUID playableStateNamespaceId,
      long catalogRevision,
      CurrentGameInstanceStatus currentGameInstanceStatus,
      long currentRowVersion,
      byte[] fixturePolicyRequestBytes,
      RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal fixturePolicySeal) {
    public FixtureResult {
      Objects.requireNonNull(request);
      Objects.requireNonNull(fixturePolicySeal);
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requireNonNil(gameInstanceUuid, "gameInstanceUuid");
      requireNonNil(canonicalVersionId, "canonicalVersionId");
      requireNonNil(canonicalRealmId, "canonicalRealmId");
      requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      Objects.requireNonNull(currentGameInstanceStatus);
      if (gameSessionTenantId <= 0
          || gameInstanceId <= 0
          || catalogRevision <= 0
          || currentRowVersion < 0) {
        throw new IllegalArgumentException(
            "Fixture allocation contains invalid private keys or fences");
      }
      fixturePolicyRequestBytes = Objects.requireNonNull(fixturePolicyRequestBytes).clone();
      if (currentGameInstanceStatus != CurrentGameInstanceStatus.STARTING) {
        throw new IllegalArgumentException(
            "Fixture launch association read requires the current STARTING snapshot");
      }
      var decoded =
          RunOwnedInitialAdmissionFixtureWireCodec.parseFixturePolicyRequestBytes(
              fixturePolicyRequestBytes);
      var cap = decoded.capability();
      if (!request.targetNamespace().equals(decoded.sourceEvidence().targetNamespace())
          || !request.fixtureOperationId().toString().equals(cap.operationId())
          || !request.fixtureOperationId().equals(fixturePolicySeal.operationId())
          || !request
              .expectedFixturePolicyEvidenceDigest()
              .equals(fixturePolicySeal.evidenceDigest())
          || !RunOwnedInitialAdmissionFixtureWireCodec.FIXTURE_POLICY_SEAL_SCHEMA.equals(
              fixturePolicySeal.schema())
          || !RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE.equals(
              fixturePolicySeal.ownerService())
          || !RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestDigest(
                  cap,
                  decoded.creationEvidence(),
                  decoded.sourceEvidence(),
                  decoded.launchReadEvidence())
              .equals(fixturePolicySeal.requestDigest())
          || !RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                  fixturePolicySeal.operationId(), fixturePolicySeal.requestDigest())
              .equals(fixturePolicySeal.evidenceDigest())
          || !canonicalTenantId.equals(decoded.sourceEvidence().canonicalTenantId())
          || !canonicalTenantId
              .toString()
              .equals(decoded.launchReadEvidence().publishedReleaseBundle().getCanonicalTenantId())
          || !canonicalVersionId
              .toString()
              .equals(
                  decoded.launchReadEvidence().publishedReleaseBundle().getCanonicalVersionId())) {
        throw new IllegalArgumentException(
            "Fixture allocation differs from complete immutable owner provenance");
      }
    }

    @Override
    public byte[] fixturePolicyRequestBytes() {
      return fixturePolicyRequestBytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof FixtureResult value)) return false;
      return request.equals(value.request)
          && gameSessionTenantId == value.gameSessionTenantId
          && gameInstanceId == value.gameInstanceId
          && canonicalTenantId.equals(value.canonicalTenantId)
          && gameInstanceUuid.equals(value.gameInstanceUuid)
          && canonicalVersionId.equals(value.canonicalVersionId)
          && canonicalRealmId.equals(value.canonicalRealmId)
          && playableStateNamespaceId.equals(value.playableStateNamespaceId)
          && catalogRevision == value.catalogRevision
          && currentGameInstanceStatus == value.currentGameInstanceStatus
          && currentRowVersion == value.currentRowVersion
          && fixturePolicySeal.equals(value.fixturePolicySeal)
          && Arrays.equals(fixturePolicyRequestBytes, value.fixturePolicyRequestBytes);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          request,
          gameSessionTenantId,
          gameInstanceId,
          canonicalTenantId,
          gameInstanceUuid,
          canonicalVersionId,
          canonicalRealmId,
          playableStateNamespaceId,
          catalogRevision,
          currentGameInstanceStatus,
          currentRowVersion,
          fixturePolicySeal,
          Arrays.hashCode(fixturePolicyRequestBytes));
    }

    public RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicyRequest fixturePolicyRequest() {
      return RunOwnedInitialAdmissionFixtureWireCodec.parseFixturePolicyRequestBytes(
          fixturePolicyRequestBytes);
    }
  }

  /** Separate transient read identity and the complete immutable association selector. */
  public record Request(
      UUID readRequestId,
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID gameInstanceUuid,
      String controlPlaneRequestId,
      String launchDescriptorId,
      String expectedDescriptorRequestDigest,
      String expectedDescriptorResultDigest,
      String expectedReleaseAttestationEvidenceDigest,
      OriginalOperation originalOperation) {
    public Request(
        UUID readRequestId,
        String targetNamespace,
        UUID canonicalTenantId,
        String worldSlug,
        UUID gameInstanceUuid,
        String controlPlaneRequestId,
        String launchDescriptorId,
        String expectedDescriptorRequestDigest,
        String expectedDescriptorResultDigest,
        String expectedReleaseAttestationEvidenceDigest) {
      this(
          readRequestId,
          targetNamespace,
          canonicalTenantId,
          worldSlug,
          gameInstanceUuid,
          controlPlaneRequestId,
          launchDescriptorId,
          expectedDescriptorRequestDigest,
          expectedDescriptorResultDigest,
          expectedReleaseAttestationEvidenceDigest,
          null);
    }

    public Request {
      requireNonNil(readRequestId, "readRequestId");
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requireNonNil(gameInstanceUuid, "gameInstanceUuid");
      AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
      StartSessionPreAuthorizationReservationTuple.requireCanonicalControlPlaneRequestId(
          controlPlaneRequestId);
      requireText(launchDescriptorId, "launchDescriptorId");
      requireDigest(expectedDescriptorRequestDigest, "expectedDescriptorRequestDigest");
      requireDigest(expectedDescriptorResultDigest, "expectedDescriptorResultDigest");
      requireDigest(
          expectedReleaseAttestationEvidenceDigest, "expectedReleaseAttestationEvidenceDigest");
      if (originalOperation != null) {
        var tuple = originalOperation.originalTuple();
        var scope = tuple.preAuthorizationTuple().action().scope();
        if (!targetNamespace.equals(scope.targetNamespace())
            || !canonicalTenantId.equals(scope.tenantId())
            || !controlPlaneRequestId.equals(tuple.controlPlaneRequestId())
            || readRequestId.equals(originalOperation.ownerMutationId())
            || readRequestId.equals(originalOperation.ownerAttemptId())) {
          throw new IllegalArgumentException(
              "Original operation differs from the exact independent launch selector");
        }
      }
    }
  }

  /** Exact Account caller selector; its bytes are integrity input, not owner authority. */
  public record OriginalOperation(
      byte[] originalPostAuthorizationTuple,
      UUID ownerMutationId,
      UUID ownerAttemptId,
      long ownerFence) {
    public OriginalOperation {
      Objects.requireNonNull(originalPostAuthorizationTuple, "originalPostAuthorizationTuple");
      if (originalPostAuthorizationTuple.length == 0
          || originalPostAuthorizationTuple.length
              > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
        throw new IllegalArgumentException("Complete bounded original tuple required");
      }
      originalPostAuthorizationTuple = originalPostAuthorizationTuple.clone();
      var tuple =
          StartSessionPostAuthorizationExecutionTuple.decode(originalPostAuthorizationTuple);
      if (!Arrays.equals(tuple.canonicalBytes(), originalPostAuthorizationTuple)
          || !"human_operator".equals(tuple.issuanceKind())
          || !"StartSession".equals(tuple.preAuthorizationTuple().actionFamily())
          || !"game-session-service".equals(tuple.preAuthorizationTuple().targetOwner())) {
        throw new IllegalArgumentException("Exact canonical original StartSession tuple required");
      }
      requireNonNil(ownerMutationId, "ownerMutationId");
      requireNonNil(ownerAttemptId, "ownerAttemptId");
      if (ownerFence <= 0L) throw new IllegalArgumentException("ownerFence must be positive");
    }

    public StartSessionPostAuthorizationExecutionTuple originalTuple() {
      return StartSessionPostAuthorizationExecutionTuple.decode(originalPostAuthorizationTuple);
    }

    @Override
    public byte[] originalPostAuthorizationTuple() {
      return originalPostAuthorizationTuple.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof OriginalOperation that
          && ownerMutationId.equals(that.ownerMutationId)
          && ownerAttemptId.equals(that.ownerAttemptId)
          && ownerFence == that.ownerFence
          && Arrays.equals(originalPostAuthorizationTuple, that.originalPostAuthorizationTuple);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          ownerMutationId,
          ownerAttemptId,
          ownerFence,
          Arrays.hashCode(originalPostAuthorizationTuple));
    }

    @Override
    public String toString() {
      return "OriginalOperation[ownerMutationId="
          + ownerMutationId
          + ", ownerAttemptId="
          + ownerAttemptId
          + ", ownerFence="
          + ownerFence
          + ", tuple=<redacted>]";
    }
  }

  /** Current Game Session row states; these do not represent World lifecycle or admission. */
  public enum CurrentGameInstanceStatus {
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED
  }

  /**
   * The exact World composition result, including the original owner attempt identity and the
   * independently closed descriptor/release pair. The identity is read evidence, not a reusable
   * owner claim or lifecycle authority.
   */
  public record Result(
      Request request,
      UUID playableStateNamespaceId,
      RealmEntryPolicy.StateScope playableStateScope,
      boolean publicProduction,
      CurrentGameInstanceStatus currentGameInstanceStatus,
      long currentRowVersion,
      UUID ownerAttemptId,
      long ownerFence,
      UUID canonicalRealmId,
      long catalogRevision,
      CompleteLaunchBindingEvidence launchBindingEvidence) {
    public Result {
      Objects.requireNonNull(request, "request");
      requireNonNil(playableStateNamespaceId, "playableStateNamespaceId");
      if (playableStateScope != RealmEntryPolicy.StateScope.SHARED) {
        throw new IllegalArgumentException(
            "Launch association read requires SHARED playable state");
      }
      if (!publicProduction) {
        throw new IllegalArgumentException("Launch association read requires public production");
      }
      Objects.requireNonNull(currentGameInstanceStatus, "currentGameInstanceStatus");
      if (currentRowVersion < 0) {
        throw new IllegalArgumentException("currentRowVersion must not be negative");
      }
      requireNonNil(ownerAttemptId, "ownerAttemptId");
      if (ownerFence <= 0) {
        throw new IllegalArgumentException("ownerFence must be positive");
      }
      requireNonNil(canonicalRealmId, "canonicalRealmId");
      if (catalogRevision <= 0) {
        throw new IllegalArgumentException("catalogRevision must be positive");
      }
      Objects.requireNonNull(launchBindingEvidence, "launchBindingEvidence");
      var descriptor = launchBindingEvidence.descriptor();
      descriptor.requireValid();
      launchBindingEvidence.releaseAttestation().requireValid(descriptor);
      var original = request.originalOperation();
      if (original != null
          && (!original.ownerAttemptId().equals(ownerAttemptId)
              || original.ownerFence() != ownerFence
              || currentGameInstanceStatus != CurrentGameInstanceStatus.STARTING
              || original.originalTuple().preAuthorizationTuple().action().target().gameTemplateId()
                  != descriptor.gameTemplateId())) {
        throw new IllegalArgumentException(
            "Original Account operation differs from its current owner or launch target");
      }
      if (!request.targetNamespace().equals(descriptor.targetNamespace())
          || !request.canonicalTenantId().equals(descriptor.canonicalTenantId())
          || !request.worldSlug().equals(descriptor.worldSlug())
          || !request.controlPlaneRequestId().equals(descriptor.controlPlaneRequestId())
          || !request.launchDescriptorId().equals(descriptor.launchDescriptorId())
          || !request.expectedDescriptorRequestDigest().equals(descriptor.requestDigest())
          || !request.expectedDescriptorResultDigest().equals(descriptor.resultDigest())
          || !request
              .expectedReleaseAttestationEvidenceDigest()
              .equals(launchBindingEvidence.releaseAttestation().evidenceDigest())) {
        throw new IllegalArgumentException(
            "Launch association read result differs from its exact selector or complete binding");
      }
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.length() > 256) {
      throw new IllegalArgumentException(name + " must be non-blank and bounded");
    }
  }

  private static void requireDigest(String value, String name) {
    if (value == null || !DIGEST.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must be a canonical SHA-256 digest");
    }
  }
}
