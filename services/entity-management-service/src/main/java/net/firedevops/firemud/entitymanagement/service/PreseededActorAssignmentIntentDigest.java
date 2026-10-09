package net.firedevops.firemud.entitymanagement.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Entity-side canonical digest for the immutable assignment intent. */
final class PreseededActorAssignmentIntentDigest {
  private static final String DOMAIN = "firemud.entity.preseeded-actor-assignment.v4";

  private PreseededActorAssignmentIntentDigest() {}

  static String compute(
      PreseededActorAssignmentRequest request, PreseededActorAssignmentOwnerEvidence evidence) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        write(output, DOMAIN);
        write(output, request.assignmentUuid().toString());
        write(output, request.canonicalAccountUuid().toString());
        write(output, request.corePayload().actorKind().name());
        write(output, request.corePayload().displayName());
        write(output, evidence.canonicalTenantUuid().toString());
        write(output, evidence.realmUuid().toString());
        write(output, evidence.worldSlug());
        write(output, evidence.realmSlug());
        write(output, evidence.gameInstanceId());
        write(output, Long.toString(evidence.catalogRevision()));
        write(output, evidence.canonicalVersionUuid().toString());
        write(output, evidence.frozenPolicyDigest());
        write(output, evidence.playableStateNamespaceId().toString());
        write(output, evidence.publishedReleaseBundleRef());
        write(output, evidence.playableStateScope().name());
        write(output, evidence.entryPolicy().name());
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode assignment intent", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }
}
