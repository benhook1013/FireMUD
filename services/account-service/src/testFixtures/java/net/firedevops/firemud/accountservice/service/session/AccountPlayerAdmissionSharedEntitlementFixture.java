package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.dto.DirectTextCallerContext;
import net.firedevops.firemud.accountservice.dto.DirectTextJoinTarget;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountCanonicalRuntimeMembershipSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinTerminalProof;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceKind;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.repository.LegacyTenantSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountCanonicalConnectScopeReader;
import net.firedevops.firemud.accountservice.service.AccountCanonicalFirstJoinTerminalCoordinator;
import net.firedevops.firemud.accountservice.service.AccountCanonicalPublicRealmScopeOwner;
import net.firedevops.firemud.accountservice.service.AccountCanonicalPublicRealmScopeOwner.RealmScope;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionLifecycleService;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapEntitlementOwner;
import net.firedevops.firemud.accountservice.service.impl.AccountPlayerAdmissionEntitlementGrpcService;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Separate global-player composition over the original Account database and real protected target
 * client. Repository insertion, absence enrollment, DEMO provisioning, LOGIN/currentness, canonical
 * scope production and the entitlement receiver use their actual owners. The player has no tenant
 * membership, no roles and no admission permission until the explicit first-JOIN method runs; the
 * original creator is never rewritten.
 *
 * <p>Construct only after the original creator/operator authoring sequence is finished: DEMO
 * advances tenant authority, which deliberately makes the creator's historical baseline unsuitable
 * for current runtime membership evidence. Scope peer/context custody, demo policy, enrollment/OTP
 * delivery and test signing-key custody remain controlled. Paid and browser-only dependencies fail
 * on every call and cannot supply successful evidence. This helper borrows connections, registers
 * no runtime beans, opens no listener and claims no public ingress, World hold or PLAY authority.
 * Its optional first JOIN proves only Account-local atomic membership/event/audit/receipt
 * ownership.
 */
public final class AccountPlayerAdmissionSharedEntitlementFixture {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final AccountControlUiOwnerSourcesFixture sources;
  private final AccountPlayerAdmissionSharedOwnerFixture login;
  private final AccountCanonicalRuntimeMembershipSnapshotRepository snapshots;
  private final AccountDemoTenantEntitlementRepository demos;
  private final DemoTenantEntitlementSnapshot demo;
  private final AccountCanonicalPublicRealmScopeOwner scopes;
  private final AccountCanonicalConnectScopeReader scopeReader;
  private final CanonicalGameplayAdmissionDecisionReadClient targets;
  private final AccountAuthoritySourceEvidenceRepository authority;
  private final AccountAuthorityOutboxRepository outbox;
  private final AccountTenantMembershipRepository members;
  private final AccountMembershipPairAuthorityRepository pairs;
  private final AccountJoinOperationRepository operations;
  private final AccountAuditOutboxRepository audits;
  private final AccountCanonicalFirstJoinTerminalCoordinator firstJoin;
  private final TransactionTemplate joinTransaction;
  private final AccountPlayerAdmissionEntitlementGrpcService receiver;
  private final AccountHostingSubscriptionLifecycleRepository unusedPaidRepository;
  private final StripeHostingSubscriptionObserver unusedProvider;
  private final SessionService unusedBrowserSessions;
  private final Clock clock;
  private final String namespace;

  public AccountPlayerAdmissionSharedEntitlementFixture(
      AccountControlUiOriginalOrderFixture original,
      Path temporary,
      CanonicalGameplayAdmissionDecisionReadClient genuineTargets,
      DemoTenantEntitlementRequest demoRequest)
      throws Exception {
    this(original, temporary, genuineTargets, demoRequest, Clock.systemUTC());
  }

  public AccountPlayerAdmissionSharedEntitlementFixture(
      AccountControlUiOriginalOrderFixture original,
      Path temporary,
      CanonicalGameplayAdmissionDecisionReadClient genuineTargets,
      DemoTenantEntitlementRequest demoRequest,
      Clock clock)
      throws Exception {
    Objects.requireNonNull(original, "Original Account fixture is required");
    targets =
        Objects.requireNonNull(
            genuineTargets, "Actual initialized protected target client is required");
    this.clock = Objects.requireNonNull(clock, "Clock is required");
    FreshTenantCreationEvidence creation = original.creationEvidence();
    requireFirstDemoRequest(demoRequest, creation);
    namespace = creation.targetNamespace();
    sources = original.preparedOriginalCreator().sources();
    login = AccountPlayerAdmissionSharedOwnerFixture.freshGlobalPlayer(original, temporary, clock);

    var generations = new AccountAuthorityGenerationRepository(sources.dsl);
    outbox = new AccountAuthorityOutboxRepository(sources.dsl);
    authority = new AccountAuthoritySourceEvidenceRepository(sources.dsl, generations, outbox);
    var fresh = new FreshTenantIdentityAssociationRepository(sources.dsl, namespace);
    pairs = new AccountMembershipPairAuthorityRepository(sources.dsl);
    members = new AccountTenantMembershipRepository(sources.dsl, sources.accounts, fresh, pairs);
    var roles = new AccountTenantMembershipRoleSnapshotRepository(sources.dsl);
    var billing = new AccountTenantEntitlementOutboxRepository(sources.dsl);
    var tenantEvents =
        new AccountTenantAuthorityEventRepository(sources.dsl, outbox, billing, fresh, generations);
    demos =
        new AccountDemoTenantEntitlementRepository(
            sources.dsl, fresh, generations, billing, tenantEvents);
    snapshots =
        new AccountCanonicalRuntimeMembershipSnapshotRepository(
            sources.accounts,
            generations,
            authority,
            tenantEvents,
            fresh,
            members,
            pairs,
            roles,
            outbox);
    // Missing rows alone are not evidence. Enroll the exact account-fenced absence baseline.
    demo =
        sources.tx(
            () -> {
              sources.accounts.findByAccountUuidForUpdate(login.accountId()).orElseThrow();
              generations.initialize(AuthorityScope.membership(login.accountId(), sources.tenant));
              members.prepareFreshCreatorAbsence(login.accountId(), sources.tenant);
              return demos.provision(demoRequest, creation);
            });

    var retainedScopes =
        new AccountConnectScopeRepository(
            sources.dsl,
            new AccountTenantIdentityResolver(
                new ApprovedLegacyTenantAssociationRepository(
                    sources.dsl, new LegacyTenantSourceEvidence(sources.dsl), namespace),
                namespace),
            fresh);
    unusedPaidRepository = excluded(AccountHostingSubscriptionLifecycleRepository.class);
    unusedProvider = excluded(StripeHostingSubscriptionObserver.class);
    unusedBrowserSessions = excluded(SessionService.class);
    var hosting =
        new AccountHostingSubscriptionLifecycleService(
            unusedPaidRepository, unusedProvider, sources.manager);
    var publicEntitlements =
        new AccountPublicBootstrapEntitlementOwner(
            tenantEvents, hosting, demos, sources.manager, clock);
    var tokens = new AccountTokenProperties();
    var jwt = new JwtUtil(scopeSigningKey(), tokens.getConnectScopeExpirationMs());
    scopeReader =
        new AccountCanonicalConnectScopeReader(jwt, retainedScopes, sources.manager, clock);
    operations = new AccountJoinOperationRepository(sources.dsl, retainedScopes);
    audits = new AccountAuditOutboxRepository(sources.dsl);
    var membershipEvents =
        transactional(
            new AccountMembershipAuthorityEventProducer(
                operations,
                sources.accounts,
                pairs,
                members,
                roles,
                generations,
                outbox,
                authority,
                tenantEvents,
                demos),
            AccountMembershipAuthorityEventProducer.class);
    firstJoin =
        transactional(
            new AccountCanonicalFirstJoinTerminalCoordinator(
                sources.accounts,
                operations,
                members,
                roles,
                outbox,
                pairs,
                audits,
                membershipEvents),
            AccountCanonicalFirstJoinTerminalCoordinator.class);
    joinTransaction = new TransactionTemplate(sources.manager);
    joinTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    joinTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    joinTransaction.setReadOnly(false);
    scopes =
        new AccountCanonicalPublicRealmScopeOwner(
            new AccountPlayerBootstrapCallerVerifier(jwt, sources.accounts, unusedBrowserSessions),
            genuineTargets,
            publicEntitlements,
            sources.accounts,
            members,
            fresh,
            tenantEvents,
            retainedScopes,
            jwt,
            tokens,
            clock,
            sources.manager,
            namespace);
    var owner =
        new AccountPlayerAdmissionEntitlementOwner(
            sources.dsl,
            sources.accounts,
            login.admissionCallerVerifier(),
            login.issuanceRepository(),
            snapshots,
            retainedScopes,
            unusedPaidRepository,
            unusedProvider,
            demos,
            tenantEvents,
            genuineTargets,
            sources.manager,
            clock,
            namespace);
    receiver = new AccountPlayerAdmissionEntitlementGrpcService(owner, namespace);
    assertAbsentMembership();
  }

  public AccountPlayerAdmissionSharedOwnerFixture login() {
    return login;
  }

  public AccountJwtJwksTrustedSource trustedJwksSource() {
    return login.trustedJwksSource();
  }

  /** The receiver creates only an explicit private mTLS listener when its caller requests one. */
  public AccountPlayerAdmissionEntitlementGrpcService receiver() {
    return receiver;
  }

  /**
   * Uses the real scope owner, signed token, retained readback and protected target client. Only
   * this scope-invocation peer is stipulated; no authenticated socket or public ingress is proved.
   */
  public RealmScope issueScopeWithControlledGameSessionCaller(
      DirectTextCallerContext caller, DirectTextJoinTarget target) {
    Objects.requireNonNull(caller, "Controlled direct-text caller is required");
    Objects.requireNonNull(target, "Actual selected target is required");
    if (!login.accountId().equals(caller.accountId())
        || !sources.tenant.equals(caller.tenantId())
        || !sources.tenant.equals(target.tenantId())) {
      throw new IllegalArgumentException("Scope must use the separate player and shared tenant");
    }
    var peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/game-session-service")
            .orElseThrow();
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      RealmScope result = scopes.issueDirectTextScope(caller, target);
      assertThat(result.membership().accountId()).isEqualTo(login.accountId());
      assertThat(result.membership().tenantId()).isEqualTo(sources.tenant);
      assertThat(result.membership().current()).isEmpty();
      assertThat(result.entitlement().sourceKind()).isEqualTo(SourceKind.NON_PAID_DEMO);
      assertThat(result.entitlement().entitlementVersion()).isEqualTo(demo.entitlementVersion());
      assertAbsentMembership();
      return result;
    } finally {
      context.detach(previous);
    }
  }

  /**
   * Explicit owner-local first JOIN. Request and caller binding are controlled correlation inputs,
   * not authentication supplied by this coordinator. Signed scope/current target checks precede
   * SQL; they establish no cross-service hold through commit. Replay uses the identical inputs.
   */
  public CanonicalJoinTerminalProof commitFirstJoin(
      RealmScope issued, String requestId, String controlledCallerBinding) {
    Objects.requireNonNull(issued, "Actual issued realm scope is required");
    var scope = scopeReader.read(login.accountId(), issued.scope().connectScopeId());
    assertThat(scope).isEqualTo(issued.scope());
    assertThat(scope.tenantId()).isEqualTo(sources.tenant);
    requireCurrentTarget(issued, scope);
    var before =
        joinTransaction.execute(
            status -> {
              sources.accounts.findByAccountUuidForUpdate(login.accountId()).orElseThrow();
              operations.insertCanonicalIntent(requestId, scope, controlledCallerBinding);
              return authority.readCurrentIssuerAccountSources(
                  GameSessionAccountDelegationProfile.ISSUER, login.accountId());
            });
    // The second actual target read detects pre-commit drift, but is intentionally not a hold.
    requireCurrentTarget(issued, scope);
    CanonicalJoinTerminalProof proof =
        joinTransaction.execute(
            status -> {
              sources.accounts.findByAccountUuidForUpdate(login.accountId()).orElseThrow();
              requireLiveScope(scope);
              var currentDemo = demos.revalidate(demo);
              operations.bindCanonicalPolicyEvidence(
                  requestId,
                  scope,
                  controlledCallerBinding,
                  currentDemo.allowPublicJoin(),
                  currentDemo.entitlementVersion());
              var committed =
                  firstJoin.commitCanonicalFirstJoin(scope, requestId, controlledCallerBinding);
              assertFirstJoinReceipt(scope, requestId, controlledCallerBinding, committed);
              requireLiveScope(scope);
              return committed;
            });
    Objects.requireNonNull(proof, "Committed first-JOIN receipt is required");
    // Independent transaction proves durable terminal replay, not merely same-transaction reads.
    var replay =
        joinTransaction.execute(
            status -> {
              sources.accounts.findByAccountUuidForUpdate(login.accountId()).orElseThrow();
              requireLiveScope(scope);
              assertThat(
                      operations.insertCanonicalIntent(requestId, scope, controlledCallerBinding))
                  .isFalse();
              var repeated =
                  firstJoin.commitCanonicalFirstJoin(scope, requestId, controlledCallerBinding);
              assertFirstJoinReceipt(scope, requestId, controlledCallerBinding, repeated);
              return repeated;
            });
    assertThat(replay).isEqualTo(proof);
    assertThat(scopeReader.read(login.accountId(), scope.connectScopeId())).isEqualTo(scope);
    var after =
        sources.tx(
            () ->
                authority.readCurrentIssuerAccountSources(
                    GameSessionAccountDelegationProfile.ISSUER, login.accountId()));
    Objects.requireNonNull(before, "Original LOGIN authority sources are required");
    assertThat(after.issuer()).isEqualTo(before.issuer());
    assertThat(after.account()).isEqualTo(before.account());
    assertThat(after.issuanceFence()).isEqualTo(before.issuanceFence());
    login.assertCreatorRemainsNonAdmitting();
    verifyNoInteractions(unusedPaidRepository, unusedProvider, unusedBrowserSessions);
    return proof;
  }

  /** Positive membership source evidence only, after the same real receiver/client reread. */
  public void assertAdmittingEvidence(RuntimePlayerAdmissionEntitlementEvidence evidence) {
    Objects.requireNonNull(evidence, "Actual Account receiver evidence is required");
    evidence.requireCurrentAt(clock.instant());
    var current = sources.tx(() -> snapshots.readCurrent(login.accountId(), sources.tenant));
    assertThat(evidence.membershipSource()).isEqualTo(current);
    assertAdmittingSnapshot(current);
    sources.tx(() -> demos.revalidate(demo));
    login.assertCreatorRemainsNonAdmitting();
    verifyNoInteractions(unusedPaidRepository, unusedProvider, unusedBrowserSessions);
  }

  private void requireCurrentTarget(RealmScope issued, CanonicalJoinScopeV2 scope) {
    var selector =
        new RetainedTargetSelector(
            UUID.nameUUIDFromBytes(
                ("first-join-target-selector/v1:" + scope.connectScopeId())
                    .getBytes(StandardCharsets.UTF_8)),
            scope.accountId(),
            namespace,
            scope.tenantId(),
            scope.tenantSlug(),
            scope.worldSlug(),
            scope.realmId(),
            scope.realmSlug(),
            scope.playableStateNamespaceId(),
            scope.playableStateScope(),
            scope.gameInstanceId(),
            scope.catalogRevision(),
            scope.pointerVersion());
    var resolved = targets.resolvePublishedRealmAdmissionTarget(selector);
    CanonicalGameplayAdmissionDecisionReadGrpcCodec.requireRetainedTargetMatches(
        selector, resolved.currentTarget());
    assertThat(resolved.selector()).isEqualTo(selector);
    var current = targets.readPublishedRealmAdmissionOwner(resolved.strictSelector());
    assertThat(current.selector()).isEqualTo(resolved.strictSelector());
    assertThat(current.currentTarget())
        .isEqualTo(resolved.currentTarget())
        .isEqualTo(issued.target());
    requireLiveScope(scope);
  }

  private void requireLiveScope(CanonicalJoinScopeV2 scope) {
    Instant now = clock.instant();
    if (now.isBefore(Instant.parse(scope.evaluatedAt()))
        || !now.isBefore(Instant.parse(scope.connectScopeExpiresAt()))) {
      throw new IllegalStateException("Original signed first-JOIN scope is no longer current");
    }
  }

  /** All rows are read beneath the same Account fence as the actual terminal owner. */
  private void assertFirstJoinReceipt(
      CanonicalJoinScopeV2 scope,
      String requestId,
      String callerBinding,
      CanonicalJoinTerminalProof proof) {
    var operation = operations.findCanonicalEvidenceForUpdateByRequestId(requestId).orElseThrow();
    assertThat(operation.status()).isEqualTo("COMMITTED");
    assertThat(operation.outcome()).isEqualTo("JOINED");
    assertThat(operation.callerBinding()).isEqualTo(callerBinding);
    assertThat(operation.scopeTokenHash())
        .isEqualTo(AccountJoinDigest.tokenHash(scope.connectScopeId()));
    assertThat(operation.connectScopeDigest()).isEqualTo(AccountJoinDigest.scopeV2(scope));
    assertThat(operation.intentDigest())
        .isEqualTo(AccountJoinDigest.intentV2(requestId, scope, callerBinding));
    assertThat(operation.terminalProof()).isEqualTo(proof);
    assertThat(proof.entitlementVersion()).isEqualTo(demo.entitlementVersion());
    assertThat(proof.membershipVersion()).isEqualTo(2L);
    assertThat(proof.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(proof.eventSequence()).isEqualTo(1L);
    var member =
        members.findFreshMembershipForUpdate(login.accountId(), sources.tenant).orElseThrow();
    assertThat(member.getId()).isEqualTo(proof.membershipId());
    assertThat(member.getAuthorityProvenance()).isEqualTo("EXPLICIT_JOIN");
    var pair = pairs.readForUpdate(login.accountId(), sources.tenant).orElseThrow();
    assertThat(pair.membershipExists()).isTrue();
    assertThat(pair.membershipVersion()).isEqualTo(proof.membershipVersion());
    assertThat(pair.membershipAuthorityGeneration())
        .isEqualTo(proof.membershipAuthorityGeneration());
    assertThat(pair.eventSequence()).isEqualTo(proof.eventSequence());
    assertThat(pair.eventId()).isEqualTo(proof.eventId());
    assertThat(pair.eventDigest()).isEqualTo(proof.eventDigest());
    assertThat(pair.lastTransitionInvalidated()).isFalse();
    var checkpoint = outbox.readCheckpoint(proof.eventStreamKey()).orElseThrow();
    assertThat(checkpoint.outboxSequence()).isEqualTo(proof.eventSequence());
    assertThat(checkpoint.sourceEventId()).isEqualTo(proof.eventId());
    assertThat(checkpoint.sourceEventDigest()).isEqualTo(proof.eventDigest());
    var event = outbox.findEvent(proof.eventStreamKey(), proof.eventSequence()).orElseThrow();
    assertThat(event.requestId()).isEqualTo(requestId);
    assertThat(event.eventId()).isEqualTo(proof.eventId());
    assertThat(event.eventDigest()).isEqualTo(proof.eventDigest());
    var decoded =
        MembershipAuthorityEventV1Codec.verify(new String(event.payload(), StandardCharsets.UTF_8));
    assertThat(event.payload()).containsExactly(decoded.canonicalJsonUtf8());
    var audit = audits.findCanonicalTenantEnvelopeForUpdate(proof.auditEventId()).orElseThrow();
    assertThat(audit.tenantUuid()).isEqualTo(sources.tenant);
    assertThat(audit.tenantIdentityVersion()).isEqualTo(2);
    assertThat(audit.eventType()).isEqualTo("ACCOUNT_JOINED_PUBLIC_PRODUCTION");
    assertThat(audit.producerService()).isEqualTo("account-service");
    assertThat(audit.payloadDigest()).isEqualTo(proof.auditPayloadDigest());
    assertThat(audit.occurredAt()).isEqualTo(proof.auditOccurredAt());
    assertAdmittingSnapshot(snapshots.readCurrent(login.accountId(), sources.tenant));
  }

  private void assertAdmittingSnapshot(Snapshot current) {
    assertThat(current.accountId()).isEqualTo(login.accountId().toString());
    assertThat(current.tenantId()).isEqualTo(sources.tenant.toString());
    assertThat(current.membershipExists()).isTrue();
    assertThat(current.membershipLifecycleState()).isEqualTo("ACTIVE");
    assertThat(current.gameplayAdmissionAllowed()).isTrue();
    assertThat(current.roles()).containsExactly("player");
    assertThat(current.membershipVersion()).containsEntry(sources.tenant.toString(), "2");
    assertThat(current.membershipAuthorityGeneration()).isEqualTo("1");
  }

  private <T> T transactional(T target, Class<T> type) {
    var proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(sources.manager, new AnnotationTransactionAttributeSource()));
    return type.cast(proxy.getProxy(type.getClassLoader()));
  }

  /** Exact current source comparison after the receiving client has authenticated the response. */
  public void assertNonAdmittingEvidence(RuntimePlayerAdmissionEntitlementEvidence evidence) {
    Objects.requireNonNull(evidence, "Actual Account receiver evidence is required");
    evidence.requireCurrentAt(clock.instant());
    var current = sources.tx(() -> snapshots.readCurrent(login.accountId(), sources.tenant));
    assertThat(evidence.membershipSource()).isEqualTo(current);
    assertAbsentMembership();
  }

  /** Confirms actual absence enrollment and current tenant DEMO source without editing history. */
  public void assertAbsentMembership() {
    sources.tx(
        () -> {
          var current = snapshots.readCurrent(login.accountId(), sources.tenant);
          assertThat(current.accountId()).isEqualTo(login.accountId().toString());
          assertThat(current.tenantId()).isEqualTo(sources.tenant.toString());
          assertThat(current.membershipExists()).isFalse();
          assertThat(current.membershipLifecycleState()).isEqualTo("MISSING");
          assertThat(current.gameplayAdmissionAllowed()).isFalse();
          assertThat(current.roles()).isEmpty();
          assertThat(current.membershipVersion()).containsEntry(sources.tenant.toString(), "1");
          assertThat(current.membershipAuthorityGeneration()).isEqualTo("1");
          assertThat(demos.revalidate(demo)).isEqualTo(demo);
          return null;
        });
    login.assertCreatorRemainsNonAdmitting();
    verifyNoInteractions(unusedPaidRepository, unusedProvider, unusedBrowserSessions);
  }

  private static void requireFirstDemoRequest(
      DemoTenantEntitlementRequest request, FreshTenantCreationEvidence creation) {
    Objects.requireNonNull(request, "Explicit controlled demo policy is required");
    if (!creation.canonicalTenantId().equals(request.canonicalTenantId())
        || !creation.creationRequestId().equals(request.tenantCreationRequestId())
        || !creation.requestDigest().equals(request.tenantCreationRequestDigest())
        || request.expectedEntitlementVersion() != null
        || request.expectedTenantAuthorityGeneration() != null
        || request.expectedTenantAuthoritySourceVersion() != null) {
      throw new IllegalArgumentException(
          "First DEMO request must preserve exact creation provenance");
    }
  }

  private static String scopeSigningKey() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private static <T> T excluded(Class<T> type) {
    return mock(
        type,
        invocation -> {
          throw new AssertionError(
              "Excluded paid/browser dependency invoked: " + type.getSimpleName());
        });
  }
}
