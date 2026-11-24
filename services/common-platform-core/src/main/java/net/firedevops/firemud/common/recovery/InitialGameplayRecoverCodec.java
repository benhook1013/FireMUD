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
  /** Raw request limit; every encoded JSON token inherits this same 16,384-byte ceiling. */
  public static final int MAX_RAW_BYTES = 16384;

  public static final int MAX_DECODED_STRING_UTF16_UNITS = 4096;

  /**
   * @deprecated This historically named limit measures decoded text, not raw JSON token bytes.
   */
  @Deprecated public static final int MAX_RAW_STRING_CHARACTERS = MAX_DECODED_STRING_UTF16_UNITS;

  private static final int MAX_DECODED_STRING_UTF8_BYTES = 4096;
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
                  .maxStringLength(MAX_DECODED_STRING_UTF16_UNITS)
                  .maxNumberLength(1)
                  .build())
          .build();

  private InitialGameplayRecoverCodec() {}

  /**
   * Reads bounded raw UTF-8 in the declared member order without a generic tree/map decoder. The
   * complete byte array is checked before UTF-8 decoding or parser allocation. Each string token's
   * original byte span is validated in the same bounded input, without copying the token. The 4,096
   * limits apply to decoded string length and UTF-8 bytes, not its escaped JSON spelling. Malformed
   * UTF-8 is rejected, not repaired. Field-specific text limits still apply before and after NFC.
   * Raw string spans never enter the canonical digest.
   */
  public static InitialGameplayRecoverRequest decode(byte[] raw) {
    Objects.requireNonNull(raw, "raw");
    if (raw.length > MAX_RAW_BYTES) throw invalid();
    // Bind framing, the parser and every span to one immutable-in-practice bounded snapshot.
    byte[] input = raw.clone();
    requireUtf8ObjectFraming(input);
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(input));
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("Recover request must be valid UTF-8", exception);
    }
    try (JsonParser parser = JSON.createParser(input)) {
      require(parser.nextToken(), JsonToken.START_OBJECT);
      var tokens = new TokenInput(parser, input);
      literal(
          tokens, "actionFamilySchemaId", InitialGameplayRecoverRequest.ACTION_FAMILY_SCHEMA_ID);
      literal(
          tokens,
          "actionFamilySchemaVersion",
          InitialGameplayRecoverRequest.ACTION_FAMILY_SCHEMA_VERSION);
      var requestId =
          InitialGameplayRecoverRequest.parseUuid(string(tokens, "controlPlaneRequestId"));
      literal(tokens, "targetOwner", InitialGameplayRecoverRequest.TARGET_OWNER);
      start(tokens, "scope");
      Scope scope =
          new Scope(
              InitialGameplayRecoverRequest.parseUuid(string(tokens, "canonicalTenantId")),
              string(tokens, "targetNamespace"),
              InitialGameplayRecoverRequest.parseUuid(string(tokens, "playableStateNamespaceId")));
      end(parser);
      start(tokens, "target");
      start(tokens, "launchAssociation");
      LaunchAssociation association =
          new LaunchAssociation(
              InitialGameplayRecoverRequest.parseUuid(string(tokens, "gameInstanceUuid")),
              InitialGameplayRecoverRequest.parseUuid(
                  string(tokens, "tenantAssociationOperationId")),
              string(tokens, "worldSlug"),
              InitialGameplayRecoverRequest.parseUuid(
                  string(tokens, "launchControlPlaneRequestId")),
              string(tokens, "launchDescriptorId"),
              string(tokens, "descriptorRequestDigest"),
              string(tokens, "descriptorResultDigest"),
              string(tokens, "releaseAttestationEvidenceDigest"));
      end(parser);
      start(tokens, "peerStorageBinding");
      PeerStorageBinding binding =
          new PeerStorageBinding(
              string(tokens, "targetNamespace"), string(tokens, "clusterIdentity"),
              string(tokens, "peerWorkloadIdentity"), string(tokens, "storageIdentity"),
              string(tokens, "storageRevision"), string(tokens, "bindingDigest"));
      end(parser);
      Target target =
          new Target(
              association,
              binding,
              string(tokens, "evidenceRef"),
              string(tokens, "evidenceDigest"));
      end(parser);
      start(tokens, "mutation");
      var operationId = InitialGameplayRecoverRequest.parseUuid(string(tokens, "operationId"));
      literal(tokens, "verb", "recover");
      literal(tokens, "workflow", "initial-gameplay-release");
      literal(tokens, "maintenanceLockPresence", "RESULT_ONLY");
      end(parser);
      start(tokens, "auditReason");
      literal(tokens, "code", "INITIAL_GAMEPLAY_RELEASE");
      AuditReason reason =
          new AuditReason(AuditReasonCode.INITIAL_GAMEPLAY_RELEASE, string(tokens, "detail"));
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

  private record TokenInput(JsonParser parser, byte[] original) {
    RawStringSpan stringSpan() {
      long location = parser.currentTokenLocation().getByteOffset();
      if (location < 0 || location >= original.length || original[(int) location] != '"') {
        throw invalid();
      }
      // Retain the original token span as a view over the bounded bytes. Jackson separately
      // enforces the escape grammar and supplies the decoded text for pre-NFC field checks.
      for (int cursor = (int) location + 1; cursor < original.length; cursor++) {
        byte octet = original[cursor];
        if (octet == '"') return new RawStringSpan(original, (int) location, cursor + 1);
        if (octet == '\\') {
          if (++cursor >= original.length) throw invalid();
        } else if (octet >= 0 && octet < 0x20) {
          throw invalid();
        }
      }
      throw invalid();
    }
  }

  private record RawStringSpan(byte[] bytes, int start, int exclusiveEnd) {
    void requireBounded() {
      if (start < 0
          || exclusiveEnd - start < 2
          || exclusiveEnd > bytes.length
          || exclusiveEnd - start > MAX_RAW_BYTES
          || bytes[start] != '"'
          || bytes[exclusiveEnd - 1] != '"') {
        throw invalid();
      }
    }
  }

  private static void requireUtf8ObjectFraming(byte[] input) {
    boolean objectStarted = false;
    for (byte octet : input) {
      boolean whitespace = octet == ' ' || octet == '\t' || octet == '\n' || octet == '\r';
      if (!objectStarted && !whitespace) {
        if (octet != '{') throw invalid();
        objectStarted = true;
      }
      // Disallow raw control bytes and prevent Jackson's UTF-16/32 byte-prefix auto-detection.
      if (octet >= 0 && octet < 0x20 && !whitespace) throw invalid();
    }
    if (!objectStarted) throw invalid();
  }

  private static String string(TokenInput input, String name) throws IOException {
    member(input, name);
    JsonParser parser = input.parser();
    require(parser.nextToken(), JsonToken.VALUE_STRING);
    input.stringSpan().requireBounded();
    String decoded = parser.getText();
    InitialGameplayRecoverRequest.text(decoded, MAX_DECODED_STRING_UTF8_BYTES);
    // Field constructors need the original decoded value to enforce pre-NFC byte limits.
    return decoded;
  }

  private static void literal(TokenInput input, String name, String value) throws IOException {
    if (!value.equals(string(input, name))) throw invalid();
  }

  private static void start(TokenInput input, String name) throws IOException {
    member(input, name);
    require(input.parser().nextToken(), JsonToken.START_OBJECT);
  }

  private static void member(TokenInput input, String name) throws IOException {
    JsonParser parser = input.parser();
    require(parser.nextToken(), JsonToken.FIELD_NAME);
    input.stringSpan().requireBounded();
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
