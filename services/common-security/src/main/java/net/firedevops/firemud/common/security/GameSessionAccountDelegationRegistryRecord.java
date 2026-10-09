package net.firedevops.firemud.common.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict finite-bounded codec for initial and distinct public-production bound delegation
 * candidates.
 *
 * <p>Pending records are non-authorizing. This codec checks JOSE/claim shape and exact Account
 * snapshot equality, but does not verify the JWS signature or establish signer/currentness proof.
 * The signing and Account evidence owners must do that before any active transition is usable.
 */
public final class GameSessionAccountDelegationRegistryRecord {
  private static final int SCHEMA_VERSION = 2;
  private static final int PENDING_REGISTRY_VERSION = 1;
  private static final int ACTIVE_REGISTRY_VERSION = 2;
  private static final String PENDING = "pending";
  private static final String ACTIVE = "active";
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern POSITIVE_LINEARIZATION = Pattern.compile("[1-9][0-9]{0,19}");
  private static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Set<String> TOKEN_CLAIMS =
      Set.of(
          "iss",
          "sub",
          "jti",
          "accountId",
          "aud",
          "iat",
          "nbf",
          "exp",
          "tokenGeneration",
          "authorityTuple",
          "membershipVersion",
          "issuanceFence");
  private static final Set<String> HEADER_REQUIRED = Set.of("alg", "kid");
  private static final Set<String> HEADER_ALLOWED = Set.of("alg", "kid", "typ");
  private static final Set<String> RECORD_FIELDS =
      Set.of(
          "schemaVersion",
          "registryVersion",
          "tokenHash",
          "kid",
          "signerGeneration",
          "accountId",
          "issuer",
          "profile",
          "type",
          "audience",
          "jti",
          "iat",
          "nbf",
          "exp",
          "tokenGeneration",
          "operationId",
          "requestId",
          "requestDigest",
          "state",
          "authorityTuple",
          "membershipVersion",
          "issuanceFence",
          "authoritySourceVersions",
          "authEvidenceBundle");
  private static final Set<String> AUTHORITY_TUPLE_FIELDS =
      Set.of(
          "issuerAuthGeneration",
          "accountAuthorityGeneration",
          "tenantAuthorityGeneration",
          "membershipAuthorityGeneration",
          "privateRealmGrantVersions");
  private static final Set<String> AUTHORITY_TUPLE_FIELDS_WITH_ACCOUNT_CUTOFF =
      Set.of(
          "issuerAuthGeneration",
          "accountAuthorityGeneration",
          "tenantAuthorityGeneration",
          "membershipAuthorityGeneration",
          "privateRealmGrantVersions",
          "accountSecurityCutoff");
  private static final Set<String> ACCOUNT_SECURITY_CUTOFF_FIELDS =
      Set.of("accountAuthorityGeneration", "outboxStreamKey", "outboxSequence");
  private static final Set<String> SOURCE_VERSION_FIELDS =
      Set.of("issuerSourceVersion", "accountSourceVersion", "issuanceFenceSourceVersion");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final Map<String, Object> fields;

  private GameSessionAccountDelegationRegistryRecord(Map<String, Object> fields) {
    this.fields = immutableMap(fields);
  }

  /** Creates the distinct first public-production tenant-bound pending record. */
  public static GameSessionAccountDelegationRegistryRecord fromAccountSignedBoundCompactJwt(
      String compactJwt,
      String signerGeneration,
      IssuanceBinding binding,
      AccountAuthoritySnapshot account,
      EvidenceBundleReference evidence,
      String tenantId,
      Map<String, Object> exactTuple,
      Map<String, String> exactMembershipVersion,
      long now,
      long maxBytes) {
    GameSessionAccountDelegationProfile.requirePublicTenantBoundAuthority(
        tenantId, exactTuple, exactMembershipVersion);
    return fromCandidate(
        compactJwt,
        signerGeneration,
        binding,
        account,
        evidence,
        now,
        maxBytes,
        new BoundShape(tenantId, exactTuple, exactMembershipVersion));
  }

  private record BoundShape(
      String tenantId, Map<String, Object> tuple, Map<String, String> membershipVersion) {}

  /** Validates the exact signed-token candidate and creates only its pending registry record. */
  public static GameSessionAccountDelegationRegistryRecord fromAccountSignedCompactJwt(
      String compactJwt,
      String signerGeneration,
      IssuanceBinding binding,
      AccountAuthoritySnapshot snapshot,
      EvidenceBundleReference evidenceBundleReference,
      long nowEpochSecond,
      long maxRecordBytes) {
    return fromCandidate(
        compactJwt,
        signerGeneration,
        binding,
        snapshot,
        evidenceBundleReference,
        nowEpochSecond,
        maxRecordBytes,
        null);
  }

  private static GameSessionAccountDelegationRegistryRecord fromCandidate(
      String compactJwt,
      String signerGeneration,
      IssuanceBinding binding,
      AccountAuthoritySnapshot snapshot,
      EvidenceBundleReference evidenceBundleReference,
      long nowEpochSecond,
      long maxRecordBytes,
      BoundShape bound) {
    Objects.requireNonNull(compactJwt, "Compact Account JWT is required");
    Objects.requireNonNull(binding, "Durable issuance binding is required");
    Objects.requireNonNull(snapshot, "Locked Account authority snapshot is required");
    Objects.requireNonNull(
        evidenceBundleReference, "Persisted Account evidence bundle is required");
    requirePositiveDecimal(signerGeneration, "signerGeneration");
    requirePositive(nowEpochSecond, "nowEpochSecond");
    requireBound(
        maxRecordBytes,
        GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
        "maxRecordBytes");

    CompactToken compact = parseCompactJwt(compactJwt);
    Map<String, Object> claims = compact.claims();
    requireExactFields(claims, fieldsFor(TOKEN_CLAIMS, bound != null), "JWT claims");
    String issuer = requireText(claims.get("iss"), "iss", 256);
    String subject = requireCanonicalUuid(claims.get("sub"), "sub");
    String accountId = requireCanonicalUuid(claims.get("accountId"), "accountId");
    String jti = requireCanonicalUuid(claims.get("jti"), "jti");
    String audience = requireText(claims.get("aud"), "aud", 128);
    long issuedAt = requirePositiveInteger(claims.get("iat"), "iat");
    long notBefore = requirePositiveInteger(claims.get("nbf"), "nbf");
    long expiresAt = requirePositiveInteger(claims.get("exp"), "exp");
    String tokenGeneration =
        requirePositiveDecimal(claims.get("tokenGeneration"), "tokenGeneration");
    String issuanceFence = requirePositiveDecimal(claims.get("issuanceFence"), "issuanceFence");

    requireEqual(GameSessionAccountDelegationProfile.ISSUER, issuer, "Account issuer mismatch");
    requireEqual(GameSessionAccountDelegationProfile.AUDIENCE, audience, "audience mismatch");
    requireEqual(snapshot.accountId().toString(), accountId, "Account UUID mismatch");
    requireEqual(subject, accountId, "sub and accountId mismatch");
    requireEqual(binding.accountId(), accountId, "durable operation account mismatch");
    requireEqual("1", tokenGeneration, "initial delegation tokenGeneration must be one");
    requireEqual(
        Long.toString(snapshot.issuanceFence()), issuanceFence, "Account issuance fence mismatch");
    validateTimeWindow(issuedAt, notBefore, expiresAt, nowEpochSecond);
    if (bound == null) {
      validateAuthorityTuple(
          claims.get("authorityTuple"), claims.get("membershipVersion"), snapshot);
    } else {
      GameSessionAccountDelegationJwtProfileValidator.validatePublicTenantBoundClaims(claims);
      requireEqual(bound.tenantId(), claims.get("tenantId"), "bound tenant mismatch");
      requireEqual(bound.tuple(), claims.get("authorityTuple"), "bound tuple mismatch");
      requireEqual(
          bound.membershipVersion(),
          claims.get("membershipVersion"),
          "independent membership version mismatch");
      requireEqual(
          Long.toString(snapshot.issuerGeneration()),
          bound.tuple().get("issuerAuthGeneration"),
          "issuer mismatch");
      requireEqual(
          Long.toString(snapshot.accountGeneration()),
          bound.tuple().get("accountAuthorityGeneration"),
          "account mismatch");
      if (!Objects.equals(
          snapshot.accountSecurityCutoff().map(value -> value.toMap()).orElse(null),
          bound.tuple().get("accountSecurityCutoff"))) throw invalid("Account cutoff mismatch");
    }

    Map<String, Object> record = new LinkedHashMap<>();
    record.put("schemaVersion", SCHEMA_VERSION);
    record.put("registryVersion", PENDING_REGISTRY_VERSION);
    record.put("tokenHash", sha256(compactJwt.getBytes(StandardCharsets.US_ASCII)));
    record.put("kid", compact.keyId());
    record.put("signerGeneration", signerGeneration);
    record.put("accountId", accountId);
    if (bound != null) record.put("tenantId", bound.tenantId());
    record.put("issuer", issuer);
    record.put("profile", GameSessionAccountDelegationProfile.PROFILE);
    record.put("type", GameSessionAccountDelegationProfile.TYPE);
    record.put("audience", audience);
    record.put("jti", jti);
    record.put("iat", issuedAt);
    record.put("nbf", notBefore);
    record.put("exp", expiresAt);
    record.put("tokenGeneration", tokenGeneration);
    record.put("operationId", binding.operationId());
    record.put("requestId", binding.requestId());
    record.put("requestDigest", binding.requestDigest());
    record.put("state", PENDING);
    record.put("authorityTuple", claims.get("authorityTuple"));
    record.put("membershipVersion", claims.get("membershipVersion"));
    record.put("issuanceFence", issuanceFence);
    record.put("authoritySourceVersions", snapshot.sourceVersions());
    record.put("authEvidenceBundle", evidenceBundleReference.toMap());

    GameSessionAccountDelegationRegistryRecord result =
        new GameSessionAccountDelegationRegistryRecord(record);
    result.toCanonicalJsonBytes(maxRecordBytes);
    return result;
  }

  /** Decodes a supported canonical registry value without granting it authority. */
  public static GameSessionAccountDelegationRegistryRecord decode(
      byte[] canonicalUtf8, long maxRecordBytes) {
    Objects.requireNonNull(canonicalUtf8, "Registry bytes are required");
    requireBound(
        maxRecordBytes,
        GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
        "maxRecordBytes");
    if (canonicalUtf8.length == 0 || canonicalUtf8.length > maxRecordBytes) {
      throw invalid("Registry record exceeds its byte bound");
    }
    String json = strictUtf8(canonicalUtf8);
    byte[] canonical = canonicalize(json);
    if (!Arrays.equals(canonicalUtf8, canonical)) {
      throw invalid("Registry value is not canonical RFC 8785 UTF-8");
    }
    Map<String, Object> decoded = parseObject(json, "registry record");
    requireExactFields(
        decoded, fieldsFor(RECORD_FIELDS, decoded.containsKey("tenantId")), "registry record");
    validateRecord(decoded);
    return new GameSessionAccountDelegationRegistryRecord(decoded);
  }

  /** Returns canonical RFC 8785 record bytes, subject to both the caller and profile ceilings. */
  public byte[] toCanonicalJsonBytes(long maxRecordBytes) {
    requireBound(
        maxRecordBytes,
        GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
        "maxRecordBytes");
    byte[] canonical = canonicalize(serialize(fields));
    if (canonical.length == 0 || canonical.length > maxRecordBytes) {
      throw invalid("Registry record exceeds its byte bound");
    }
    return canonical;
  }

  public String tokenHash() {
    return textField("tokenHash");
  }

  public String kid() {
    return textField("kid");
  }

  public String signerGeneration() {
    return textField("signerGeneration");
  }

  public String accountId() {
    return textField("accountId");
  }

  public String jti() {
    return textField("jti");
  }

  public String operationId() {
    return textField("operationId");
  }

  public String requestId() {
    return textField("requestId");
  }

  public String requestDigest() {
    return textField("requestDigest");
  }

  public String state() {
    return textField("state");
  }

  public long issuedAtEpochSecond() {
    return requirePositiveInteger(fields.get("iat"), "iat");
  }

  public long notBeforeEpochSecond() {
    return requirePositiveInteger(fields.get("nbf"), "nbf");
  }

  public long issuanceFence() {
    return requirePositiveDecimalLong(fields.get("issuanceFence"), "issuanceFence");
  }

  public long registryVersion() {
    return requirePositiveInteger(fields.get("registryVersion"), "registryVersion");
  }

  public EvidenceBundleReference evidenceBundleReference() {
    Map<String, Object> value =
        requireObject(fields.get("authEvidenceBundle"), "authEvidenceBundle");
    return new EvidenceBundleReference(
        requirePositiveDecimal(value.get("bundleVersion"), "bundleVersion"),
        requirePositiveDecimal(value.get("sourceVersion"), "sourceVersion"),
        requirePositiveDecimal(value.get("sourceFence"), "sourceFence"),
        requirePositiveLinearization(value.get("linearization")),
        requireMatchValue(HASH, value.get("canonicalSha256"), "canonicalSha256"));
  }

  public long expiresAtEpochSecond() {
    return requirePositiveInteger(fields.get("exp"), "exp");
  }

  public Map<String, Object> fields() {
    return immutableMap(fields);
  }

  private String textField(String field) {
    return requireText(fields.get(field), field, 256);
  }

  private static Set<String> fieldsFor(Set<String> initial, boolean bound) {
    if (!bound) return initial;
    var fields = new java.util.HashSet<>(initial);
    fields.add("tenantId");
    return Set.copyOf(fields);
  }

  private static void validateRecord(Map<String, Object> record) {
    requireExactInteger(record.get("schemaVersion"), SCHEMA_VERSION, "schemaVersion");
    long registryVersion = requirePositiveInteger(record.get("registryVersion"), "registryVersion");
    String state = requireText(record.get("state"), "state", 16);
    if (PENDING.equals(state)) {
      if (registryVersion != PENDING_REGISTRY_VERSION) {
        throw invalid("pending registry version mismatch");
      }
    } else if (ACTIVE.equals(state)) {
      if (registryVersion != ACTIVE_REGISTRY_VERSION) {
        throw invalid("active registry version mismatch");
      }
    } else {
      throw invalid("Unsupported registry lifecycle state");
    }
    requireMatch(HASH, record.get("tokenHash"), "tokenHash");
    requireMatch(HASH, record.get("requestDigest"), "requestDigest");
    requireCanonicalUuid(record.get("jti"), "jti");
    requireCanonicalUuid(record.get("operationId"), "operationId");
    requireCanonicalUuid(record.get("requestId"), "requestId");
    requireMatch(KID, record.get("kid"), "kid");
    requirePositiveDecimal(record.get("signerGeneration"), "signerGeneration");
    requireEqual(GameSessionAccountDelegationProfile.ISSUER, record.get("issuer"), "issuer");
    requireEqual(GameSessionAccountDelegationProfile.PROFILE, record.get("profile"), "profile");
    requireEqual(GameSessionAccountDelegationProfile.TYPE, record.get("type"), "type");
    requireEqual(GameSessionAccountDelegationProfile.AUDIENCE, record.get("audience"), "audience");
    String accountId = requireCanonicalUuid(record.get("accountId"), "accountId");
    long issuedAt = requirePositiveInteger(record.get("iat"), "iat");
    long notBefore = requirePositiveInteger(record.get("nbf"), "nbf");
    long expiresAt = requirePositiveInteger(record.get("exp"), "exp");
    requireEqual(
        "1",
        requirePositiveDecimal(record.get("tokenGeneration"), "tokenGeneration"),
        "initial token generation mismatch");
    validateTimeWindow(issuedAt, notBefore, expiresAt, 0L);
    Map<String, Object> tuple = requireObject(record.get("authorityTuple"), "authorityTuple");
    String accountGeneration =
        requirePositiveDecimal(
            tuple.get("accountAuthorityGeneration"), "accountAuthorityGeneration");
    boolean hasCutoff = tuple.containsKey("accountSecurityCutoff");
    if (hasCutoff != !"1".equals(accountGeneration)) {
      throw invalid("Account security cutoff presence does not match the source generation");
    }
    Set<String> tupleFields =
        new java.util.HashSet<>(
            hasCutoff ? AUTHORITY_TUPLE_FIELDS_WITH_ACCOUNT_CUTOFF : AUTHORITY_TUPLE_FIELDS);
    if (record.containsKey("tenantId") && tuple.containsKey("tenantBillingCutoff"))
      tupleFields.add("tenantBillingCutoff");
    requireExactFields(tuple, tupleFields, "authorityTuple");
    requirePositiveDecimal(tuple.get("issuerAuthGeneration"), "issuerAuthGeneration");
    if (record.containsKey("tenantId")) {
      GameSessionAccountDelegationProfile.requirePublicTenantBoundAuthority(
          requireCanonicalUuid(record.get("tenantId"), "tenantId"),
          tuple,
          requireObject(record.get("membershipVersion"), "membershipVersion"));
    } else {
      requireEmptyObject(tuple.get("tenantAuthorityGeneration"), "tenantAuthorityGeneration");
      requireEmptyObject(
          tuple.get("membershipAuthorityGeneration"), "membershipAuthorityGeneration");
    }
    requireEmptyList(tuple.get("privateRealmGrantVersions"), "privateRealmGrantVersions");
    if (hasCutoff) {
      Map<String, Object> cutoff =
          requireObject(tuple.get("accountSecurityCutoff"), "accountSecurityCutoff");
      requireExactFields(cutoff, ACCOUNT_SECURITY_CUTOFF_FIELDS, "accountSecurityCutoff");
      String cutoffGeneration =
          requirePositiveDecimal(cutoff.get("accountAuthorityGeneration"), "cutoff generation");
      String cutoffSequence =
          requirePositiveDecimal(cutoff.get("outboxSequence"), "cutoff outbox sequence");
      String cutoffStream = requireText(cutoff.get("outboxStreamKey"), "cutoff stream", 2048);
      long accountGenerationValue =
          requirePositiveDecimalLong(accountGeneration, "account generation");
      if (!accountGeneration.equals(cutoffGeneration)
          || !Long.toString(accountGenerationValue - 1L).equals(cutoffSequence)
          || !("account:auth-authority:v1:account/" + accountId).equals(cutoffStream)) {
        throw invalid("Account security cutoff scope or sequence is invalid");
      }
    }
    if (!record.containsKey("tenantId"))
      requireEmptyObject(record.get("membershipVersion"), "membershipVersion");
    requirePositiveDecimal(record.get("issuanceFence"), "issuanceFence");
    Map<String, Object> versions =
        requireObject(record.get("authoritySourceVersions"), "authoritySourceVersions");
    requireExactFields(versions, SOURCE_VERSION_FIELDS, "authoritySourceVersions");
    for (String field : SOURCE_VERSION_FIELDS) {
      requirePositiveDecimal(versions.get(field), field);
    }
    Map<String, Object> bundle =
        requireObject(record.get("authEvidenceBundle"), "authEvidenceBundle");
    requireExactFields(
        bundle,
        Set.of("bundleVersion", "sourceVersion", "sourceFence", "linearization", "canonicalSha256"),
        "authEvidenceBundle");
    requirePositiveDecimal(bundle.get("bundleVersion"), "bundleVersion");
    requirePositiveDecimal(bundle.get("sourceVersion"), "sourceVersion");
    requirePositiveDecimal(bundle.get("sourceFence"), "sourceFence");
    requirePositiveLinearization(bundle.get("linearization"));
    requireMatch(HASH, bundle.get("canonicalSha256"), "canonicalSha256");
  }

  private static void validateAuthorityTuple(
      Object tupleValue, Object membershipVersionValue, AccountAuthoritySnapshot snapshot) {
    Map<String, Object> tuple = requireObject(tupleValue, "authorityTuple");
    Set<String> expectedFields =
        snapshot.accountSecurityCutoff().isPresent()
            ? AUTHORITY_TUPLE_FIELDS_WITH_ACCOUNT_CUTOFF
            : AUTHORITY_TUPLE_FIELDS;
    requireExactFields(tuple, expectedFields, "authorityTuple");
    if (canonicalize(serialize(tuple)).length
        > GameSessionAccountDelegationProfile.MAX_AUTHORITY_TUPLE_BYTES) {
      throw invalid("authorityTuple exceeds its finite profile byte bound");
    }
    requireEqual(
        Long.toString(snapshot.issuerGeneration()),
        requirePositiveDecimal(tuple.get("issuerAuthGeneration"), "issuerAuthGeneration"),
        "issuer authority generation mismatch");
    requireEqual(
        Long.toString(snapshot.accountGeneration()),
        requirePositiveDecimal(
            tuple.get("accountAuthorityGeneration"), "accountAuthorityGeneration"),
        "Account authority generation mismatch");
    requireEmptyObject(tuple.get("tenantAuthorityGeneration"), "tenantAuthorityGeneration");
    requireEmptyObject(tuple.get("membershipAuthorityGeneration"), "membershipAuthorityGeneration");
    requireEmptyList(tuple.get("privateRealmGrantVersions"), "privateRealmGrantVersions");
    if (snapshot.accountSecurityCutoff().isPresent()) {
      Map<String, Object> cutoff =
          requireObject(tuple.get("accountSecurityCutoff"), "accountSecurityCutoff");
      requireExactFields(cutoff, ACCOUNT_SECURITY_CUTOFF_FIELDS, "accountSecurityCutoff");
      requirePositiveDecimal(cutoff.get("accountAuthorityGeneration"), "cutoff generation");
      requirePositiveDecimal(cutoff.get("outboxSequence"), "cutoff outbox sequence");
      requireText(cutoff.get("outboxStreamKey"), "cutoff outboxStreamKey", 2048);
      if (!snapshot.accountSecurityCutoff().orElseThrow().toMap().equals(cutoff)) {
        throw invalid("Account security cutoff does not match the source snapshot");
      }
    }
    requireEmptyObject(membershipVersionValue, "membershipVersion");
  }

  private static CompactToken parseCompactJwt(String compactJwt) {
    byte[] compactBytes = compactJwt.getBytes(StandardCharsets.US_ASCII);
    if (compactJwt.length() == 0
        || compactJwt.length() > GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES
        || compactBytes.length != compactJwt.length()) {
      throw invalid("Compact JWT is absent or exceeds its profile byte bound");
    }
    String[] parts = compactJwt.split("\\.", -1);
    if (parts.length != 3 || Arrays.stream(parts).anyMatch(String::isEmpty)) {
      throw invalid("Compact JWT is malformed");
    }
    byte[] headerBytes = decodeBase64Url(parts[0]);
    byte[] claimsBytes = decodeBase64Url(parts[1]);
    decodeBase64Url(parts[2]);
    Map<String, Object> header = parseObject(strictUtf8(headerBytes), "JOSE header");
    Set<String> headerFields = header.keySet();
    if (!HEADER_ALLOWED.containsAll(headerFields) || !headerFields.containsAll(HEADER_REQUIRED)) {
      throw invalid("JOSE header fields are not the exact supported set");
    }
    requireEqual("RS256", header.get("alg"), "Account delegation must use RS256");
    String kid = requireText(header.get("kid"), "kid", 64);
    requireMatch(KID, kid, "kid");
    if (header.containsKey("typ")) requireEqual("JWT", header.get("typ"), "typ");
    Map<String, Object> claims = parseObject(strictUtf8(claimsBytes), "JWT claims");
    return new CompactToken(kid, claims);
  }

  private static void validateTimeWindow(long issuedAt, long notBefore, long expiresAt, long now) {
    long lifetime;
    try {
      lifetime = Math.subtractExact(expiresAt, issuedAt);
    } catch (ArithmeticException ex) {
      throw invalid("JWT time bounds are invalid or expired");
    }
    long latestAllowedIssuedAt = now;
    if (now > 0L) {
      try {
        latestAllowedIssuedAt = Math.addExact(now, 5L);
      } catch (ArithmeticException ex) {
        latestAllowedIssuedAt = Long.MAX_VALUE;
      }
    }
    if (notBefore > issuedAt
        || lifetime <= 0L
        || lifetime > GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS
        || (now > 0L && (issuedAt > latestAllowedIssuedAt || expiresAt <= now))) {
      throw invalid("JWT time bounds are invalid or expired");
    }
  }

  private static byte[] decodeBase64Url(String value) {
    try {
      if (value.indexOf('=') >= 0 || !value.matches("[A-Za-z0-9_-]+"))
        throw invalid("JWT segment is not base64url");
      byte[] decoded = Base64.getUrlDecoder().decode(value);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
        throw invalid("JWT segment has a non-canonical base64url encoding");
      }
      return decoded;
    } catch (IllegalArgumentException ex) {
      if (ex instanceof InvalidRecordException invalid) throw invalid;
      throw invalid("JWT segment is malformed");
    }
  }

  private static Map<String, Object> parseObject(String json, String field) {
    try {
      Map<String, Object> parsed = JSON.readValue(json, new TypeReference<>() {});
      if (parsed == null || parsed.isEmpty()) throw invalid(field + " must be a non-empty object");
      return parsed;
    } catch (RuntimeException ex) {
      if (ex instanceof InvalidRecordException invalid) throw invalid;
      throw invalid(field + " is malformed");
    }
  }

  private static Map<String, Object> requireObject(Object value, String field) {
    if (!(value instanceof Map<?, ?> map)) throw invalid(field + " must be an object");
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach(
        (key, item) -> {
          if (!(key instanceof String name) || item == null || result.put(name, item) != null) {
            throw invalid(field + " contains an invalid member");
          }
        });
    return result;
  }

  private static Map<String, Object> immutableMap(Map<String, Object> input) {
    Map<String, Object> copy = new LinkedHashMap<>();
    input.forEach(
        (key, value) -> {
          if (key == null || value == null) throw invalid("Null registry field is forbidden");
          copy.put(key, deepImmutable(value));
        });
    return Map.copyOf(copy);
  }

  private static Object deepImmutable(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> nested = new LinkedHashMap<>();
      map.forEach(
          (key, item) -> {
            if (!(key instanceof String name) || item == null) {
              throw invalid("Null or invalid nested registry field is forbidden");
            }
            nested.put(name, deepImmutable(item));
          });
      return Map.copyOf(nested);
    }
    if (value instanceof List<?> list) {
      return list.stream().map(GameSessionAccountDelegationRegistryRecord::deepImmutable).toList();
    }
    return value;
  }

  private static void requireExactFields(
      Map<String, Object> value, Set<String> expected, String field) {
    if (!value.keySet().equals(expected)) throw invalid(field + " has missing or unknown fields");
    if (value.values().stream().anyMatch(Objects::isNull)) throw invalid(field + " contains null");
  }

  private static void requireEmptyObject(Object value, String field) {
    if (!(value instanceof Map<?, ?> map) || !map.isEmpty())
      throw invalid(field + " must be an empty object");
  }

  private static void requireEmptyList(Object value, String field) {
    if (!(value instanceof List<?> list) || !list.isEmpty())
      throw invalid(field + " must be an empty list");
  }

  private static long requirePositiveInteger(Object value, String field) {
    if (!(value instanceof Number number)) throw invalid(field + " must be a JSON integer");
    String encoded = number.toString();
    if (!POSITIVE_DECIMAL.matcher(encoded).matches()) {
      throw invalid(field + " must be a bounded positive integer");
    }
    try {
      return Long.parseLong(encoded);
    } catch (NumberFormatException ex) {
      throw invalid(field + " must be a bounded positive integer");
    }
  }

  private static long requirePositiveDecimalLong(Object value, String field) {
    String decimal = requirePositiveDecimal(value, field);
    try {
      return Long.parseLong(decimal);
    } catch (NumberFormatException ex) {
      throw invalid(field + " exceeds the supported range");
    }
  }

  private static long requirePositive(long value, String field) {
    if (value <= 0L) throw invalid(field + " must be positive");
    return value;
  }

  private static String requirePositiveDecimal(Object value, String field) {
    if (!(value instanceof String string) || !POSITIVE_DECIMAL.matcher(string).matches()) {
      throw invalid(field + " must be a canonical positive decimal string");
    }
    try {
      Long.parseLong(string);
      return string;
    } catch (NumberFormatException ex) {
      throw invalid(field + " exceeds the supported range");
    }
  }

  private static String requireCanonicalUuid(Object value, String field) {
    String text = requireText(value, field, 36);
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text) || parsed.equals(new UUID(0L, 0L)))
        throw invalid(field + " is not a canonical UUID");
      return text;
    } catch (IllegalArgumentException ex) {
      if (ex instanceof InvalidRecordException invalid) throw invalid;
      throw invalid(field + " is not a canonical UUID");
    }
  }

  private static String requireText(Object value, String field, int maxLength) {
    if (!(value instanceof String text) || text.isEmpty() || text.length() > maxLength) {
      throw invalid(field + " must be a bounded non-empty string");
    }
    for (int i = 0; i < text.length(); i++) {
      if (Character.isISOControl(text.charAt(i)))
        throw invalid(field + " contains a control character");
    }
    return text;
  }

  private static void requireMatch(Pattern pattern, Object value, String field) {
    if (!(value instanceof String text) || !pattern.matcher(text).matches())
      throw invalid(field + " has an invalid format");
  }

  private static String requireMatchValue(Pattern pattern, Object value, String field) {
    requireMatch(pattern, value, field);
    return (String) value;
  }

  private static String requirePositiveLinearization(Object value) {
    if (!(value instanceof String text) || !POSITIVE_LINEARIZATION.matcher(text).matches()) {
      throw invalid("bundle linearization is malformed");
    }
    return text;
  }

  private static void requireExactInteger(Object value, long expected, String field) {
    if (!(value instanceof Number number)) throw invalid(field + " must be an integer");
    String encoded = number.toString();
    if (!encoded.matches("0|[1-9][0-9]{0,18}")) {
      throw invalid(field + " is unsupported");
    }
    try {
      if (Long.parseLong(encoded) != expected) throw invalid(field + " is unsupported");
    } catch (NumberFormatException ex) {
      throw invalid(field + " is unsupported");
    }
  }

  private static void requireEqual(Object expected, Object actual, String message) {
    if (!Objects.equals(expected, actual)) throw invalid(message);
  }

  private static void requireBound(long value, long maximum, String field) {
    if (value <= 0L || value > maximum)
      throw invalid(field + " is outside the finite profile bound");
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
      throw invalid("JWT/registry JSON is not valid UTF-8");
    }
  }

  private static String serialize(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (RuntimeException ex) {
      throw invalid("Registry JSON could not be serialized");
    }
  }

  private static byte[] canonicalize(String value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(value);
    } catch (IOException | RuntimeException ex) {
      throw invalid("RFC 8785 canonicalization failed");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private static InvalidRecordException invalid(String message) {
    return new InvalidRecordException(message);
  }

  private record CompactToken(String keyId, Map<String, Object> claims) {}

  public record AccountAuthoritySnapshot(
      UUID accountId,
      long issuerGeneration,
      long issuerSourceVersion,
      long accountGeneration,
      long accountSourceVersion,
      long issuanceFence,
      long issuanceFenceSourceVersion,
      Optional<GameSessionAccountDelegationProfile.AccountSecurityCutoff> accountSecurityCutoff) {
    public AccountAuthoritySnapshot {
      Objects.requireNonNull(accountId, "Canonical Account UUID is required");
      Objects.requireNonNull(accountSecurityCutoff, "Account cutoff applicability is required");
      if (accountId.equals(new UUID(0L, 0L))
          || issuerGeneration <= 0L
          || issuerSourceVersion <= 0L
          || accountGeneration <= 0L
          || accountSourceVersion <= 0L
          || issuanceFence <= 0L
          || issuanceFenceSourceVersion <= 0L
          || issuerGeneration != issuerSourceVersion
          || accountGeneration != accountSourceVersion
          || accountGeneration != issuanceFence
          || accountGeneration != issuanceFenceSourceVersion) {
        throw invalid("Durable Account authority snapshot is malformed");
      }
      String expectedStream = "account:auth-authority:v1:account/" + accountId;
      if (accountGeneration == 1L) {
        if (accountSecurityCutoff.isPresent()) {
          throw invalid("Fresh sequence-zero Account authority cannot carry a cutoff");
        }
      } else {
        GameSessionAccountDelegationProfile.AccountSecurityCutoff cutoff =
            accountSecurityCutoff.orElseThrow(
                () -> invalid("Advanced Account authority requires its source cutoff"));
        if (!Long.toString(accountGeneration).equals(cutoff.accountAuthorityGeneration())
            || !expectedStream.equals(cutoff.outboxStreamKey())
            || !Long.toString(accountGeneration - 1L).equals(cutoff.outboxSequence())) {
          throw invalid("Account security cutoff scope or sequence is stale");
        }
      }
    }

    public Map<String, Object> sourceVersions() {
      return Map.of(
          "issuerSourceVersion", Long.toString(issuerSourceVersion),
          "accountSourceVersion", Long.toString(accountSourceVersion),
          "issuanceFenceSourceVersion", Long.toString(issuanceFenceSourceVersion));
    }
  }

  public record IssuanceBinding(
      String operationId, String requestId, String requestDigest, String accountId) {
    public IssuanceBinding {
      requireCanonicalUuid(operationId, "operationId");
      requireCanonicalUuid(requestId, "requestId");
      requireMatch(HASH, requestDigest, "requestDigest");
      requireCanonicalUuid(accountId, "accountId");
    }
  }

  /** Exact immutable V46 bundle reference and digest bound into the registry projection. */
  public record EvidenceBundleReference(
      String bundleVersion,
      String sourceVersion,
      String sourceFence,
      String linearization,
      String canonicalSha256) {
    public EvidenceBundleReference {
      requirePositiveDecimal(bundleVersion, "bundleVersion");
      requirePositiveDecimal(sourceVersion, "sourceVersion");
      requirePositiveDecimal(sourceFence, "sourceFence");
      requirePositiveLinearization(linearization);
      requireMatch(HASH, canonicalSha256, "canonicalSha256");
    }

    public Map<String, Object> toMap() {
      return Map.of(
          "bundleVersion", bundleVersion,
          "sourceVersion", sourceVersion,
          "sourceFence", sourceFence,
          "linearization", linearization,
          "canonicalSha256", canonicalSha256);
    }
  }

  public static final class InvalidRecordException extends IllegalArgumentException {
    public InvalidRecordException(String message) {
      super(message);
    }
  }
}

