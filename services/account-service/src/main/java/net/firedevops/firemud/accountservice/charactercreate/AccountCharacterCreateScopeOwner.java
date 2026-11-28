package net.firedevops.firemud.accountservice.charactercreate;

import io.jsonwebtoken.Claims;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCanonicalConnectScopeReader;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.JwtUtil;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered creation-selector issuer. It accepts only the authenticated public PLAYER_CREATED
 * target capability, persists exact canonical scope evidence, and grants no owner protection,
 * membership, character allocation or gameplay admission. The PRESEEDED selector owner is
 * unchanged.
 */
public final class AccountCharacterCreateScopeOwner {
  private final String namespace;
  private final JwtUtil jwt;
  private final Clock clock;
  private final long lifetimeMillis;
  private final FreshTenantIdentityAssociationRepository tenants;
  private final AccountConnectScopeRepository scopes;
  private final AccountCanonicalConnectScopeReader reader;
  private final TransactionTemplate transaction;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Owner-managed repository remains private and cannot be value-copied.")
  public AccountCharacterCreateScopeOwner(
      String namespace,
      JwtUtil jwt,
      Clock clock,
      AccountTokenProperties properties,
      FreshTenantIdentityAssociationRepository tenants,
      AccountConnectScopeRepository scopes,
      AccountCanonicalConnectScopeReader reader,
      PlatformTransactionManager transactions) {
    this.namespace = AccountCharacterCreateDelegationBindings.namespace(namespace);
    this.jwt = Objects.requireNonNull(jwt);
    this.clock = Objects.requireNonNull(clock);
    this.tenants = Objects.requireNonNull(tenants);
    this.scopes = Objects.requireNonNull(scopes);
    this.reader = Objects.requireNonNull(reader);
    lifetimeMillis = Objects.requireNonNull(properties).getConnectScopeExpirationMs();
    if (lifetimeMillis <= 0) throw new IllegalArgumentException("Positive scope lifetime required");
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    transaction.setReadOnly(false);
    transaction.setTimeout(5);
  }

  public CanonicalJoinScopeV2 issueCanonicalCharacterCreateScope(
      AccountCharacterCreatePrincipal principal,
      AccountCharacterCreatePublicSourceClient.AuthenticatedTarget authenticated) {
    AccountCharacterCreateDelegationBindings.outsideSql();
    var caller = Objects.requireNonNull(principal).authenticateCurrentCaller();
    var response = Objects.requireNonNull(authenticated).response();
    var catalog = response.getCatalog();
    if (!namespace.equals(response.getRequest().getTargetNamespace())
        || !catalog.getVisible()
        || !catalog.getPublicProduction()
        || !"SHARED".equals(catalog.getStateScope())
        || !"PLAYER_CREATED".equals(catalog.getCharacterCreationPolicy())) {
      throw unavailable("Exact public creation target required", null);
    }
    var target =
        new Target(
            UUID.fromString(catalog.getTenantId()),
            UUID.fromString(catalog.getRealmId()),
            catalog.getTenantSlug(),
            catalog.getWorldSlug(),
            catalog.getRealmSlug(),
            UUID.fromString(catalog.getPlayableStateNamespaceId()),
            catalog.getStateScope(),
            UUID.fromString(response.getCanonicalGameInstanceId()),
            catalog.getCatalogRevision(),
            response.getPointer().getPointerVersion());
    Instant evaluated = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    Instant expires = evaluated.plusMillis(lifetimeMillis);
    CanonicalJoinScopeV2 scope = mintScope(caller, target, evaluated, expires);
    // Credential calls remain outside SQL; Account/tenant identity is locked and rechecked by
    // insertCanonical. Selection is evidence only; original source and all owner authorities are
    // independently revalidated when the creation operation acquires protection.
    principal.authenticateCurrentAccount();
    transaction.executeWithoutResult(
        status -> {
          var tenant =
              tenants
                  .read(target.canonicalTenantId())
                  .orElseThrow(() -> unavailable("Fresh tenant source required", null));
          if (!namespace.equals(tenant.targetNamespace())
              || !target.canonicalTenantId().equals(tenant.canonicalTenantId())) {
            throw unavailable("Tenant source differs from creation target", null);
          }
          scopes.insertCanonical(
              caller.accountId(),
              scope,
              new VerifiedTenantProvenance(
                  null,
                  TenantProvenanceKind.FRESH_GAME_DESIGN,
                  tenant.operationId(),
                  tenant.evidenceDigest()));
        });
    CanonicalJoinScopeV2 retained = reader.read(caller.accountUuid(), scope.connectScopeId());
    if (!scope.equals(retained)) throw unavailable("Creation scope readback differs", null);
    principal.authenticateCurrentAccount();
    return retained;
  }

  public CanonicalJoinScopeV2 verifyCanonicalCharacterCreateScope(
      AccountCharacterCreatePrincipal principal, String opaqueScope, String requestedNamespace) {
    AccountCharacterCreateDelegationBindings.outsideSql();
    if (!namespace.equals(requestedNamespace)) throw unavailable("Namespace differs", null);
    UUID account = Objects.requireNonNull(principal).authenticateCurrentAccount();
    var scope = reader.read(account, opaqueScope);
    principal.authenticateCurrentAccount();
    return scope;
  }

  private static IllegalStateException unavailable(String message, Throwable cause) {
    return new IllegalStateException(message, cause);
  }

  private record Target(
      UUID canonicalTenantId,
      UUID realmId,
      String tenantSlug,
      String worldSlug,
      String realmSlug,
      UUID playableStateNamespaceId,
      String playableStateScope,
      UUID canonicalGameInstanceId,
      long catalogRevision,
      long pointerVersion) {}

  private CanonicalJoinScopeV2 mintScope(
      AccountPlayerBootstrapCallerVerifier.VerifiedCaller caller,
      Target target,
      Instant evaluatedAt,
      Instant expiresAt) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("aud", "bootstrap-connect-scope");
    claims.put("accountId", caller.accountUuid().toString());
    claims.put("targetClass", "PUBLIC_PRODUCTION");
    claims.put("scopeDigestVersion", 2L);
    claims.put("tenantId", target.canonicalTenantId().toString());
    claims.put("tenantSlug", target.tenantSlug());
    claims.put("worldSlug", target.worldSlug());
    claims.put("realmId", target.realmId().toString());
    claims.put("realmSlug", target.realmSlug());
    claims.put("playableStateNamespaceId", target.playableStateNamespaceId().toString());
    claims.put("playableStateScope", target.playableStateScope());
    claims.put("gameInstanceId", target.canonicalGameInstanceId().toString());
    claims.put("catalogRevision", target.catalogRevision());
    claims.put("pointerVersion", target.pointerVersion());
    claims.put("evaluatedAt", evaluatedAt.toString());
    claims.put("connectScopeExpiresAt", expiresAt.toString());
    claims.put("jti", UUID.randomUUID().toString());
    // Preserve the owner-selected absolute expiry even if signing starts after a scheduling pause.
    final String token;
    try {
      token = jwt.generateToken(caller.accountUuid().toString(), expiresAt, claims);
    } catch (IllegalArgumentException expired) {
      throw unavailable("Scope signing clock is outside its validity", expired);
    }
    Claims signed = jwt.parseToken(token).getPayload();
    if (!caller.accountUuid().toString().equals(signed.getSubject())
        || signed.getExpiration() == null
        || !expiresAt.equals(signed.getExpiration().toInstant())
        || signed.getIssuedAt() == null
        || !signed.getIssuedAt().toInstant().isBefore(expiresAt)
        || !"bootstrap-connect-scope".equals(JwtClaims.requireText(signed.get("aud"), "aud"))) {
      throw unavailable("Signed scope profile differs from the retained preimage", null);
    }
    for (var entry : claims.entrySet()) {
      if (entry.getKey().equals("aud")) continue;
      Object actual = signed.get(entry.getKey());
      if (entry.getValue() instanceof Long number) {
        if (JwtClaims.requireLong(actual, entry.getKey(), false) != number) {
          throw unavailable("Signed scope fields differ from the retained preimage", null);
        }
      } else if (!entry.getValue().equals(actual)) {
        throw unavailable("Signed scope fields differ from the retained preimage", null);
      }
    }
    return new CanonicalJoinScopeV2(
        token,
        caller.accountUuid(),
        target.canonicalTenantId(),
        target.realmId(),
        target.tenantSlug(),
        target.worldSlug(),
        target.realmSlug(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        target.canonicalGameInstanceId(),
        target.catalogRevision(),
        target.pointerVersion(),
        evaluatedAt.toString(),
        expiresAt.toString());
  }
}
