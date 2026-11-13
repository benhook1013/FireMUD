package net.firedevops.firemud.common.account.capacity;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.PlayerContext;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Shape;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.WorldContext;

/** The capacity-admission/1 action-family mapping of ADR 0047 mutationDigest/v1. */
public final class CapacityAdmissionDigest {
  public static final int MAX_PREIMAGE_BYTES = 512 * 1024;
  public static final int MAX_SEGMENT_BYTES = 384 * 1024;
  public static final int MAX_STRING_BYTES = 352 * 1024;

  private CapacityAdmissionDigest() {}

  public static byte[] preimage(Request request) {
    var output = new ByteArrayOutputStream();
    segment(output, ascii("mutationDigest/v1"));
    field(output, "actionFamilySchemaId", string(TenantCapacityAdmission.SCHEMA_ID));
    field(output, "actionFamilySchemaVersion", string(TenantCapacityAdmission.SCHEMA_VERSION));
    field(
        output,
        "scope",
        object(
            "tenantId", string(request.identity().tenantId().toString()),
            "requestId", string(request.identity().requestId().toString()),
            "admissionId", string(request.identity().admissionId().toString())));
    byte[] context;
    if (request.context() instanceof PlayerContext player) {
      context =
          object(
              "accountId",
              string(player.accountId().toString()),
              "tenantId",
              string(player.tenantId().toString()),
              "playableStateNamespaceId",
              string(player.playableStateNamespaceId().toString()),
              "gameInstanceId",
              string(player.gameInstanceId().toString()),
              "characterId",
              string(player.characterId().toString()),
              "sessionId",
              string(player.sessionId().toString()),
              "realmId",
              string(player.realmId().toString()),
              "requestId",
              string(player.requestId().toString()),
              "playableStateScope",
              string(player.playableStateScope()));
    } else {
      var world = (WorldContext) request.context();
      context =
          object(
              "tenantId",
              string(world.tenantId().toString()),
              "operationId",
              string(world.operationId().toString()),
              "lifecycleId",
              string(world.lifecycleId().toString()),
              "gameInstanceId",
              string(world.gameInstanceId().toString()),
              "ownerId",
              string(world.ownerId().toString()),
              "ownerFence",
              number(world.ownerFence()));
    }
    field(
        output,
        "target",
        object(
            "ownerService",
            string(request.context().owner().service()),
            "targetNamespace",
            string(request.targetNamespace()),
            "ownerContext",
            context));
    field(
        output,
        "expectedVersion",
        object(
            "admissionAttemptId", string(request.admissionAttemptId().toString()),
            "capacityRearmFence", number(request.capacityRearmFence())));
    field(
        output,
        "mutation",
        object(
            "operation",
            string(request.operation().name()),
            "priorShape",
            shape(request.priorShape()),
            "requestedShape",
            shape(request.requestedShape()),
            "accountEvidence",
            string(Base64.getEncoder().encodeToString(request.evidence().canonicalBytes())),
            "capacityDelta",
            request.capacityDelta() == null
                ? value("absent", false, new byte[0])
                : number(request.capacityDelta())));
    field(output, "auditReason", string("capacity-admission"));
    if (output.size() > MAX_PREIMAGE_BYTES) throw invalid();
    return output.toByteArray();
  }

  public static String digest(Request request) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(preimage(request)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  /**
   * Decodes the one canonical capacity-admission/1 preimage accepted for a new committable
   * admission. The digest and Account evidence remain content bindings, not proof of authority.
   */
  public static Request decodeCommittable(byte[] canonicalPreimage, String mutationDigest) {
    try {
      if (canonicalPreimage == null
          || canonicalPreimage.length == 0
          || canonicalPreimage.length > MAX_PREIMAGE_BYTES
          || mutationDigest == null
          || mutationDigest.length() != 64
          || !mutationDigest.matches("[0-9a-f]{64}")) throw invalid();

      byte[] preimageBytes = canonicalPreimage.clone();
      var parser = new Frame(preimageBytes);
      if (!"mutationDigest/v1".equals(parser.segment())) throw invalid();
      Map<String, Object> fields = parser.fields(7, 0);
      if (!parser.done()
          || !TenantCapacityAdmission.SCHEMA_ID.equals(text(fields, "actionFamilySchemaId"))
          || !TenantCapacityAdmission.SCHEMA_VERSION.equals(
              text(fields, "actionFamilySchemaVersion"))) throw invalid();

      var scope = object(fields, "scope");
      var target = object(fields, "target");
      var expected = object(fields, "expectedVersion");
      var mutation = object(fields, "mutation");
      var identity =
          new TenantCapacityAdmission.Identity(
              uuid(scope, "tenantId"), uuid(scope, "requestId"), uuid(scope, "admissionId"));

      String ownerService = text(target, "ownerService");
      var contextFields = object(target, "ownerContext");
      TenantCapacityAdmission.OwnerContext context;
      TenantCapacityAdmission.Owner owner;
      if (TenantCapacityAdmission.Owner.GAME_SESSION.service().equals(ownerService)) {
        owner = TenantCapacityAdmission.Owner.GAME_SESSION;
        context =
            new PlayerContext(
                uuid(contextFields, "accountId"),
                uuid(contextFields, "tenantId"),
                uuid(contextFields, "playableStateNamespaceId"),
                uuid(contextFields, "gameInstanceId"),
                uuid(contextFields, "characterId"),
                uuid(contextFields, "sessionId"),
                uuid(contextFields, "realmId"),
                uuid(contextFields, "requestId"),
                text(contextFields, "playableStateScope"));
      } else if (TenantCapacityAdmission.Owner.WORLD_MANAGEMENT.service().equals(ownerService)) {
        owner = TenantCapacityAdmission.Owner.WORLD_MANAGEMENT;
        context =
            new WorldContext(
                uuid(contextFields, "tenantId"),
                uuid(contextFields, "operationId"),
                uuid(contextFields, "lifecycleId"),
                uuid(contextFields, "gameInstanceId"),
                uuid(contextFields, "ownerId"),
                number(contextFields, "ownerFence"));
      } else {
        throw invalid();
      }

      Object rawCapacityDelta = mutation.get("capacityDelta");
      Long capacityDelta;
      if (rawCapacityDelta == null) capacityDelta = null;
      else if (rawCapacityDelta instanceof Long value) capacityDelta = value;
      else throw invalid();
      Request request =
          new Request(
              identity,
              uuid(expected, "admissionAttemptId"),
              number(expected, "capacityRearmFence"),
              text(target, "targetNamespace"),
              context,
              TenantCapacityAdmission.Operation.valueOf(text(mutation, "operation")),
              shape(object(mutation, "priorShape")),
              shape(object(mutation, "requestedShape")),
              TenantCapacityAdmission.Evidence.parse(
                  Base64.getDecoder().decode(text(mutation, "accountEvidence")), owner),
              capacityDelta,
              mutationDigest);
      if (!Arrays.equals(preimageBytes, preimage(request))) throw invalid();
      request.requireCommittable();
      return request;
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  private static Map<String, Object> object(Map<String, Object> fields, String key) {
    if (!(fields.get(key) instanceof Map<?, ?> value)) throw invalid();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) value;
    return result;
  }

  private static String text(Map<String, Object> fields, String key) {
    if (!(fields.get(key) instanceof String value)) throw invalid();
    return value;
  }

  private static long number(Map<String, Object> fields, String key) {
    if (!(fields.get(key) instanceof Long value)) throw invalid();
    return value;
  }

  private static java.util.UUID uuid(Map<String, Object> fields, String key) {
    return java.util.UUID.fromString(text(fields, key));
  }

  private static Shape shape(Map<String, Object> fields) {
    return new Shape(
        number(fields, "activeSessions"),
        number(fields, "concurrentGameInstances"),
        number(fields, "storageBytes"));
  }

  private static byte[] shape(Shape shape) {
    return object(
        "activeSessions",
        number(shape.activeSessions()),
        "concurrentGameInstances",
        number(shape.concurrentGameInstances()),
        "storageBytes",
        number(shape.storageBytes()));
  }

  private static byte[] number(long value) {
    if (value < 0) throw invalid();
    return value("number", true, ascii(Long.toString(value)));
  }

  private static byte[] string(String value) {
    if (value == null || value.length() > MAX_STRING_BYTES) throw invalid();
    for (int index = 0; index < value.length(); index++) {
      char c = value.charAt(index);
      if (Character.isHighSurrogate(c)) {
        if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index)))
          throw invalid();
      } else if (Character.isLowSurrogate(c)) throw invalid();
    }
    byte[] bytes =
        Normalizer.normalize(value, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_STRING_BYTES) throw invalid();
    return value("string", true, bytes);
  }

  private static byte[] object(Object... fields) {
    if (fields.length % 2 != 0 || fields.length > 32) throw invalid();
    var output = new ByteArrayOutputStream();
    segment(output, ascii(Integer.toString(fields.length / 2)));
    for (int index = 0; index < fields.length; index += 2) {
      field(output, (String) fields[index], (byte[]) fields[index + 1]);
    }
    return value("object", true, output.toByteArray());
  }

  private static byte[] value(String type, boolean present, byte[] payload) {
    var output = new ByteArrayOutputStream();
    segment(output, ascii(type));
    output.write(present ? '1' : '0');
    segment(output, payload);
    return output.toByteArray();
  }

  private static void field(ByteArrayOutputStream output, String name, byte[] value) {
    segment(output, ascii(name));
    output.writeBytes(value);
    if (output.size() > MAX_PREIMAGE_BYTES) throw invalid();
  }

  private static void segment(ByteArrayOutputStream output, byte[] bytes) {
    if (bytes.length > MAX_SEGMENT_BYTES) throw invalid();
    output.writeBytes(ascii(Integer.toString(bytes.length)));
    output.write(':');
    output.writeBytes(bytes);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid capacity-admission/1 canonical value");
  }

  private static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw invalid();
    }
  }

  /** Bounded parser for the shared ADR 0047 frame grammar. */
  private static final class Frame {
    private final byte[] data;
    private int offset;

    private Frame(byte[] data) {
      if (data == null || data.length > MAX_PREIMAGE_BYTES) throw invalid();
      this.data = data;
    }

    private String segment() {
      return decodeUtf8(segmentBytes());
    }

    private byte[] segmentBytes() {
      int start = offset;
      while (offset < data.length && data[offset] >= '0' && data[offset] <= '9') offset++;
      int digits = offset - start;
      if (digits == 0
          || digits > 7
          || offset >= data.length
          || data[offset++] != ':'
          || (digits > 1 && data[start] == '0')) throw invalid();
      int size;
      try {
        size = Integer.parseInt(new String(data, start, digits, StandardCharsets.US_ASCII));
      } catch (NumberFormatException malformed) {
        throw invalid();
      }
      if (size < 0 || size > MAX_SEGMENT_BYTES || size > data.length - offset) throw invalid();
      byte[] result = Arrays.copyOfRange(data, offset, offset + size);
      offset += size;
      return result;
    }

    private Map<String, Object> fields(int count, int depth) {
      if (count < 0 || count > 16 || depth > 8) throw invalid();
      Map<String, Object> result = new LinkedHashMap<>();
      for (int index = 0; index < count; index++) {
        String name = segment();
        String type = segment();
        if (offset >= data.length) throw invalid();
        byte presence = data[offset++];
        byte[] payload = segmentBytes();
        Object value;
        if ("object".equals(type) && presence == '1') {
          Frame nested = new Frame(payload);
          Map<String, Object> object = nested.fields(parseCount(nested.segment()), depth + 1);
          if (!nested.done()) throw invalid();
          value = object;
        } else if ("string".equals(type) && presence == '1') {
          value = decodeUtf8(payload);
        } else if ("number".equals(type) && presence == '1') {
          String number = decodeUtf8(payload);
          if (!number.matches("0|[1-9][0-9]{0,18}")) throw invalid();
          try {
            value = Long.parseLong(number);
          } catch (NumberFormatException overflow) {
            throw invalid();
          }
        } else if ("absent".equals(type) && presence == '0' && payload.length == 0) {
          value = null;
        } else {
          throw invalid();
        }
        if (result.containsKey(name)) throw invalid();
        result.put(name, value);
      }
      return result;
    }

    private static int parseCount(String value) {
      if (value == null || !value.matches("0|[1-9][0-9]{0,1}")) throw invalid();
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException malformed) {
        throw invalid();
      }
    }

    private boolean done() {
      return offset == data.length;
    }
  }
}
