package net.firedevops.firemud.entitymanagement.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;

/** Entity-side canonical digest for the immutable assignment intent. */
final class PreseededActorAssignmentIntentDigest {
  private static final String DOMAIN = "firemud.entity.preseeded-actor-assignment.v4";

  private PreseededActorAssignmentIntentDigest() {}

  static String computeGrant(PreseededActorAssignmentRequest request) {
    var target = request.expectedTarget();
    return encode(
        request,
        target.canonicalTenantUuid(),
        target.realmUuid(),
        target.worldSlug(),
        target.realmSlug(),
        target.gameInstanceId(),
        target.catalogRevision(),
        target.canonicalVersionUuid(),
        target.frozenPolicyDigest(),
        target.playableStateNamespaceId(),
        target.publishedReleaseBundleRef(),
        target.playableStateScope(),
        PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY);
  }

  static String compute(
      PreseededActorAssignmentRequest request, PreseededActorAssignmentOwnerEvidence evidence) {
    return encode(
        request,
        evidence.canonicalTenantUuid(),
        evidence.realmUuid(),
        evidence.worldSlug(),
        evidence.realmSlug(),
        evidence.gameInstanceId(),
        evidence.catalogRevision(),
        evidence.canonicalVersionUuid(),
        evidence.frozenPolicyDigest(),
        evidence.playableStateNamespaceId(),
        evidence.publishedReleaseBundleRef(),
        evidence.playableStateScope(),
        evidence.entryPolicy());
  }

  private static String encode(
      PreseededActorAssignmentRequest request,
      UUID canonicalTenantUuid,
      UUID realmUuid,
      String worldSlug,
      String realmSlug,
      String gameInstanceId,
      long catalogRevision,
      UUID canonicalVersionUuid,
      String frozenPolicyDigest,
      UUID playableStateNamespaceId,
      String publishedReleaseBundleRef,
      PlayableStateScope playableStateScope,
      PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy entryPolicy) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        write(output, DOMAIN);
        write(output, request.assignmentUuid().toString());
        write(output, request.canonicalAccountUuid().toString());
        write(output, request.corePayload().actorKind().name());
        write(output, request.corePayload().displayName());
        write(output, canonicalTenantUuid.toString());
        write(output, realmUuid.toString());
        write(output, worldSlug);
        write(output, realmSlug);
        write(output, gameInstanceId);
        write(output, Long.toString(catalogRevision));
        write(output, canonicalVersionUuid.toString());
        write(output, frozenPolicyDigest);
        write(output, playableStateNamespaceId.toString());
        write(output, publishedReleaseBundleRef);
        write(output, scopeText(playableStateScope));
        write(output, entryPolicy.name());
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode assignment intent", exception);
    }
  }

  private static String scopeText(PlayableStateScope scope) {
    return switch (scope) {
      case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
      case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
      default -> throw new IllegalArgumentException("Unsupported playable-state scope");
    };
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }
}
