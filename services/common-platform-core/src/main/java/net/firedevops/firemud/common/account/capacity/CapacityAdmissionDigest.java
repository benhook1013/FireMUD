package net.firedevops.firemud.common.account.capacity;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Base64;
import java.util.HexFormat;
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
}
