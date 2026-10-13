package net.firedevops.firemud.entitymanagement.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Canonical digest of complete owner-resolved target evidence and its ordered persisted roster. */
public final class CanonicalGameplayRosterSnapshotDigest {
  public static final int MAX_ROSTER_SIZE = 100;
  private static final String DOMAIN = "firemud.entity.canonical-gameplay-roster.v3";

  private CanonicalGameplayRosterSnapshotDigest() {}

  public static String compute(
      UUID canonicalAccountUuid,
      CanonicalGameplayRosterTarget target,
      List<CanonicalGameplayRosterActor> actors) {
    if (actors.size() > MAX_ROSTER_SIZE) {
      throw new IllegalArgumentException("Roster exceeds the maximum supported size");
    }
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        write(output, DOMAIN);
        write(output, canonicalAccountUuid.toString());
        write(output, target.tenantUuid().toString());
        write(output, target.realmUuid().toString());
        write(output, target.worldSlug());
        write(output, target.realmSlug());
        write(output, target.gameInstanceUuid().toString());
        write(output, Long.toString(target.catalogRevision()));
        write(output, Long.toString(target.pointerVersion()));
        write(output, Long.toString(target.activeWorldEpoch()));
        write(output, target.canonicalVersionUuid().toString());
        write(output, target.publishedPolicyDigest());
        write(output, target.publishedReleaseBundleRef());
        write(output, target.admissionPointerSnapshotDigest());
        write(output, target.publishedOwnerProofDigest());
        write(output, target.playableStateNamespaceId().toString());
        write(output, target.playableStateScope().name());
        write(output, target.entryPolicy().name());
        write(output, Integer.toString(actors.size()));
        for (CanonicalGameplayRosterActor actor : actors) {
          write(output, actor.characterUuid().toString());
          write(output, actor.displayName());
        }
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode canonical roster snapshot", exception);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }
}
