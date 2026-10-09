package net.firedevops.firemud.common.operator;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict token-preserving JSON codec and digest entry point for StartSession operator actions. */
public final class StartSessionOperatorActionCodec {
  public static final int MAX_RAW_BYTES = 16 * 1_024;
  public static final int MAX_NESTING_DEPTH = 8;
  public static final int MAX_OBJECT_MEMBERS = 16;
  public static final int MAX_ARRAY_ELEMENTS = 64;
  public static final int MAX_NORMALIZED_STRING_BYTES =
      MutationDigestV1.MAX_NORMALIZED_STRING_BYTES;
  public static final int MAX_NUMERIC_BYTES = MutationDigestV1.MAX_NUMERIC_BYTES;
  public static final int MAX_NUMERIC_SCALE = MutationDigestV1.MAX_NUMERIC_SCALE;
  public static final int MAX_SEGMENT_BYTES = MutationDigestV1.MAX_SEGMENT_BYTES;
  public static final int MAX_PREIMAGE_BYTES = MutationDigestV1.MAX_PREIMAGE_BYTES;

  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final List<String> TOP_LEVEL_FIELDS =
      List.of(
          "actionFamilySchemaId",
          "actionFamilySchemaVersion",
          "scope",
          "target",
          "expectedVersion",
          "mutation",
          "auditReason");

  private StartSessionOperatorActionCodec() {}

  /** Decodes only the declared StartSession schema and rejects every unsupported shape. */
  public static StartSessionOperatorAction decode(byte[] rawJson) {
    JsonValue root = new StrictJsonParser(rawJson).parseDocument();
    if (!(root instanceof JsonObject object)) {
      throw invalid("StartSession action must be a JSON object");
    }
    Map<String, JsonValue> members =
        orderedMembers(object, TOP_LEVEL_FIELDS, Set.of("expectedVersion"));
    String schemaId = requiredString(members.get("actionFamilySchemaId"), "actionFamilySchemaId");
    String schemaVersion =
        requiredString(members.get("actionFamilySchemaVersion"), "actionFamilySchemaVersion");
    if (!StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID.equals(schemaId)
        || !StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION.equals(schemaVersion)) {
      throw invalid("unsupported StartSession action-family schema pair");
    }
    if (members.containsKey("expectedVersion")) {
      throw invalid("expectedVersion must be absent for StartSession creation");
    }
    StartSessionOperatorAction.Scope scope = readScope(members.get("scope"));
    StartSessionOperatorAction.Target target = readTarget(members.get("target"));
    StartSessionOperatorAction.Mutation mutation = readMutation(members.get("mutation"));
    String auditReason = requiredString(members.get("auditReason"), "auditReason");
    return new StartSessionOperatorAction(
        schemaId,
        schemaVersion,
        scope,
        target,
        StartSessionOperatorAction.ExpectedVersion.ABSENT,
        mutation,
        auditReason);
  }

  /** Returns the exact ADR 0047 mutationDigest/v1 preimage for one typed action. */
  public static byte[] canonicalPreimage(StartSessionOperatorAction action) {
    return MutationDigestV1.preimage(Objects.requireNonNull(action, "action is required"));
  }

  /** Decodes an action and returns its exact ADR 0047 mutationDigest/v1 preimage. */
  public static byte[] canonicalPreimage(byte[] rawJson) {
    return canonicalPreimage(decode(rawJson));
  }

  /** Returns the lowercase hexadecimal SHA-256 digest for one typed action. */
  public static String mutationDigest(StartSessionOperatorAction action) {
    return MutationDigestV1.sha256Hex(canonicalPreimage(action));
  }

  /** Decodes an action and returns its lowercase hexadecimal SHA-256 digest. */
  public static String mutationDigest(byte[] rawJson) {
    return mutationDigest(decode(rawJson));
  }

  /* Test bridge for the recursive value grammar. It is not an action-schema decoder. */
  static byte[] canonicalGrammarValueForTest(byte[] rawJson) {
    JsonValue parsed = new StrictJsonParser(rawJson).parseDocument();
    return MutationDigestV1.encodedValue(toGrammarValue(parsed));
  }

  static byte[] absentGrammarValueForTest() {
    return MutationDigestV1.encodedValue(MutationDigestV1.absent());
  }

  static byte[] canonicalNumberLexemeForTest(String rawLexeme) {
    return MutationDigestV1.encodedValue(MutationDigestV1.number(rawLexeme));
  }

  private static StartSessionOperatorAction.Scope readScope(JsonValue value) {
    JsonObject object = requireObject(value, "scope");
    Map<String, JsonValue> members =
        orderedMembers(object, List.of("tenantId", "targetNamespace"), Set.of());
    UUID tenantId = canonicalUuid(requiredString(members.get("tenantId"), "tenantId"), "tenantId");
    String namespace = requiredString(members.get("targetNamespace"), "targetNamespace");
    return new StartSessionOperatorAction.Scope(tenantId, namespace);
  }

  private static StartSessionOperatorAction.Target readTarget(JsonValue value) {
    JsonObject object = requireObject(value, "target");
    Map<String, JsonValue> members =
        orderedMembers(object, List.of("gameTemplateId", "ownerAccountId"), Set.of());
    JsonValue templateValue = members.get("gameTemplateId");
    if (!(templateValue instanceof JsonString templateIdValue)) {
      throw invalid("gameTemplateId must be a positive canonical decimal string");
    }
    String templateIdText =
        normalizeString(templateIdValue.value(), MAX_NORMALIZED_STRING_BYTES, "gameTemplateId");
    if (!templateIdText.matches("[1-9][0-9]*")) {
      throw invalid("gameTemplateId must be a positive canonical decimal string");
    }
    long gameTemplateId;
    try {
      gameTemplateId = Long.parseLong(templateIdText);
    } catch (NumberFormatException exception) {
      throw invalid("gameTemplateId exceeds the current owner long range");
    }
    UUID ownerAccountId =
        canonicalUuid(
            requiredString(members.get("ownerAccountId"), "ownerAccountId"), "ownerAccountId");
    return new StartSessionOperatorAction.Target(gameTemplateId, ownerAccountId);
  }

  private static StartSessionOperatorAction.Mutation readMutation(JsonValue value) {
    JsonObject object = requireObject(value, "mutation");
    Map<String, JsonValue> members =
        orderedMembers(object, List.of("clientIp"), Set.of("clientIp"));
    JsonValue clientIp = members.get("clientIp");
    if (clientIp == null) {
      return new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent());
    }
    if (clientIp instanceof JsonNull) {
      throw invalid("clientIp may be absent or a string, but not null");
    }
    if (!(clientIp instanceof JsonString string)) {
      throw invalid("clientIp must be a string");
    }
    String normalized = normalizeString(string.value(), 128, "clientIp");
    return new StartSessionOperatorAction.Mutation(
        StartSessionOperatorAction.ClientIp.of(normalized));
  }

  private static String requiredString(JsonValue value, String fieldName) {
    if (!(value instanceof JsonString string)) {
      throw invalid(fieldName + " must be a string");
    }
    return normalizeString(string.value(), MAX_NORMALIZED_STRING_BYTES, fieldName);
  }

  private static UUID canonicalUuid(String value, String fieldName) {
    if (!UUID_PATTERN.matcher(value).matches()) {
      throw invalid(fieldName + " must be a canonical lowercase UUID");
    }
    UUID uuid;
    try {
      uuid = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw invalid(fieldName + " must be a canonical lowercase UUID");
    }
    if (uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() == 0L) {
      throw invalid(fieldName + " must not be nil");
    }
    return uuid;
  }

  private static JsonObject requireObject(JsonValue value, String fieldName) {
    if (!(value instanceof JsonObject object)) {
      throw invalid(fieldName + " must be an object");
    }
    return object;
  }

  private static Map<String, JsonValue> orderedMembers(
      JsonObject object, List<String> declaredOrder, Set<String> optionalMembers) {
    if (object.members().size() > MAX_OBJECT_MEMBERS) {
      throw invalid("object exceeds its member limit");
    }
    Map<String, JsonValue> normalizedMembers = new LinkedHashMap<>();
    for (JsonMember member : object.members()) {
      String key = normalizeString(member.key().value(), MAX_NORMALIZED_STRING_BYTES, "object key");
      if (normalizedMembers.putIfAbsent(key, member.value()) != null) {
        throw invalid("object contains a duplicate or normalized-colliding member");
      }
      if (!declaredOrder.contains(key)) {
        throw invalid("object contains an unknown member");
      }
    }
    List<String> expectedOrder =
        declaredOrder.stream()
            .filter(key -> normalizedMembers.containsKey(key) || !optionalMembers.contains(key))
            .toList();
    if (!new ArrayList<>(normalizedMembers.keySet()).equals(expectedOrder)) {
      throw invalid("object members are missing or out of declared order");
    }
    return normalizedMembers;
  }

  private static MutationDigestV1.Value toGrammarValue(JsonValue value) {
    if (value instanceof JsonString string) {
      return MutationDigestV1.string(string.value());
    }
    if (value instanceof JsonNumber number) {
      return MutationDigestV1.number(number.lexeme());
    }
    if (value instanceof JsonBoolean bool) {
      return MutationDigestV1.bool(bool.value());
    }
    if (value instanceof JsonNull) {
      return MutationDigestV1.nullValue();
    }
    if (value instanceof JsonArray array) {
      return MutationDigestV1.array(
          array.values().stream().map(StartSessionOperatorActionCodec::toGrammarValue).toList());
    }
    if (value instanceof JsonObject object) {
      List<MutationDigestV1.Member> members = new ArrayList<>(object.members().size());
      for (JsonMember member : object.members()) {
        String key =
            normalizeString(member.key().value(), MAX_NORMALIZED_STRING_BYTES, "object key");
        members.add(MutationDigestV1.member(key, toGrammarValue(member.value())));
      }
      return MutationDigestV1.normalizeObject(members);
    }
    throw invalid("unsupported JSON value");
  }

  private static String normalizeString(String value, int maxUtf8Bytes, String fieldName) {
    requireUnicodeScalars(value, fieldName);
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
    requireUnicodeScalars(normalized, fieldName);
    if (normalized.getBytes(StandardCharsets.UTF_8).length > maxUtf8Bytes) {
      throw invalid(fieldName + " exceeds its normalized UTF-8 byte limit");
    }
    return normalized;
  }

  private static void requireUnicodeScalars(String value, String fieldName) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw invalid(fieldName + " contains malformed Unicode");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw invalid(fieldName + " contains malformed Unicode");
      }
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private sealed interface JsonValue
      permits JsonString, JsonNumber, JsonBoolean, JsonNull, JsonObject, JsonArray {
    byte[] rawToken();
  }

  private record JsonString(String value, byte[] rawToken) implements JsonValue {
    private JsonString {
      rawToken = rawToken.clone();
    }
  }

  private record JsonNumber(String lexeme, byte[] rawToken) implements JsonValue {
    private JsonNumber {
      rawToken = rawToken.clone();
    }
  }

  private record JsonBoolean(boolean value, byte[] rawToken) implements JsonValue {
    private JsonBoolean {
      rawToken = rawToken.clone();
    }
  }

  private record JsonNull(byte[] rawToken) implements JsonValue {
    private JsonNull {
      rawToken = rawToken.clone();
    }
  }

  private record JsonMember(JsonString key, JsonValue value) {}

  private record JsonObject(List<JsonMember> members, byte[] rawToken) implements JsonValue {
    private JsonObject {
      members = List.copyOf(members);
      rawToken = rawToken.clone();
    }
  }

  private record JsonArray(List<JsonValue> values, byte[] rawToken) implements JsonValue {
    private JsonArray {
      values = List.copyOf(values);
      rawToken = rawToken.clone();
    }
  }

  /** Small bounded parser that retains source tokens and object occurrence order. */
  private static final class StrictJsonParser {
    private final byte[] input;
    private final String source;
    private final int[] byteOffsets;
    private int position;

    private StrictJsonParser(byte[] input) {
      Objects.requireNonNull(input, "raw JSON is required");
      if (input.length > MAX_RAW_BYTES) {
        throw invalid("raw JSON exceeds its byte limit");
      }
      this.input = input.clone();
      try {
        CharBuffer decoded =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(this.input));
        this.source = decoded.toString();
      } catch (CharacterCodingException exception) {
        throw invalid("raw JSON is not valid UTF-8");
      }
      this.byteOffsets = computeByteOffsets(this.source, this.input.length);
    }

    private JsonValue parseDocument() {
      skipWhitespace();
      JsonValue value = parseValue(0);
      skipWhitespace();
      if (position != source.length()) {
        throw invalid("raw JSON contains trailing tokens");
      }
      return value;
    }

    private JsonValue parseValue(int parentDepth) {
      if (position >= source.length()) {
        throw invalid("raw JSON ended before a value");
      }
      char next = source.charAt(position);
      if (next == '"') {
        return parseString();
      }
      if (next == '{') {
        if (parentDepth + 1 > MAX_NESTING_DEPTH) {
          throw invalid("raw JSON exceeds its nesting-depth limit");
        }
        return parseObject(parentDepth + 1);
      }
      if (next == '[') {
        if (parentDepth + 1 > MAX_NESTING_DEPTH) {
          throw invalid("raw JSON exceeds its nesting-depth limit");
        }
        return parseArray(parentDepth + 1);
      }
      if (next == 't') {
        return parseLiteral("true", new JsonBoolean(true, new byte[0]));
      }
      if (next == 'f') {
        return parseLiteral("false", new JsonBoolean(false, new byte[0]));
      }
      if (next == 'n') {
        return parseLiteral("null", new JsonNull(new byte[0]));
      }
      return parseNumber();
    }

    private JsonValue parseLiteral(String literal, JsonValue placeholder) {
      int start = position;
      if (!source.startsWith(literal, position)) {
        throw invalid("raw JSON contains an invalid token");
      }
      position += literal.length();
      byte[] raw = rawToken(start, position);
      if (placeholder instanceof JsonBoolean bool) {
        return new JsonBoolean(bool.value(), raw);
      }
      return new JsonNull(raw);
    }

    private JsonNumber parseNumber() {
      int start = position;
      while (position < source.length() && !isValueDelimiter(source.charAt(position))) {
        position++;
      }
      if (start == position) {
        throw invalid("raw JSON contains an invalid value token");
      }
      byte[] raw = rawToken(start, position);
      if (raw.length > MAX_NUMERIC_BYTES) {
        throw invalid("numeric token exceeds its byte limit");
      }
      return new JsonNumber(source.substring(start, position), raw);
    }

    private JsonString parseString() {
      int start = position;
      position++;
      StringBuilder value = new StringBuilder();
      while (position < source.length()) {
        char current = source.charAt(position++);
        if (current == '"') {
          byte[] raw = rawToken(start, position);
          String decoded = value.toString();
          requireUnicodeScalars(decoded, "string");
          return new JsonString(decoded, raw);
        }
        if (current == '\\') {
          if (position >= source.length()) {
            throw invalid("raw JSON ends inside a string escape");
          }
          char escaped = source.charAt(position++);
          switch (escaped) {
            case '"' -> value.append('"');
            case '\\' -> value.append('\\');
            case '/' -> value.append('/');
            case 'b' -> value.append('\b');
            case 'f' -> value.append('\f');
            case 'n' -> value.append('\n');
            case 'r' -> value.append('\r');
            case 't' -> value.append('\t');
            case 'u' -> appendUnicodeEscape(value);
            default -> throw invalid("raw JSON contains an invalid string escape");
          }
        } else {
          if (current < 0x20) {
            throw invalid("raw JSON string contains an unescaped control character");
          }
          value.append(current);
        }
      }
      throw invalid("raw JSON string is unterminated");
    }

    private void appendUnicodeEscape(StringBuilder value) {
      char first = readHexCodeUnit();
      if (Character.isHighSurrogate(first)) {
        if (position + 1 >= source.length()
            || source.charAt(position) != '\\'
            || source.charAt(position + 1) != 'u') {
          throw invalid("raw JSON string contains an unpaired surrogate");
        }
        position += 2;
        char second = readHexCodeUnit();
        if (!Character.isLowSurrogate(second)) {
          throw invalid("raw JSON string contains an unpaired surrogate");
        }
        value.append(first).append(second);
      } else if (Character.isLowSurrogate(first)) {
        throw invalid("raw JSON string contains an unpaired surrogate");
      } else {
        value.append(first);
      }
    }

    private char readHexCodeUnit() {
      if (position + 4 > source.length()) {
        throw invalid("raw JSON string contains a short Unicode escape");
      }
      int value = 0;
      for (int index = 0; index < 4; index++) {
        char digit = source.charAt(position++);
        int hex = Character.digit(digit, 16);
        if (hex < 0 || digit > 0x7f) {
          throw invalid("raw JSON string contains an invalid Unicode escape");
        }
        value = (value << 4) | hex;
      }
      return (char) value;
    }

    private JsonObject parseObject(int depth) {
      int start = position++;
      skipWhitespace();
      List<JsonMember> members = new ArrayList<>();
      if (consume('}')) {
        return new JsonObject(members, rawToken(start, position));
      }
      while (true) {
        if (position >= source.length() || source.charAt(position) != '"') {
          throw invalid("raw JSON object member key must be a string");
        }
        if (members.size() >= MAX_OBJECT_MEMBERS) {
          throw invalid("raw JSON object exceeds its member limit");
        }
        JsonString key = parseString();
        skipWhitespace();
        require(':');
        skipWhitespace();
        JsonValue value = parseValue(depth);
        members.add(new JsonMember(key, value));
        skipWhitespace();
        if (consume('}')) {
          return new JsonObject(members, rawToken(start, position));
        }
        require(',');
        skipWhitespace();
      }
    }

    private JsonArray parseArray(int depth) {
      int start = position++;
      skipWhitespace();
      List<JsonValue> values = new ArrayList<>();
      if (consume(']')) {
        return new JsonArray(values, rawToken(start, position));
      }
      while (true) {
        if (values.size() >= MAX_ARRAY_ELEMENTS) {
          throw invalid("raw JSON array exceeds its element limit");
        }
        values.add(parseValue(depth));
        skipWhitespace();
        if (consume(']')) {
          return new JsonArray(values, rawToken(start, position));
        }
        require(',');
        skipWhitespace();
      }
    }

    private byte[] rawToken(int start, int end) {
      return Arrays.copyOfRange(input, byteOffsets[start], byteOffsets[end]);
    }

    private void skipWhitespace() {
      while (position < source.length()) {
        char value = source.charAt(position);
        if (value != ' ' && value != '\t' && value != '\r' && value != '\n') {
          return;
        }
        position++;
      }
    }

    private boolean consume(char expected) {
      if (position < source.length() && source.charAt(position) == expected) {
        position++;
        return true;
      }
      return false;
    }

    private void require(char expected) {
      if (!consume(expected)) {
        throw invalid("raw JSON punctuation or member order is invalid");
      }
    }

    private static boolean isValueDelimiter(char value) {
      return value == ','
          || value == ']'
          || value == '}'
          || value == ' '
          || value == '\t'
          || value == '\r'
          || value == '\n';
    }

    private static int[] computeByteOffsets(String source, int expectedBytes) {
      int[] offsets = new int[source.length() + 1];
      int characterIndex = 0;
      int byteIndex = 0;
      while (characterIndex < source.length()) {
        int codePoint = source.codePointAt(characterIndex);
        int characterCount = Character.charCount(codePoint);
        int width = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
        offsets[characterIndex] = byteIndex;
        if (characterCount == 2) {
          offsets[characterIndex + 1] = byteIndex;
        }
        characterIndex += characterCount;
        byteIndex += width;
        offsets[characterIndex] = byteIndex;
      }
      if (byteIndex != expectedBytes) {
        throw invalid("raw JSON UTF-8 framing is inconsistent");
      }
      return offsets;
    }
  }
}
