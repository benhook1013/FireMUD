package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository.CanonicalConnectScopeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceSelection;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapEntitlementOwner.Evaluation;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier.VerifiedCaller;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.DiscoveryRequest;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manually composed public SHARED/PRESEEDED REALMS and canonical selector-scope producer. Bootstrap
 * registry, provider and protected GS reads occur outside SQL. Short Account transactions read
 * current identity, provenance, membership and billing heads; the final transaction retains and
 * exactly reads back the V2 scope. Every repository must use the supplied transaction manager.
 *
 * <p>Browser credentials use their persisted Account/session-registry verifier. Direct text uses
 * the exact same-namespace Game Session peer and canonical caller correlation, with its own
 * invocation deadline; it never fabricates a bootstrap credential or registry success.
 * Membership-independent public visibility is preserved when enrollment is closed. Returned
 * membership/entitlement facts are observations for later selection, never permission to JOIN,
 * create a character or admit gameplay. This owner neither enrolls membership nor claims a World
 * hold, private/ISOLATED support, a registered facade, or qualified credential custody.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Explicitly composed owner collaborators remain private transaction sources.")
public final class AccountCanonicalPublicRealmScopeOwner {
  private final AccountPlayerBootstrapCallerVerifier callers;
  private final CanonicalGameplayAdmissionDecisionReadClient targets;
  private final AccountPublicBootstrapEntitlementOwner entitlements;
  private final AccountRepository accounts;
  private final AccountTenantMembershipRepository memberships;
  private final FreshTenantIdentityAssociationRepository tenants;
  private final AccountTenantAuthorityEventRepository heads;
  private final AccountConnectScopeRepository scopes;
  private final JwtUtil jwt;
  private final Clock clock;
  private final String namespace;
  private final long scopeLifetimeMillis;
  private final TransactionTemplate transaction;

  public AccountCanonicalPublicRealmScopeOwner(
      AccountPlayerBootstrapCallerVerifier callers,
      CanonicalGameplayAdmissionDecisionReadClient targets,
      AccountPublicBootstrapEntitlementOwner entitlements,
      AccountRepository accounts,
      AccountTenantMembershipRepository memberships,
      FreshTenantIdentityAssociationRepository tenants,
      AccountTenantAuthorityEventRepository heads,
      AccountConnectScopeRepository scopes,
      JwtUtil jwt,
      AccountTokenProperties tokenProperties,
      Clock clock,
      PlatformTransactionManager transactions,
      String namespace) {
    this.callers = Objects.requireNonNull(callers);
    this.targets = Objects.requireNonNull(targets);
    this.entitlements = Objects.requireNonNull(entitlements);
    this.accounts = Objects.requireNonNull(accounts);
    this.memberships = Objects.requireNonNull(memberships);
    this.tenants = Objects.requireNonNull(tenants);
    this.heads = Objects.requireNonNull(heads);
    this.scopes = Objects.requireNonNull(scopes);
    this.jwt = Objects.requireNonNull(jwt);
    this.clock = Objects.requireNonNull(clock);
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.namespace = namespace;
    scopeLifetimeMillis = Objects.requireNonNull(tokenProperties).getConnectScopeExpirationMs();
    if (scopeLifetimeMillis <= 0L) {
      throw new IllegalArgumentException("Configured positive connect-scope lifetime required");
    }
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    transaction.setReadOnly(false);
  }

  /** Selects the canonical tenant/world pair through the same complete-source pipeline. */
  public List<RealmScope> listRealmScopes(
      String bootstrapToken, String tenantSlug, String worldSlug) {
    return listRealmScopes(bootstrapToken, tenantSlug, worldSlug, true);
  }

  private List<RealmScope> listRealmScopes(
      String bootstrapToken, String tenantSlug, String worldSlug, boolean tenantQualified) {
    requireSourceContext();
    Caller original = verifyCaller(bootstrapToken);
    return listRealmScopes(
        original, tenantSlug, worldSlug, tenantQualified, () -> verifyCaller(bootstrapToken), null);
  }

  /** Existing direct-text selection, authenticated independently of the browser registry. */
  public RealmScope issueDirectTextScope(
      DirectTextCallerContext caller, DirectTextJoinTarget target) {
    requireSourceContext();
    requireDirectPeer();
    if (caller == null
        || target == null
        || !Objects.equals(caller.tenantId(), target.tenantId())
        || !Objects.equals(caller.realmId(), target.realmId())
        || !Objects.equals(caller.playableStateNamespaceId(), target.playableStateNamespaceId())
        || !Objects.equals(caller.gameInstanceId(), target.gameInstanceId())
        || !Objects.equals(caller.playableStateScope(), target.playableStateScope())
        || caller.sessionId() == null
        || caller.requestId() == null
        || caller.sessionId().isBlank()
        || caller.requestId().isBlank()
        || caller.sessionId().length() > 32
        || caller.requestId().length() > 128) {
      throw new AuthenticationException(
          "CONNECT_CONTEXT_INVALID", "Direct caller correlation is invalid");
    }
    try {
      if (!caller.sessionId().matches("[0-9]+")) {
        throw new NumberFormatException("Invalid transport session");
      }
      if (Long.parseLong(caller.sessionId()) <= 0) {
        throw new NumberFormatException("Invalid transport session");
      }
    } catch (NumberFormatException failure) {
      throw new AuthenticationException(
          "CONNECT_CONTEXT_INVALID", "Direct caller session is invalid", failure);
    }
    // These times describe this Account producer invocation, not a fabricated bootstrap lifetime.
    Instant issuedAt = now();
    Instant deadline = issuedAt.plusMillis(scopeLifetimeMillis);
    java.util.function.Supplier<Caller> authenticate =
        () -> verifyDirectCaller(caller, issuedAt, deadline);
    Caller original = authenticate.get();
    List<RealmScope> selected =
        listRealmScopes(original, null, target.worldSlug(), false, authenticate, target);
    if (selected.size() != 1) throw pointerUnavailable(null);
    return selected.getFirst();
  }

  private Caller verifyDirectCaller(
      DirectTextCallerContext context, Instant issuedAt, Instant deadline) {
    requireDirectPeer();
    if (context.accountId() == null
        || new UUID(0L, 0L).equals(context.accountId())
        || now().isBefore(issuedAt)
        || !now().isBefore(deadline)) {
      throw new AuthenticationException(
          "CONNECT_CONTEXT_INVALID", "Direct caller expired or malformed");
    }
    return inTransaction(
        () -> {
          requireDirectPeer();
          Account account =
              accounts
                  .findByAccountUuidForUpdate(context.accountId())
                  .orElseThrow(
                      () ->
                          new AuthenticationException(
                              "CONNECT_CONTEXT_INVALID", "Direct caller Account is unknown"));
          if (account.getId() == null
              || account.getId() <= 0L
              || !context.accountId().equals(account.getAccountUuid())
              || !Objects.equals(account.getId(), account.getAccountUuidSourceNumericId())
              || !AccountIdentityProvenance.isAccepted(account.getAccountUuidProvenance())
              || account.getLifecycleState() != AccountLifecycleState.ACTIVE) {
            throw authUnavailable("Direct Account owner is unavailable or ineligible", null);
          }
          return new Caller(account.getId(), account.getAccountUuid(), issuedAt, deadline);
        });
  }

  private void requireDirectPeer() {
    var peer = net.firedevops.firemud.common.grpc.GrpcPeerIdentity.current();
    if (peer == null || !peer.isService("game-session-service") || !peer.isInNamespace(namespace)) {
      throw new AuthenticationException(
          "AUTH_UNAVAILABLE", "Exact Game Session workload is required");
    }
  }

  private List<RealmScope> listRealmScopes(
      Caller original,
      String tenantSlug,
      String worldSlug,
      boolean tenantQualified,
      java.util.function.Supplier<Caller> authenticate,
      DirectTextJoinTarget directTarget) {
    if ((tenantQualified && !RealmEntryPolicy.isCanonicalSlug(tenantSlug))
        || !RealmEntryPolicy.isCanonicalSlug(worldSlug)) {
      throw pointerUnavailable(null);
    }
    Optional<Target> selected =
        selectForCaller(readTargets(), tenantSlug, worldSlug, tenantQualified, directTarget);
    if (selected.isEmpty()) {
      if (selectForCaller(readTargets(), tenantSlug, worldSlug, tenantQualified, directTarget)
          .isPresent()) {
        throw pointerUnavailable(null);
      }
      requireSameCaller(original, authenticate.get());
      return List.of();
    }
    Target target = selected.orElseThrow();
    if (directTarget != null
        && (!directTarget.tenantId().equals(target.canonicalTenantId())
            || !directTarget.realmId().equals(target.realmId())
            || !directTarget.realmSlug().equals(target.realmSlug())
            || !directTarget.playableStateNamespaceId().equals(target.playableStateNamespaceId())
            || !directTarget.playableStateScope().equals(target.playableStateScope())
            || !directTarget.gameInstanceId().equals(target.canonicalGameInstanceId())
            || directTarget.catalogRevision() != target.catalogRevision()
            || directTarget.pointerVersion() != target.pointerVersion())) {
      throw new AuthenticationException("CONNECT_SCOPE_MISMATCH", "Direct target changed");
    }
    Evaluation entitlement = evaluate(target);
    if (!entitlement.gameplayAvailable()) {
      requireSameTarget(
          target,
          selectForCaller(readTargets(), tenantSlug, worldSlug, tenantQualified, directTarget));
      requireSameCaller(original, authenticate.get());
      requireFresh(entitlement);
      if (directTarget != null) throw entitlementUnavailable(null);
      return List.of();
    }

    Prepared prepared = inTransaction(() -> readSources(original, target, entitlement));
    requireSameTarget(
        target,
        selectForCaller(readTargets(), tenantSlug, worldSlug, tenantQualified, directTarget));
    requireSameCaller(original, authenticate.get());
    requireFresh(entitlement);
    RealmScope retained =
        inTransaction(
            () -> {
              Prepared current = readSources(original, target, entitlement);
              if (!prepared.tenant().equals(current.tenant())
                  || !prepared.membership().equals(current.membership())) {
                throw authUnavailable("Account discovery sources changed", null);
              }
              if (prepared.head().kind() != current.head().kind()
                  || !prepared.head().matches(current.head().checkpoint())) {
                throw entitlementUnavailable(null);
              }
              requireFresh(entitlement);
              Instant evaluatedAt = now();
              Instant expiresAt = evaluatedAt.plusMillis(scopeLifetimeMillis);
              if (original.expiresAt().isBefore(expiresAt)) expiresAt = original.expiresAt();
              // JWT NumericDate has second precision. The persisted preimage uses that exact
              // expiry.
              expiresAt = expiresAt.truncatedTo(ChronoUnit.SECONDS);
              if (!evaluatedAt.isBefore(expiresAt)) {
                throw new AuthenticationException(
                    "CONNECT_CONTEXT_INVALID", "Bootstrap credential expired");
              }
              CanonicalJoinScopeV2 scope = mintScope(original, target, evaluatedAt, expiresAt);
              VerifiedTenantProvenance provenance =
                  new VerifiedTenantProvenance(
                      null,
                      TenantProvenanceKind.FRESH_GAME_DESIGN,
                      current.tenant().operationId(),
                      current.tenant().evidenceDigest());
              scopes.insertCanonical(original.accountId(), scope, provenance);
              if (!scope.equals(scopes.findCanonical(scope.connectScopeId()).orElse(null))) {
                throw authUnavailable("Canonical scope readback differs", null);
              }
              CanonicalConnectScopeEvidence expected = evidence(original, scope, provenance);
              if (!expected.equals(
                  scopes
                      .findCanonicalEvidenceByTokenHash(expected.scopeTokenHash())
                      .orElse(null))) {
                throw authUnavailable("Canonical scope evidence readback differs", null);
              }
              return new RealmScope(target, scope, current.membership(), entitlement);
            });
    // No value escapes a failed commit or a caller/source observation that expired during commit.
    requireSameCaller(original, authenticate.get());
    requireFresh(entitlement);
    if (!now().isBefore(Instant.parse(retained.scope().connectScopeExpiresAt()))) {
      throw authUnavailable("Canonical scope expired before publication", null);
    }
    return List.of(retained);
  }

  private Caller verifyCaller(String token) {
    requireSourceContext();
    try {
      VerifiedCaller caller = Objects.requireNonNull(callers.requireCaller(token));
      Claims claims = jwt.parseToken(token).getPayload();
      if (claims.getIssuedAt() == null || claims.getExpiration() == null) {
        throw new AuthenticationException(
            "CONNECT_CONTEXT_INVALID", "Incomplete bootstrap credential");
      }
      Instant issuedAt = claims.getIssuedAt().toInstant();
      Instant expiresAt = claims.getExpiration().toInstant();
      JwtClaims.requireText(claims.get("jti"), "jti");
      Instant current = now();
      if (!"player-bootstrap".equals(JwtClaims.requireText(claims.get("aud"), "aud"))
          || !caller.accountUuid().toString().equals(claims.getSubject())
          || !caller.accountUuid().toString().equals(claims.get("accountId"))
          || !issuedAt.isAfter(Instant.EPOCH)
          || !issuedAt.isBefore(expiresAt)
          || current.isBefore(issuedAt)
          || !current.isBefore(expiresAt)) {
        throw new AuthenticationException(
            "CONNECT_CONTEXT_INVALID", "Invalid bootstrap credential");
      }
      return new Caller(caller.accountId(), caller.accountUuid(), issuedAt, expiresAt);
    } catch (AuthenticationException failure) {
      throw failure;
    } catch (JwtException | IllegalArgumentException failure) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Invalid bootstrap credential");
    } catch (RuntimeException failure) {
      throw authUnavailable("Bootstrap caller authority is unavailable", failure);
    }
  }

  private List<Target> readTargets() {
    requireSourceContext();
    try {
      UUID requestId = UUID.randomUUID();
      var response =
          Objects.requireNonNull(targets.listVisiblePublicProductionAdmissionTargets(requestId));
      if (!new DiscoveryRequest(requestId, namespace).equals(response.request())) {
        throw pointerUnavailable(null);
      }
      return List.copyOf(response.targets());
    } catch (AuthenticationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      if (AccountPublicDiscoverySourceFailure.isUnavailable(failure)) {
        throw authUnavailable("Game Session discovery source is unavailable", failure);
      }
      throw pointerUnavailable(failure);
    }
  }

  private Optional<Target> select(
      List<Target> complete, String tenantSlug, String worldSlug, boolean tenantQualified) {
    List<Target> matches =
        complete.stream()
            .filter(
                target ->
                    worldSlug.equals(target.worldSlug())
                        && (!tenantQualified || tenantSlug.equals(target.tenantSlug())))
            .toList();
    if (matches.size() > 1) throw pointerUnavailable(null);
    for (Target target : matches) {
      if (!namespace.equals(target.targetNamespace())
          || !"SHARED".equals(target.playableStateScope())
          || !"PRESEEDED_ONLY".equals(target.characterCreationPolicy())) {
        throw pointerUnavailable(null);
      }
    }
    return matches.stream().findFirst();
  }

  private Optional<Target> selectForCaller(
      List<Target> complete,
      String tenantSlug,
      String worldSlug,
      boolean tenantQualified,
      DirectTextJoinTarget directTarget) {
    List<Target> selectedTenant =
        directTarget == null
            ? complete
            : complete.stream()
                .filter(target -> directTarget.tenantId().equals(target.canonicalTenantId()))
                .toList();
    return select(selectedTenant, tenantSlug, worldSlug, tenantQualified);
  }

  private Evaluation evaluate(Target target) {
    requireSourceContext();
    try {
      Evaluation evaluation =
          Objects.requireNonNull(entitlements.evaluate(target.canonicalTenantId()));
      if (!target.canonicalTenantId().equals(evaluation.canonicalTenantId())
          || evaluation.sourceKind() == null
          || evaluation.entitlementVersion() <= 0L
          || evaluation.tenantBillingSequence() <= 0L) throw entitlementUnavailable(null);
      requireFresh(evaluation);
      return evaluation;
    } catch (AuthenticationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw entitlementUnavailable(failure);
    }
  }

  private Prepared readSources(Caller caller, Target target, Evaluation entitlement) {
    Account account =
        accounts
            .findByAccountUuidForUpdate(caller.accountUuid())
            .orElseThrow(() -> authUnavailable("Account owner is unavailable", null));
    if (!Objects.equals(account.getId(), caller.accountId())
        || !caller.accountUuid().equals(account.getAccountUuid())
        || !Objects.equals(account.getId(), account.getAccountUuidSourceNumericId())
        || !AccountIdentityProvenance.isAccepted(account.getAccountUuidProvenance())
        || account.getLifecycleState() != AccountLifecycleState.ACTIVE) {
      throw authUnavailable("Account owner changed or is ineligible", null);
    }
    FreshTenantCreationEvidence tenant =
        tenants
            .read(target.canonicalTenantId())
            .orElseThrow(() -> authUnavailable("Fresh tenant provenance is unavailable", null));
    if (!namespace.equals(tenant.targetNamespace())
        || !target.canonicalTenantId().equals(tenant.canonicalTenantId())) {
      throw authUnavailable("Fresh tenant provenance differs from the target", null);
    }
    SourceSelection head;
    try {
      head = Objects.requireNonNull(heads.readCurrentSourceSelection(target.canonicalTenantId()));
      if (head.kind() != entitlement.sourceKind()
          || !target.canonicalTenantId().equals(head.checkpoint().tenantId())
          || head.checkpoint().tenantBillingSequence() != entitlement.tenantBillingSequence()) {
        throw entitlementUnavailable(null);
      }
    } catch (AuthenticationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw entitlementUnavailable(failure);
    }
    Optional<AccountTenantMembership> member =
        memberships.findFreshMembershipForUpdate(caller.accountUuid(), target.canonicalTenantId());
    Optional<ExistingMembership> current =
        member.map(
            value -> {
              if (value.getId() == null
                  || value.getId() <= 0L
                  || value.getAccount() == null
                  || !Objects.equals(account.getId(), value.getAccount().getId())
                  || !account.getAccountUuid().equals(value.getAccount().getAccountUuid())
                  || value.getTenantId() != null
                  || !target.canonicalTenantId().equals(value.getTenantUuid())
                  || !TenantProvenanceKind.FRESH_GAME_DESIGN
                      .name()
                      .equals(value.getTenantProvenanceKind())
                  || !tenant.operationId().equals(value.getTenantSourceOperationId())
                  || !tenant.evidenceDigest().equals(value.getTenantProvenanceDigest())
                  || value.getMembershipVersion() <= 0L
                  || value.getMembershipAuthorityGeneration() <= 0L
                  || !List.of("ACTIVE", "INACTIVE").contains(value.getLifecycleState())) {
                throw authUnavailable("Canonical membership evidence is malformed", null);
              }
              return new ExistingMembership(
                  value.getId(),
                  value.getLifecycleState(),
                  value.isGameplayAdmissionAllowed(),
                  value.getMembershipVersion(),
                  value.getMembershipAuthorityGeneration());
            });
    return new Prepared(
        tenant,
        new MembershipFacts(account.getAccountUuid(), target.canonicalTenantId(), current),
        head);
  }

  private CanonicalJoinScopeV2 mintScope(
      Caller caller, Target target, Instant evaluatedAt, Instant expiresAt) {
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
      throw authUnavailable("Scope signing clock is outside its validity", expired);
    }
    Claims signed = jwt.parseToken(token).getPayload();
    if (!caller.accountUuid().toString().equals(signed.getSubject())
        || signed.getExpiration() == null
        || !expiresAt.equals(signed.getExpiration().toInstant())
        || signed.getIssuedAt() == null
        || !signed.getIssuedAt().toInstant().isBefore(expiresAt)
        || !"bootstrap-connect-scope".equals(JwtClaims.requireText(signed.get("aud"), "aud"))) {
      throw authUnavailable("Signed scope profile differs from the retained preimage", null);
    }
    for (var entry : claims.entrySet()) {
      if (entry.getKey().equals("aud")) continue;
      Object actual = signed.get(entry.getKey());
      if (entry.getValue() instanceof Long number) {
        if (JwtClaims.requireLong(actual, entry.getKey(), false) != number) {
          throw authUnavailable("Signed scope fields differ from the retained preimage", null);
        }
      } else if (!entry.getValue().equals(actual)) {
        throw authUnavailable("Signed scope fields differ from the retained preimage", null);
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

  private static CanonicalConnectScopeEvidence evidence(
      Caller caller, CanonicalJoinScopeV2 scope, VerifiedTenantProvenance provenance) {
    return new CanonicalConnectScopeEvidence(
        AccountJoinDigest.tokenHash(scope.connectScopeId()),
        caller.accountId(),
        "PUBLIC_PRODUCTION",
        scope.accountId(),
        scope.tenantId(),
        scope.realmId(),
        scope.tenantSlug(),
        scope.worldSlug(),
        scope.realmSlug(),
        scope.playableStateNamespaceId(),
        scope.playableStateScope(),
        scope.gameInstanceId(),
        scope.catalogRevision(),
        scope.pointerVersion(),
        scope.evaluatedAt(),
        scope.connectScopeExpiresAt(),
        2,
        AccountJoinDigest.scopeV2(scope),
        provenance);
  }

  private <T> T inTransaction(java.util.function.Supplier<T> action) {
    requireSourceContext();
    try {
      return Objects.requireNonNull(
          transaction.execute(
              status -> {
                if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                  throw authUnavailable("Writable Account source transaction required", null);
                }
                return action.get();
              }));
    } catch (AuthenticationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw authUnavailable("Account scope transaction is unavailable", failure);
    }
  }

  private void requireFresh(Evaluation evaluation) {
    Instant current = now();
    if (evaluation.evaluatedAt() == null
        || evaluation.validUntil() == null
        || current.isBefore(evaluation.evaluatedAt())
        || !current.isBefore(evaluation.validUntil())) {
      throw entitlementUnavailable(null);
    }
  }

  private static void requireSameCaller(Caller original, Caller current) {
    if (original.accountId() != current.accountId()
        || !original.accountUuid().equals(current.accountUuid())
        || !original.issuedAt().equals(current.issuedAt())
        || !original.expiresAt().equals(current.expiresAt())) {
      throw new AuthenticationException("CONNECT_CONTEXT_INVALID", "Bootstrap caller changed");
    }
  }

  private static void requireSameTarget(Target original, Optional<Target> current) {
    if (current.isEmpty() || !original.equals(current.orElseThrow()))
      throw pointerUnavailable(null);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static void requireSourceContext() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Canonical public scope preparation refuses ambient SQL");
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null) {
      throw new IllegalStateException(
          "Canonical public scope preparation requires workload-only source context");
    }
  }

  private static AuthenticationException authUnavailable(String message, Throwable cause) {
    return new AuthenticationException("AUTH_UNAVAILABLE", message, cause);
  }

  private static AuthenticationException pointerUnavailable(Throwable cause) {
    return new AuthenticationException(
        "ADMISSION_POINTER_UNAVAILABLE",
        "Canonical public realm target is unavailable or changed",
        cause);
  }

  private static AuthenticationException entitlementUnavailable(Throwable cause) {
    return new AuthenticationException(
        "ENTITLEMENT_UNAVAILABLE",
        "Original public discovery entitlement is unavailable or changed",
        cause);
  }

  private record Caller(long accountId, UUID accountUuid, Instant issuedAt, Instant expiresAt) {}

  private record Prepared(
      FreshTenantCreationEvidence tenant, MembershipFacts membership, SourceSelection head) {}

  /** Current owner facts only; absence does not fabricate versions, generations or membership. */
  public record MembershipFacts(
      UUID accountId, UUID tenantId, Optional<ExistingMembership> current) {
    public MembershipFacts {
      Objects.requireNonNull(accountId);
      Objects.requireNonNull(tenantId);
      current = Objects.requireNonNull(current);
    }
  }

  public record ExistingMembership(
      long membershipId,
      String lifecycleState,
      boolean gameplayAdmissionAllowed,
      long membershipVersion,
      long membershipAuthorityGeneration) {}

  /** An as-of target plus persisted selector evidence, never admission or JOIN permission. */
  public record RealmScope(
      Target target,
      CanonicalJoinScopeV2 scope,
      MembershipFacts membership,
      Evaluation entitlement) {
    @Override
    public String toString() {
      return "CanonicalPublicRealmScope[nonadmitting, redacted]";
    }
  }
}
