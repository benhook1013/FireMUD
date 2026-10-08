package net.firedevops.firemud.common.gamesession;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
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
      String expectedReleaseAttestationEvidenceDigest) {
    public Request {
      requireNonNil(readRequestId, "readRequestId");
      requireNonNil(canonicalTenantId, "canonicalTenantId");
      requireNonNil(gameInstanceUuid, "gameInstanceUuid");
      AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
      requireText(controlPlaneRequestId, "controlPlaneRequestId");
      requireText(launchDescriptorId, "launchDescriptorId");
      requireDigest(expectedDescriptorRequestDigest, "expectedDescriptorRequestDigest");
      requireDigest(expectedDescriptorResultDigest, "expectedDescriptorResultDigest");
      requireDigest(
          expectedReleaseAttestationEvidenceDigest, "expectedReleaseAttestationEvidenceDigest");
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
   * The exact World composition result, including the independently closed descriptor/release pair.
   */
  public record Result(
      Request request,
      UUID playableStateNamespaceId,
      RealmEntryPolicy.StateScope playableStateScope,
      boolean publicProduction,
      CurrentGameInstanceStatus currentGameInstanceStatus,
      long currentRowVersion,
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
      Objects.requireNonNull(launchBindingEvidence, "launchBindingEvidence");
      var descriptor = launchBindingEvidence.descriptor();
      descriptor.requireValid();
      launchBindingEvidence.releaseAttestation().requireValid(descriptor);
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
