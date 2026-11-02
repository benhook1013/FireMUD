package net.firedevops.firemud.common.account.authority;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/** Canonical bounded codec for Account-owned lifecycle-bound grant authority scopes. */
public final class GrantAuthorityScopeCodec {
  public static final String SCOPE_ID_PREFIX = "grant/v1/";
  public static final String OUTBOX_STREAM_PREFIX = "account:auth-authority:v1:";
  public static final OwnerBounds V1_BOUNDS = new OwnerBounds(36, 36, 120, 120, 36);

  private static final String MARKER = "grantScope/v1";
  private static final String BASE64URL_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern CANONICAL_SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

  private GrantAuthorityScopeCodec() {}

  /** Encodes the exact five-field owner tuple as the canonical v1 scope identifier. */
  public static String scopeId(Scope scope, OwnerBounds bounds) {
    Maxima maxima = requireBounds(bounds);
    requireScope(scope);
    String[] fields = fields(scope);
    int encodedLength = 0;
    try {
      for (int index = 0; index < fields.length; index++) {
        String field = fields[index];
        int fieldLength = field.length();
        int ownerMaximum = ownerMaximum(bounds, index);
        if (fieldLength == 0 || fieldLength > ownerMaximum) {
          throw invalid("scope field exceeds its fixed owner maximum");
        }
        encodedLength = Math.addExact(encodedLength, segmentLength(fieldLength));
      }
    } catch (ArithmeticException overflow) {
      throw invalid("grant scope byte length overflows", overflow);
    }
    if (encodedLength > maxima.scopeBytes()) {
      throw invalid("grant scope exceeds its derived byte maximum");
    }

    byte[] scopeBytes = new byte[encodedLength];
    int offset = 0;
    for (String field : fields) {
      offset = writeSegment(scopeBytes, offset, field);
    }
    String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(scopeBytes);
    if (payload.length() > maxima.payloadLength()) {
      throw invalid("grant scope exceeds its derived encoded maximum");
    }
    return SCOPE_ID_PREFIX + payload;
  }

  /** The event source scope is exactly the canonical grant scope identifier. */
  public static String sourceScope(Scope scope, OwnerBounds bounds) {
    return scopeId(scope, bounds);
  }

  /** Builds the exact Account authority outbox stream key for a grant scope. */
  public static String outboxStreamKey(Scope scope, OwnerBounds bounds) {
    Maxima maxima = requireBounds(bounds);
    String scopeId = scopeId(scope, bounds);
    String key = OUTBOX_STREAM_PREFIX + scopeId;
    if (key.length() > maxima.outboxStreamKeyLength()) {
      throw invalid("grant outbox stream key exceeds its derived maximum");
    }
    return key;
  }

  /** Parses only the exact canonical unpadded v1 scope identifier. */
  public static Scope parseScopeId(String scopeId, OwnerBounds bounds) {
    Maxima maxima = requireBounds(bounds);
    if (scopeId == null) {
      throw invalid("scopeId is required");
    }
    if (scopeId.length() > maxima.scopeIdLength()) {
      throw invalid("scopeId exceeds its derived maximum");
    }
    if (!scopeId.startsWith(SCOPE_ID_PREFIX)) {
      throw invalid("scopeId must use the exact grant/v1 prefix");
    }
    int payloadStart = SCOPE_ID_PREFIX.length();
    int payloadLength = scopeId.length() - payloadStart;
    if (payloadLength == 0 || payloadLength > maxima.payloadLength()) {
      throw invalid("scopeId payload is empty or exceeds its derived maximum");
    }

    for (int index = payloadStart; index < scopeId.length(); index++) {
      if (BASE64URL_ALPHABET.indexOf(scopeId.charAt(index)) < 0) {
        throw invalid("scopeId payload must be unpadded canonical base64url");
      }
    }
    if ((payloadLength & 3) == 1) {
      throw invalid("scopeId payload has an impossible base64url length");
    }

    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(scopeId.substring(payloadStart));
    } catch (IllegalArgumentException malformed) {
      throw invalid("scopeId payload is not valid base64url", malformed);
    }
    if (decoded.length > maxima.scopeBytes()) {
      throw invalid("decoded grant scope exceeds its derived byte maximum");
    }

    Scope parsed = decodeScopeBytes(decoded, bounds);
    if (!scopeId(parsed, bounds).equals(scopeId)) {
      throw invalid("scopeId is not the exact canonical encoding of its tuple");
    }
    return parsed;
  }

  /** Parses only the exact Account authority stream prefix and canonical grant scope suffix. */
  public static Scope parseOutboxStreamKey(String outboxStreamKey, OwnerBounds bounds) {
    Maxima maxima = requireBounds(bounds);
    if (outboxStreamKey == null) {
      throw invalid("outboxStreamKey is required");
    }
    if (outboxStreamKey.length() > maxima.outboxStreamKeyLength()) {
      throw invalid("outboxStreamKey exceeds its derived maximum");
    }
    if (!outboxStreamKey.startsWith(OUTBOX_STREAM_PREFIX)) {
      throw invalid("outboxStreamKey must use the exact Account authority prefix");
    }
    int scopeStart = OUTBOX_STREAM_PREFIX.length();
    if (outboxStreamKey.length() <= scopeStart) {
      throw invalid("outboxStreamKey is missing its grant scope");
    }

    Scope parsed = parseScopeId(outboxStreamKey.substring(scopeStart), bounds);
    if (!outboxStreamKey(parsed, bounds).equals(outboxStreamKey)) {
      throw invalid("outboxStreamKey is not the exact canonical scope projection");
    }
    return parsed;
  }

  private static Scope decodeScopeBytes(byte[] encoded, OwnerBounds bounds) {
    int offset = 0;
    Segment marker = readSegment(encoded, offset, MARKER.length(), "marker");
    if (!MARKER.equals(marker.value())) {
      throw invalid("scope marker is not grantScope/v1");
    }
    offset = marker.nextOffset();

    Segment accountId = readSegment(encoded, offset, bounds.accountId(), "accountId");
    offset = accountId.nextOffset();
    Segment tenantId = readSegment(encoded, offset, bounds.tenantId(), "tenantId");
    offset = tenantId.nextOffset();
    Segment worldSlug = readSegment(encoded, offset, bounds.worldSlug(), "worldSlug");
    offset = worldSlug.nextOffset();
    Segment realmSlug = readSegment(encoded, offset, bounds.realmSlug(), "realmSlug");
    offset = realmSlug.nextOffset();
    Segment lifecycleId =
        readSegment(encoded, offset, bounds.playtestLifecycleId(), "playtestLifecycleId");
    offset = lifecycleId.nextOffset();

    if (offset != encoded.length) {
      throw invalid("scope contains trailing bytes after the five owner fields");
    }
    return new Scope(
        parseUuid(accountId.value(), "accountId"),
        parseUuid(tenantId.value(), "tenantId"),
        worldSlug.value(),
        realmSlug.value(),
        parseUuid(lifecycleId.value(), "playtestLifecycleId"));
  }

  private static Segment readSegment(byte[] encoded, int offset, int maximum, String field) {
    if (offset < 0 || offset >= encoded.length) {
      throw invalid(field + " segment is missing");
    }
    int cursor = offset;
    int digitCount = 0;
    int firstDigit = -1;
    int declaredLength = 0;
    while (cursor < encoded.length && encoded[cursor] != ':') {
      int value = encoded[cursor] & 0xff;
      if (value < '0' || value > '9') {
        throw invalid(field + " segment length is not canonical unsigned decimal");
      }
      int digit = value - '0';
      if (digitCount == 0) {
        firstDigit = digit;
      }
      try {
        declaredLength = Math.addExact(Math.multiplyExact(declaredLength, 10), digit);
      } catch (ArithmeticException overflow) {
        throw invalid(field + " segment length overflows", overflow);
      }
      digitCount++;
      if (declaredLength > maximum) {
        throw invalid(field + " declared segment length exceeds its owner maximum");
      }
      cursor++;
    }
    if (cursor >= encoded.length || digitCount == 0) {
      throw invalid(field + " segment length delimiter is missing");
    }
    if (digitCount > 1 && firstDigit == 0) {
      throw invalid(field + " segment length has a leading zero");
    }
    if (declaredLength == 0) {
      throw invalid(field + " segment must be nonempty");
    }

    int contentStart = cursor + 1;
    if (declaredLength > encoded.length - contentStart) {
      throw invalid(field + " segment is truncated");
    }
    String value = decodeUtf8(encoded, contentStart, declaredLength, field);
    return new Segment(value, contentStart + declaredLength);
  }

  private static String decodeUtf8(byte[] bytes, int offset, int length, String field) {
    try {
      CharBuffer decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes, offset, length));
      return decoded.toString();
    } catch (CharacterCodingException malformed) {
      throw invalid(field + " segment is not valid UTF-8", malformed);
    }
  }

  private static UUID parseUuid(String value, String field) {
    if (value == null || value.length() != 36 || !CANONICAL_UUID.matcher(value).matches()) {
      throw invalid(field + " must be canonical lowercase 36-byte UUID text");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " must be canonical lowercase 36-byte UUID text", malformed);
    }
    if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
      throw invalid(field + " must be a canonical non-nil UUID");
    }
    return parsed;
  }

  private static String requireSlug(String value, String field) {
    if (value == null
        || value.isEmpty()
        || value.length() > 120
        || !CANONICAL_SLUG.matcher(value).matches()) {
      throw invalid(field + " must be a lowercase ASCII slug of 1-120 bytes");
    }
    return value;
  }

  private static UUID requireUuid(UUID value, String field) {
    if (value == null || NIL_UUID.equals(value)) {
      throw invalid(field + " must be a canonical non-nil UUID");
    }
    return value;
  }

  private static String[] fields(Scope scope) {
    return new String[] {
      MARKER,
      scope.accountId().toString(),
      scope.tenantId().toString(),
      scope.worldSlug(),
      scope.realmSlug(),
      scope.playtestLifecycleId().toString()
    };
  }

  private static int ownerMaximum(OwnerBounds bounds, int fieldIndex) {
    return switch (fieldIndex) {
      case 0 -> MARKER.length();
      case 1 -> bounds.accountId();
      case 2 -> bounds.tenantId();
      case 3 -> bounds.worldSlug();
      case 4 -> bounds.realmSlug();
      case 5 -> bounds.playtestLifecycleId();
      default -> throw invalid("unexpected grant scope field");
    };
  }

  private static int segmentLength(int byteLength) {
    try {
      return Math.addExact(Math.addExact(Integer.toString(byteLength).length(), 1), byteLength);
    } catch (ArithmeticException overflow) {
      throw invalid("grant scope segment length overflows", overflow);
    }
  }

  private static int writeSegment(byte[] target, int offset, String value) {
    int byteLength = value.length();
    String decimalLength = Integer.toString(byteLength);
    for (int index = 0; index < decimalLength.length(); index++) {
      target[offset++] = (byte) decimalLength.charAt(index);
    }
    target[offset++] = ':';
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character > 0x7f) {
        throw invalid("grant scope fields must use exact ASCII owner text");
      }
      target[offset++] = (byte) character;
    }
    return offset;
  }

  private static Maxima requireBounds(OwnerBounds bounds) {
    if (bounds == null) {
      throw invalid("owner bounds are required");
    }
    if (!V1_BOUNDS.equals(bounds)) {
      throw invalid("owner bounds contradict the fixed v1 owner vector");
    }
    return deriveMaxima(bounds);
  }

  private static Maxima deriveMaxima(OwnerBounds bounds) {
    try {
      int scopeBytes = segmentLength(MARKER.length());
      scopeBytes = Math.addExact(scopeBytes, segmentLength(bounds.accountId()));
      scopeBytes = Math.addExact(scopeBytes, segmentLength(bounds.tenantId()));
      scopeBytes = Math.addExact(scopeBytes, segmentLength(bounds.worldSlug()));
      scopeBytes = Math.addExact(scopeBytes, segmentLength(bounds.realmSlug()));
      scopeBytes = Math.addExact(scopeBytes, segmentLength(bounds.playtestLifecycleId()));

      long roundedBytes = Math.addExact((long) scopeBytes, 2L);
      long groups = roundedBytes / 3L;
      long base64Length = Math.multiplyExact(4L, groups);
      long unusedPaddingCharacters = Math.floorMod(3L - (scopeBytes % 3L), 3L);
      int payloadLength =
          Math.toIntExact(Math.subtractExact(base64Length, unusedPaddingCharacters));
      int scopeIdLength = Math.addExact(SCOPE_ID_PREFIX.length(), payloadLength);
      int outboxStreamKeyLength = Math.addExact(OUTBOX_STREAM_PREFIX.length(), scopeIdLength);
      return new Maxima(scopeBytes, payloadLength, scopeIdLength, outboxStreamKeyLength);
    } catch (ArithmeticException overflow) {
      throw invalid("grant scope derived maximum overflows", overflow);
    }
  }

  private static void requireScope(Scope scope) {
    if (scope == null) {
      throw invalid("grant scope tuple is required");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, RuntimeException cause) {
    return new IllegalArgumentException(message, cause);
  }

  private static IllegalArgumentException invalid(String message, CharacterCodingException cause) {
    return new IllegalArgumentException(message, cause);
  }

  /** The exact Account-owned grant identity used by the v1 stream scope. */
  public record Scope(
      UUID accountId, UUID tenantId, String worldSlug, String realmSlug, UUID playtestLifecycleId) {
    public Scope {
      accountId = requireUuid(accountId, "accountId");
      tenantId = requireUuid(tenantId, "tenantId");
      worldSlug = requireSlug(worldSlug, "worldSlug");
      realmSlug = requireSlug(realmSlug, "realmSlug");
      playtestLifecycleId = requireUuid(playtestLifecycleId, "playtestLifecycleId");
    }
  }

  /** Fixed field maxima owned by Account and consumed unchanged by every codec operation. */
  public record OwnerBounds(
      int accountId, int tenantId, int worldSlug, int realmSlug, int playtestLifecycleId) {
    public OwnerBounds {
      if (accountId <= 0
          || tenantId <= 0
          || worldSlug <= 0
          || realmSlug <= 0
          || playtestLifecycleId <= 0) {
        throw invalid("every owner bound must be positive");
      }
      if (accountId != 36
          || tenantId != 36
          || worldSlug != 120
          || realmSlug != 120
          || playtestLifecycleId != 36) {
        throw invalid("owner bounds must equal the fixed v1 vector (36, 36, 120, 120, 36)");
      }
    }
  }

  private record Segment(String value, int nextOffset) {}

  private record Maxima(
      int scopeBytes, int payloadLength, int scopeIdLength, int outboxStreamKeyLength) {}
}
