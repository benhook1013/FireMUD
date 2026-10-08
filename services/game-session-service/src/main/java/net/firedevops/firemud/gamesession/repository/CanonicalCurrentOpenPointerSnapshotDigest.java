package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;

/** Closed canonical bytes for the owner-resolved current OPEN pointer projection. */
final class CanonicalCurrentOpenPointerSnapshotDigest {
  static final String DOMAIN = "gs-canonical-current-open-pointer-snapshot/v1";

  private static final int MAX_STRING_BYTES = 4096;
  private static final Pattern REQUEST_DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern OWNER_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String AUDIT_ACTOR = "game-session-canonical-initial-admission";
  private static final String AUDIT_REASON = "World-held initial admission";

  private CanonicalCurrentOpenPointerSnapshotDigest() {}

  static String digest(Projection projection) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes(projection)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  static byte[] canonicalBytes(Projection projection) {
    Objects.requireNonNull(projection, "projection");
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        writeString(output, DOMAIN);
        output.writeInt(projection.representationVersion());
        writeString(output, projection.targetNamespace());
        writeUuid(output, projection.canonicalTenantId());
        writeString(output, projection.worldSlug());
        writeString(output, projection.worldDisplayName());
        writeUuid(output, projection.realmId());
        writeString(output, projection.realmSlug());
        writeString(output, projection.realmDisplayName());
        writeUuid(output, projection.playableStateNamespaceId());
        writeString(output, projection.playableStateScope());
        writeCounter(output, projection.catalogRevision());
        writeCounter(output, projection.pointerVersion());
        writeString(output, projection.admissionState());
        output.writeBoolean(projection.visible());
        output.writeBoolean(projection.publicProductionRealm());
        writeNullableBoolean(output, projection.requiresCharacterSelection());
        writeString(output, projection.characterCreationPolicy());
        writeUuid(output, projection.canonicalGameInstanceId());
        writeUuid(output, projection.canonicalVersionId());
        writeString(output, projection.initialAdmissionRequestId());
        writeString(output, projection.initialAdmissionRequestDigest());
        writeString(output, projection.originKind().name());
        writeNullableLong(output, projection.expectedPriorPointerVersion());
        writeCounter(output, projection.activeWorldEpoch());
        writeUuid(output, projection.holdId());
        writeUuid(output, projection.holdFence());
        writeString(output, projection.holdBindingDigest());
        writeString(output, projection.lastUpdatedBy());
        writeString(output, projection.lastUpdateReason());
        writeString(output, projection.preparedVersionUpgradeId());
        writeString(output, "COMMITTED");
        writeString(output, projection.ownerProofDigest());
        writeCounter(output, projection.auditEventId());
        writeString(output, projection.terminalAt().toString());
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("Canonical pointer snapshot encoding failed", impossible);
    }
  }

  private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
    writeString(output, Objects.requireNonNull(value, "UUID").toString());
  }

  /** Strings are exact UTF-8 with a signed 32-bit byte count; -1 is the sole null marker. */
  private static void writeString(DataOutputStream output, String value) throws IOException {
    if (value == null) {
      output.writeInt(-1);
      return;
    }
    byte[] encoded = strictUtf8(value);
    if (encoded.length > MAX_STRING_BYTES) {
      throw new IllegalArgumentException("Canonical snapshot string exceeds its byte bound");
    }
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static byte[] strictUtf8(String value) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (CharacterCodingException invalid) {
      throw new IllegalArgumentException("Canonical snapshot string is not valid Unicode", invalid);
    }
  }

  private static void writeNullableBoolean(DataOutputStream output, Boolean value)
      throws IOException {
    output.writeByte(value == null ? 0 : value ? 2 : 1);
  }

  private static void writeNullableLong(DataOutputStream output, Long value) throws IOException {
    output.writeBoolean(value != null);
    if (value != null) {
      writeCounter(output, value);
    }
  }

  /** Long counters use their unique, nonlocalized base-10 representation. */
  private static void writeCounter(DataOutputStream output, long value) throws IOException {
    writeString(output, Long.toString(value));
  }

  /** Values are copied from one validated V3 OPEN row and its exact terminal owner readback. */
  record Projection(
      int representationVersion,
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      String worldDisplayName,
      UUID realmId,
      String realmSlug,
      String realmDisplayName,
      UUID playableStateNamespaceId,
      String playableStateScope,
      long catalogRevision,
      long pointerVersion,
      String admissionState,
      boolean visible,
      boolean publicProductionRealm,
      Boolean requiresCharacterSelection,
      String characterCreationPolicy,
      UUID canonicalGameInstanceId,
      UUID canonicalVersionId,
      String initialAdmissionRequestId,
      String initialAdmissionRequestDigest,
      OriginKind originKind,
      Long expectedPriorPointerVersion,
      long activeWorldEpoch,
      UUID holdId,
      UUID holdFence,
      String holdBindingDigest,
      String lastUpdatedBy,
      String lastUpdateReason,
      String preparedVersionUpgradeId,
      String ownerProofDigest,
      long auditEventId,
      Instant terminalAt) {
    @SuppressFBWarnings(
        value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
        justification =
            "Canonical OPEN projection requires absent selection and upgrade fields; the record stores those validated nulls without dereferencing them.")
    Projection {
      if (originKind == null) {
        throw new IllegalArgumentException("Current OPEN pointer snapshot origin is required");
      }
      OriginKind validatedOriginKind = originKind;
      if (representationVersion != 3
          || !GrpcPeerIdentity.isValidNamespace(targetNamespace)
          || !nonNil(canonicalTenantId)
          || !RealmEntryPolicy.isCanonicalSlug(worldSlug)
          || !text(worldDisplayName, 200)
          || !nonNil(realmId)
          || !RealmEntryPolicy.isCanonicalSlug(realmSlug)
          || !text(realmDisplayName, 200)
          || !nonNil(playableStateNamespaceId)
          || !"SHARED".equals(playableStateScope)
          || catalogRevision <= 0L
          || pointerVersion <= 0L
          || !"OPEN".equals(admissionState)
          || !visible
          || !publicProductionRealm
          || requiresCharacterSelection != null
          || !text(characterCreationPolicy, 32)
          || !nonNil(canonicalGameInstanceId)
          || !nonNil(canonicalVersionId)
          || !text(initialAdmissionRequestId, 120)
          || !REQUEST_DIGEST
              .matcher(Objects.requireNonNull(initialAdmissionRequestDigest))
              .matches()
          || (validatedOriginKind == OriginKind.NO_PRIOR_POINTER
              && expectedPriorPointerVersion != null)
          || (validatedOriginKind == OriginKind.EXPECT_CLOSED
              && (expectedPriorPointerVersion == null || expectedPriorPointerVersion <= 0L))
          || activeWorldEpoch <= 0L
          || !nonNil(holdId)
          || !nonNil(holdFence)
          || !OWNER_DIGEST.matcher(Objects.requireNonNull(holdBindingDigest)).matches()
          || !AUDIT_ACTOR.equals(lastUpdatedBy)
          || !AUDIT_REASON.equals(lastUpdateReason)
          || preparedVersionUpgradeId != null
          || !OWNER_DIGEST.matcher(Objects.requireNonNull(ownerProofDigest)).matches()
          || auditEventId <= 0L
          || terminalAt == null
          || terminalAt.getNano() % 1_000 != 0) {
        throw new IllegalArgumentException(
            "Current OPEN pointer snapshot is incomplete or invalid");
      }
      // Reject malformed UTF-16 rather than allowing replacement-byte collisions in the digest.
      for (String value :
          new String[] {
            targetNamespace,
            worldSlug,
            worldDisplayName,
            realmSlug,
            realmDisplayName,
            playableStateScope,
            admissionState,
            characterCreationPolicy,
            initialAdmissionRequestId,
            initialAdmissionRequestDigest,
            validatedOriginKind.name(),
            holdBindingDigest,
            lastUpdatedBy,
            lastUpdateReason,
            ownerProofDigest
          }) {
        strictUtf8(value);
      }
    }

    private static boolean nonNil(UUID value) {
      return value != null && !NIL_UUID.equals(value);
    }

    private static boolean text(String value, int maxChars) {
      return value != null
          && !value.isBlank()
          && value.equals(value.trim())
          && value.length() <= maxChars;
    }
  }
}
