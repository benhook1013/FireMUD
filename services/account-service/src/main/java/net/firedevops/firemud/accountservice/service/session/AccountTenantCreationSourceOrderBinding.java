package net.firedevops.firemud.accountservice.service.session;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationAdmissionRepository.Admitted;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence;

/** Immutable Account storage binding for exact tenant-creation source participation data only. */
final class AccountTenantCreationSourceOrderBinding {
  static final String SCHEMA = "account-tenant-creation-source-order/v1";
  static final int SOURCE_COUNT = 8;

  private final UUID creationOperationId;
  private final UUID intentRequestId;
  private final String targetNamespace;
  private final UUID gameDesignCreationRequestId;
  private final UUID actorAccountId;
  private final UUID creatorPartyId;
  private final String gameDesignRequestDigest;
  private final byte[] intentPayload;
  private final String intentDigest;
  private final byte[] admissionPayload;
  private final String admissionDigest;
  private final List<SourceEvidence> sources;
  private final byte[] canonicalBytes;
  private final String authorizationDigest;

  private AccountTenantCreationSourceOrderBinding(
      UUID creationOperationId,
      UUID intentRequestId,
      String targetNamespace,
      UUID gameDesignCreationRequestId,
      UUID actorAccountId,
      UUID creatorPartyId,
      byte[] intentPayload,
      String intentDigest,
      byte[] admissionPayload,
      String admissionDigest,
      List<SourceEvidence> sources) {
    this.creationOperationId = requireUuid(creationOperationId);
    this.intentRequestId = requireUuid(intentRequestId);
    this.targetNamespace = requireText(targetNamespace);
    this.gameDesignCreationRequestId = requireUuid(gameDesignCreationRequestId);
    this.actorAccountId = requireUuid(actorAccountId);
    this.creatorPartyId = requireUuid(creatorPartyId);
    this.intentPayload = exactBytes(intentPayload);
    IntentFields requestFields = parseIntentPayload(this.intentPayload);
    if (!requestFields.requestId().equals(this.intentRequestId)
        || !requestFields.creationOperationId().equals(this.creationOperationId)
        || !requestFields.gameDesignCreationRequestId().equals(this.gameDesignCreationRequestId)
        || !requestFields.initiatingAccountId().equals(this.actorAccountId)
        || !requestFields.targetNamespace().equals(this.targetNamespace)
        || !requestFields.creatorPartyId().equals(this.creatorPartyId)) {
      throw conflict("Tenant-creation source-order tuple differs from its original request bytes");
    }
    this.gameDesignRequestDigest =
        GameTenantCreationDigest.requestDigest(
            this.targetNamespace,
            this.gameDesignCreationRequestId,
            requestFields.sourceGameTenantKey(),
            requestFields.name(),
            requestFields.description());
    this.intentDigest = requirePrefixedDigest(intentDigest);
    this.admissionPayload = exactBytes(admissionPayload);
    this.admissionDigest = requireBareDigest(admissionDigest);
    if (!("sha256:" + digestBare(this.intentPayload)).equals(this.intentDigest)
        || !digestBare(this.admissionPayload).equals(this.admissionDigest)) {
      throw conflict("Tenant-creation source-order parent digest differs from its exact bytes");
    }
    this.sources = validateSources(sources, actorAccountId, creatorPartyId);
    this.canonicalBytes = encodeCanonicalBytes();
    this.authorizationDigest = DraftAuthorizationFenceBinding.digest(canonicalBytes);
  }

  static AccountTenantCreationSourceOrderBinding from(
      OriginalIntent intent, Admitted admitted, List<SourceEvidence> suppliedSources) {
    Objects.requireNonNull(intent, "exact persisted original intent is required");
    Objects.requireNonNull(admitted, "exact durable admission is required");
    validateOriginalIntent(intent);

    var admittedBinding = admitted.binding();
    byte[] admissionPayload = admittedBinding.canonicalReceipt();
    if (!Arrays.equals(admissionPayload, admitted.receiptPayload())
        || !digestBare(admissionPayload).equals(admitted.receiptDigest())
        || !intent.requestId().equals(admittedBinding.intentRequestId())
        || !intent.creationOperationId().equals(admittedBinding.creationOperationId())
        || !intent.initiatingAccountId().equals(admittedBinding.actorAccountId())
        || !intent.targetNamespace().equals(admittedBinding.targetNamespace())
        || !intent
            .gameDesignCreationRequestId()
            .equals(admittedBinding.gameDesignCreationRequestId())
        || !intent.gameDesignRequestDigest().equals(admittedBinding.gameDesignRequestDigest())
        || !intent.requestDigest().equals(admittedBinding.intentDigest())
        || !intent
            .recentReauthenticationEvidenceId()
            .equals(admittedBinding.reauthenticationEvidenceId())) {
      throw conflict("Durable admission differs from the exact original intent");
    }

    return new AccountTenantCreationSourceOrderBinding(
        intent.creationOperationId(),
        intent.requestId(),
        intent.targetNamespace(),
        intent.gameDesignCreationRequestId(),
        intent.initiatingAccountId(),
        intent.creatorPartyId(),
        intent.requestPayload(),
        intent.requestDigest(),
        admissionPayload,
        admitted.receiptDigest(),
        suppliedSources);
  }

  /**
   * Decodes exact immutable storage data; this does not authenticate sources or confer authority.
   */
  static AccountTenantCreationSourceOrderBinding fromStored(byte[] stored) {
    byte[] exact = exactBytes(stored);
    var reader = new DraftAuthorizationFenceBinding.FrameReader(exact);
    reader.expect(SCHEMA);
    byte[] requestPayload = reader.bytes();
    String requestDigest = reader.text();
    byte[] admissionPayload = reader.bytes();
    String admissionDigest = reader.text();
    String count = reader.text();
    DraftAuthorizationFenceBinding.decimal(count, false);
    if (!Integer.toString(SOURCE_COUNT).equals(count)) {
      throw conflict("Tenant-creation source vector is incomplete");
    }
    List<SourceEvidence> sources = new ArrayList<>(SOURCE_COUNT);
    for (int index = 0; index < SOURCE_COUNT; index++) {
      sources.add(SourceEvidence.fromStored(reader.bytes()));
    }
    reader.requireEnd();
    IntentFields intent = parseIntentPayload(requestPayload);
    AccountTenantCreationSourceOrderBinding binding =
        new AccountTenantCreationSourceOrderBinding(
            intent.creationOperationId(),
            intent.requestId(),
            intent.targetNamespace(),
            intent.gameDesignCreationRequestId(),
            intent.initiatingAccountId(),
            intent.creatorPartyId(),
            requestPayload,
            requestDigest,
            admissionPayload,
            admissionDigest,
            sources);
    if (!Arrays.equals(exact, binding.canonicalBytes)) {
      throw conflict("Noncanonical tenant-creation source-order binding");
    }
    return binding;
  }

  UUID creationOperationId() {
    return creationOperationId;
  }

  UUID intentRequestId() {
    return intentRequestId;
  }

  String targetNamespace() {
    return targetNamespace;
  }

  UUID gameDesignCreationRequestId() {
    return gameDesignCreationRequestId;
  }

  UUID actorAccountId() {
    return actorAccountId;
  }

  UUID creatorPartyId() {
    return creatorPartyId;
  }

  byte[] intentPayload() {
    return intentPayload.clone();
  }

  String intentDigest() {
    return intentDigest;
  }

  String gameDesignRequestDigest() {
    return gameDesignRequestDigest;
  }

  byte[] admissionPayload() {
    return admissionPayload.clone();
  }

  String admissionDigest() {
    return admissionDigest;
  }

  List<SourceEvidence> sources() {
    return List.copyOf(sources);
  }

  byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  String authorizationDigest() {
    return authorizationDigest;
  }

  GameTenantCreationOriginalOutcomeEvidence.Binding outcomeBinding() {
    return new GameTenantCreationOriginalOutcomeEvidence.Binding(
        targetNamespace,
        gameDesignCreationRequestId,
        creationOperationId,
        actorAccountId,
        gameDesignRequestDigest,
        authorizationDigest);
  }

  private byte[] encodeCanonicalBytes() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(bytes, SCHEMA);
    DraftAuthorizationFenceBinding.frame(bytes, intentPayload);
    DraftAuthorizationFenceBinding.frame(bytes, intentDigest);
    DraftAuthorizationFenceBinding.frame(bytes, admissionPayload);
    DraftAuthorizationFenceBinding.frame(bytes, admissionDigest);
    DraftAuthorizationFenceBinding.frame(bytes, Integer.toString(sources.size()));
    for (SourceEvidence source : sources) {
      DraftAuthorizationFenceBinding.frame(bytes, source.canonicalBytes());
    }
    return bytes.toByteArray();
  }

  private static List<SourceEvidence> validateSources(
      List<SourceEvidence> supplied, UUID actorAccountId, UUID creatorPartyId) {
    List<SourceEvidence> sources =
        List.copyOf(Objects.requireNonNull(supplied, "exact current owner sources are required"))
            .stream()
            .sorted(Comparator.comparing(SourceEvidence::key))
            .toList();
    if (sources.size() != SOURCE_COUNT
        || sources.stream().map(SourceEvidence::key).distinct().count() != SOURCE_COUNT) {
      throw conflict("Exact closed tenant-creation source vector is required");
    }

    String actor = actorAccountId.toString();
    String party = creatorPartyId.toString();
    List<String> keys = sources.stream().map(SourceEvidence::key).toList();
    String environmentKey = null;
    String hostedScopeKey = null;
    for (SourceEvidence source : sources) {
      if (source.kind() == SourceKind.HOSTED_TERMS) {
        if (source.scopeId().startsWith("environment-boundary/")) {
          if (environmentKey != null
              || source.scopeId().length() == "environment-boundary/".length()) {
            throw conflict("One exact environment-boundary source is required");
          }
          environmentKey = source.key();
        } else {
          DraftAuthorizationFenceBinding.canonicalUuid(source.scopeId());
          if (hostedScopeKey != null) {
            throw conflict("One exact hosted-terms scope source is required");
          }
          hostedScopeKey = source.key();
        }
      }
    }
    List<String> expected =
        new ArrayList<>(
            List.of(
                "ISSUER:firemud-account-service",
                "ACCOUNT:" + actor,
                "GLOBAL_ROLES:" + actor,
                "CREATOR_PARTY:" + party,
                "RECENT_REAUTH_POLICY:SELF_SERVICE_GAME_CREATION/DEPLOYMENT",
                "RECENT_REAUTH_POLICY:SELF_SERVICE_GAME_CREATION/PLATFORM"));
    if (environmentKey == null || hostedScopeKey == null) {
      throw conflict("Exact hosted-terms sources are required");
    }
    expected.add(environmentKey);
    expected.add(hostedScopeKey);
    expected.sort(String::compareTo);
    if (!keys.equals(expected)) {
      throw conflict("Tenant-creation sources differ from the exact closed owner vector");
    }
    return sources;
  }

  private static void validateOriginalIntent(OriginalIntent intent) {
    requireUuid(intent.requestId());
    requireUuid(intent.creationOperationId());
    requireUuid(intent.gameDesignCreationRequestId());
    requireUuid(intent.initiatingAccountId());
    requireUuid(intent.recentReauthenticationEvidenceId());
    requireUuid(intent.creatorPartyId());
    requireUuid(intent.consentOperationId());
    if (intent.consentStatementVersion() <= 0 || !intent.affirmativeConsent()) {
      throw conflict("Affirmative versioned tenant-creation consent is required");
    }
    byte[] canonicalRequest = canonicalRequestPayload(intent);
    if (!Arrays.equals(canonicalRequest, intent.requestPayload())
        || !DraftAuthorizationFenceBinding.digest(canonicalRequest).equals(intent.requestDigest())
        || !GameTenantCreationDigest.requestDigest(
                intent.targetNamespace(),
                intent.gameDesignCreationRequestId(),
                intent.sourceGameTenantKey(),
                intent.name(),
                intent.description())
            .equals(intent.gameDesignRequestDigest())) {
      throw conflict("Original tenant-creation intent bytes or digest differ");
    }
  }

  private static byte[] canonicalRequestPayload(OriginalIntent intent) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      frame(output, "account-tenant-creation-intent-request/v1");
      frame(output, "requestId");
      frame(output, intent.requestId().toString());
      frame(output, "creationOperationId");
      frame(output, intent.creationOperationId().toString());
      frame(output, "gameDesignCreationRequestId");
      frame(output, intent.gameDesignCreationRequestId().toString());
      frame(output, "initiatingAccountId");
      frame(output, intent.initiatingAccountId().toString());
      frame(output, "targetNamespace");
      frame(output, intent.targetNamespace());
      frame(output, "sourceGameTenantKey");
      frame(output, intent.sourceGameTenantKey());
      frame(output, "name");
      frame(output, intent.name());
      frame(output, "description");
      frame(output, intent.description() == null ? "ABSENT" : "PRESENT");
      if (intent.description() != null) {
        frame(output, intent.description());
      }
      frame(output, "recentReauthenticationEvidenceId");
      frame(output, intent.recentReauthenticationEvidenceId().toString());
      frame(output, "creatorPartyId");
      frame(output, intent.creatorPartyId().toString());
      frame(output, "consentOperationId");
      frame(output, intent.consentOperationId().toString());
      frame(output, "consentStatementVersion");
      frame(output, Integer.toString(intent.consentStatementVersion()));
      frame(output, "affirmativeConsent");
      frame(output, Boolean.toString(intent.affirmativeConsent()));
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Unable to encode original tenant-creation request", impossible);
    }
    return bytes.toByteArray();
  }

  private static void frame(DataOutputStream output, String value) throws IOException {
    String exact = Objects.requireNonNull(value);
    byte[] encoded = exact.getBytes(StandardCharsets.UTF_8);
    if (GameTenantCreationDigest.utf8ByteLength(exact) != encoded.length) {
      throw new IllegalArgumentException("Creation inputs must contain valid Unicode");
    }
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static IntentFields parseIntentPayload(byte[] payload) {
    IntentFrameReader reader = new IntentFrameReader(payload);
    reader.expect("account-tenant-creation-intent-request/v1");
    reader.expect("requestId");
    UUID requestId = canonicalUuid(reader.text());
    reader.expect("creationOperationId");
    UUID operationId = canonicalUuid(reader.text());
    reader.expect("gameDesignCreationRequestId");
    UUID gameDesignRequestId = canonicalUuid(reader.text());
    reader.expect("initiatingAccountId");
    UUID actorId = canonicalUuid(reader.text());
    reader.expect("targetNamespace");
    String targetNamespace = reader.text();
    reader.expect("sourceGameTenantKey");
    String sourceGameTenantKey = reader.text();
    reader.expect("name");
    String name = reader.text();
    reader.expect("description");
    String description = reader.text();
    String exactDescription;
    if ("PRESENT".equals(description)) {
      exactDescription = reader.text();
    } else if (!"ABSENT".equals(description)) {
      throw conflict("Original intent description presence is malformed");
    } else {
      exactDescription = null;
    }
    reader.expect("recentReauthenticationEvidenceId");
    canonicalUuid(reader.text());
    reader.expect("creatorPartyId");
    UUID partyId = canonicalUuid(reader.text());
    reader.expect("consentOperationId");
    canonicalUuid(reader.text());
    reader.expect("consentStatementVersion");
    String version = reader.text();
    DraftAuthorizationFenceBinding.decimal(version, false);
    reader.expect("affirmativeConsent");
    if (!"true".equals(reader.text())) {
      throw conflict("Original tenant-creation consent is not affirmative");
    }
    reader.requireEnd();
    return new IntentFields(
        requestId,
        operationId,
        gameDesignRequestId,
        actorId,
        targetNamespace,
        sourceGameTenantKey,
        name,
        exactDescription,
        partyId);
  }

  private static UUID canonicalUuid(String value) {
    DraftAuthorizationFenceBinding.canonicalUuid(value);
    return UUID.fromString(value);
  }

  private static UUID requireUuid(UUID value) {
    DraftAuthorizationFenceBinding.requireUuid(value);
    return value;
  }

  private static String requireText(String value) {
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
      throw conflict("Canonical source-order text is required");
    }
    GameTenantCreationDigest.utf8ByteLength(value);
    return value;
  }

  private static byte[] exactBytes(byte[] value) {
    return DraftAuthorizationFenceBinding.bytes(value);
  }

  private static String requirePrefixedDigest(String value) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw conflict("Canonical original request digest is required");
    }
    return value;
  }

  private static String requireBareDigest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw conflict("Canonical admission binding digest is required");
    }
    return value;
  }

  private static String digestBare(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static IllegalArgumentException conflict(String message) {
    return new IllegalArgumentException(message);
  }

  private record IntentFields(
      UUID requestId,
      UUID creationOperationId,
      UUID gameDesignCreationRequestId,
      UUID initiatingAccountId,
      String targetNamespace,
      String sourceGameTenantKey,
      String name,
      String description,
      UUID creatorPartyId) {}

  private static final class IntentFrameReader {
    private final java.nio.ByteBuffer input;

    IntentFrameReader(byte[] encoded) {
      input = java.nio.ByteBuffer.wrap(exactBytes(encoded));
    }

    String text() {
      byte[] bytes = bytes();
      String value = new String(bytes, StandardCharsets.UTF_8);
      if (GameTenantCreationDigest.utf8ByteLength(value) != bytes.length
          || !Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
        throw conflict("Original request contains malformed UTF-8");
      }
      return value;
    }

    void expect(String expected) {
      if (!expected.equals(text())) {
        throw conflict("Original request framing differs");
      }
    }

    void requireEnd() {
      if (input.hasRemaining()) {
        throw conflict("Original request contains trailing frames");
      }
    }

    private byte[] bytes() {
      if (input.remaining() < Integer.BYTES) {
        throw conflict("Original request frame is truncated");
      }
      int length = input.getInt();
      if (length < 0 || length > input.remaining()) {
        throw conflict("Original request frame length is invalid");
      }
      byte[] value = new byte[length];
      input.get(value);
      return value;
    }
  }
}
