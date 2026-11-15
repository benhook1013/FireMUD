package net.firedevops.firemud.common.recovery;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.AuditReason;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.AuditReasonCode;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.LaunchAssociation;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.MaintenanceLockPresence;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.Mutation;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.PeerStorageBinding;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.Scope;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.Target;

/**
 * One closed action-family implementation of ADR 0047's exact mutationDigest/v1 byte grammar. All
 * scalar wire values are strings; nulls, numbers, arrays and optional lock inputs are forbidden.
 * The request identity and fixed target owner are context, deliberately outside the digest.
 */
public final class InitialGameplayRecoverCodec {
  public static final int MAX_RAW_BYTES = 16384;
  public static final int MAX_RAW_STRING_CHARACTERS = 4096;
  public static final int MAX_DEPTH = 4;
  public static final int MAX_OBJECT_MEMBERS = 8;
  public static final int MAX_CANONICAL_SEGMENT_BYTES = 8192;
  public static final int MAX_PREIMAGE_BYTES = 16384;

  private static final JsonFactory JSON =
      JsonFactory.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(MAX_DEPTH)
                  .maxStringLength(MAX_RAW_STRING_CHARACTERS)
                  .maxNumberLength(1)
                  .build())
          .build();

  private InitialGameplayRecoverCodec() {}

  /**
   * Reads bounded raw UTF-8 in the declared member order without a generic tree/map decoder.
   * Original bytes are validated before normalization; no replacement-character repair is used.
   */
  public static InitialGameplayRecoverRequest decode(byte[] raw) {
    Objects.requireNonNull(raw, "raw");
    if (raw.length > MAX_RAW_BYTES) throw invalid();
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(raw));
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("Recover request must be valid UTF-8", exception);
    }
    try (JsonParser parser = JSON.createParser(raw)) {
      require(parser.nextToken(), JsonToken.START_OBJECT);
      literal(
          parser, "actionFamilySchemaId", InitialGameplayRecoverRequest.ACTION_FAMILY_SCHEMA_ID);
      literal(
          parser,
          "actionFamilySchemaVersion",
          InitialGameplayRecoverRequest.ACTION_FAMILY_SCHEMA_VERSION);
      var requestId =
          InitialGameplayRecoverRequest.parseUuid(string(parser, "controlPlaneRequestId"));
      literal(parser, "targetOwner", InitialGameplayRecoverRequest.TARGET_OWNER);
      start(parser, "scope");
      Scope scope =
          new Scope(
              InitialGameplayRecoverRequest.parseUuid(string(parser, "canonicalTenantId")),
              string(parser, "targetNamespace"),
              InitialGameplayRecoverRequest.parseUuid(string(parser, "playableStateNamespaceId")));
      end(parser);
      start(parser, "target");
      start(parser, "launchAssociation");
      LaunchAssociation association =
          new LaunchAssociation(
              InitialGameplayRecoverRequest.parseUuid(string(parser, "gameInstanceUuid")),
              InitialGameplayRecoverRequest.parseUuid(
                  string(parser, "tenantAssociationOperationId")),
              string(parser, "worldSlug"),
              InitialGameplayRecoverRequest.parseUuid(
                  string(parser, "launchControlPlaneRequestId")),
              string(parser, "launchDescriptorId"),
              string(parser, "descriptorRequestDigest"),
              string(parser, "descriptorResultDigest"),
              string(parser, "releaseAttestationEvidenceDigest"));
      end(parser);
      start(parser, "peerStorageBinding");
      PeerStorageBinding binding =
          new PeerStorageBinding(
              string(parser, "targetNamespace"), string(parser, "clusterIdentity"),
              string(parser, "peerWorkloadIdentity"), string(parser, "storageIdentity"),
              string(parser, "storageRevision"), string(parser, "bindingDigest"));
      end(parser);
      Target target =
          new Target(
              association,
              binding,
              string(parser, "evidenceRef"),
              string(parser, "evidenceDigest"));
      end(parser);
      start(parser, "mutation");
      var operationId = InitialGameplayRecoverRequest.parseUuid(string(parser, "operationId"));
      literal(parser, "verb", "recover");
      literal(parser, "workflow", "initial-gameplay-release");
      literal(parser, "maintenanceLockPresence", "RESULT_ONLY");
      end(parser);
      start(parser, "auditReason");
      literal(parser, "code", "INITIAL_GAMEPLAY_RELEASE");
      AuditReason reason =
          new AuditReason(AuditReasonCode.INITIAL_GAMEPLAY_RELEASE, string(parser, "detail"));
      end(parser);
      end(parser);
      if (parser.nextToken() != null) throw invalid();
      return new InitialGameplayRecoverRequest(
          requestId,
          scope,
          target,
          new Mutation(operationId, MaintenanceLockPresence.RESULT_ONLY),
          reason);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Malformed recover request", exception);
    }
  }

  /** Exact seven-field byte preimage; expectedVersion is schema-declared absent, never null. */
  public static byte[] mutationPreimage(InitialGameplayRecoverRequest request) {
    Objects.requireNonNull(request, "request");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    segment(output, "mutationDigest/v1".getBytes(StandardCharsets.UTF_8));
    field(output, "actionFamilySchemaId", InitialGameplayRecoverRequest.ACTION_FAMILY_SCHEMA_ID);
    field(
        output,
        "actionFamilySchemaVersion",
        InitialGameplayRecoverRequest.ACTION_FAMILY_SCHEMA_VERSION);
    field(output, "scope", scope(request.scope()));
    field(output, "target", target(request.target()));
    segment(output, "expectedVersion".getBytes(StandardCharsets.UTF_8));
    segment(output, "absent".getBytes(StandardCharsets.UTF_8));
    output.write('0');
    segment(output, new byte[0]);
    field(
        output,
        "mutation",
        ordered(
            "operationId",
            request.mutation().operationId().toString(),
            "verb",
            request.mutation().verb(),
            "workflow",
            request.mutation().workflow(),
            "maintenanceLockPresence",
            request.mutation().maintenanceLockPresence().name()));
    field(
        output,
        "auditReason",
        ordered(
            "code", request.auditReason().code().name(), "detail", request.auditReason().detail()));
    if (output.size() > MAX_PREIMAGE_BYTES) throw invalid();
    return output.toByteArray();
  }

  public static String mutationDigest(InitialGameplayRecoverRequest request) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(mutationPreimage(request)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static Map<String, Object> scope(Scope scope) {
    return ordered(
        "canonicalTenantId",
        scope.canonicalTenantId().toString(),
        "targetNamespace",
        scope.targetNamespace(),
        "playableStateNamespaceId",
        scope.playableStateNamespaceId().toString());
  }

  private static Map<String, Object> target(Target target) {
    LaunchAssociation launch = target.launchAssociation();
    PeerStorageBinding binding = target.peerStorageBinding();
    return ordered(
        "launchAssociation",
        ordered(
            "gameInstanceUuid", launch.gameInstanceUuid().toString(),
            "tenantAssociationOperationId", launch.tenantAssociationOperationId().toString(),
            "worldSlug", launch.worldSlug(),
            "launchControlPlaneRequestId", launch.launchControlPlaneRequestId().toString(),
            "launchDescriptorId", launch.launchDescriptorId(),
            "descriptorRequestDigest", launch.descriptorRequestDigest(),
            "descriptorResultDigest", launch.descriptorResultDigest(),
            "releaseAttestationEvidenceDigest", launch.releaseAttestationEvidenceDigest()),
        "peerStorageBinding",
        ordered(
            "targetNamespace",
            binding.targetNamespace(),
            "clusterIdentity",
            binding.clusterIdentity(),
            "peerWorkloadIdentity",
            binding.peerWorkloadIdentity(),
            "storageIdentity",
            binding.storageIdentity(),
            "storageRevision",
            binding.storageRevision(),
            "bindingDigest",
            binding.bindingDigest()),
        "evidenceRef",
        target.evidenceRef(),
        "evidenceDigest",
        target.evidenceDigest());
  }

  private static Map<String, Object> ordered(Object... entries) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < entries.length; index += 2) {
      result.put((String) entries[index], entries[index + 1]);
    }
    if (result.size() > MAX_OBJECT_MEMBERS) throw invalid();
    return result;
  }

  private static void field(ByteArrayOutputStream output, String name, Object value) {
    segment(output, name.getBytes(StandardCharsets.UTF_8));
    value(output, value);
  }

  private static void value(ByteArrayOutputStream output, Object value) {
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    String type;
    if (value instanceof String string) {
      type = "string";
      payload.writeBytes(string.getBytes(StandardCharsets.UTF_8));
    } else if (value instanceof Map<?, ?> object) {
      type = "object";
      segment(payload, Integer.toString(object.size()).getBytes(StandardCharsets.US_ASCII));
      for (var member : object.entrySet())
        field(payload, (String) member.getKey(), member.getValue());
    } else {
      throw invalid();
    }
    segment(output, type.getBytes(StandardCharsets.UTF_8));
    output.write('1');
    segment(output, payload.toByteArray());
  }

  private static void segment(ByteArrayOutputStream output, byte[] bytes) {
    if (bytes.length > MAX_CANONICAL_SEGMENT_BYTES) throw invalid();
    output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(bytes);
  }

  private static String string(JsonParser parser, String name) throws IOException {
    member(parser, name);
    require(parser.nextToken(), JsonToken.VALUE_STRING);
    return InitialGameplayRecoverRequest.text(parser.getText(), MAX_RAW_STRING_CHARACTERS);
  }

  private static void literal(JsonParser parser, String name, String value) throws IOException {
    if (!value.equals(string(parser, name))) throw invalid();
  }

  private static void start(JsonParser parser, String name) throws IOException {
    member(parser, name);
    require(parser.nextToken(), JsonToken.START_OBJECT);
  }

  private static void member(JsonParser parser, String name) throws IOException {
    require(parser.nextToken(), JsonToken.FIELD_NAME);
    if (!name.equals(parser.currentName())) throw invalid();
  }

  private static void end(JsonParser parser) throws IOException {
    require(parser.nextToken(), JsonToken.END_OBJECT);
  }

  private static void require(JsonToken actual, JsonToken expected) {
    if (actual != expected) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid closed initial-gameplay-recover/v1 input");
  }
}
