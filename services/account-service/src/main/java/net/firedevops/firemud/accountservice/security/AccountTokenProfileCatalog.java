package net.firedevops.firemud.accountservice.security;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/**
 * Immutable, explicit Account registry-backed token-profile limits and candidate-bound checks.
 *
 * <p>This catalog is an unwired issuance prerequisite. It checks the finite profile limits, the
 * caller-supplied typed shape evidence against the supported authority-tuple scope fields, exact
 * membershipVersion and scopedRoles claim-map structure and scope, exact UTC-second lifetime
 * arithmetic, and canonical UTF-8 byte lengths at the pre-sign and post-sign boundaries. It does
 * not establish current Account authority, prove role authorization, validate a signature, perform
 * registry I/O, or authorize a token. Issuers and lifecycle handlers must continue to prove those
 * separate contracts before mutation or exposure. This unwired catalog does not prove runtime
 * issuance or activation.
 */
public final class AccountTokenProfileCatalog {
  public static final String VERSION = "account-token-profile-catalog/v1";

  public static final String CONTROL_UI = "control-ui";
  public static final String PLAYER_BOOTSTRAP = "player-bootstrap";
  public static final String GAME_SESSION_ACCOUNT_DELEGATION = "game-session-account-delegation";

  private static final String CATALOG_INVALID = "Account token profile catalog is invalid";
  private static final String CANDIDATE_INVALID = "Account token profile candidate is invalid";
  private static final String CANDIDATE_OVER_LIMIT =
      "Account token profile candidate exceeds configured limits";
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<String> AUTHORITY_TUPLE_REQUIRED_FIELDS =
      Set.of(
          "issuerAuthGeneration",
          "accountAuthorityGeneration",
          "tenantAuthorityGeneration",
          "membershipAuthorityGeneration",
          "privateRealmGrantVersions");
  private static final Set<String> AUTHORITY_TUPLE_ALLOWED_FIELDS =
      Set.of(
          "issuerAuthGeneration",
          "accountAuthorityGeneration",
          "tenantAuthorityGeneration",
          "membershipAuthorityGeneration",
          "privateRealmGrantVersions",
          "accountSecurityCutoff",
          "tenantBillingCutoff");
  private static final Set<String> ACCOUNT_CUTOFF_FIELDS =
      Set.of("accountAuthorityGeneration", "outboxStreamKey", "outboxSequence");
  private static final Set<String> TENANT_CUTOFF_FIELDS =
      Set.of(
          "tenantAuthorityGeneration",
          "tenantBillingSequence",
          "outboxStreamKey",
          "outboxSequence");
  private static final Set<String> PRIVATE_REALM_GRANT_FIELDS =
      Set.of("tenantId", "worldSlug", "realmSlug", "playtestLifecycleId", "grantVersion");
  // Canonical tenant-role vocabulary from Authentication & Authorization.
  private static final Set<String> TENANT_ROLE_VALUES =
      Set.of("player", "designer", "tenantAdmin", "moderator");
  private static final List<String> PROFILE_ORDER =
      List.of(CONTROL_UI, PLAYER_BOOTSTRAP, GAME_SESSION_ACCOUNT_DELEGATION);
  private static final Set<String> REQUIRED_PROFILES = Set.copyOf(PROFILE_ORDER);
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final String version;
  private final Map<String, ProfileLimits> profiles;
  private final DeploymentCeilings deploymentCeilings;

  /**
   * Creates a catalog only from an explicitly supplied version, complete profile map, and
   * deployment ceilings that have already passed the owning deployment validator.
   *
   * <p>No limits are defaulted or inferred from current token contents, membership counts,
   * transport acceptance, or Redis acceptance.
   *
   * @param version exact supported catalog schema version
   * @param profileLimits all three ordinary registry-backed profiles and no other profile
   * @param deploymentCeilings already validated transport and Coordination Redis ceilings
   * @throws IllegalArgumentException if the catalog is missing, inconsistent, or over deployment
   */
  public AccountTokenProfileCatalog(
      String version,
      Map<String, ProfileLimits> profileLimits,
      DeploymentCeilings deploymentCeilings) {
    if (!VERSION.equals(version)
        || profileLimits == null
        || deploymentCeilings == null
        || !REQUIRED_PROFILES.equals(profileLimits.keySet())) {
      throw invalidCatalog();
    }

    Map<String, ProfileLimits> copied = new LinkedHashMap<>();
    for (String profileId : PROFILE_ORDER) {
      ProfileLimits limits = profileLimits.get(profileId);
      if (limits == null) {
        throw invalidCatalog();
      }
      validateLimits(profileId, limits, deploymentCeilings);
      copied.put(profileId, limits);
    }

    this.version = version;
    this.profiles = Collections.unmodifiableMap(copied);
    this.deploymentCeilings = deploymentCeilings;
  }

  public String version() {
    return version;
  }

  /** Returns the immutable complete profile map supplied to this catalog. */
  public Map<String, ProfileLimits> profiles() {
    return profiles;
  }

  public DeploymentCeilings deploymentCeilings() {
    return deploymentCeilings;
  }

  /** Returns the validated immutable limits for one supported registry-backed profile. */
  public ProfileLimits requireProfile(String profileId) {
    ProfileLimits limits = profiles.get(profileId);
    if (limits == null) {
      throw invalidCatalog();
    }
    return limits;
  }

  /**
   * Validates exact UTC epoch-second representability and checked {@code exp - iat} lifetime.
   *
   * <p>The caller must use this check before mutation for issuance, refresh, and lifecycle
   * operations that accept token identity. This method does not compare the timestamps with a clock
   * or establish token freshness.
   *
   * @return the positive checked lifetime in seconds
   * @throws IllegalArgumentException for impossible timestamps, arithmetic overflow, or an
   *     over-limit lifetime
   */
  public long validateTokenLifetime(
      String profileId, long issuedAtEpochSecond, long expiresAtEpochSecond) {
    ProfileLimits limits = requireProfile(profileId);
    final long lifetime;
    try {
      lifetime = Math.subtractExact(expiresAtEpochSecond, issuedAtEpochSecond);
      Instant.ofEpochSecond(issuedAtEpochSecond);
      Instant.ofEpochSecond(expiresAtEpochSecond);
    } catch (ArithmeticException | DateTimeException exception) {
      throw invalidCandidate();
    }
    if (lifetime <= 0 || lifetime > limits.maxTokenLifetimeSeconds()) {
      throw invalidCandidate();
    }
    return lifetime;
  }

  /**
   * Applies profile shape, actual membership/role claim structure, cardinality, checked lifetime,
   * and canonical tuple byte limits before the one permitted signing operation.
   *
   * <p>{@code canonicalAuthorityTupleJson} must be the exact RFC 8785 serialization already
   * constructed by the owning Account claim builder. The input is rejected unless it is already
   * canonical; this method does not silently replace it with canonical bytes.
   *
   * <p>{@code actualClaims} carries the separate {@code membershipVersion} and {@code scopedRoles}
   * maps from the same candidate. Their exact presence, tenant keys, positive counters, and role
   * values are checked against the typed profile shape. This is structural proof only; current
   * Account sources, role authorization, JWT signature, and registry postconditions remain outside
   * this unwired API.
   */
  public void validatePreSignCandidate(
      String profileId,
      ProfileShape shape,
      ActualClaimMaps actualClaims,
      long issuedAtEpochSecond,
      long expiresAtEpochSecond,
      String canonicalAuthorityTupleJson) {
    ProfileLimits limits = requireProfile(profileId);
    validateTokenLifetime(profileId, issuedAtEpochSecond, expiresAtEpochSecond);
    validateActualClaimPresence(shape, actualClaims);
    validateShape(profileId, limits, shape);
    validateActualClaimMaps(shape, actualClaims);

    CanonicalJsonObject authorityTuple =
        requireCanonicalJsonObject(canonicalAuthorityTupleJson, limits.maxAuthorityTupleBytes());
    validateAuthorityTupleMatchesShape(authorityTuple.object(), shape);
  }

  /**
   * Applies post-sign complete compact-JWT and canonical registry-record byte ceilings.
   *
   * <p>The compact JWT is checked as three non-empty base64url segments and measured as UTF-8 bytes
   * after signing. {@code canonicalRegistryRecordJson} must be the exact RFC 8785 serialization of
   * the complete state-specific logical record; it must already be canonical. The JWT and record
   * contents are never retained, placed in an exception, or included in this catalog's diagnostic
   * representation.
   *
   * <p>This size check does not verify the token signature, its claims, the registry postcondition,
   * or successful activation.
   */
  public void validatePostSignCandidate(
      String profileId, String compactJwt, String canonicalRegistryRecordJson) {
    ProfileLimits limits = requireProfile(profileId);
    compactJwtUtf8Length(compactJwt, limits.maxCompactJwtBytes());
    canonicalJsonObjectUtf8Length(canonicalRegistryRecordJson, limits.maxRegistryRecordBytes());
  }

  private static void validateLimits(
      String profileId, ProfileLimits limits, DeploymentCeilings deploymentCeilings) {
    String expectedAudience = expectedAudience(profileId);
    if (expectedAudience == null
        || !expectedAudience.equals(limits.audience())
        || limits.maxTokenLifetimeSeconds() <= 0
        || limits.maxTokenLifetimeSeconds() > deploymentCeilings.maxRegistryTokenLifetimeSeconds()
        || limits.maxAuthorityTupleBytes() <= 0
        || limits.maxAuthorityTupleBytes() > deploymentCeilings.maxCoordinationAuthorityTupleBytes()
        || limits.maxCompactJwtBytes() <= 0
        || limits.maxCompactJwtBytes() > deploymentCeilings.maxTransportCompactJwtBytes()
        || limits.maxRegistryRecordBytes() <= 0
        || limits.maxRegistryRecordBytes() > deploymentCeilings.maxCoordinationRegistryRecordBytes()
        || limits.maxAuthorityTupleBytes() > limits.maxRegistryRecordBytes()) {
      throw invalidCatalog();
    }

    int[] cardinalityCaps = {
      limits.maxTenantAuthorityEntries(),
      limits.maxMembershipAuthorityEntries(),
      limits.maxMembershipVersionEntries(),
      limits.maxTenantBillingCutoffEntries(),
      limits.maxPrivateRealmGrantEntries()
    };
    for (int cap : cardinalityCaps) {
      if (cap < 0) {
        throw invalidCatalog();
      }
    }

    Optional<Integer> sharedUiCap = limits.maxControlUiTenantScopes();
    switch (profileId) {
      case CONTROL_UI -> {
        if (sharedUiCap.isEmpty() || sharedUiCap.orElseThrow() <= 0) {
          throw invalidCatalog();
        }
        int cap = sharedUiCap.orElseThrow();
        if (limits.maxTenantAuthorityEntries() <= 0
            || limits.maxTenantAuthorityEntries() > cap
            || limits.maxMembershipAuthorityEntries() <= 0
            || limits.maxMembershipAuthorityEntries() > cap
            || limits.maxMembershipVersionEntries() <= 0
            || limits.maxMembershipVersionEntries() > cap
            || limits.maxTenantBillingCutoffEntries() <= 0
            || limits.maxTenantBillingCutoffEntries() > cap
            || limits.maxPrivateRealmGrantEntries() != 0) {
          throw invalidCatalog();
        }
      }
      case PLAYER_BOOTSTRAP -> {
        if (sharedUiCap.isPresent()
            || limits.maxTenantAuthorityEntries() != 0
            || limits.maxMembershipAuthorityEntries() != 0
            || limits.maxMembershipVersionEntries() != 0
            || limits.maxTenantBillingCutoffEntries() != 0
            || limits.maxPrivateRealmGrantEntries() != 0) {
          throw invalidCatalog();
        }
      }
      case GAME_SESSION_ACCOUNT_DELEGATION -> {
        if (sharedUiCap.isPresent()
            || limits.maxTenantAuthorityEntries() != 1
            || limits.maxMembershipAuthorityEntries() != 1
            || limits.maxMembershipVersionEntries() != 1
            || limits.maxTenantBillingCutoffEntries() != 1
            || limits.maxPrivateRealmGrantEntries() != 1) {
          throw invalidCatalog();
        }
      }
      default -> throw invalidCatalog();
    }
  }

  private static String expectedAudience(String profileId) {
    return switch (profileId) {
      case CONTROL_UI -> CONTROL_UI;
      case PLAYER_BOOTSTRAP -> PLAYER_BOOTSTRAP;
      case GAME_SESSION_ACCOUNT_DELEGATION -> "account-service";
      default -> null;
    };
  }

  private static void validateShape(String profileId, ProfileLimits limits, ProfileShape shape) {
    if (shape == null) {
      throw invalidCandidate();
    }
    ClaimFieldPresence claims = shape.claims();
    AuthorityMapShape authority = shape.authority();
    validateCommonClaimPresence(claims, limits);

    if (claims.tenantBillingCutoffPresent() != !authority.tenantBillingCutoffKeys().isEmpty()) {
      throw invalidCandidate();
    }

    switch (profileId) {
      case CONTROL_UI -> {
        if (!(shape instanceof ControlUiShape controlUi)) {
          throw invalidCandidate();
        }
        validateControlUiShape(limits, controlUi);
      }
      case PLAYER_BOOTSTRAP -> {
        if (!(shape instanceof PlayerBootstrapShape bootstrap)) {
          throw invalidCandidate();
        }
        validateBootstrapShape(bootstrap);
      }
      case GAME_SESSION_ACCOUNT_DELEGATION -> {
        if (shape instanceof TenantBoundDelegationShape tenantBound) {
          validateTenantBoundDelegationShape(limits, tenantBound);
        } else if (shape instanceof NonTenantDelegationShape nonTenant) {
          validateNonTenantDelegationShape(nonTenant);
        } else {
          throw invalidCandidate();
        }
      }
      default -> throw invalidCatalog();
    }
  }

  private static void validateCommonClaimPresence(ClaimFieldPresence claims, ProfileLimits limits) {
    if (claims == null
        || !claims.issuerPresent()
        || !claims.subjectPresent()
        || !claims.tokenIdPresent()
        || !claims.accountIdPresent()
        || !limits.audience().equals(claims.audience())
        || !claims.issuedAtPresent()
        || !claims.expiresAtPresent()
        || !claims.authorityTuplePresent()
        || !claims.issuerAuthGenerationPresent()
        || !claims.accountAuthorityGenerationPresent()
        || !claims.tenantAuthorityGenerationPresent()
        || !claims.membershipAuthorityGenerationPresent()
        || !claims.membershipVersionPresent()
        || !claims.privateRealmGrantVersionsPresent()
        || !claims.tokenGenerationPresent()
        || !claims.issuanceFencePresent()) {
      throw invalidCandidate();
    }
  }

  private static void validateActualClaimPresence(
      ProfileShape shape, ActualClaimMaps actualClaims) {
    if (shape == null || shape.claims() == null || actualClaims == null) {
      throw invalidCandidate();
    }
    ClaimFieldPresence presence = shape.claims();
    if (presence.membershipVersionPresent() != actualClaims.membershipVersion().isPresent()
        || presence.scopedRolesPresent() != actualClaims.scopedRoles().isPresent()) {
      throw invalidCandidate();
    }
  }

  private static void validateActualClaimMaps(ProfileShape shape, ActualClaimMaps actualClaims) {
    Map<String, BigInteger> membershipVersion =
        actualClaims.membershipVersion().orElseThrow(AccountTokenProfileCatalog::invalidCandidate);
    Set<String> membershipVersionKeys = requireNonNegativeTenantCounterMap(membershipVersion);
    if (!membershipVersionKeys.equals(
        canonicalTenantKeys(shape.authority().membershipVersionKeys()))) {
      throw invalidCandidate();
    }

    if (actualClaims.scopedRoles().isPresent()) {
      Map<String, List<String>> scopedRoles = actualClaims.scopedRoles().orElseThrow();
      Set<String> scopedRoleKeys = requireCanonicalScopedRoles(scopedRoles);
      if (!scopedRoleKeys.equals(canonicalTenantKeys(shape.scopedRoleKeys()))) {
        throw invalidCandidate();
      }
    } else if (!shape.scopedRoleKeys().isEmpty()) {
      throw invalidCandidate();
    }
  }

  private static Set<String> requireNonNegativeTenantCounterMap(Map<String, BigInteger> values) {
    Set<String> keys = new HashSet<>();
    for (Map.Entry<String, BigInteger> entry : values.entrySet()) {
      String key = entry.getKey();
      BigInteger value = entry.getValue();
      requireCanonicalTenantKey(key);
      if (value == null || value.signum() < 0) {
        throw invalidCandidate();
      }
      keys.add(key);
    }
    return Set.copyOf(keys);
  }

  private static Set<String> requireCanonicalScopedRoles(Map<String, List<String>> values) {
    Set<String> keys = new HashSet<>();
    for (Map.Entry<String, List<String>> entry : values.entrySet()) {
      String tenantKey = entry.getKey();
      requireCanonicalTenantKey(tenantKey);
      List<String> roles = entry.getValue();
      if (roles == null || roles.isEmpty()) {
        throw invalidCandidate();
      }
      Set<String> seenRoles = new HashSet<>();
      for (String role : roles) {
        if (!TENANT_ROLE_VALUES.contains(role) || !seenRoles.add(role)) {
          throw invalidCandidate();
        }
      }
      keys.add(tenantKey);
    }
    return Set.copyOf(keys);
  }

  private static void validateControlUiShape(ProfileLimits limits, ControlUiShape shape) {
    AuthorityMapShape authority = shape.authority();
    int sharedLimit = limits.maxControlUiTenantScopes().orElseThrow();
    if (!shape.claims().scopedRolesPresent()
        || !shape.scopedTenantKeys().containsAll(shape.scopedRoleKeys())
        || !shape.scopedRoleKeys().containsAll(shape.tenantAuthorityRouteKeys())
        || !shape.scopedTenantKeys().containsAll(shape.requiredCallerMembershipKeys())
        || !shape.scopedTenantKeys().containsAll(shape.billingSafeTenantKeys())
        || !shape.scopedTenantKeys().containsAll(shape.applicableBillingCutoffKeys())
        || !Collections.disjoint(shape.billingSafeTenantKeys(), shape.tenantAuthorityRouteKeys())
        || !shape.requiredCallerMembershipKeys().containsAll(shape.billingSafeTenantKeys())
        || !authority.tenantAuthorityKeys().equals(shape.tenantAuthorityRouteKeys())
        || !authority.membershipAuthorityKeys().equals(shape.requiredCallerMembershipKeys())
        || !authority.membershipVersionKeys().equals(shape.requiredCallerMembershipKeys())
        || !authority.tenantBillingCutoffKeys().equals(shape.applicableBillingCutoffKeys())
        || !authority.privateRealmGrants().isEmpty()) {
      throw invalidCandidate();
    }

    requireCount(shape.scopedRoleKeys().size(), sharedLimit);
    requireCount(authority.tenantAuthorityKeys().size(), limits.maxTenantAuthorityEntries());
    requireCount(authority.tenantAuthorityKeys().size(), sharedLimit);
    requireCount(
        authority.membershipAuthorityKeys().size(), limits.maxMembershipAuthorityEntries());
    requireCount(authority.membershipAuthorityKeys().size(), sharedLimit);
    requireCount(authority.membershipVersionKeys().size(), limits.maxMembershipVersionEntries());
    requireCount(authority.membershipVersionKeys().size(), sharedLimit);
    requireCount(
        authority.tenantBillingCutoffKeys().size(), limits.maxTenantBillingCutoffEntries());
    requireCount(authority.tenantBillingCutoffKeys().size(), sharedLimit);
    requireCount(authority.privateRealmGrants().size(), limits.maxPrivateRealmGrantEntries());
  }

  private static void validateBootstrapShape(PlayerBootstrapShape shape) {
    if (!shape.claims().scopedRolesPresent()
        || !shape.scopedRoleKeys().isEmpty()
        || !isEmpty(shape.authority())) {
      throw invalidCandidate();
    }
  }

  private static void validateTenantBoundDelegationShape(
      ProfileLimits limits, TenantBoundDelegationShape shape) {
    AuthorityMapShape authority = shape.authority();
    UUID tenantId = shape.tenantId();
    Set<UUID> soleTenant = Set.of(tenantId);
    if (!authority.tenantAuthorityKeys().equals(soleTenant)
        || !authority.membershipAuthorityKeys().equals(soleTenant)
        || !authority.membershipVersionKeys().equals(soleTenant)
        || !(authority.tenantBillingCutoffKeys().isEmpty()
            || authority.tenantBillingCutoffKeys().equals(soleTenant))
        || !(shape.scopedRoleKeys().isEmpty() || shape.scopedRoleKeys().equals(soleTenant))) {
      throw invalidCandidate();
    }

    if (!shape.claims().scopedRolesPresent() && !shape.scopedRoleKeys().isEmpty()) {
      throw invalidCandidate();
    }

    if (shape.publicProduction()) {
      if (shape.selectedPrivateRealm().isPresent() || !authority.privateRealmGrants().isEmpty()) {
        throw invalidCandidate();
      }
    } else {
      if (shape.selectedPrivateRealm().isEmpty() || authority.privateRealmGrants().size() != 1) {
        throw invalidCandidate();
      }
      PrivateRealmTarget target = shape.selectedPrivateRealm().orElseThrow();
      PrivateRealmGrantShape grant = authority.privateRealmGrants().getFirst();
      if (grant.grantVersionDecimal().length() > limits.maxAuthorityTupleBytes()
          || !grant.tenantId().equals(tenantId)
          || !target.tenantId().equals(tenantId)
          || !grant.matches(target)) {
        throw invalidCandidate();
      }
    }

    if (authority.privateRealmGrants().size() > 1) {
      throw invalidCandidate();
    }
  }

  private static void validateNonTenantDelegationShape(NonTenantDelegationShape shape) {
    if (!isEmpty(shape.authority()) || !shape.scopedRoleKeys().isEmpty()) {
      throw invalidCandidate();
    }
    if (!shape.claims().scopedRolesPresent() && !shape.scopedRoleKeys().isEmpty()) {
      throw invalidCandidate();
    }
  }

  private static boolean isEmpty(AuthorityMapShape authority) {
    return authority.tenantAuthorityKeys().isEmpty()
        && authority.membershipAuthorityKeys().isEmpty()
        && authority.membershipVersionKeys().isEmpty()
        && authority.tenantBillingCutoffKeys().isEmpty()
        && authority.privateRealmGrants().isEmpty();
  }

  private static void requireCount(int count, int limit) {
    if (count > limit) {
      throw overLimitCandidate();
    }
  }

  private static long canonicalJsonObjectUtf8Length(String json, long maximumBytes) {
    return requireCanonicalJsonObject(json, maximumBytes).utf8Length();
  }

  private static CanonicalJsonObject requireCanonicalJsonObject(String json, long maximumBytes) {
    if (json == null || json.isEmpty()) {
      throw invalidCandidate();
    }
    if (json.length() > maximumBytes) {
      throw overLimitCandidate();
    }
    byte[] exactUtf8 = strictUtf8(json);
    if (exactUtf8.length > maximumBytes) {
      throw overLimitCandidate();
    }

    final JsonNode parsed;
    try {
      parsed = JSON.readTree(json);
    } catch (IOException | RuntimeException exception) {
      throw invalidCandidate();
    }
    if (!(parsed instanceof ObjectNode object)) {
      throw invalidCandidate();
    }
    requireWellFormedJsonUnicode(object);

    final byte[] canonicalUtf8;
    try {
      canonicalUtf8 = Rfc8785CanonicalJson.canonicalizeUtf8(json);
    } catch (IOException | RuntimeException exception) {
      throw invalidCandidate();
    }
    if (!Arrays.equals(exactUtf8, canonicalUtf8)
        || exactUtf8[0] != (byte) '{'
        || exactUtf8[exactUtf8.length - 1] != (byte) '}') {
      throw invalidCandidate();
    }
    return new CanonicalJsonObject(object, exactUtf8.length);
  }

  private static void validateAuthorityTupleMatchesShape(ObjectNode tuple, ProfileShape shape) {
    // The typed model contains scope keys and grant versions, but not the source values needed to
    // prove that generation/cutoff counters are current. Those values receive schema checks only.
    requireExactFields(tuple, AUTHORITY_TUPLE_REQUIRED_FIELDS, AUTHORITY_TUPLE_ALLOWED_FIELDS);
    requirePositiveDecimal(tuple.get("issuerAuthGeneration"));
    requirePositiveDecimal(tuple.get("accountAuthorityGeneration"));

    AuthorityMapShape authority = shape.authority();
    Set<String> tenantAuthorityKeys =
        requirePositiveDecimalMap(tuple.get("tenantAuthorityGeneration"));
    Set<String> membershipAuthorityKeys =
        requirePositiveDecimalMap(tuple.get("membershipAuthorityGeneration"));
    if (!tenantAuthorityKeys.equals(canonicalTenantKeys(authority.tenantAuthorityKeys()))
        || !membershipAuthorityKeys.equals(
            canonicalTenantKeys(authority.membershipAuthorityKeys()))) {
      throw invalidCandidate();
    }

    JsonNode billingCutoff = tuple.get("tenantBillingCutoff");
    if (tuple.has("tenantBillingCutoff") != shape.claims().tenantBillingCutoffPresent()) {
      throw invalidCandidate();
    }
    if (tuple.has("tenantBillingCutoff")) {
      Set<String> billingKeys = requireTenantBillingCutoff(billingCutoff);
      if (!billingKeys.equals(canonicalTenantKeys(authority.tenantBillingCutoffKeys()))) {
        throw invalidCandidate();
      }
    }

    JsonNode accountCutoff = tuple.get("accountSecurityCutoff");
    if (tuple.has("accountSecurityCutoff") != shape.claims().accountSecurityCutoffPresent()) {
      throw invalidCandidate();
    }
    if (tuple.has("accountSecurityCutoff")) {
      requireAccountSecurityCutoff(accountCutoff);
    }

    requirePrivateRealmGrants(
        tuple.get("privateRealmGrantVersions"), authority.privateRealmGrants());
  }

  private static Set<String> requirePositiveDecimalMap(JsonNode value) {
    if (!(value instanceof ObjectNode map)) {
      throw invalidCandidate();
    }
    Set<String> keys = new HashSet<>();
    Iterator<Map.Entry<String, JsonNode>> entries = map.fields();
    while (entries.hasNext()) {
      Map.Entry<String, JsonNode> entry = entries.next();
      requireCanonicalTenantKey(entry.getKey());
      requirePositiveDecimal(entry.getValue());
      keys.add(entry.getKey());
    }
    return Set.copyOf(keys);
  }

  private static Set<String> requireTenantBillingCutoff(JsonNode value) {
    if (!(value instanceof ObjectNode cutoffs) || cutoffs.isEmpty()) {
      throw invalidCandidate();
    }
    Set<String> keys = new HashSet<>();
    Iterator<Map.Entry<String, JsonNode>> entries = cutoffs.fields();
    while (entries.hasNext()) {
      Map.Entry<String, JsonNode> entry = entries.next();
      String tenantKey = entry.getKey();
      requireCanonicalTenantKey(tenantKey);
      if (!(entry.getValue() instanceof ObjectNode cutoff)) {
        throw invalidCandidate();
      }
      requireExactFields(cutoff, TENANT_CUTOFF_FIELDS, TENANT_CUTOFF_FIELDS);
      requirePositiveDecimal(cutoff.get("tenantAuthorityGeneration"));
      requirePositiveDecimal(cutoff.get("tenantBillingSequence"));
      requirePositiveDecimal(cutoff.get("outboxSequence"));
      requireNonEmptyText(cutoff.get("outboxStreamKey"));
      keys.add(tenantKey);
    }
    return Set.copyOf(keys);
  }

  private static void requireAccountSecurityCutoff(JsonNode value) {
    if (!(value instanceof ObjectNode cutoff)) {
      throw invalidCandidate();
    }
    requireExactFields(cutoff, ACCOUNT_CUTOFF_FIELDS, ACCOUNT_CUTOFF_FIELDS);
    requirePositiveDecimal(cutoff.get("accountAuthorityGeneration"));
    requirePositiveDecimal(cutoff.get("outboxSequence"));
    requireNonEmptyText(cutoff.get("outboxStreamKey"));
  }

  private static void requirePrivateRealmGrants(
      JsonNode value, List<PrivateRealmGrantShape> expected) {
    if (value == null || !value.isArray() || value.size() != expected.size()) {
      throw invalidCandidate();
    }
    for (int index = 0; index < expected.size(); index++) {
      JsonNode entry = value.get(index);
      if (!(entry instanceof ObjectNode grant)) {
        throw invalidCandidate();
      }
      requireExactFields(grant, PRIVATE_REALM_GRANT_FIELDS, PRIVATE_REALM_GRANT_FIELDS);
      PrivateRealmGrantShape expectedGrant = expected.get(index);
      String tenantId = requireText(grant.get("tenantId"));
      String worldSlug = requireNonEmptyText(grant.get("worldSlug"));
      String realmSlug = requireNonEmptyText(grant.get("realmSlug"));
      String lifecycleId = requireText(grant.get("playtestLifecycleId"));
      String grantVersion = requirePositiveDecimal(grant.get("grantVersion"));
      if (!expectedGrant.tenantId().toString().equals(tenantId)
          || !expectedGrant.worldSlug().equals(worldSlug)
          || !expectedGrant.realmSlug().equals(realmSlug)
          || !expectedGrant.playtestLifecycleId().toString().equals(lifecycleId)
          || !expectedGrant.grantVersionDecimal().equals(grantVersion)) {
        throw invalidCandidate();
      }
    }
  }

  private static void requireExactFields(
      ObjectNode object, Set<String> required, Set<String> allowed) {
    Set<String> actual = new HashSet<>();
    object.fieldNames().forEachRemaining(actual::add);
    if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
      throw invalidCandidate();
    }
  }

  private static String requirePositiveDecimal(JsonNode value) {
    String text = requireText(value);
    if (text.isEmpty() || text.charAt(0) < '1' || text.charAt(0) > '9') {
      throw invalidCandidate();
    }
    for (int index = 1; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character < '0' || character > '9') {
        throw invalidCandidate();
      }
    }
    return text;
  }

  private static String requireNonEmptyText(JsonNode value) {
    String text = requireText(value);
    if (text.isBlank()) {
      throw invalidCandidate();
    }
    return text;
  }

  private static String requireText(JsonNode value) {
    if (value == null || !value.isTextual()) {
      throw invalidCandidate();
    }
    return value.textValue();
  }

  private static void requireCanonicalTenantKey(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || NIL_UUID.equals(parsed)) {
        throw invalidCandidate();
      }
    } catch (IllegalArgumentException exception) {
      throw invalidCandidate();
    }
  }

  private static Set<String> canonicalTenantKeys(Set<UUID> tenantIds) {
    Set<String> keys = new HashSet<>();
    tenantIds.forEach(tenantId -> keys.add(tenantId.toString()));
    return Set.copyOf(keys);
  }

  private static Map<String, BigInteger> immutableMembershipVersion(
      Map<String, BigInteger> values) {
    if (values == null || values.entrySet().stream().anyMatch(entry -> entry.getKey() == null)) {
      throw invalidCandidate();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  private static Map<String, List<String>> immutableScopedRoles(Map<String, List<String>> values) {
    if (values == null) {
      throw invalidCandidate();
    }
    Map<String, List<String>> copied = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : values.entrySet()) {
      if (entry.getKey() == null
          || entry.getValue() == null
          || entry.getValue().stream().anyMatch(Objects::isNull)) {
        throw invalidCandidate();
      }
      copied.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(copied);
  }

  private static void requireWellFormedJsonUnicode(JsonNode node) {
    if (node.isTextual()) {
      requireWellFormedUtf16(node.textValue());
    } else if (node.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        requireWellFormedUtf16(field.getKey());
        requireWellFormedJsonUnicode(field.getValue());
      }
    } else if (node.isArray()) {
      for (JsonNode element : node) {
        requireWellFormedJsonUnicode(element);
      }
    }
  }

  private static byte[] strictUtf8(String value) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (CharacterCodingException exception) {
      throw invalidCandidate();
    }
  }

  private static void requireWellFormedUtf16(String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw invalidCandidate();
        }
        index++;
      } else if (Character.isLowSurrogate(character)) {
        throw invalidCandidate();
      }
    }
  }

  private static int compactJwtUtf8Length(String compactJwt, long maximumBytes) {
    if (compactJwt == null || compactJwt.isEmpty()) {
      throw invalidCandidate();
    }
    if (compactJwt.length() > maximumBytes) {
      throw overLimitCandidate();
    }
    int firstDot = compactJwt.indexOf('.');
    int secondDot = firstDot < 0 ? -1 : compactJwt.indexOf('.', firstDot + 1);
    if (firstDot <= 0
        || secondDot <= firstDot + 1
        || secondDot == compactJwt.length() - 1
        || compactJwt.indexOf('.', secondDot + 1) >= 0) {
      throw invalidCandidate();
    }
    for (int index = 0; index < compactJwt.length(); index++) {
      char character = compactJwt.charAt(index);
      if (character == '.') {
        continue;
      }
      if (!isBase64UrlCharacter(character)) {
        throw invalidCandidate();
      }
    }
    requireCanonicalBase64UrlSegment(compactJwt.substring(0, firstDot));
    requireCanonicalBase64UrlSegment(compactJwt.substring(firstDot + 1, secondDot));
    requireCanonicalBase64UrlSegment(compactJwt.substring(secondDot + 1));
    int utf8Length = compactJwt.getBytes(StandardCharsets.UTF_8).length;
    if (utf8Length > maximumBytes) {
      throw overLimitCandidate();
    }
    return utf8Length;
  }

  private static void requireCanonicalBase64UrlSegment(String segment) {
    try {
      String canonical =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(Base64.getUrlDecoder().decode(segment));
      if (!canonical.equals(segment)) {
        throw invalidCandidate();
      }
    } catch (IllegalArgumentException exception) {
      throw invalidCandidate();
    }
  }

  private static boolean isBase64UrlCharacter(char character) {
    return (character >= 'A' && character <= 'Z')
        || (character >= 'a' && character <= 'z')
        || (character >= '0' && character <= '9')
        || character == '-'
        || character == '_';
  }

  private record CanonicalJsonObject(ObjectNode object, long utf8Length) {}

  private static Set<UUID> immutableTenantKeys(Set<UUID> keys) {
    if (keys == null || keys.stream().anyMatch(key -> key == null || NIL_UUID.equals(key))) {
      throw invalidCandidate();
    }
    return Set.copyOf(keys);
  }

  private static List<PrivateRealmGrantShape> immutableGrants(List<PrivateRealmGrantShape> grants) {
    if (grants == null || grants.stream().anyMatch(Objects::isNull)) {
      throw invalidCandidate();
    }
    return List.copyOf(grants);
  }

  private static IllegalArgumentException invalidCatalog() {
    return new IllegalArgumentException(CATALOG_INVALID);
  }

  private static IllegalArgumentException invalidCandidate() {
    return new IllegalArgumentException(CANDIDATE_INVALID);
  }

  private static IllegalArgumentException overLimitCandidate() {
    return new IllegalArgumentException(CANDIDATE_OVER_LIMIT);
  }

  @Override
  public String toString() {
    return "AccountTokenProfileCatalog[version="
        + version
        + ", profiles="
        + profiles.keySet()
        + "]";
  }

  /** The one required limit set for one exact ordinary registry-backed profile. */
  public record ProfileLimits(
      String audience,
      long maxTokenLifetimeSeconds,
      int maxTenantAuthorityEntries,
      int maxMembershipAuthorityEntries,
      int maxMembershipVersionEntries,
      int maxTenantBillingCutoffEntries,
      int maxPrivateRealmGrantEntries,
      long maxAuthorityTupleBytes,
      long maxCompactJwtBytes,
      long maxRegistryRecordBytes,
      Optional<Integer> maxControlUiTenantScopes) {
    public ProfileLimits {
      if (audience == null || maxControlUiTenantScopes == null) {
        throw invalidCatalog();
      }
    }
  }

  /** Caller-supplied deployment ceilings already validated by transport and Redis owners. */
  public record DeploymentCeilings(
      long maxTransportCompactJwtBytes,
      long maxCoordinationAuthorityTupleBytes,
      long maxCoordinationRegistryRecordBytes,
      long maxRegistryTokenLifetimeSeconds) {
    public DeploymentCeilings {
      if (maxTransportCompactJwtBytes <= 0
          || maxCoordinationAuthorityTupleBytes <= 0
          || maxCoordinationRegistryRecordBytes <= 0
          || maxRegistryTokenLifetimeSeconds <= 0) {
        throw invalidCatalog();
      }
    }
  }

  /** Typed evidence that required and optional claim fields have the canonical presence shape. */
  public record ClaimFieldPresence(
      boolean issuerPresent,
      boolean subjectPresent,
      boolean tokenIdPresent,
      boolean accountIdPresent,
      String audience,
      boolean issuedAtPresent,
      boolean expiresAtPresent,
      boolean authorityTuplePresent,
      boolean issuerAuthGenerationPresent,
      boolean accountAuthorityGenerationPresent,
      boolean tenantAuthorityGenerationPresent,
      boolean membershipAuthorityGenerationPresent,
      boolean membershipVersionPresent,
      boolean privateRealmGrantVersionsPresent,
      boolean tokenGenerationPresent,
      boolean issuanceFencePresent,
      boolean scopedRolesPresent,
      boolean tenantBillingCutoffPresent,
      boolean accountSecurityCutoffPresent) {
    public ClaimFieldPresence {
      if (audience == null) {
        throw invalidCandidate();
      }
    }
  }

  /**
   * Actual separate JWT map claims, preserving omission and arbitrary-precision non-negative
   * membership-version counters.
   *
   * <p>The optionals preserve claim absence separately from an empty object. Maps and nested role
   * lists are copied defensively.
   */
  public record ActualClaimMaps(
      Optional<Map<String, BigInteger>> membershipVersion,
      Optional<Map<String, List<String>>> scopedRoles) {
    public ActualClaimMaps {
      if (membershipVersion == null || scopedRoles == null) {
        throw invalidCandidate();
      }
      membershipVersion =
          membershipVersion.map(AccountTokenProfileCatalog::immutableMembershipVersion);
      scopedRoles = scopedRoles.map(AccountTokenProfileCatalog::immutableScopedRoles);
    }

    @Override
    public String toString() {
      return "ActualClaimMaps[membershipVersionEntries="
          + membershipVersion.map(Map::size).orElse(0)
          + ", scopedRoleTenants="
          + scopedRoles.map(Map::size).orElse(0)
          + "]";
    }
  }

  /** Exact map/list keys and grant identities represented in the canonical authority tuple. */
  public record AuthorityMapShape(
      Set<UUID> tenantAuthorityKeys,
      Set<UUID> membershipAuthorityKeys,
      Set<UUID> membershipVersionKeys,
      Set<UUID> tenantBillingCutoffKeys,
      List<PrivateRealmGrantShape> privateRealmGrants) {
    public AuthorityMapShape {
      tenantAuthorityKeys = immutableTenantKeys(tenantAuthorityKeys);
      membershipAuthorityKeys = immutableTenantKeys(membershipAuthorityKeys);
      membershipVersionKeys = immutableTenantKeys(membershipVersionKeys);
      tenantBillingCutoffKeys = immutableTenantKeys(tenantBillingCutoffKeys);
      privateRealmGrants = immutableGrants(privateRealmGrants);
    }

    @Override
    public Set<UUID> tenantAuthorityKeys() {
      return Set.copyOf(this.tenantAuthorityKeys);
    }

    @Override
    public Set<UUID> membershipAuthorityKeys() {
      return Set.copyOf(this.membershipAuthorityKeys);
    }

    @Override
    public Set<UUID> membershipVersionKeys() {
      return Set.copyOf(this.membershipVersionKeys);
    }

    @Override
    public Set<UUID> tenantBillingCutoffKeys() {
      return Set.copyOf(this.tenantBillingCutoffKeys);
    }

    @Override
    public List<PrivateRealmGrantShape> privateRealmGrants() {
      return List.copyOf(this.privateRealmGrants);
    }

    @Override
    public String toString() {
      return "AuthorityMapShape[tenantAuthority="
          + tenantAuthorityKeys.size()
          + ", membershipAuthority="
          + membershipAuthorityKeys.size()
          + ", membershipVersion="
          + membershipVersionKeys.size()
          + ", tenantBillingCutoff="
          + tenantBillingCutoffKeys.size()
          + ", privateRealmGrants="
          + privateRealmGrants.size()
          + "]";
    }
  }

  /** Caller-supplied route-derived key sets for independent control-UI authority maps. */
  public record ControlUiShape(
      ClaimFieldPresence claims,
      AuthorityMapShape authority,
      Set<UUID> scopedTenantKeys,
      Set<UUID> scopedRoleKeys,
      Set<UUID> tenantAuthorityRouteKeys,
      Set<UUID> requiredCallerMembershipKeys,
      Set<UUID> billingSafeTenantKeys,
      Set<UUID> applicableBillingCutoffKeys)
      implements ProfileShape {
    public ControlUiShape {
      if (claims == null || authority == null) {
        throw invalidCandidate();
      }
      scopedTenantKeys = immutableTenantKeys(scopedTenantKeys);
      scopedRoleKeys = immutableTenantKeys(scopedRoleKeys);
      tenantAuthorityRouteKeys = immutableTenantKeys(tenantAuthorityRouteKeys);
      requiredCallerMembershipKeys = immutableTenantKeys(requiredCallerMembershipKeys);
      billingSafeTenantKeys = immutableTenantKeys(billingSafeTenantKeys);
      applicableBillingCutoffKeys = immutableTenantKeys(applicableBillingCutoffKeys);
    }

    @Override
    public Set<UUID> scopedTenantKeys() {
      return Set.copyOf(this.scopedTenantKeys);
    }

    @Override
    public Set<UUID> scopedRoleKeys() {
      return Set.copyOf(this.scopedRoleKeys);
    }

    @Override
    public Set<UUID> tenantAuthorityRouteKeys() {
      return Set.copyOf(this.tenantAuthorityRouteKeys);
    }

    @Override
    public Set<UUID> requiredCallerMembershipKeys() {
      return Set.copyOf(this.requiredCallerMembershipKeys);
    }

    @Override
    public Set<UUID> billingSafeTenantKeys() {
      return Set.copyOf(this.billingSafeTenantKeys);
    }

    @Override
    public Set<UUID> applicableBillingCutoffKeys() {
      return Set.copyOf(this.applicableBillingCutoffKeys);
    }

    @Override
    public String toString() {
      return "ControlUiShape[scopedTenants="
          + scopedTenantKeys.size()
          + ", scopedRoles="
          + scopedRoleKeys.size()
          + ", membershipScopes="
          + requiredCallerMembershipKeys.size()
          + "]";
    }
  }

  /** The base pre-tenant profile, whose scope maps and lists must be present and empty. */
  public record PlayerBootstrapShape(
      ClaimFieldPresence claims, AuthorityMapShape authority, Set<UUID> scopedRoleKeys)
      implements ProfileShape {
    public PlayerBootstrapShape {
      if (claims == null || authority == null) {
        throw invalidCandidate();
      }
      scopedRoleKeys = immutableTenantKeys(scopedRoleKeys);
    }

    @Override
    public Set<UUID> scopedRoleKeys() {
      return Set.copyOf(this.scopedRoleKeys);
    }

    @Override
    public String toString() {
      return "PlayerBootstrapShape[scope=empty]";
    }
  }

  /** One tenant-bound receiver delegation and its selected public or non-public target. */
  public record TenantBoundDelegationShape(
      ClaimFieldPresence claims,
      AuthorityMapShape authority,
      UUID tenantId,
      Set<UUID> scopedRoleKeys,
      boolean publicProduction,
      Optional<PrivateRealmTarget> selectedPrivateRealm)
      implements ProfileShape {
    public TenantBoundDelegationShape {
      if (claims == null
          || authority == null
          || tenantId == null
          || NIL_UUID.equals(tenantId)
          || selectedPrivateRealm == null) {
        throw invalidCandidate();
      }
      scopedRoleKeys = immutableTenantKeys(scopedRoleKeys);
    }

    @Override
    public Set<UUID> scopedRoleKeys() {
      return Set.copyOf(this.scopedRoleKeys);
    }

    @Override
    public String toString() {
      return "TenantBoundDelegationShape[tenant-bound=true, publicProduction="
          + publicProduction
          + ", scopeEntries="
          + authority.tenantAuthorityKeys().size()
          + ", grants="
          + authority.privateRealmGrants().size()
          + "]";
    }
  }

  /** Explicitly non-tenant private delegation; all tenant and grant collections are empty. */
  public record NonTenantDelegationShape(
      ClaimFieldPresence claims, AuthorityMapShape authority, Set<UUID> scopedRoleKeys)
      implements ProfileShape {
    public NonTenantDelegationShape {
      if (claims == null || authority == null) {
        throw invalidCandidate();
      }
      scopedRoleKeys = immutableTenantKeys(scopedRoleKeys);
    }

    @Override
    public Set<UUID> scopedRoleKeys() {
      return Set.copyOf(this.scopedRoleKeys);
    }

    @Override
    public String toString() {
      return "NonTenantDelegationShape[scope=empty]";
    }
  }

  /** Exact selected non-public realm identity required by a lifecycle-bound private grant. */
  public record PrivateRealmTarget(
      UUID tenantId, String worldSlug, String realmSlug, UUID playtestLifecycleId) {
    public PrivateRealmTarget {
      if (tenantId == null
          || NIL_UUID.equals(tenantId)
          || worldSlug == null
          || worldSlug.isBlank()
          || realmSlug == null
          || realmSlug.isBlank()
          || playtestLifecycleId == null
          || NIL_UUID.equals(playtestLifecycleId)) {
        throw invalidCandidate();
      }
    }

    @Override
    public String toString() {
      return "PrivateRealmTarget[identity=redacted]";
    }
  }

  /**
   * Typed identity evidence for the exact five-field non-public grant entry. The grant version is a
   * canonical positive decimal string so the shape check never rounds a large counter. This is not
   * an ordinary JWT encoder or a replacement for the owning authority codec.
   */
  public record PrivateRealmGrantShape(
      UUID tenantId,
      String worldSlug,
      String realmSlug,
      UUID playtestLifecycleId,
      String grantVersionDecimal) {
    public PrivateRealmGrantShape {
      if (tenantId == null
          || NIL_UUID.equals(tenantId)
          || worldSlug == null
          || worldSlug.isBlank()
          || realmSlug == null
          || realmSlug.isBlank()
          || playtestLifecycleId == null
          || NIL_UUID.equals(playtestLifecycleId)
          || !isCanonicalPositiveDecimal(grantVersionDecimal)) {
        throw invalidCandidate();
      }
    }

    private boolean matches(PrivateRealmTarget target) {
      return tenantId.equals(target.tenantId())
          && worldSlug.equals(target.worldSlug())
          && realmSlug.equals(target.realmSlug())
          && playtestLifecycleId.equals(target.playtestLifecycleId());
    }

    private static boolean isCanonicalPositiveDecimal(String value) {
      if (value == null || value.isEmpty() || value.charAt(0) < '1' || value.charAt(0) > '9') {
        return false;
      }
      for (int index = 1; index < value.length(); index++) {
        char digit = value.charAt(index);
        if (digit < '0' || digit > '9') {
          return false;
        }
      }
      return true;
    }

    @Override
    public String toString() {
      return "PrivateRealmGrantShape[identity=redacted, grantVersion=redacted]";
    }
  }

  /** Profile-specific typed pre-sign shape evidence. */
  public sealed interface ProfileShape
      permits ControlUiShape,
          PlayerBootstrapShape,
          TenantBoundDelegationShape,
          NonTenantDelegationShape {
    ClaimFieldPresence claims();

    AuthorityMapShape authority();

    Set<UUID> scopedRoleKeys();
  }
}
