package net.firedevops.firemud.common.publication;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Canonical publication-read binding shared by Game Design and its digest participants.
 *
 * <p>This is the {@code publicationDigestRequest/v1} contract, which is intentionally separate from
 * the ADR 0047 operator-mutation digest. The binding contains request identity and scope only;
 * publication content and notes are not inputs.
 */
public final class PublicationDigestRequestBinding {
  public static final String SCHEMA_VERSION = "publicationDigestRequest/v1";
  public static final int MAX_TEXT_UTF8_BYTES = 256;
  public static final int MAX_DECIMAL_ID_DIGITS = 19;
  public static final int MAX_PREIMAGE_BYTES = 4096;

  private static final String FULL_SCOPE_KIND = "FULL_VERSION";
  private static final String PATCH_SCOPE_KIND = "SCRIPT_PATCH";
  private static final String WORKFLOW_PREFIX = "publish:";
  private static final String PATCH_WORKFLOW_PREFIX = "publish-script-patch:";
  private static final String REQUEST_WORKFLOW_SEPARATOR = ":publish-request:";
  private static final String[] FIELD_NAMES = {
    "tenantId",
    "scopeKind",
    "versionId",
    "baseVersionId",
    "scriptPatchVersion",
    "publishRequestId",
    "derivedWorkflowIdentity"
  };

  /** Scope branch encoded in the canonical request. */
  public enum ScopeKind {
    FULL_VERSION,
    SCRIPT_PATCH
  }

  private final String tenantId;
  private final ScopeKind scopeKind;
  private final String versionId;
  private final String baseVersionId;
  private final String scriptPatchVersion;
  private final String publishRequestId;
  private final String derivedWorkflowIdentity;
  private final byte[] canonicalPreimage;
  private final String requestDigest;

  private PublicationDigestRequestBinding(
      String tenantId,
      ScopeKind scopeKind,
      String versionId,
      String baseVersionId,
      String scriptPatchVersion,
      String publishRequestId) {
    this.tenantId = requireId(tenantId, "tenantId");
    this.scopeKind = Objects.requireNonNull(scopeKind, "scopeKind must not be null");
    this.publishRequestId = requireId(publishRequestId, "publishRequestId");

    if (scopeKind == ScopeKind.FULL_VERSION) {
      this.versionId = requirePositiveDecimal(versionId, "versionId");
      this.baseVersionId = requireInactive(baseVersionId, "baseVersionId");
      this.scriptPatchVersion = requireInactive(scriptPatchVersion, "scriptPatchVersion");
    } else {
      this.versionId = requireInactive(versionId, "versionId");
      this.baseVersionId = requirePositiveDecimal(baseVersionId, "baseVersionId");
      this.scriptPatchVersion = requireText(scriptPatchVersion, "scriptPatchVersion", false);
    }

    this.derivedWorkflowIdentity = deriveWorkflowIdentity();
    this.canonicalPreimage = buildCanonicalPreimage();
    this.requestDigest = sha256Hex(canonicalPreimage);
  }

  /** Creates a binding for a full-version publication. */
  public static PublicationDigestRequestBinding full(
      String tenantId, String versionId, String publishRequestId) {
    return new PublicationDigestRequestBinding(
        tenantId, ScopeKind.FULL_VERSION, versionId, "", "", publishRequestId);
  }

  /** Creates a binding for a script-only patch publication. */
  public static PublicationDigestRequestBinding patch(
      String tenantId, String baseVersionId, String scriptPatchVersion, String publishRequestId) {
    return new PublicationDigestRequestBinding(
        tenantId, ScopeKind.SCRIPT_PATCH, "", baseVersionId, scriptPatchVersion, publishRequestId);
  }

  /** Validates publication identity before a draft or durable workflow is created. */
  public static void validatePublicationIdentity(String tenantId, String publishRequestId) {
    requireId(tenantId, "tenantId");
    requireId(publishRequestId, "publishRequestId");
  }

  public String tenantId() {
    return tenantId;
  }

  public ScopeKind scopeKind() {
    return scopeKind;
  }

  public String scopeKindValue() {
    return scopeKind == ScopeKind.FULL_VERSION ? FULL_SCOPE_KIND : PATCH_SCOPE_KIND;
  }

  public String versionId() {
    return versionId;
  }

  public String baseVersionId() {
    return baseVersionId;
  }

  public String scriptPatchVersion() {
    return scriptPatchVersion;
  }

  public String publishRequestId() {
    return publishRequestId;
  }

  /** Returns the deterministic workflow identity expected for this request. */
  public String derivedWorkflowIdentity() {
    return derivedWorkflowIdentity;
  }

  /** Returns a defensive copy of the exact canonical preimage bytes. */
  public byte[] canonicalPreimage() {
    return canonicalPreimage.clone();
  }

  /** Returns the exact canonical preimage as lowercase hexadecimal bytes. */
  public String canonicalPreimageHex() {
    return HexFormat.of().formatHex(canonicalPreimage);
  }

  /** Returns the lowercase hexadecimal SHA-256 digest of {@link #canonicalPreimage()}. */
  public String requestDigest() {
    return requestDigest;
  }

  /**
   * Validates both binding values supplied by a receiver or caller.
   *
   * @throws IllegalArgumentException when either value is absent, malformed, or mismatched
   */
  public void validateSupplied(String suppliedWorkflowIdentity, String suppliedRequestDigest) {
    if (!derivedWorkflowIdentity.equals(suppliedWorkflowIdentity)) {
      throw new IllegalArgumentException(
          "derivedWorkflowIdentity does not match expected identity");
    }
    if (suppliedRequestDigest == null
        || suppliedRequestDigest.length() != 64
        || !isLowerHex(suppliedRequestDigest)
        || !requestDigest.equals(suppliedRequestDigest)) {
      throw new IllegalArgumentException("requestDigest does not match canonical digest");
    }
  }

  private String deriveWorkflowIdentity() {
    String prefix = scopeKind == ScopeKind.FULL_VERSION ? WORKFLOW_PREFIX : PATCH_WORKFLOW_PREFIX;
    return prefix + tenantId + REQUEST_WORKFLOW_SEPARATOR + publishRequestId;
  }

  private byte[] buildCanonicalPreimage() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    appendSegment(output, SCHEMA_VERSION);
    appendField(output, FIELD_NAMES[0], tenantId);
    appendField(output, FIELD_NAMES[1], scopeKindValue());
    appendField(output, FIELD_NAMES[2], versionId);
    appendField(output, FIELD_NAMES[3], baseVersionId);
    appendField(output, FIELD_NAMES[4], scriptPatchVersion);
    appendField(output, FIELD_NAMES[5], publishRequestId);
    appendField(output, FIELD_NAMES[6], derivedWorkflowIdentity);
    byte[] preimage = output.toByteArray();
    if (preimage.length > MAX_PREIMAGE_BYTES) {
      throw new IllegalArgumentException("canonical publication request exceeds preimage limit");
    }
    return preimage;
  }

  private static void appendField(ByteArrayOutputStream output, String name, String value) {
    appendSegment(output, name);
    appendSegment(output, value);
  }

  private static void appendSegment(ByteArrayOutputStream output, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(bytes);
  }

  private static String requireId(String value, String fieldName) {
    String normalized = requireText(value, fieldName, false);
    if (normalized.indexOf(':') >= 0) {
      throw new IllegalArgumentException(fieldName + " must not contain ':'");
    }
    return normalized;
  }

  private static String requirePositiveDecimal(String value, String fieldName) {
    String normalized = requireText(value, fieldName, false);
    if (normalized.length() > MAX_DECIMAL_ID_DIGITS
        || normalized.charAt(0) < '1'
        || normalized.charAt(0) > '9') {
      throw new IllegalArgumentException(fieldName + " must be a canonical positive decimal");
    }
    for (int index = 1; index < normalized.length(); index++) {
      char character = normalized.charAt(index);
      if (character < '0' || character > '9') {
        throw new IllegalArgumentException(fieldName + " must be a canonical positive decimal");
      }
    }
    if (normalized.length() == MAX_DECIMAL_ID_DIGITS
        && normalized.compareTo("9223372036854775807") > 0) {
      throw new IllegalArgumentException(fieldName + " exceeds signed positive long range");
    }
    return normalized;
  }

  private static String requireInactive(String value, String fieldName) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    throw new IllegalArgumentException(fieldName + " must be empty for this scope");
  }

  private static String requireText(String value, String fieldName, boolean allowEmpty) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " must not be null");
    }
    validateUnicodeScalars(value, fieldName);
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
    if (!normalized.equals(value)) {
      throw new IllegalArgumentException(fieldName + " must already be Unicode NFC");
    }
    if (!allowEmpty && normalized.isEmpty()) {
      throw new IllegalArgumentException(fieldName + " must not be empty");
    }
    if (!normalized.isEmpty()
        && (isBoundaryWhitespace(normalized.codePointAt(0))
            || isBoundaryWhitespace(normalized.codePointBefore(normalized.length())))) {
      throw new IllegalArgumentException(fieldName + " must not have surrounding whitespace");
    }
    int byteLength = normalized.getBytes(StandardCharsets.UTF_8).length;
    if (byteLength > MAX_TEXT_UTF8_BYTES) {
      throw new IllegalArgumentException(fieldName + " exceeds UTF-8 byte limit");
    }
    return normalized;
  }

  private static void validateUnicodeScalars(String value, String fieldName) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new IllegalArgumentException(fieldName + " contains an unpaired UTF-16 surrogate");
        }
        index++;
      } else if (Character.isLowSurrogate(character)) {
        throw new IllegalArgumentException(fieldName + " contains an unpaired UTF-16 surrogate");
      }
    }
  }

  private static boolean isBoundaryWhitespace(int codePoint) {
    return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
  }

  private static boolean isLowerHex(String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
        return false;
      }
    }
    return true;
  }

  private static String sha256Hex(byte[] preimage) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(preimage));
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA-256 is required by the Java platform", exception);
    }
  }
}
