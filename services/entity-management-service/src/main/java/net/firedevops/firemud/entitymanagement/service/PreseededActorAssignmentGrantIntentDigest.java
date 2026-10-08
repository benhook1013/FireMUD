package net.firedevops.firemud.entitymanagement.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes the assignment digest from the caller target before any owner reads are performed. */
final class PreseededActorAssignmentGrantIntentDigest {
  private static final String DOMAIN = "firemud.entity.preseeded-actor-assignment.v4";

  private PreseededActorAssignmentGrantIntentDigest() {}

  static String compute(PreseededActorAssignmentRequest request) {
    var target = request.expectedTarget();
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        write(output, DOMAIN);
        write(output, request.assignmentUuid().toString());
        write(output, request.canonicalAccountUuid().toString());
        write(output, request.corePayload().actorKind().name());
        write(output, request.corePayload().displayName());
        write(output, target.canonicalTenantUuid().toString());
        write(output, target.realmUuid().toString());
        write(output, target.worldSlug());
        write(output, target.realmSlug());
        write(output, target.gameInstanceId());
        write(output, Long.toString(target.catalogRevision()));
        write(output, target.canonicalVersionUuid().toString());
        write(output, target.frozenPolicyDigest());
        write(output, target.playableStateNamespaceId().toString());
        write(output, target.publishedReleaseBundleRef());
        write(output, target.playableStateScope().name());
        write(
            output,
            PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY.name());
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode assignment grant intent", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }
}
