package net.firedevops.firemud.common.gamedesign;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;

/** Closed, source-qualified current Game Design version-state evidence for World Management. */
public record AuthoredWorldVersionStateEvidence(
    Request request,
    AuthoredWorldSourceEvidence sourceEvidence,
    VersionLifecycleState versionState,
    long versionStateEpoch,
    String evidenceDigest) {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final String EVIDENCE_DOMAIN = "game-design-authored-world-version-state/v1";

  /** The exact selector and caller-owned read identity for one current version-state read. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID readRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      UUID sourceOperationId,
      String expectedSourceEvidenceDigest,
      long versionId) {
    public Request {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported authored-world version-state schema");
      }
      Objects.requireNonNull(readRequestId, "readRequestId");
      Objects.requireNonNull(sourceOperationId, "sourceOperationId");
      if (NIL_UUID.equals(readRequestId) || NIL_UUID.equals(sourceOperationId)) {
        throw new IllegalArgumentException("Read and source operation IDs must be non-nil UUIDs");
      }
      if (readRequestId.equals(sourceOperationId)) {
        throw new IllegalArgumentException("readRequestId must be separate from sourceOperationId");
      }
      AuthoredWorldSourceDigest.validateReadSelector(targetNamespace, canonicalTenantId, worldSlug);
      if (expectedSourceEvidenceDigest == null
          || !SHA256.matcher(expectedSourceEvidenceDigest).matches()) {
        throw new IllegalArgumentException(
            "Expected source evidence digest must be canonical SHA-256 text");
      }
      if (versionId <= 0) {
        throw new IllegalArgumentException("versionId must be positive");
      }
    }
  }

  public AuthoredWorldVersionStateEvidence {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(sourceEvidence, "sourceEvidence");
    requireLifecycleState(versionState);
    if (versionStateEpoch <= 0) {
      throw new IllegalArgumentException("versionStateEpoch must be positive");
    }
    if (!request.targetNamespace().equals(sourceEvidence.targetNamespace())
        || !request.canonicalTenantId().equals(sourceEvidence.canonicalTenantId())
        || !request.worldSlug().equals(sourceEvidence.worldSlug())
        || !request.sourceOperationId().equals(sourceEvidence.operationId())
        || !request.expectedSourceEvidenceDigest().equals(sourceEvidence.evidenceDigest())) {
      throw new IllegalArgumentException(
          "Complete authored-world source receipt does not match the exact version-state request");
    }
    if (request.readRequestId().equals(sourceEvidence.registrationRequestId())) {
      throw new IllegalArgumentException(
          "readRequestId must be separate from the original source registration request");
    }
    if (evidenceDigest == null || !SHA256.matcher(evidenceDigest).matches()) {
      throw new IllegalArgumentException("evidenceDigest must be canonical SHA-256 text");
    }
    if (!evidenceDigest.equals(evidenceDigest(request, versionState, versionStateEpoch))) {
      throw new IllegalArgumentException(
          "Version-state evidence digest does not match the exact current-state tuple");
    }
  }

  public static AuthoredWorldVersionStateEvidence create(
      Request request,
      AuthoredWorldSourceEvidence sourceEvidence,
      VersionLifecycleState versionState,
      long versionStateEpoch) {
    Objects.requireNonNull(request, "request");
    requireLifecycleState(versionState);
    return new AuthoredWorldVersionStateEvidence(
        request,
        sourceEvidence,
        versionState,
        versionStateEpoch,
        evidenceDigest(request, versionState, versionStateEpoch));
  }

  /** Recomputes the complete outer binding after construction or wire decoding. */
  public void requireValid() {
    if (!evidenceDigest.equals(evidenceDigest(request, versionState, versionStateEpoch))) {
      throw new IllegalArgumentException("Version-state evidence digest is invalid");
    }
  }

  public static String evidenceDigest(
      Request request, VersionLifecycleState versionState, long versionStateEpoch) {
    Objects.requireNonNull(request, "request");
    requireLifecycleState(versionState);
    if (versionStateEpoch <= 0) {
      throw new IllegalArgumentException("versionStateEpoch must be positive");
    }
    return sha256(evidencePreimage(request, versionState, versionStateEpoch));
  }

  /** Fixed-order UTF-8 length-framed preimage owned by the Game Design API contract. */
  public static byte[] evidencePreimage(
      Request request, VersionLifecycleState versionState, long versionStateEpoch) {
    Objects.requireNonNull(request, "request");
    requireLifecycleState(versionState);
    if (versionStateEpoch <= 0) {
      throw new IllegalArgumentException("versionStateEpoch must be positive");
    }
    return frame(
        EVIDENCE_DOMAIN,
        Integer.toString(request.schemaVersion()),
        request.targetNamespace(),
        request.readRequestId().toString(),
        request.canonicalTenantId().toString(),
        request.worldSlug(),
        request.sourceOperationId().toString(),
        request.expectedSourceEvidenceDigest(),
        Long.toString(request.versionId()),
        lifecycleStateName(versionState),
        Long.toString(versionStateEpoch));
  }

  private static String lifecycleStateName(VersionLifecycleState value) {
    return value.name().substring("VERSION_LIFECYCLE_STATE_".length());
  }

  private static byte[] frame(String... segments) {
    ByteArrayOutputStream preimage = new ByteArrayOutputStream();
    for (String segment : segments) {
      byte[] bytes = strictUtf8(segment);
      preimage.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
      preimage.write(':');
      preimage.writeBytes(bytes);
    }
    return preimage.toByteArray();
  }

  private static byte[] strictUtf8(String value) {
    Objects.requireNonNull(value, "digest segment");
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
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("Digest segments must contain valid Unicode", exception);
    }
  }

  private static String sha256(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void requireLifecycleState(VersionLifecycleState value) {
    if (value == null
        || value == VersionLifecycleState.VERSION_LIFECYCLE_STATE_UNSPECIFIED
        || value == VersionLifecycleState.UNRECOGNIZED) {
      throw new IllegalArgumentException("A recognized version lifecycle state is required");
    }
  }
}
