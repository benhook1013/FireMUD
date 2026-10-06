package net.firedevops.firemud.accountservice.service.controlui;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.ActualClaimMaps;
import net.firedevops.firemud.accountservice.security.AccountTokenProfileCatalog.ControlUiShape;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy;
import net.firedevops.firemud.common.security.AccountJwtExactValues;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;

/**
 * Account-owned signature, profile, catalog and exact active-registry claim predicates.
 *
 * <p>This unwired prerequisite performs no issuance or registry mutation. Its private-minted
 * inspection is deliberately non-authorizing: the owner still needs one complete current
 * account-auth-evidence-bundle/v1, exact projection freshness, immutable signer-generation/kid
 * retention and the operation's registry postconditions before creating a caller principal. Neither
 * a raw actor UUID nor a caller-constructed snapshot can satisfy those missing producers.
 */
public final class AccountControlUiTokenChecks {
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
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private final AccountAsymmetricJwtVerifier verifier;
  private final AccountTokenProfileCatalog catalog;
  private final Clock clock;

  public AccountControlUiTokenChecks(
      AccountAsymmetricJwtVerifier verifier, AccountTokenProfileCatalog catalog, Clock clock) {
    this.verifier = Objects.requireNonNull(verifier);
    this.catalog = Objects.requireNonNull(catalog);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * Checks the exact record returned by the owning adapter's one atomic registry lookup. The
   * route-derived applicability shape is owner policy, never request data. It keeps tenant and
   * caller-membership scope sets independent, including the closed billing-safe exception.
   */
  public InspectedToken inspect(
      String routeId, String compactJwt, byte[] exactRegistryBytes, ControlUiShape applicability) {
    try {
      Objects.requireNonNull(applicability);
      var limits = catalog.requireProfile(AccountTokenProfileCatalog.CONTROL_UI);
      ExplicitRouteProfilePolicy policy =
          new ExplicitRouteProfilePolicy(
              routeId,
              ControlUiJwtProfileValidator.PROFILE,
              ControlUiJwtProfileValidator.TOKEN_TYPE,
              ControlUiJwtProfileValidator.ISSUER,
              ControlUiJwtProfileValidator.AUDIENCE,
              ControlUiJwtProfileValidator.REQUIRED_CLAIMS,
              ControlUiJwtProfileValidator.OPTIONAL_CLAIMS,
              limits.maxTokenLifetimeSeconds(),
              0L,
              Math.toIntExact(limits.maxCompactJwtBytes()),
              claims -> {
                ControlUiJwtProfileValidator.validateClaims(
                    claims,
                    limits.maxControlUiTenantScopes().orElseThrow(),
                    limits.maxAuthorityTupleBytes());
              });
      var signed = verifier.verify(compactJwt, policy);
      if (exactRegistryBytes == null
          || exactRegistryBytes.length == 0
          || exactRegistryBytes.length > limits.maxRegistryRecordBytes()) throw invalid();
      Map<String, Object> record = JSON.readValue(exactRegistryBytes, OBJECT);
      if (!record.keySet().equals(RECORD_FIELDS)
          || !positiveCounter(record.get("schemaVersion")).equals(BigInteger.TWO)
          || !positiveCounter(record.get("registryVersion")).equals(BigInteger.TWO)
          || !"active".equals(record.get("state"))
          || !ControlUiJwtProfileValidator.PROFILE.equals(record.get("profile"))
          || !ControlUiJwtProfileValidator.TOKEN_TYPE.equals(record.get("type"))
          || !signed.keyId().equals(record.get("kid"))
          || !sha256(compactJwt).equals(record.get("tokenHash"))) throw invalid();
      requirePositiveDecimal(record.get("signerGeneration"));
      requireUuid(record.get("operationId"));
      requireUuid(record.get("requestId"));
      if (!(record.get("requestDigest") instanceof String digest)
          || !digest.matches("[0-9a-f]{64}")) throw invalid();
      Map<String, Object> claims = signed.claims();
      requireEqual(record.get("issuer"), claims.get("iss"));
      requireEqual(record.get("audience"), claims.get("aud"));
      for (String field :
          List.of(
              "accountId",
              "jti",
              "iat",
              "nbf",
              "exp",
              "tokenGeneration",
              "authorityTuple",
              "membershipVersion",
              "issuanceFence")) {
        requireEqual(record.get(field), claims.get(field));
      }
      if (clock.instant().getEpochSecond() >= positiveEpochSecond(record.get("exp")))
        throw invalid();
      requireSourceReferences(record);
      if (!Arrays.equals(exactRegistryBytes, canonicalBytes(record))) throw invalid();
      catalog.validatePreSignCandidate(
          AccountTokenProfileCatalog.CONTROL_UI,
          applicability,
          actualMaps(claims),
          positiveEpochSecond(claims.get("iat")),
          positiveEpochSecond(claims.get("exp")),
          canonicalText(claims.get("authorityTuple")));
      catalog.validatePostSignCandidate(
          AccountTokenProfileCatalog.CONTROL_UI, compactJwt, canonicalText(record));
      return new InspectedToken(UUID.fromString((String) claims.get("accountId")), signed, record);
    } catch (AccountAsymmetricJwtVerifier.VerificationUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw invalid();
    } catch (Exception failure) {
      throw invalid();
    }
  }

  private static ActualClaimMaps actualMaps(Map<String, Object> claims) {
    Map<String, BigInteger> membership = new LinkedHashMap<>();
    object(claims.get("membershipVersion"))
        .forEach(
            (tenant, version) ->
                membership.put(tenant, AccountJwtExactValues.nonNegativeDecimalCounter(version)));
    Map<String, List<String>> roles = new LinkedHashMap<>();
    object(claims.get("scopedRoles"))
        .forEach(
            (tenant, value) -> {
              if (!(value instanceof List<?> list)) throw invalid();
              roles.put(
                  tenant,
                  list.stream()
                      .map(
                          item -> {
                            if (!(item instanceof String role)) throw invalid();
                            return role;
                          })
                      .toList());
            });
    return new ActualClaimMaps(Optional.of(membership), Optional.of(roles));
  }

  private static void requireSourceReferences(Map<String, Object> record) {
    Map<String, Object> source = object(record.get("authoritySourceVersions"));
    if (!source
        .keySet()
        .equals(
            Set.of("issuerSourceVersion", "accountSourceVersion", "issuanceFenceSourceVersion"))) {
      throw invalid();
    }
    source.values().forEach(AccountControlUiTokenChecks::positiveCounter);
    Map<String, Object> bundle = object(record.get("authEvidenceBundle"));
    if (!bundle
        .keySet()
        .equals(
            Set.of(
                "bundleVersion",
                "sourceVersion",
                "sourceFence",
                "linearization",
                "canonicalSha256"))) {
      throw invalid();
    }
    for (String field : List.of("bundleVersion", "sourceVersion", "sourceFence", "linearization")) {
      requirePositiveDecimal(bundle.get(field));
    }
    if (!(bundle.get("canonicalSha256") instanceof String digest)
        || !digest.matches("[0-9a-f]{64}")) throw invalid();
    if (!"1".equals(bundle.get("bundleVersion"))) throw invalid();
    // sourceVersion identifies the durable capture and sourceFence fences its commit/readback.
    // Both remain independent of issuanceFence and each authority source counter; equality with
    // the actual complete capture reference and its exact source versions/checkpoints belongs to
    // the missing owner-readback/currentness gate. Structural validity is not authorization.
  }

  private static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw invalid();
    Map<String, Object> copy = new LinkedHashMap<>();
    map.forEach(
        (key, item) -> {
          if (!(key instanceof String text) || item == null) throw invalid();
          copy.put(text, item);
        });
    return Map.copyOf(copy);
  }

  private static BigInteger positiveCounter(Object value) {
    return AccountJwtExactValues.positiveCounter(value);
  }

  private static long positiveEpochSecond(Object value) {
    return positiveCounter(value).longValueExact();
  }

  private static void requirePositiveDecimal(Object value) {
    AccountJwtExactValues.positiveDecimalCounter(value);
  }

  private static void requireUuid(Object value) {
    if (!(value instanceof String text)
        || !UUID.fromString(text).toString().equals(text)
        || new UUID(0L, 0L).toString().equals(text)) throw invalid();
  }

  private static void requireEqual(Object actual, Object expected) {
    if (actual == null
        || expected == null
        || !AccountJwtExactValues.sameJsonValue(actual, expected)) throw invalid();
  }

  private static byte[] canonicalBytes(Object value) {
    try {
      return AccountJwtExactValues.losslessCanonicalBytes(value);
    } catch (Exception failure) {
      throw invalid();
    }
  }

  private static String canonicalText(Object value) {
    return new String(canonicalBytes(value), StandardCharsets.UTF_8);
  }

  private static String sha256(String compactJwt) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(compactJwt.getBytes(StandardCharsets.US_ASCII)));
    } catch (Exception failure) {
      throw invalid();
    }
  }

  private static InvalidTokenException invalid() {
    return new InvalidTokenException();
  }

  /** An immutable cryptographic/record inspection, never a principal or freshness receipt. */
  public static final class InspectedToken {
    private final UUID accountId;
    private final AccountAsymmetricJwtVerifier.VerifiedClaims signed;
    private final Map<String, Object> record;

    private InspectedToken(
        UUID accountId,
        AccountAsymmetricJwtVerifier.VerifiedClaims signed,
        Map<String, Object> record) {
      this.accountId = accountId;
      this.signed = signed;
      this.record = JSON.convertValue(record, OBJECT);
    }

    public UUID accountId() {
      return accountId;
    }

    public Map<String, Object> claims() {
      return signed.claims();
    }

    public Map<String, Object> registryRecord() {
      return JSON.convertValue(record, OBJECT);
    }

    @Override
    public String toString() {
      return "AccountControlUiInspectedToken[non-authorizing]";
    }
  }

  public static final class InvalidTokenException extends RuntimeException {
    private InvalidTokenException() {
      super("Account control-ui token inspection failed");
    }
  }
}
