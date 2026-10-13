package net.firedevops.firemud.common.operator;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Package-private implementation of the ADR 0047 mutationDigest/v1 byte grammar. */
final class MutationDigestV1 {
  static final int MAX_NORMALIZED_STRING_BYTES = 4_096;
  static final int MAX_NUMERIC_BYTES = 128;
  static final int MAX_NUMERIC_SCALE = 128;
  static final int MAX_SEGMENT_BYTES = 8_192;
  static final int MAX_PREIMAGE_BYTES = 32 * 1_024;

  private static final String DOMAIN = "mutationDigest/v1";
  private static final Pattern NUMBER_SOURCE = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
  private static final Pattern NUMBER_CANONICAL =
      Pattern.compile("0|-?(?:[1-9][0-9]*(?:\\.[0-9]*[1-9])?|0\\.[0-9]*[1-9])");

  private MutationDigestV1() {}

  static byte[] preimage(StartSessionOperatorAction action) {
    Objects.requireNonNull(action, "action is required");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    writeSegment(output, utf8(DOMAIN));
    writeField(output, "actionFamilySchemaId", string(action.actionFamilySchemaId()));
    writeField(output, "actionFamilySchemaVersion", string(action.actionFamilySchemaVersion()));
    writeField(
        output,
        "scope",
        object(
            List.of(
                member("tenantId", string(action.scope().tenantId().toString())),
                member("targetNamespace", string(action.scope().targetNamespace())))));
    writeField(
        output,
        "target",
        object(
            List.of(
                member("gameTemplateId", string(Long.toString(action.target().gameTemplateId()))),
                member("ownerAccountId", string(action.target().ownerAccountId().toString())))));
    writeField(output, "expectedVersion", absent());
    Value clientIp =
        action.mutation().clientIp() instanceof StartSessionOperatorAction.AbsentClientIp
            ? absent()
            : string(
                ((StartSessionOperatorAction.StringClientIp) action.mutation().clientIp()).value());
    writeField(output, "mutation", object(List.of(member("clientIp", clientIp))));
    writeField(output, "auditReason", string(action.auditReason()));
    byte[] preimage = output.toByteArray();
    if (preimage.length > MAX_PREIMAGE_BYTES) {
      throw invalid("canonical preimage exceeds its byte limit");
    }
    return preimage;
  }

  static String sha256Hex(byte[] preimage) {
    Objects.requireNonNull(preimage, "preimage is required");
    if (preimage.length > MAX_PREIMAGE_BYTES) {
      throw invalid("canonical preimage exceeds its byte limit");
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(preimage);
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
        hex.append(Character.forDigit(value & 0x0f, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  static Value absent() {
    return new ScalarValue("absent", false, new byte[0]);
  }

  static Value nullValue() {
    return new ScalarValue("null", true, new byte[0]);
  }

  static Value string(String value) {
    String normalized = normalize(value, MAX_NORMALIZED_STRING_BYTES, "string");
    return new ScalarValue("string", true, utf8(normalized));
  }

  static Value number(String rawLexeme) {
    Objects.requireNonNull(rawLexeme, "number lexeme is required");
    byte[] rawBytes = utf8(rawLexeme);
    if (rawBytes.length > MAX_NUMERIC_BYTES || !NUMBER_SOURCE.matcher(rawLexeme).matches()) {
      throw invalid("number lexeme is invalid or exceeds its byte limit");
    }
    int decimalPoint = rawLexeme.indexOf('.');
    if (decimalPoint >= 0 && rawLexeme.length() - decimalPoint - 1 > MAX_NUMERIC_SCALE) {
      throw invalid("number scale exceeds its limit");
    }
    BigDecimal decimal;
    try {
      decimal = new BigDecimal(rawLexeme);
    } catch (NumberFormatException exception) {
      throw invalid("number lexeme is invalid");
    }
    String canonical = decimal.signum() == 0 ? "0" : decimal.stripTrailingZeros().toPlainString();
    byte[] canonicalBytes = utf8(canonical);
    if (canonicalBytes.length > MAX_NUMERIC_BYTES
        || !NUMBER_CANONICAL.matcher(canonical).matches()) {
      throw invalid("canonical number exceeds its byte limit");
    }
    return new ScalarValue("number", true, canonicalBytes);
  }

  static Value bool(boolean value) {
    return new ScalarValue("boolean", true, utf8(value ? "true" : "false"));
  }

  static Value object(List<Member> members) {
    return new ObjectValue(members);
  }

  static Value array(List<Value> values) {
    return new ArrayValue(values);
  }

  static Member member(String key, Value value) {
    return new Member(key, value);
  }

  static byte[] encodedValue(Value value) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    writeValue(output, value);
    return output.toByteArray();
  }

  static Value normalizeObject(List<Member> rawMembers) {
    Objects.requireNonNull(rawMembers, "object members are required");
    if (rawMembers.size() > 16) {
      throw invalid("object exceeds its member limit");
    }
    List<Member> normalizedMembers = new ArrayList<>(rawMembers.size());
    List<String> seenKeys = new ArrayList<>(rawMembers.size());
    for (Member member : rawMembers) {
      String key = normalize(member.key(), MAX_NORMALIZED_STRING_BYTES, "object key");
      if (seenKeys.contains(key)) {
        throw invalid("object contains a duplicate or normalized-colliding key");
      }
      seenKeys.add(key);
      normalizedMembers.add(new Member(key, member.value()));
    }
    return new ObjectValue(normalizedMembers);
  }

  private static void writeField(ByteArrayOutputStream output, String name, Value value) {
    writeSegment(output, utf8(name));
    writeValue(output, value);
    if (output.size() > MAX_PREIMAGE_BYTES) {
      throw invalid("canonical preimage exceeds its byte limit");
    }
  }

  private static void writeValue(ByteArrayOutputStream output, Value value) {
    Objects.requireNonNull(value, "typed value is required");
    String type;
    boolean present;
    byte[] payload;
    if (value instanceof ScalarValue scalar) {
      type = scalar.type();
      present = scalar.present();
      payload = scalar.payload();
    } else if (value instanceof ObjectValue object) {
      type = "object";
      present = true;
      payload = objectPayload(object.members());
    } else if (value instanceof ArrayValue array) {
      type = "array";
      present = true;
      payload = arrayPayload(array.values());
    } else {
      throw invalid("unsupported typed value");
    }
    writeSegment(output, utf8(type));
    output.write(present ? '1' : '0');
    writeSegment(output, payload);
  }

  private static byte[] objectPayload(List<Member> members) {
    if (members.size() > 16) {
      throw invalid("object exceeds its member limit");
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    writeSegment(output, utf8(Integer.toString(members.size())));
    for (Member member : members) {
      writeSegment(output, utf8(member.key()));
      writeValue(output, member.value());
    }
    return boundedPayload(output);
  }

  private static byte[] arrayPayload(List<Value> values) {
    if (values.size() > 64) {
      throw invalid("array exceeds its element limit");
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    writeSegment(output, utf8(Integer.toString(values.size())));
    for (Value value : values) {
      writeValue(output, value);
    }
    return boundedPayload(output);
  }

  private static byte[] boundedPayload(ByteArrayOutputStream output) {
    if (output.size() > MAX_SEGMENT_BYTES) {
      throw invalid("canonical segment exceeds its byte limit");
    }
    return output.toByteArray();
  }

  private static void writeSegment(ByteArrayOutputStream output, byte[] bytes) {
    if (bytes.length > MAX_SEGMENT_BYTES) {
      throw invalid("canonical segment exceeds its byte limit");
    }
    output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(bytes);
  }

  private static String normalize(String value, int byteLimit, String label) {
    Objects.requireNonNull(value, label + " is required");
    requireUnicodeScalars(value, label);
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
    requireUnicodeScalars(normalized, label);
    if (utf8(normalized).length > byteLimit) {
      throw invalid(label + " exceeds its normalized UTF-8 byte limit");
    }
    return normalized;
  }

  private static void requireUnicodeScalars(String value, String label) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw invalid(label + " contains malformed Unicode");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw invalid(label + " contains malformed Unicode");
      }
    }
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  sealed interface Value permits ScalarValue, ObjectValue, ArrayValue {}

  record ScalarValue(String type, boolean present, byte[] payload) implements Value {
    ScalarValue {
      Objects.requireNonNull(type);
      payload = payload.clone();
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }

  record Member(String key, Value value) {
    Member {
      Objects.requireNonNull(key);
      Objects.requireNonNull(value);
    }
  }

  record ObjectValue(List<Member> members) implements Value {
    ObjectValue {
      members = List.copyOf(members);
    }
  }

  record ArrayValue(List<Value> values) implements Value {
    ArrayValue {
      values = List.copyOf(values);
    }
  }
}
