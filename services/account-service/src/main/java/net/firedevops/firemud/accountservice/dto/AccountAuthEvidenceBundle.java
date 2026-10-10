package net.firedevops.firemud.accountservice.dto;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed canonical Account-owned {@code account-auth-evidence-bundle/v1} value for the explicitly
 * non-tenant Game Session-to-Account delegation.
 *
 * <p>This codec validates shape and canonical bytes only. It does not establish that owner source
 * rows, event history, cutoff applicability, or the caller are authentic/current. The DTO itself is
 * never proof; the Account repository must derive and persist it only from locked owner readback.
 */
public final class AccountAuthEvidenceBundle {
  public static final String SCHEMA = "account-auth-evidence-bundle/v1";
  // The existing Account response-envelope AEAD binding accepts at most 8 KiB for this value.
  public static final int MAX_CANONICAL_BYTES = 8 * 1024;

  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern EVENT_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern UUID_TEXT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern V4_UUID_TEXT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern NONNEGATIVE_DECIMAL = Pattern.compile("0|[1-9][0-9]{0,18}");
  private static final Pattern POSITIVE_XID8 = Pattern.compile("[1-9][0-9]{0,19}");
  private static final Pattern WORKLOAD =
      Pattern.compile(
          "^spiffe://firemud/ns/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?/sa/game-session-service$");

  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "schema",
          "bundleRef",
          "snapshotIdentity",
          "evaluationIdentity",
          "issuer",
          "profile",
          "audience",
          "scope",
          "operation",
          "tokenIdentity",
          "authorityTuple",
          "issuanceFence",
          "membershipVersion",
          "authoritySourceVersions",
          "accountIdentitySource",
          "outboxCheckpoints");
  private static final Set<String> BUNDLE_REF_FIELDS =
      Set.of("bundleVersion", "sourceVersion", "sourceFence", "linearization");
  private static final Set<String> SCOPE_FIELDS = Set.of("kind", "accountId", "tenantIds");
  private static final Set<String> OPERATION_FIELDS =
      Set.of(
          "operationId",
          "requestId",
          "requestDigest",
          "callerWorkload",
          "callerContextId",
          "accountId");
  private static final Set<String> TOKEN_IDENTITY_FIELDS =
      Set.of("jti", "tokenGeneration", "iat", "nbf", "exp");
  private static final Set<String> AUTHORITY_TUPLE_FIELDS =
      Set.of(
          "issuerAuthGeneration",
          "accountAuthorityGeneration",
          "tenantAuthorityGeneration",
          "membershipAuthorityGeneration",
          "privateRealmGrantVersions");
  private static final Set<String> OPTIONAL_AUTHORITY_TUPLE_FIELDS =
      Set.of("accountSecurityCutoff");
  private static final Set<String> AUTHORITY_SOURCE_VERSION_FIELDS =
      Set.of("issuerSourceVersion", "accountSourceVersion", "issuanceFenceSourceVersion");
  private static final Set<String> ACCOUNT_IDENTITY_FIELDS =
      Set.of("sourceRowId", "provenance", "sourceNumericId");
  private static final Set<String> OUTBOX_CHECKPOINT_FIELDS =
      Set.of("outboxStreamKey", "outboxSequence", "sourceEventId", "sourceEventDigest");
  private static final Set<String> ZERO_OUTBOX_CHECKPOINT_FIELDS =
      Set.of("outboxStreamKey", "outboxSequence");
  private static final Set<String> ACCOUNT_SECURITY_CUTOFF_FIELDS =
      Set.of("accountAuthorityGeneration", "outboxStreamKey", "outboxSequence");
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private static final String PROFILE = GameSessionAccountDelegationProfile.PROFILE;
  private static final String AUDIENCE = GameSessionAccountDelegationProfile.AUDIENCE;
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final Map<String, Object> fields;
  private final byte[] canonicalBytes;

  private AccountAuthEvidenceBundle(Map<String, Object> fields, byte[] canonicalBytes) {
    this.fields = immutableMap(fields);
    this.canonicalBytes = canonicalBytes.clone();
  }

  /**
   * Encodes the supplied closed value. This factory is not an authenticity boundary; production
   * persistence must derive the values from locked owner readback and independently compare all
   * source rows and event payloads.
   */
  public static AccountAuthEvidenceBundle fromOwnerEvaluation(OwnerEvaluation evaluation) {
    Objects.requireNonNull(evaluation, "Account owner evaluation is required");
    Map<String, Object> fields = fields(evaluation);
    validateFields(fields);
    return canonicalValue(fields);
  }

  /** Strictly parses the exact closed canonical bundle encoding. */
  public static AccountAuthEvidenceBundle parseCanonical(byte[] value) {
    if (value == null || value.length == 0 || value.length > MAX_CANONICAL_BYTES) {
      throw invalid("Account auth-evidence bundle is absent or exceeds its byte bound");
    }
    String text = strictUtf8(value);
    final Map<String, Object> parsed;
    try {
      parsed = JSON.readValue(text, new TypeReference<>() {});
    } catch (RuntimeException ex) {
      throw invalid("Account auth-evidence bundle JSON is malformed");
    }
    validateFields(parsed);
    byte[] canonical = canonicalize(text);
    if (!Arrays.equals(value, canonical)) {
      throw invalid("Account auth-evidence bundle bytes are not canonical");
    }
    return new AccountAuthEvidenceBundle(parsed, canonical);
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  /** Lowercase SHA-256 digest of these exact canonical bytes. */
  public String canonicalSha256() {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }

  public Map<String, Object> fields() {
    return immutableMap(fields);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof AccountAuthEvidenceBundle bundle
            && Arrays.equals(canonicalBytes, bundle.canonicalBytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes);
  }

  @Override
  public String toString() {
    return "AccountAuthEvidenceBundle[redacted]";
  }

  private static Map<String, Object> fields(OwnerEvaluation evaluation) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schema", SCHEMA);
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("bundleVersion", evaluation.bundleReference().bundleVersion());
    ref.put("sourceVersion", evaluation.bundleReference().sourceVersion());
    ref.put("sourceFence", evaluation.bundleReference().sourceFence());
    ref.put("linearization", evaluation.bundleReference().linearization());
    root.put("bundleRef", ref);
    root.put("snapshotIdentity", evaluation.snapshotIdentity());
    root.put("evaluationIdentity", evaluation.evaluationIdentity());
    root.put("issuer", ISSUER);
    root.put("profile", PROFILE);
    root.put("audience", AUDIENCE);

    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("kind", "account");
    scope.put("accountId", evaluation.accountId().toString());
    scope.put("tenantIds", List.of());
    root.put("scope", scope);

    OperationIdentity operation = evaluation.operation();
    Map<String, Object> operationFields = new LinkedHashMap<>();
    operationFields.put("operationId", operation.operationId().toString());
    operationFields.put("requestId", operation.requestId().toString());
    operationFields.put("requestDigest", operation.requestDigest());
    operationFields.put("callerWorkload", operation.callerWorkload());
    operationFields.put("callerContextId", operation.callerContextId().toString());
    operationFields.put("accountId", operation.accountId().toString());
    root.put("operation", operationFields);

    TokenIdentity token = evaluation.token();
    Map<String, Object> tokenFields = new LinkedHashMap<>();
    tokenFields.put("jti", token.jti().toString());
    tokenFields.put("tokenGeneration", Long.toString(token.tokenGeneration()));
    tokenFields.put("iat", token.issuedAtEpochSecond());
    tokenFields.put("nbf", token.notBeforeEpochSecond());
    tokenFields.put("exp", token.expiresAtEpochSecond());
    root.put("tokenIdentity", tokenFields);

    root.put("authorityTuple", deepCopyMap(evaluation.authorityTuple()));
    root.put("issuanceFence", decimal(evaluation.issuanceFence()));
    root.put("membershipVersion", Map.of());

    AuthoritySourceVersions sourceVersions = evaluation.authoritySourceVersions();
    Map<String, Object> versions = new LinkedHashMap<>();
    versions.put("issuerSourceVersion", decimal(sourceVersions.issuerSourceVersion()));
    versions.put("accountSourceVersion", decimal(sourceVersions.accountSourceVersion()));
    versions.put(
        "issuanceFenceSourceVersion", decimal(sourceVersions.issuanceFenceSourceVersion()));
    root.put("authoritySourceVersions", versions);

    AccountIdentitySource accountSource = evaluation.accountIdentitySource();
    Map<String, Object> accountIdentity = new LinkedHashMap<>();
    accountIdentity.put("sourceRowId", decimal(accountSource.sourceRowId()));
    accountIdentity.put("provenance", accountSource.provenance().name());
    accountIdentity.put("sourceNumericId", decimal(accountSource.sourceNumericId()));
    root.put("accountIdentitySource", accountIdentity);

    List<Map<String, Object>> checkpoints = new ArrayList<>();
    for (OutboxCheckpoint checkpoint : evaluation.outboxCheckpoints()) {
      Map<String, Object> checkpointFields = new LinkedHashMap<>();
      checkpointFields.put("outboxStreamKey", checkpoint.outboxStreamKey());
      checkpointFields.put("outboxSequence", nonnegativeDecimal(checkpoint.outboxSequence()));
      if (checkpoint.outboxSequence() > 0L) {
        checkpointFields.put("sourceEventId", checkpoint.sourceEventId());
        checkpointFields.put("sourceEventDigest", checkpoint.sourceEventDigest());
      }
      checkpoints.add(checkpointFields);
    }
    root.put("outboxCheckpoints", checkpoints);
    return root;
  }

  private static void validateFields(Map<String, Object> root) {
    requireExactFields(root, ROOT_FIELDS, "bundle");
    requireText(root.get("schema"), SCHEMA, "schema");
    requireText(root.get("issuer"), ISSUER, "issuer");
    requireText(root.get("profile"), PROFILE, "profile");
    requireText(root.get("audience"), AUDIENCE, "audience");
    requireHash(root.get("snapshotIdentity"), "snapshotIdentity");
    requireHash(root.get("evaluationIdentity"), "evaluationIdentity");

    Map<String, Object> ref = requireObject(root.get("bundleRef"), "bundleRef");
    requireExactFields(ref, BUNDLE_REF_FIELDS, "bundleRef");
    requirePositiveDecimal(ref.get("bundleVersion"), "bundleVersion");
    requirePositiveXid8(ref.get("sourceVersion"), "sourceVersion");
    requirePositiveDecimal(ref.get("sourceFence"), "sourceFence");
    requirePositiveXid8(ref.get("linearization"), "linearization");

    Map<String, Object> scope = requireObject(root.get("scope"), "scope");
    requireExactFields(scope, SCOPE_FIELDS, "scope");
    requireText(scope.get("kind"), "account", "scope.kind");
    String accountId = requireUuid(scope.get("accountId"), "scope.accountId");
    requireEmptyList(scope.get("tenantIds"), "scope.tenantIds");

    Map<String, Object> operation = requireObject(root.get("operation"), "operation");
    requireExactFields(operation, OPERATION_FIELDS, "operation");
    requireV4Uuid(operation.get("operationId"), "operation.operationId");
    requireV4Uuid(operation.get("requestId"), "operation.requestId");
    requireHash(operation.get("requestDigest"), "operation.requestDigest");
    String caller =
        requireBoundedText(operation.get("callerWorkload"), "operation.callerWorkload", 256);
    if (!WORKLOAD.matcher(caller).matches()) {
      throw invalid(
          "Account auth-evidence bundle caller workload is not the exact Game Session peer");
    }
    requireV4Uuid(operation.get("callerContextId"), "operation.callerContextId");
    if (!accountId.equals(requireUuid(operation.get("accountId"), "operation.accountId"))) {
      throw invalid("Account auth-evidence bundle operation is scoped to another Account");
    }

    Map<String, Object> token = requireObject(root.get("tokenIdentity"), "tokenIdentity");
    requireExactFields(token, TOKEN_IDENTITY_FIELDS, "tokenIdentity");
    requireV4Uuid(token.get("jti"), "tokenIdentity.jti");
    long tokenGeneration = parsePositiveDecimal(token.get("tokenGeneration"), "tokenGeneration");
    long issuedAt = requirePositiveInteger(token.get("iat"), "iat");
    long notBefore = requirePositiveInteger(token.get("nbf"), "nbf");
    long expiresAt = requirePositiveInteger(token.get("exp"), "exp");
    if (tokenGeneration != 1L
        || notBefore > issuedAt
        || expiresAt <= issuedAt
        || expiresAt - issuedAt > GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS) {
      throw invalid("Account auth-evidence bundle token identity bounds are invalid");
    }

    Map<String, Object> tuple = requireObject(root.get("authorityTuple"), "authorityTuple");
    requireRequiredAndOptionalFields(
        tuple, AUTHORITY_TUPLE_FIELDS, OPTIONAL_AUTHORITY_TUPLE_FIELDS, "authorityTuple");
    parsePositiveDecimal(tuple.get("issuerAuthGeneration"), "issuerAuthGeneration");
    parsePositiveDecimal(tuple.get("accountAuthorityGeneration"), "accountAuthorityGeneration");
    requireEmptyObject(tuple.get("tenantAuthorityGeneration"), "tenantAuthorityGeneration");
    requireEmptyObject(tuple.get("membershipAuthorityGeneration"), "membershipAuthorityGeneration");
    requireEmptyList(tuple.get("privateRealmGrantVersions"), "privateRealmGrantVersions");
    requirePositiveDecimal(root.get("issuanceFence"), "issuanceFence");
    requireEmptyObject(root.get("membershipVersion"), "membershipVersion");

    Map<String, Object> versions =
        requireObject(root.get("authoritySourceVersions"), "authoritySourceVersions");
    requireExactFields(versions, AUTHORITY_SOURCE_VERSION_FIELDS, "authoritySourceVersions");
    for (String field : AUTHORITY_SOURCE_VERSION_FIELDS) {
      requirePositiveDecimal(versions.get(field), field);
    }

    Map<String, Object> identitySource =
        requireObject(root.get("accountIdentitySource"), "accountIdentitySource");
    requireExactFields(identitySource, ACCOUNT_IDENTITY_FIELDS, "accountIdentitySource");
    long sourceRowId = parsePositiveDecimal(identitySource.get("sourceRowId"), "sourceRowId");
    long sourceNumericId =
        parsePositiveDecimal(identitySource.get("sourceNumericId"), "sourceNumericId");
    if (sourceRowId != sourceNumericId) {
      throw invalid("Account auth-evidence bundle Account source identity is contradictory");
    }
    AccountIdentityProvenance provenance =
        AccountIdentityProvenance.fromStorageValue(
            requireBoundedText(identitySource.get("provenance"), "provenance", 40));
    if (!AccountIdentityProvenance.isAccepted(provenance)) {
      throw invalid("Account auth-evidence bundle Account provenance is not accepted");
    }

    List<?> checkpoints = requireList(root.get("outboxCheckpoints"), "outboxCheckpoints");
    if (checkpoints.size() != 2) {
      throw invalid("Account auth-evidence bundle requires exact issuer and Account checkpoints");
    }
    String issuerStream = "account:auth-authority:v1:issuer/" + ISSUER;
    String accountStream = "account:auth-authority:v1:account/" + accountId;
    Map<String, Map<String, Object>> byStream = new LinkedHashMap<>();
    for (Object item : checkpoints) {
      Map<String, Object> checkpoint = requireObject(item, "outbox checkpoint");
      String stream =
          requireBoundedText(checkpoint.get("outboxStreamKey"), "outboxStreamKey", 2048);
      if (byStream.put(stream, checkpoint) != null) {
        throw invalid("Account auth-evidence bundle has a duplicate outbox stream");
      }
      long sequence = parseNonnegativeDecimal(checkpoint.get("outboxSequence"), "outboxSequence");
      if (sequence == 0L) {
        requireExactFields(checkpoint, ZERO_OUTBOX_CHECKPOINT_FIELDS, "zero outbox baseline");
      } else {
        requireExactFields(checkpoint, OUTBOX_CHECKPOINT_FIELDS, "positive outbox checkpoint");
        requireBoundedText(checkpoint.get("sourceEventId"), "sourceEventId", 512);
        requireEventDigest(checkpoint.get("sourceEventDigest"), "sourceEventDigest");
      }
    }
    if (!byStream.keySet().equals(Set.of(issuerStream, accountStream))) {
      throw invalid("Account auth-evidence bundle outbox checkpoint scope is not exact");
    }
    List<String> canonicalOrder = new ArrayList<>(byStream.keySet());
    canonicalOrder.sort(Comparator.naturalOrder());
    List<String> suppliedOrder = new ArrayList<>();
    for (Object item : checkpoints) {
      Map<String, Object> checkpoint = requireObject(item, "outbox checkpoint");
      suppliedOrder.add(
          requireBoundedText(checkpoint.get("outboxStreamKey"), "outboxStreamKey", 2048));
    }
    if (!canonicalOrder.equals(suppliedOrder)) {
      throw invalid("Account auth-evidence bundle checkpoints are not in canonical stream order");
    }
    if (tuple.containsKey("accountSecurityCutoff")) {
      Map<String, Object> cutoff =
          requireObject(tuple.get("accountSecurityCutoff"), "authorityTuple.accountSecurityCutoff");
      requireExactFields(cutoff, ACCOUNT_SECURITY_CUTOFF_FIELDS, "accountSecurityCutoff");
      long cutoffGeneration =
          parsePositiveDecimal(
              cutoff.get("accountAuthorityGeneration"),
              "authorityTuple.accountSecurityCutoff.accountAuthorityGeneration");
      requirePositiveDecimal(
          cutoff.get("outboxSequence"), "authorityTuple.accountSecurityCutoff.outboxSequence");
      String cutoffStream =
          requireBoundedText(
              cutoff.get("outboxStreamKey"),
              "authorityTuple.accountSecurityCutoff.outboxStreamKey",
              2048);
      if (!accountStream.equals(cutoffStream)
          || cutoffGeneration
              != parsePositiveDecimal(
                  tuple.get("accountAuthorityGeneration"), "accountAuthorityGeneration")) {
        throw invalid("Account auth-evidence security cutoff is outside the exact Account scope");
      }
      Map<String, Object> accountCheckpoint = byStream.get(accountStream);
      if (parseNonnegativeDecimal(
              accountCheckpoint.get("outboxSequence"), "account outbox sequence")
          < parsePositiveDecimal(cutoff.get("outboxSequence"), "cutoff outbox sequence")) {
        throw invalid("Account auth-evidence checkpoint does not cover the security cutoff");
      }
    }
    if (!accountId.equals(requireUuid(scope.get("accountId"), "scope.accountId"))) {
      throw invalid("Account auth-evidence bundle Account scope is inconsistent");
    }
  }

  private static AccountAuthEvidenceBundle canonicalValue(Map<String, Object> fields) {
    try {
      String json = JSON.writeValueAsString(fields);
      byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(json);
      if (bytes.length == 0 || bytes.length > MAX_CANONICAL_BYTES) {
        throw invalid("Account auth-evidence bundle exceeds its canonical byte bound");
      }
      return new AccountAuthEvidenceBundle(fields, bytes);
    } catch (IOException | RuntimeException ex) {
      if (ex instanceof InvalidBundleException invalid) throw invalid;
      throw invalid("Account auth-evidence bundle canonicalization failed");
    }
  }

  private static Map<String, Object> immutableMap(Map<String, Object> input) {
    return Collections.unmodifiableMap(deepCopyMap(input));
  }

  private static Map<String, Object> deepCopyMap(Map<String, ?> input) {
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<String, ?> entry : input.entrySet()) {
      Object value = entry.getValue();
      if (value == null) throw invalid("Account auth-evidence bundle contains null");
      if (value instanceof Map<?, ?> map) {
        Map<String, Object> child = new LinkedHashMap<>();
        for (Map.Entry<?, ?> item : map.entrySet()) {
          if (!(item.getKey() instanceof String key)) {
            throw invalid("Account auth-evidence bundle object key is not text");
          }
          child.put(key, item.getValue());
        }
        copy.put(entry.getKey(), immutableMap(child));
      } else if (value instanceof List<?> list) {
        List<Object> child = new ArrayList<>(list.size());
        for (Object item : list) {
          if (item == null) throw invalid("Account auth-evidence bundle contains null");
          if (item instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> nestedItem : map.entrySet()) {
              if (!(nestedItem.getKey() instanceof String key)) {
                throw invalid("Account auth-evidence bundle object key is not text");
              }
              nested.put(key, nestedItem.getValue());
            }
            child.add(immutableMap(nested));
          } else {
            child.add(item);
          }
        }
        copy.put(entry.getKey(), List.copyOf(child));
      } else {
        copy.put(entry.getKey(), value);
      }
    }
    return copy;
  }

  private static Map<String, Object> requireObject(Object value, String field) {
    if (!(value instanceof Map<?, ?> input)) throw invalid(field + " must be an object");
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : input.entrySet()) {
      if (!(entry.getKey() instanceof String key) || entry.getValue() == null) {
        throw invalid(field + " has a non-text key or null value");
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static List<?> requireList(Object value, String field) {
    if (!(value instanceof List<?> list)) throw invalid(field + " must be an array");
    return list;
  }

  private static void requireExactFields(
      Map<String, Object> value, Set<String> expected, String field) {
    if (!value.keySet().equals(expected) || value.values().stream().anyMatch(Objects::isNull)) {
      throw invalid(field + " has missing, unknown, or null fields");
    }
  }

  private static void requireRequiredAndOptionalFields(
      Map<String, Object> value, Set<String> required, Set<String> optional, String field) {
    if (!value.keySet().containsAll(required)
        || !union(required, optional).containsAll(value.keySet())
        || value.values().stream().anyMatch(Objects::isNull)) {
      throw invalid(field + " has missing, unknown, or null fields");
    }
  }

  private static Set<String> union(Set<String> required, Set<String> optional) {
    java.util.HashSet<String> values = new java.util.HashSet<>(required);
    values.addAll(optional);
    return Set.copyOf(values);
  }

  private static void requireText(Object value, String expected, String field) {
    if (!(value instanceof String text) || !text.equals(expected)) {
      throw invalid(field + " is not the exact Account delegation value");
    }
  }

  private static String requireBoundedText(Object value, String field, int maxLength) {
    if (!(value instanceof String text)
        || text.isEmpty()
        || text.length() > maxLength
        || !text.equals(text.strip())
        || text.chars().anyMatch(Character::isISOControl)) {
      throw invalid(field + " is absent, unbounded, or malformed");
    }
    return text;
  }

  private static String requireUuid(Object value, String field) {
    String text = requireBoundedText(value, field, 36);
    if (!UUID_TEXT.matcher(text).matches()) throw invalid(field + " is not a canonical UUID");
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text) || parsed.equals(new UUID(0L, 0L))) {
        throw invalid(field + " is not a nonzero canonical UUID");
      }
    } catch (IllegalArgumentException ex) {
      throw invalid(field + " is not a canonical UUID");
    }
    return text;
  }

  private static void requireHash(Object value, String field) {
    if (!(value instanceof String text) || !SHA256.matcher(text).matches()) {
      throw invalid(field + " must be lowercase SHA-256 hex");
    }
  }

  private static void requireEventDigest(Object value, String field) {
    if (!(value instanceof String text) || !EVENT_DIGEST.matcher(text).matches()) {
      throw invalid(field + " must be a canonical Account event digest");
    }
  }

  private static String requireV4Uuid(Object value, String field) {
    String text = requireBoundedText(value, field, 36);
    if (!V4_UUID_TEXT.matcher(text).matches()) {
      throw invalid(field + " is not a canonical high-entropy UUID");
    }
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text) || parsed.equals(new UUID(0L, 0L))) {
        throw invalid(field + " is not a nonzero canonical UUID");
      }
    } catch (IllegalArgumentException ex) {
      throw invalid(field + " is not a canonical UUID");
    }
    return text;
  }

  private static long requirePositiveInteger(Object value, String field) {
    if (!(value instanceof Number number)) throw invalid(field + " must be a JSON integer");
    String text = number.toString();
    if (!POSITIVE_DECIMAL.matcher(text).matches())
      throw invalid(field + " is not a canonical integer");
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException ex) {
      throw invalid(field + " is outside the supported bound");
    }
  }

  private static void requirePositiveDecimal(Object value, String field) {
    parsePositiveDecimal(value, field);
  }

  private static long parsePositiveDecimal(Object value, String field) {
    if (!(value instanceof String text) || !POSITIVE_DECIMAL.matcher(text).matches()) {
      throw invalid(field + " must be a canonical positive decimal string");
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException ex) {
      throw invalid(field + " is outside the supported bound");
    }
  }

  private static long parseNonnegativeDecimal(Object value, String field) {
    if (!(value instanceof String text) || !NONNEGATIVE_DECIMAL.matcher(text).matches()) {
      throw invalid(field + " must be a canonical non-negative decimal string");
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException ex) {
      throw invalid(field + " is outside the supported bound");
    }
  }

  private static void requirePositiveXid8(Object value, String field) {
    if (!(value instanceof String text) || !POSITIVE_XID8.matcher(text).matches()) {
      throw invalid(field + " must be a positive canonical xid8 decimal string");
    }
    BigInteger xact = new BigInteger(text);
    if (xact.signum() <= 0 || xact.bitLength() > 64) {
      throw invalid(field + " is outside the PostgreSQL xid8 bound");
    }
  }

  private static String decimal(long value) {
    if (value <= 0L) throw invalid("Account auth-evidence bundle counter must be positive");
    return Long.toString(value);
  }

  private static String nonnegativeDecimal(long value) {
    if (value < 0L) throw invalid("Account auth-evidence checkpoint cannot be negative");
    return Long.toString(value);
  }

  private static void requireEmptyObject(Object value, String field) {
    Map<String, Object> object = requireObject(value, field);
    if (!object.isEmpty()) throw invalid(field + " must be an empty object for this profile");
  }

  private static void requireEmptyList(Object value, String field) {
    if (!(value instanceof List<?> list) || !list.isEmpty()) {
      throw invalid(field + " must be an empty array for this profile");
    }
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException ex) {
      throw invalid("Account auth-evidence bundle is not valid UTF-8");
    }
  }

  private static byte[] canonicalize(String value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(value);
    } catch (IOException | RuntimeException ex) {
      throw invalid("Account auth-evidence bundle canonicalization failed");
    }
  }

  private static InvalidBundleException invalid(String message) {
    return new InvalidBundleException(message);
  }

  /** Values captured from locked Account source rows; this record itself is not authority. */
  public record OwnerEvaluation(
      BundleReference bundleReference,
      String snapshotIdentity,
      String evaluationIdentity,
      UUID accountId,
      OperationIdentity operation,
      TokenIdentity token,
      Map<String, Object> authorityTuple,
      long issuanceFence,
      AuthoritySourceVersions authoritySourceVersions,
      AccountIdentitySource accountIdentitySource,
      List<OutboxCheckpoint> outboxCheckpoints) {
    public OwnerEvaluation {
      Objects.requireNonNull(bundleReference, "Bundle reference is required");
      requireHash(snapshotIdentity, "snapshotIdentity");
      requireHash(evaluationIdentity, "evaluationIdentity");
      Objects.requireNonNull(accountId, "Account ID is required");
      if (issuanceFence <= 0L) {
        throw invalid("Account issuance fence must be positive");
      }
      Objects.requireNonNull(operation, "Operation identity is required");
      Objects.requireNonNull(token, "Token identity is required");
      authorityTuple = immutableMap(authorityTuple);
      Objects.requireNonNull(authoritySourceVersions, "Authority source versions are required");
      Objects.requireNonNull(accountIdentitySource, "Account identity source is required");
      outboxCheckpoints = List.copyOf(outboxCheckpoints);
      if (outboxCheckpoints.stream().anyMatch(Objects::isNull)) {
        throw invalid("Account auth-evidence bundle has a null outbox checkpoint");
      }
      if (!accountId.equals(operation.accountId())) {
        throw invalid("Account auth-evidence bundle operation scope is contradictory");
      }
    }

    @Override
    public Map<String, Object> authorityTuple() {
      return immutableMap(authorityTuple);
    }
  }

  public record BundleReference(
      String bundleVersion, String sourceVersion, String sourceFence, String linearization) {
    public BundleReference {
      requirePositiveDecimal(bundleVersion, "bundleVersion");
      requirePositiveXid8(sourceVersion, "sourceVersion");
      requirePositiveDecimal(sourceFence, "sourceFence");
      requirePositiveXid8(linearization, "linearization");
    }
  }

  public record OperationIdentity(
      UUID operationId,
      UUID requestId,
      String requestDigest,
      String callerWorkload,
      UUID callerContextId,
      UUID accountId) {
    public OperationIdentity {
      requireV4Uuid(operationId == null ? null : operationId.toString(), "operationId");
      requireV4Uuid(requestId == null ? null : requestId.toString(), "requestId");
      requireHash(requestDigest, "requestDigest");
      if (callerWorkload == null || !WORKLOAD.matcher(callerWorkload).matches()) {
        throw invalid("Account auth-evidence caller is not the exact Game Session workload");
      }
      requireV4Uuid(callerContextId == null ? null : callerContextId.toString(), "callerContextId");
      requireUuid(accountId == null ? null : accountId.toString(), "accountId");
    }
  }

  public record TokenIdentity(
      UUID jti,
      long tokenGeneration,
      long issuedAtEpochSecond,
      long notBeforeEpochSecond,
      long expiresAtEpochSecond) {
    public TokenIdentity {
      requireV4Uuid(jti == null ? null : jti.toString(), "jti");
      if (tokenGeneration != 1L
          || issuedAtEpochSecond <= 0L
          || notBeforeEpochSecond <= 0L
          || notBeforeEpochSecond > issuedAtEpochSecond
          || expiresAtEpochSecond <= issuedAtEpochSecond
          || expiresAtEpochSecond - issuedAtEpochSecond
              > GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS) {
        throw invalid("Account auth-evidence token identity is invalid");
      }
    }
  }

  public record AuthoritySourceVersions(
      long issuerSourceVersion, long accountSourceVersion, long issuanceFenceSourceVersion) {
    public AuthoritySourceVersions {
      if (issuerSourceVersion <= 0L
          || accountSourceVersion <= 0L
          || issuanceFenceSourceVersion <= 0L) {
        throw invalid("Account authority source versions must be positive");
      }
    }
  }

  public record AccountIdentitySource(
      long sourceRowId, AccountIdentityProvenance provenance, long sourceNumericId) {
    public AccountIdentitySource {
      if (sourceRowId <= 0L || sourceNumericId != sourceRowId) {
        throw invalid("Account UUID source row identity is contradictory");
      }
      Objects.requireNonNull(provenance, "Account UUID provenance is required");
      if (!AccountIdentityProvenance.isAccepted(provenance)) {
        throw invalid("Account UUID provenance is not accepted");
      }
    }
  }

  public record OutboxCheckpoint(
      String outboxStreamKey, long outboxSequence, String sourceEventId, String sourceEventDigest) {
    public OutboxCheckpoint {
      if (outboxStreamKey == null || outboxStreamKey.isBlank() || outboxStreamKey.length() > 2048) {
        throw invalid("Account authority outbox stream key is invalid");
      }
      if (outboxSequence < 0L) {
        throw invalid("Account outbox checkpoint sequence cannot be negative");
      }
      if (outboxSequence == 0L) {
        if (sourceEventId != null || sourceEventDigest != null) {
          throw invalid("An explicit zero baseline cannot claim an event identity");
        }
      } else {
        if (sourceEventId == null || sourceEventId.isBlank() || sourceEventId.length() > 512) {
          throw invalid("Account outbox checkpoint event ID is invalid");
        }
        requireEventDigest(sourceEventDigest, "sourceEventDigest");
      }
    }
  }

  public static final class InvalidBundleException extends IllegalArgumentException {
    public InvalidBundleException(String message) {
      super(message);
    }
  }
}
