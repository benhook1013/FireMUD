package net.firedevops.firemud.common.gamelogic;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;

/**
 * Exact bounded binding between a full-version publication read and its GL intake authorization.
 *
 * <p>This value preserves bytes and checks their selected tenant and Game Design Version row
 * correlation. Constructing it does not authenticate Account or Game Design, establish that Account
 * issued the order, prove Game Logic retained a manifest, or authorize publication or runtime
 * activation.
 */
public final class GameLogicPublicationSourceReadBinding {
  public static final String SCHEMA = "game-logic-publication-source-read/v1";
  public static final int MAX_PUBLICATION_REQUEST_BYTES = 4096;
  public static final int MAX_AUTHORIZATION_BYTES = 4 * 1024 * 1024;
  public static final int MAX_TOTAL_BYTES =
      maxTotalBytes(SCHEMA, MAX_PUBLICATION_REQUEST_BYTES, MAX_AUTHORIZATION_BYTES);

  private static final byte[] SCHEMA_BYTES = SCHEMA.getBytes(StandardCharsets.US_ASCII);

  private final PublicationDigestRequestBinding publicationRequest;
  private final GameLogicIntakeAuthorizationBinding authorization;
  private final byte[] canonicalBytes;
  private final String digest;

  /** Creates the exact source-read binding from its canonical component bindings. */
  public GameLogicPublicationSourceReadBinding(
      PublicationDigestRequestBinding publicationRequest,
      GameLogicIntakeAuthorizationBinding authorization) {
    this.publicationRequest = Objects.requireNonNull(publicationRequest, "publicationRequest");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    requireMatchingFullVersion(publicationRequest, authorization);
    byte[] publicationBytes = publicationRequest.canonicalPreimage();
    byte[] authorizationBytes = authorization.canonicalBytes();
    requireComponentBounds(publicationBytes.length, authorizationBytes.length);
    this.canonicalBytes = encode(publicationBytes, authorizationBytes);
    this.digest = sha256(canonicalBytes);
  }

  /** Creates the binding from the complete canonical Account intake-authorization bytes. */
  public GameLogicPublicationSourceReadBinding(
      PublicationDigestRequestBinding publicationRequest, byte[] authorizationBytes) {
    this(publicationRequest, decodeAuthorization(authorizationBytes));
  }

  /**
   * Reconstructs a stored binding, enforcing exact frame grammar and complete canonical equality.
   */
  public static GameLogicPublicationSourceReadBinding fromStored(
      PublicationDigestRequestBinding publicationRequest, byte[] stored) {
    Objects.requireNonNull(publicationRequest, "publicationRequest");
    if (stored == null || stored.length == 0 || stored.length > MAX_TOTAL_BYTES) {
      throw new IllegalArgumentException("Invalid publication source-read binding size");
    }

    int offset = readAndRequireSchema(stored);
    Frame publicationFrame = readFrame(stored, offset, MAX_PUBLICATION_REQUEST_BYTES);
    offset = publicationFrame.nextOffset();
    byte[] expectedPublication = publicationRequest.canonicalPreimage();
    if (!Arrays.equals(publicationFrame.bytes(), expectedPublication)) {
      throw new IllegalArgumentException("Stored publication request differs from binding");
    }

    Frame authorizationFrame = readFrame(stored, offset, MAX_AUTHORIZATION_BYTES);
    if (authorizationFrame.nextOffset() != stored.length) {
      throw new IllegalArgumentException("Trailing publication source-read binding bytes");
    }
    GameLogicIntakeAuthorizationBinding authorization =
        decodeAuthorization(authorizationFrame.bytes());
    GameLogicPublicationSourceReadBinding result =
        new GameLogicPublicationSourceReadBinding(publicationRequest, authorization);
    if (!Arrays.equals(stored, result.canonicalBytes)) {
      throw new IllegalArgumentException("Noncanonical publication source-read binding");
    }
    return result;
  }

  public PublicationDigestRequestBinding publicationRequest() {
    return publicationRequest;
  }

  public GameLogicIntakeAuthorizationBinding authorization() {
    return authorization;
  }

  /** Returns a defensive copy of the exact three-frame preimage. */
  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  /** Returns the lowercase SHA-256 digest of {@link #canonicalBytes()}. */
  public String digest() {
    return digest;
  }

  private static void requireMatchingFullVersion(
      PublicationDigestRequestBinding publicationRequest,
      GameLogicIntakeAuthorizationBinding authorization) {
    if (publicationRequest.scopeKind() != PublicationDigestRequestBinding.ScopeKind.FULL_VERSION) {
      throw new IllegalArgumentException("Only full-version publication reads are supported");
    }
    UUID publicationTenant;
    try {
      publicationTenant = UUID.fromString(publicationRequest.tenantId());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "Publication tenant must be the canonical GL tenant UUID", malformed);
    }
    if (!publicationTenant.toString().equals(publicationRequest.tenantId())
        || !publicationTenant.equals(authorization.tenantId())) {
      throw new IllegalArgumentException("Publication tenant differs from retained GL source");
    }
    String selectedVersionRowId =
        Long.toString(authorization.source().binding().target().gameDesignVersionRowId());
    if (!selectedVersionRowId.equals(publicationRequest.versionId())) {
      throw new IllegalArgumentException("Publication version differs from selected GL source");
    }
  }

  private static byte[] encode(byte[] publicationRequestBytes, byte[] authorizationBytes) {
    int totalBytes =
        encodedLength(SCHEMA_BYTES.length)
            + encodedLength(publicationRequestBytes.length)
            + encodedLength(authorizationBytes.length);
    if (totalBytes > MAX_TOTAL_BYTES) {
      throw new IllegalArgumentException("Publication source-read binding exceeds total limit");
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream(totalBytes);
    appendFrame(output, SCHEMA_BYTES);
    appendFrame(output, publicationRequestBytes);
    appendFrame(output, authorizationBytes);
    byte[] encoded = output.toByteArray();
    if (encoded.length != totalBytes) {
      throw new IllegalStateException("Publication source-read frame length calculation diverged");
    }
    return encoded;
  }

  private static int readAndRequireSchema(byte[] stored) {
    Frame schema = readFrame(stored, 0, SCHEMA_BYTES.length);
    if (!Arrays.equals(schema.bytes(), SCHEMA_BYTES)) {
      throw new IllegalArgumentException("Unsupported publication source-read binding schema");
    }
    return schema.nextOffset();
  }

  private static Frame readFrame(byte[] stored, int offset, int maximumLength) {
    if (offset < 0 || offset >= stored.length) {
      throw new IllegalArgumentException("Missing publication source-read frame");
    }
    int colon = offset;
    int length = 0;
    while (colon < stored.length && stored[colon] != ':') {
      int digit = stored[colon] - '0';
      if (digit < 0
          || digit > 9
          || (colon == offset
              && digit == 0
              && colon + 1 < stored.length
              && stored[colon + 1] != ':')) {
        throw new IllegalArgumentException("Noncanonical publication source-read frame length");
      }
      if (digit > maximumLength || length > (maximumLength - digit) / 10) {
        throw new IllegalArgumentException("Publication source-read frame exceeds limit");
      }
      length = length * 10 + digit;
      colon++;
    }
    if (colon == offset || colon >= stored.length || stored[colon] != ':') {
      throw new IllegalArgumentException("Malformed publication source-read frame length");
    }
    int contentStart = colon + 1;
    if (length == 0 || length > stored.length - contentStart) {
      throw new IllegalArgumentException("Truncated publication source-read frame");
    }
    int nextOffset = contentStart + length;
    return new Frame(Arrays.copyOfRange(stored, contentStart, nextOffset), nextOffset);
  }

  private static void requireComponentBounds(int publicationLength, int authorizationLength) {
    if (publicationLength == 0
        || publicationLength > MAX_PUBLICATION_REQUEST_BYTES
        || authorizationLength == 0
        || authorizationLength > MAX_AUTHORIZATION_BYTES) {
      throw new IllegalArgumentException("Publication source-read component exceeds limit");
    }
  }

  private static GameLogicIntakeAuthorizationBinding decodeAuthorization(byte[] stored) {
    if (stored == null || stored.length == 0 || stored.length > MAX_AUTHORIZATION_BYTES) {
      throw new IllegalArgumentException("Invalid Account intake-authorization size");
    }
    try {
      return GameLogicIntakeAuthorizationBinding.fromStored(stored);
    } catch (IllegalArgumentException malformed) {
      throw malformed;
    } catch (RuntimeException corruptStoredBinding) {
      throw new IllegalArgumentException(
          "Invalid stored Account intake authorization", corruptStoredBinding);
    }
  }

  private static int maxTotalBytes(String schema, int publicationLength, int authorizationLength) {
    int schemaLength = schema.getBytes(StandardCharsets.US_ASCII).length;
    return Math.addExact(
        Math.addExact(schemaLength, publicationLength + authorizationLength),
        Math.addExact(
            frameOverhead(schemaLength),
            Math.addExact(frameOverhead(publicationLength), frameOverhead(authorizationLength))));
  }

  private static int encodedLength(int contentLength) {
    return Math.addExact(contentLength, frameOverhead(contentLength));
  }

  private static int frameOverhead(int contentLength) {
    int digits = 1;
    for (int value = contentLength; value >= 10; value /= 10) digits++;
    return digits + 1;
  }

  private static void appendFrame(ByteArrayOutputStream output, byte[] value) {
    output.writeBytes(Integer.toString(value.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(value);
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private record Frame(byte[] bytes, int nextOffset) {}
}
