package net.firedevops.firemud.common.account.authority;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Account's PUBLIC_PRODUCTION SHARED PRESEEDED PLAYER_ADMISSION variant of the existing runtime
 * evidence contract. Content validation is not source authentication or an admission grant.
 *
 * <p>The owner must derive this value from its final Account transaction and protected target read.
 * Target source references name that complete read; they hold no World lock through later use.
 * Decoding validates content only and cannot promote caller-supplied data to source authority.
 */
public final class RuntimePlayerAdmissionEntitlementEvidence {
  public static final String SCHEMA = RuntimeTenantOperationEntitlementEvidence.SCHEMA;
  public static final String VARIANT = "PLAYER_ADMISSION";
  private static final String BUNDLE_VERSION = "2";
  public static final int MAX_CANONICAL_BYTES = 256 * 1024;
  public static final int MAX_CONNECT_SCOPE_CHARS = 16384;
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(16)
                          .maxStringLength(65536)
                          .maxNumberLength(19)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Set<String> TARGET_FIELDS =
      Set.of(
          "targetClass",
          "characterCreationPolicy",
          "targetNamespace",
          "tenantId",
          "tenantSlug",
          "worldSlug",
          "realmId",
          "realmSlug",
          "playableStateNamespaceId",
          "playableStateScope",
          "gameInstanceId",
          "canonicalVersionId",
          "catalogRevision",
          "pointerVersion",
          "activeWorldEpoch",
          "evaluatedAt",
          "connectScopeExpiresAt");
  private static final Set<String> FACT_FIELDS =
      Set.of(
          "sourceKind",
          "subscriptionStatus",
          "provisioningStatus",
          "gameplayAvailable",
          "allowPublicJoin",
          "allowNewGameplayBindings",
          "allowNewInstanceStarts",
          "quotas",
          "entitlementVersion",
          "tenantBillingSequence",
          "tenantAuthorityGeneration");
  private static final Set<String> SOURCE_FIELDS =
      Set.of(
          "callerTokenJti",
          "callerTokenExpiresAt",
          "paidReceiptValidUntil",
          "callerTokenSha256",
          "callerRegistryRecordSha256",
          "callerCommitProofSha256",
          "callerContextId",
          "callerOperationId",
          "scopeDigest",
          "entitlementSourceDigest",
          "admissionPointerSnapshotDigest",
          "gameSessionOwnerProofDigest",
          "publishedPolicySetDigest",
          "worldLifecycleDigest",
          "worldCaptureId",
          "worldRowVersion");
  private final Snapshot membership;
  private final byte[] canonicalBytes;

  private RuntimePlayerAdmissionEntitlementEvidence(Snapshot membership, byte[] canonicalBytes) {
    this.membership = membership;
    this.canonicalBytes = canonicalBytes.clone();
  }

  /** Content-only construction; production source provenance belongs to the Account owner. */
  public static RuntimePlayerAdmissionEntitlementEvidence fromOwnerEvaluation(
      UUID requestId,
      String callerWorkload,
      String verifiedConnectScopeTokenHash,
      Snapshot membership,
      Map<String, Object> target,
      Map<String, Object> facts,
      Map<String, Object> source,
      Instant evaluatedAt) {
    Objects.requireNonNull(membership);
    for (List<?> bounded :
        List.of(
            membership.roles(),
            membership.checkpoints(),
            membership.sourceEvidence(),
            membership.authorityTuple().privateRealmGrantVersions())) {
      if (bounded.size() > 16) throw invalid("Bounded list required");
    }
    RuntimeMembershipAuthorityEvidenceValidator.validate(membership);
    requireUuid(membership.accountId());
    requireUuid(membership.tenantId());
    if (!membership.authorityTuple().privateRealmGrantVersions().isEmpty())
      throw invalid("Public player evidence cannot carry private grant authority");
    if (!"firemud-account-service".equals(membership.issuerId()))
      throw invalid("Canonical Account issuer required");
    requireUuid(requestId.toString());
    var peer =
        GrpcPeerIdentity.parseUri(callerWorkload)
            .filter(
                value ->
                    value.uri().equals(callerWorkload)
                        && "game-session-service".equals(value.service()))
            .orElseThrow(() -> invalid("Exact Game Session caller required"));
    if (verifiedConnectScopeTokenHash == null
        || !verifiedConnectScopeTokenHash.matches("sha256:[0-9a-f]{64}"))
      throw invalid("Verified connect scope token hash required");
    exact(target, TARGET_FIELDS);
    exact(facts, FACT_FIELDS);
    exact(source, SOURCE_FIELDS);
    if (!"PUBLIC_PRODUCTION".equals(target.get("targetClass"))
        || !"PRESEEDED_ONLY".equals(target.get("characterCreationPolicy"))
        || !peer.namespace().equals(target.get("targetNamespace"))
        || !membership.tenantId().equals(target.get("tenantId"))
        || !"SHARED".equals(target.get("playableStateScope")))
      throw invalid("Exact public SHARED tenant target required");
    for (String key :
        List.of(
            "tenantId",
            "realmId",
            "playableStateNamespaceId",
            "gameInstanceId",
            "canonicalVersionId")) requireUuid(text(target.get(key)));
    for (String key : List.of("tenantSlug", "worldSlug", "realmSlug"))
      if (!text(target.get(key)).matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
        throw invalid("Canonical slug required");
    for (String key : List.of("catalogRevision", "pointerVersion", "activeWorldEpoch"))
      positive(target.get(key));
    Instant scopeTime = instant(target.get("evaluatedAt"));
    Instant scopeExpiry = instant(target.get("connectScopeExpiresAt"));
    if (!evaluatedAt.isAfter(Instant.EPOCH)
        || evaluatedAt.isBefore(scopeTime)
        || !evaluatedAt.isBefore(scopeExpiry)) throw invalid("Scope is not current at evaluation");
    validateFacts(facts, membership);
    for (String key :
        List.of("callerTokenJti", "callerContextId", "callerOperationId", "worldCaptureId"))
      requireUuid(text(source.get(key)));
    for (String key :
        List.of("callerTokenSha256", "callerRegistryRecordSha256", "callerCommitProofSha256"))
      if (!text(source.get(key)).matches("[0-9a-f]{64}")) throw invalid("Source SHA-256 required");
    for (String key :
        List.of(
            "scopeDigest",
            "entitlementSourceDigest",
            "worldLifecycleDigest",
            "publishedPolicySetDigest",
            "gameSessionOwnerProofDigest"))
      if (!text(source.get(key)).matches("sha256:[0-9a-f]{64}"))
        throw invalid("Source digest required");
    for (String key : List.of("admissionPointerSnapshotDigest"))
      if (!text(source.get(key)).matches("[0-9a-f]{64}"))
        throw invalid("Canonical target source digest required");
    if (!text(source.get("worldRowVersion")).matches("0|[1-9][0-9]{0,18}")
        || Long.parseLong(text(source.get("worldRowVersion"))) < 0)
      throw invalid("World row version required");
    Instant validUntil = instant(source.get("callerTokenExpiresAt"));
    if (scopeExpiry.isBefore(validUntil)) validUntil = scopeExpiry;
    if ("PAID_HOSTING".equals(facts.get("sourceKind"))) {
      Instant providerDeadline = instant(source.get("paidReceiptValidUntil"));
      if (providerDeadline.isBefore(validUntil)) validUntil = providerDeadline;
    } else if (source.get("paidReceiptValidUntil") != null) {
      throw invalid("Demo evidence cannot assert a paid receipt deadline");
    }
    if (!evaluatedAt.isBefore(validUntil)) throw invalid("Player source validity expired");
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema", SCHEMA);
    fields.put("variant", VARIANT);
    fields.put("bundleVersion", BUNDLE_VERSION);
    fields.put("authorityAvailability", "AVAILABLE");
    fields.put("issuer", membership.issuerId());
    fields.put("tenantId", membership.tenantId());
    fields.put("callerAccountId", membership.accountId());
    fields.put("callerWorkload", callerWorkload);
    fields.put("verifiedConnectScopeTokenHash", verifiedConnectScopeTokenHash);
    fields.put("target", target);
    fields.putAll(facts);
    fields.put("membershipExists", membership.membershipExists());
    fields.put("membershipLifecycleState", membership.membershipLifecycleState());
    fields.put("gameplayAdmissionAllowed", membership.gameplayAdmissionAllowed());
    fields.put("membershipVersion", membership.membershipVersion());
    fields.put("membershipAuthorityGeneration", membership.membershipAuthorityGeneration());
    fields.put("roles", membership.roles());
    fields.put("issuanceFence", membership.issuanceFence());
    fields.put("authorityTuple", tuple(membership));
    fields.put(
        "outboxCheckpoints",
        membership.checkpoints().stream()
            .map(
                checkpoint -> {
                  Map<String, Object> entry = new LinkedHashMap<>();
                  entry.put("outboxStreamKey", checkpoint.outboxStreamKey());
                  entry.put("outboxSequence", checkpoint.outboxSequence());
                  membership.sourceEvidence().stream()
                      .filter(event -> event.outboxStreamKey().equals(checkpoint.outboxStreamKey()))
                      .findFirst()
                      .ifPresent(
                          event -> {
                            entry.put("sourceEventId", event.eventId());
                            entry.put("sourceEventDigest", event.eventDigest());
                          });
                  return entry;
                })
            .toList());
    fields.put("authoritySourceEvidence", membership.sourceEvidence());
    fields.put("sourceReferences", source);
    fields.put("validUntil", validUntil.toString());
    // The committed-input identity excludes the request and evaluation time. Source references
    // include the actual caller issuance, retained scope, billing and protected target captures.
    String snapshotIdentity = digest(fields);
    fields.put("snapshotIdentity", snapshotIdentity);
    fields.put("requestId", requestId.toString());
    fields.put("evaluatedAt", evaluatedAt.toString());
    fields.put(
        "evaluationIdentity",
        digest(
            Map.of(
                "snapshotIdentity",
                snapshotIdentity,
                "requestId",
                requestId.toString(),
                "callerWorkload",
                callerWorkload,
                "evaluatedAt",
                evaluatedAt.toString())));
    byte[] bytes = canonical(fields);
    if (bytes.length > MAX_CANONICAL_BYTES) throw invalid("Player evidence exceeds size bound");
    // Apply the decoder's exact JSON limits without recursively invoking parseCanonical.
    try {
      if (!JSON.readTree(bytes).isObject()) throw invalid("Player evidence object required");
    } catch (RuntimeException malformed) {
      throw invalid("Player evidence exceeds decoder constraints");
    }
    return new RuntimePlayerAdmissionEntitlementEvidence(membership, bytes);
  }

  /** Strict content decoder only; authenticated transport and Account sources remain mandatory. */
  public static RuntimePlayerAdmissionEntitlementEvidence parseCanonical(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_CANONICAL_BYTES)
      throw invalid("Player evidence is absent or exceeds size bound");
    try {
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      Map<String, Object> fields = JSON.readValue(json, new TypeReference<>() {});
      if (!BUNDLE_VERSION.equals(fields.get("bundleVersion"))
          || !VARIANT.equals(fields.get("variant"))
          || fields.containsKey("verifiedConnectScopeId"))
        throw invalid("Unsupported player evidence format");
      Map<String, Object> authority = object(fields.get("authorityTuple"));
      Set<String> requiredTuple =
          Set.of(
              "issuerAuthGeneration",
              "accountAuthorityGeneration",
              "tenantAuthorityGeneration",
              "membershipAuthorityGeneration",
              "privateRealmGrantVersions");
      if (!authority.keySet().containsAll(requiredTuple)
          || authority.keySet().stream()
              .anyMatch(
                  k ->
                      !requiredTuple.contains(k)
                          && !Set.of("accountSecurityCutoff", "tenantBillingCutoff").contains(k))
          || !list(authority.get("privateRealmGrantVersions")).isEmpty())
        throw invalid("Closed public authority tuple required");
      Optional<MembershipAuthorityEventV1Codec.AccountSecurityCutoff> accountCutoff =
          Optional.empty();
      if (authority.containsKey("accountSecurityCutoff")) {
        var cutoff = object(authority.get("accountSecurityCutoff"));
        exact(cutoff, Set.of("accountAuthorityGeneration", "outboxStreamKey", "outboxSequence"));
        accountCutoff =
            Optional.of(
                new MembershipAuthorityEventV1Codec.AccountSecurityCutoff(
                    text(cutoff.get("accountAuthorityGeneration")),
                    text(cutoff.get("outboxStreamKey")),
                    text(cutoff.get("outboxSequence"))));
      }
      Map<String, MembershipAuthorityEventV1Codec.TenantBillingCutoff> tenantCutoffs =
          new LinkedHashMap<>();
      object(authority.get("tenantBillingCutoff"))
          .forEach(
              (tenant, raw) -> {
                var cutoff = object(raw);
                exact(
                    cutoff,
                    Set.of(
                        "tenantAuthorityGeneration",
                        "tenantBillingSequence",
                        "outboxStreamKey",
                        "outboxSequence"));
                tenantCutoffs.put(
                    tenant,
                    new MembershipAuthorityEventV1Codec.TenantBillingCutoff(
                        text(cutoff.get("tenantAuthorityGeneration")),
                            text(cutoff.get("tenantBillingSequence")),
                        text(cutoff.get("outboxStreamKey")), text(cutoff.get("outboxSequence"))));
              });
      var tuple =
          new MembershipAuthorityEventV1Codec.AuthorityTuple(
              text(authority.get("issuerAuthGeneration")),
              text(authority.get("accountAuthorityGeneration")),
              strings(authority.get("tenantAuthorityGeneration")),
              strings(authority.get("membershipAuthorityGeneration")),
              List.of(),
              accountCutoff,
              Optional.of(tenantCutoffs));
      var checkpoints =
          list(fields.get("outboxCheckpoints")).stream()
              .map(
                  raw -> {
                    var checkpoint = object(raw);
                    String stream = text(checkpoint.get("outboxStreamKey"));
                    String sequence = text(checkpoint.get("outboxSequence"));
                    return new RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(
                        stream, sequence);
                  })
              .toList();
      var events =
          list(fields.get("authoritySourceEvidence")).stream()
              .map(
                  raw -> {
                    var event = object(raw);
                    exact(
                        event,
                        Set.of(
                            "outboxStreamKey",
                            "outboxSequence",
                            "eventId",
                            "eventDigest",
                            "canonicalEventJson"));
                    return new RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
                        text(event.get("outboxStreamKey")),
                        text(event.get("outboxSequence")),
                        text(event.get("eventId")),
                        text(event.get("eventDigest")),
                        text(event.get("canonicalEventJson")));
                  })
              .toList();
      Snapshot membership =
          new Snapshot(
              text(fields.get("issuer")),
              text(fields.get("callerAccountId")),
              text(fields.get("tenantId")),
              bool(fields.get("membershipExists")),
              text(fields.get("membershipLifecycleState")),
              bool(fields.get("gameplayAdmissionAllowed")),
              strings(fields.get("membershipVersion")),
              text(fields.get("membershipAuthorityGeneration")),
              list(fields.get("roles")).stream()
                  .map(RuntimePlayerAdmissionEntitlementEvidence::text)
                  .toList(),
              tuple,
              text(fields.get("issuanceFence")),
              checkpoints,
              events);
      Map<String, Object> facts = new LinkedHashMap<>();
      FACT_FIELDS.forEach(key -> facts.put(key, fields.get(key)));
      var rebuilt =
          fromOwnerEvaluation(
              UUID.fromString(text(fields.get("requestId"))),
              text(fields.get("callerWorkload")),
              text(fields.get("verifiedConnectScopeTokenHash")),
              membership,
              object(fields.get("target")),
              facts,
              object(fields.get("sourceReferences")),
              instant(fields.get("evaluatedAt")));
      if (!Arrays.equals(bytes, rebuilt.canonicalBytes))
        throw invalid("Player evidence must be the exact closed canonical value");
      return rebuilt;
    } catch (CharacterCodingException failure) {
      throw invalid("Player evidence requires strict UTF-8");
    } catch (RuntimeException failure) {
      throw invalid("Invalid canonical player evidence");
    }
  }

  private static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw invalid("Object required");
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, member) -> result.put(text(key), member));
    return result;
  }

  private static Map<String, String> strings(Object value) {
    Map<String, String> result = new LinkedHashMap<>();
    object(value).forEach((key, member) -> result.put(key, text(member)));
    return result;
  }

  private static List<?> list(Object value) {
    if (!(value instanceof List<?> list) || list.size() > 16)
      throw invalid("Bounded list required");
    return list;
  }

  private static boolean bool(Object value) {
    if (!(value instanceof Boolean flag)) throw invalid("Boolean required");
    return flag;
  }

  private static Instant instant(Object value) {
    String text = text(value);
    Instant instant = Instant.parse(text);
    if (!instant.toString().equals(text)) throw invalid("Canonical timestamp required");
    return instant;
  }

  private static void validateFacts(Map<String, Object> facts, Snapshot membership) {
    String kind = text(facts.get("sourceKind"));
    if (!Set.of("PAID_HOSTING", "NON_PAID_DEMO").contains(kind)
        || facts.get("provisioningStatus") != null)
      throw invalid("Current explicit source required");
    Object status = facts.get("subscriptionStatus");
    if ("NON_PAID_DEMO".equals(kind)
        ? status != null
        : !(status instanceof String)
            || !Set.of("trialing", "active", "past_due", "grace", "suspended", "canceled")
                .contains(status)) throw invalid("Source subscription status differs");
    for (String key :
        List.of(
            "gameplayAvailable",
            "allowPublicJoin",
            "allowNewGameplayBindings",
            "allowNewInstanceStarts"))
      if (!(facts.get(key) instanceof Boolean))
        throw invalid("Explicit entitlement booleans required");
    positive(facts.get("entitlementVersion"));
    positive(facts.get("tenantBillingSequence"));
    positive(facts.get("tenantAuthorityGeneration"));
    var tuple = membership.authorityTuple();
    var cutoff =
        tuple
            .tenantBillingCutoff()
            .orElseThrow(() -> invalid("Billing cutoff required"))
            .get(membership.tenantId());
    if (!Objects.equals(
            tuple.tenantAuthorityGeneration().get(membership.tenantId()),
            facts.get("tenantAuthorityGeneration"))
        || cutoff == null
        || !Objects.equals(cutoff.tenantBillingSequence(), facts.get("tenantBillingSequence")))
      throw invalid("Facts differ from current tenant authority");
    if (!(facts.get("quotas") instanceof Map<?, ?> quotas)
        || !quotas
            .keySet()
            .equals(Set.of("maxActiveSessions", "maxConcurrentGameInstances", "maxStorageBytes")))
      throw invalid("Complete quotas required");
    quotas
        .values()
        .forEach(
            value -> {
              if (!text(value).matches("0|[1-9][0-9]{0,18}") || Long.parseLong(text(value)) < 0)
                throw invalid("Nonnegative quota required");
            });
  }

  private static Map<String, Object> tuple(Snapshot membership) {
    var supplied = membership.authorityTuple();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("issuerAuthGeneration", supplied.issuerAuthGeneration());
    result.put("accountAuthorityGeneration", supplied.accountAuthorityGeneration());
    result.put("tenantAuthorityGeneration", supplied.tenantAuthorityGeneration());
    result.put("membershipAuthorityGeneration", supplied.membershipAuthorityGeneration());
    result.put("privateRealmGrantVersions", supplied.privateRealmGrantVersions());
    supplied.accountSecurityCutoff().ifPresent(value -> result.put("accountSecurityCutoff", value));
    supplied.tenantBillingCutoff().ifPresent(value -> result.put("tenantBillingCutoff", value));
    return result;
  }

  private static void exact(Map<String, Object> fields, Set<String> expected) {
    if (fields == null || !fields.keySet().equals(expected))
      throw invalid("Missing or extra scoped fields");
  }

  private static String text(Object value) {
    if (!(value instanceof String text) || text.isBlank()) throw invalid("Required text absent");
    return text;
  }

  private static void requireUuid(String value) {
    if (!UUID.fromString(value).toString().equals(value) || new UUID(0, 0).toString().equals(value))
      throw invalid("Canonical UUID required");
  }

  private static void positive(Object value) {
    if (!text(value).matches("[1-9][0-9]{0,18}") || Long.parseLong(text(value)) <= 0)
      throw invalid("Positive owner counter required");
  }

  private static byte[] canonical(Map<String, Object> value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException failure) {
      throw invalid("Cannot encode canonical player evidence");
    }
  }

  public static String digest(Map<String, Object> preimage) {
    return digestBytes(canonical(preimage));
  }

  public static String digestBytes(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  /** Correlates the exact authenticated request token with the non-secret evidence binding. */
  public static String connectScopeTokenHash(String token) {
    if (token == null || token.isBlank() || token.length() > MAX_CONNECT_SCOPE_CHARS)
      throw invalid("Exact connect scope token required");
    return digestBytes(token.getBytes(StandardCharsets.UTF_8));
  }

  public String verifiedConnectScopeTokenHash() {
    return text(fields().get("verifiedConnectScopeTokenHash"));
  }

  public Instant evaluatedAt() {
    return instant(fields().get("evaluatedAt"));
  }

  public Instant validUntil() {
    return instant(fields().get("validUntil"));
  }

  /** Applies actual source time bounds only; this never establishes a current World hold. */
  public void requireCurrentAt(Instant now) {
    Objects.requireNonNull(now);
    if (now.isBefore(evaluatedAt()) || !now.isBefore(validUntil()))
      throw invalid("Player entitlement source observation expired");
  }

  public Snapshot membershipSource() {
    return membership;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  /** A detached copy; editing it cannot alter this value. */
  public Map<String, Object> fields() {
    return JSON.readValue(
        new String(canonicalBytes, StandardCharsets.UTF_8), new TypeReference<>() {});
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof RuntimePlayerAdmissionEntitlementEvidence value
        && Arrays.equals(canonicalBytes, value.canonicalBytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes);
  }

  @Override
  public String toString() {
    return "RuntimePlayerAdmissionEntitlementEvidence[PLAYER_ADMISSION, redacted]";
  }
}
