package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
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
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceKind;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceSelection;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCanonicalPublicRealmScopeOwner;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapEntitlementOwner;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapEntitlementOwner.Evaluation;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.DiscoveryRequest;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.security.JwtClaims;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Real local JWT signing/verifier with controlled Account/GS/entitlement/transaction sources. These
 * tests prove orchestration and refusal, not SQL rollback, mTLS provenance, issuer custody,
 * registry durability, a World hold or gameplay admission.
 */
class AccountCanonicalPublicRealmScopeOwnerTest {
  private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final long PRIVATE_ACCOUNT = 41L;
  private static final String NAMESPACE = "prod";
  private static final String HASH = "a".repeat(64);

  @AfterEach
  void clearContexts() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void genuineAbsenceAndClosedEnrollmentStillProduceOnlyACanonicalPublicSelectorScope() {
    Fixture f = new Fixture(300000L);

    var result = f.owner.listRealmScopes(f.token, "tenant", "world").getFirst();

    assertThat(result.target()).isEqualTo(f.target);
    assertThat(result.membership().accountId()).isEqualTo(ACCOUNT);
    assertThat(result.membership().tenantId()).isEqualTo(TENANT);
    assertThat(result.membership().current()).isEmpty();
    assertThat(result.entitlement()).isSameAs(f.evaluation);
    assertThat(result.entitlement().allowPublicJoin()).isFalse();
    var scope = result.scope();
    assertThat(scope.accountId()).isEqualTo(ACCOUNT);
    assertThat(scope.tenantId()).isEqualTo(TENANT);
    assertThat(scope.gameInstanceId()).isEqualTo(f.target.canonicalGameInstanceId());
    assertThat(scope.playableStateScope()).isEqualTo("SHARED");
    var signed = f.jwt.parseToken(scope.connectScopeId()).getPayload();
    assertThat(signed.getSubject()).isEqualTo(ACCOUNT.toString());
    assertThat(signed.get("accountId")).isEqualTo(ACCOUNT.toString());
    assertThat(JwtClaims.requireText(signed.get("aud"), "aud"))
        .isEqualTo("bootstrap-connect-scope");
    assertThat(signed.get("tenantId")).isEqualTo(TENANT.toString());
    assertThat(signed.get("gameInstanceId"))
        .isEqualTo(f.target.canonicalGameInstanceId().toString());
    assertThat(signed.get("evaluatedAt")).isEqualTo(scope.evaluatedAt());
    assertThat(signed.getExpiration().toInstant())
        .isEqualTo(Instant.parse(scope.connectScopeExpiresAt()));
    assertThat(f.retainedEvidence.get().scopeDigest()).isEqualTo(AccountJoinDigest.scopeV2(scope));
    assertThat(f.retainedEvidence.get().tenantProvenance()).isEqualTo(f.provenance());
    assertThat(result.toString()).doesNotContain(scope.connectScopeId(), f.token);
    assertThat(f.transactions.commits).isEqualTo(2);
    assertThat(f.registryChecks.get()).isEqualTo(3);
    verify(f.scopes).insertCanonical(PRIVATE_ACCOUNT, scope, f.provenance());
    verify(f.memberships, never()).createFreshMembershipForJoin(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "INACTIVE"})
  void closedEnrollmentPreservesCurrentMembershipFactsWithoutRepairOrAdmission(String lifecycle) {
    Fixture f = new Fixture(300000L);
    AccountTenantMembership membership = f.membership(lifecycle);
    f.member.set(Optional.of(membership));

    var result = f.owner.listRealmScopes(f.token, "tenant", "world").getFirst();

    var current = result.membership().current().orElseThrow();
    assertThat(current.lifecycleState()).isEqualTo(lifecycle);
    assertThat(current.gameplayAdmissionAllowed()).isEqualTo(lifecycle.equals("ACTIVE"));
    assertThat(current.membershipVersion()).isEqualTo(7L);
    assertThat(current.membershipAuthorityGeneration()).isEqualTo(3L);
    assertThat(result.entitlement().allowPublicJoin()).isFalse();
    verify(f.memberships, never()).createFreshMembershipForJoin(any(), any());
  }

  @Test
  void scopeExpiryIsCappedByTheActualBootstrapExpiryAndSignedExactly() {
    Fixture f = new Fixture(60000L);
    var bootstrapExpiry = f.jwt.parseToken(f.token).getPayload().getExpiration().toInstant();

    var scope = f.owner.listRealmScopes(f.token, "tenant", "world").getFirst().scope();

    assertThat(Instant.parse(scope.connectScopeExpiresAt())).isEqualTo(bootstrapExpiry);
    assertThat(f.jwt.parseToken(scope.connectScopeId()).getPayload().getExpiration().toInstant())
        .isEqualTo(bootstrapExpiry);
  }

  @Test
  void callerAuthenticationPrecedesAllTargetAndAccountSourceWork() {
    Fixture f = new Fixture(300000L);

    assertCode(
        "CONNECT_CONTEXT_INVALID", () -> f.owner.listRealmScopes("malformed", "tenant", "world"));

    verifyNoInteractions(f.targets, f.entitlements, f.tenants, f.heads, f.memberships, f.scopes);
    assertThat(f.transactions.started).isZero();
  }

  @Test
  void bootstrapExpiryAtTheOwnerClockRefusesBeforeTargetOrScopeSources() {
    Fixture f = new Fixture(300000L);
    f.clock.offset.set(Duration.ofSeconds(301));

    assertCode(
        "CONNECT_CONTEXT_INVALID", () -> f.owner.listRealmScopes(f.token, "tenant", "world"));

    verifyNoInteractions(f.targets, f.entitlements, f.scopes, f.memberships);
  }

  @Test
  void gameplayDenialOmitsTheRealmWithoutReadingOrWritingMembershipAndScopes() {
    Fixture f = new Fixture(300000L);
    when(f.entitlements.evaluate(TENANT)).thenReturn(f.evaluation(false));

    assertThat(f.owner.listRealmScopes(f.token, "tenant", "world")).isEmpty();

    verifyNoInteractions(f.memberships, f.scopes, f.tenants, f.heads);
  }

  @Test
  void exactTenantWorldSelectionNeverUsesABareGlobalWorldOrAnotherTenant() {
    Fixture f = new Fixture(300000L);

    assertThat(f.owner.listRealmScopes(f.token, "other-tenant", "world")).isEmpty();

    verifyNoInteractions(f.entitlements, f.memberships, f.scopes);
  }

  @Test
  void changedIndependentTargetReadRefusesBeforeAnyScopeWrite() {
    Fixture f = new Fixture(300000L);
    f.secondTarget = target(2L);

    assertCode(
        "ADMISSION_POINTER_UNAVAILABLE", () -> f.owner.listRealmScopes(f.token, "tenant", "world"));

    verifyNoInteractions(f.scopes);
  }

  @Test
  void unavailableTransportIsNotAnEmptyRealmAndCannotRetainAScope() {
    Fixture f = new Fixture(300000L);
    doThrow(Status.DEADLINE_EXCEEDED.asRuntimeException())
        .when(f.targets)
        .listVisiblePublicProductionAdmissionTargets(any());

    assertCode("AUTH_UNAVAILABLE", () -> f.owner.listRealmScopes(f.token, "tenant", "world"));

    verifyNoInteractions(f.entitlements, f.scopes);
  }

  @Test
  void changedBillingHeadRefusesRatherThanRefreshingTheOriginalEntitlement() {
    Fixture f = new Fixture(300000L);
    doReturn(f.head, new SourceSelection(SourceKind.NON_PAID_DEMO, f.event(2L)))
        .when(f.heads)
        .readCurrentSourceSelection(TENANT);

    assertCode(
        "ENTITLEMENT_UNAVAILABLE", () -> f.owner.listRealmScopes(f.token, "tenant", "world"));

    verify(f.entitlements).evaluate(TENANT);
    verifyNoInteractions(f.scopes);
  }

  @Test
  void missingTenantSourceAndMalformedMembershipAreNotConvertedToGenuineAbsence() {
    Fixture missingSource = new Fixture(300000L);
    doReturn(Optional.empty()).when(missingSource.tenants).read(TENANT);
    assertCode(
        "AUTH_UNAVAILABLE",
        () -> missingSource.owner.listRealmScopes(missingSource.token, "tenant", "world"));
    verifyNoInteractions(missingSource.scopes);

    Fixture malformed = new Fixture(300000L);
    AccountTenantMembership member = malformed.membership("ACTIVE");
    member.setMembershipAuthorityGeneration(0L);
    malformed.member.set(Optional.of(member));
    assertCode(
        "AUTH_UNAVAILABLE",
        () -> malformed.owner.listRealmScopes(malformed.token, "tenant", "world"));
    verifyNoInteractions(malformed.scopes);
  }

  @Test
  void changedMembershipAndAccountStateRefuseBeforeSigningOrRetainingScope() {
    Fixture changedMember = new Fixture(300000L);
    changedMember.onSecondTargetRead =
        () -> changedMember.member.set(Optional.of(changedMember.membership("ACTIVE")));
    assertCode(
        "AUTH_UNAVAILABLE",
        () -> changedMember.owner.listRealmScopes(changedMember.token, "tenant", "world"));
    verifyNoInteractions(changedMember.scopes);

    Fixture changedAccount = new Fixture(300000L);
    changedAccount.onSecondTargetRead =
        () ->
            changedAccount.account.setLifecycleState(
                AccountLifecycleState.DEACTIVATED_PENDING_DELETE);
    assertCode(
        "AUTH_UNAVAILABLE",
        () -> changedAccount.owner.listRealmScopes(changedAccount.token, "tenant", "world"));
    verifyNoInteractions(changedAccount.scopes);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void registryLossBeforePersistenceOrDuringCommitNeverPublishesScope(boolean duringCommit) {
    Fixture f = new Fixture(300000L);
    if (duringCommit) f.transactions.afterFinalCommit = () -> f.active.set(false);
    else f.onSecondTargetRead = () -> f.active.set(false);

    assertCode(
        "CONNECT_CONTEXT_INVALID", () -> f.owner.listRealmScopes(f.token, "tenant", "world"));

    if (!duringCommit) verifyNoInteractions(f.scopes);
    else assertThat(f.transactions.commits).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void entitlementExpiryBeforePersistenceOrDuringCommitCannotBeRestamped(boolean duringCommit) {
    Fixture f = new Fixture(300000L);
    if (duringCommit)
      f.transactions.afterFinalCommit = () -> f.clock.offset.set(Duration.ofSeconds(20));
    else f.onSecondTargetRead = () -> f.clock.offset.set(Duration.ofSeconds(20));

    assertCode(
        "ENTITLEMENT_UNAVAILABLE", () -> f.owner.listRealmScopes(f.token, "tenant", "world"));

    verify(f.entitlements).evaluate(TENANT);
    if (!duringCommit) verifyNoInteractions(f.scopes);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void alteredV2ScopeOrDigestReadbackProducesNoReturnedValue(boolean digestOnly) {
    Fixture f = new Fixture(300000L);
    f.corruptReadback = !digestOnly;
    f.corruptDigest = digestOnly;

    assertCode("AUTH_UNAVAILABLE", () -> f.owner.listRealmScopes(f.token, "tenant", "world"));

    assertThat(f.transactions.rollbacks).isEqualTo(1);
  }

  @Test
  void failedFinalCommitDoesNotExposeTheSignedSelector() {
    Fixture f = new Fixture(300000L);
    f.transactions.failFinalCommit = true;

    assertCode("AUTH_UNAVAILABLE", () -> f.owner.listRealmScopes(f.token, "tenant", "world"));

    assertThat(f.transactions.commitAttempts).isEqualTo(2);
  }

  @Test
  void ambientSqlAndPlayerContextRefuseWithoutClearingContextOrReadingSources() {
    Fixture f = new Fixture(300000L);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> f.owner.listRealmScopes(f.token, "tenant", "world"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient SQL");
    TransactionSynchronizationManager.clear();
    SessionContext.setContext(ACCOUNT.toString(), List.of(), Map.of());
    assertThatThrownBy(() -> f.owner.listRealmScopes(f.token, "tenant", "world"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("workload-only");
    assertThat(SessionContext.hasAuthenticatedCallerContext()).isTrue();
    verifyNoInteractions(f.targets, f.entitlements, f.scopes);
  }

  private static void assertCode(String expected, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(AuthenticationException.class)
        .extracting(failure -> ((AuthenticationException) failure).getCode())
        .isEqualTo(expected);
  }

  private static final class Fixture {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountTenantMembershipRepository memberships =
        mock(AccountTenantMembershipRepository.class);
    private final FreshTenantIdentityAssociationRepository tenants =
        mock(FreshTenantIdentityAssociationRepository.class);
    private final AccountTenantAuthorityEventRepository heads =
        mock(AccountTenantAuthorityEventRepository.class);
    private final AccountConnectScopeRepository scopes = mock(AccountConnectScopeRepository.class);
    private final CanonicalGameplayAdmissionDecisionReadClient targets =
        mock(CanonicalGameplayAdmissionDecisionReadClient.class);
    private final AccountPublicBootstrapEntitlementOwner entitlements =
        mock(AccountPublicBootstrapEntitlementOwner.class);
    private final SessionService sessions = mock(SessionService.class);
    private final JwtUtil jwt =
        new JwtUtil("controlled-local-canonical-scope-key-01234567890123456789", 300000L);
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicInteger registryChecks = new AtomicInteger();
    private final AtomicInteger targetReads = new AtomicInteger();
    private final AtomicReference<Optional<AccountTenantMembership>> member =
        new AtomicReference<>(Optional.empty());
    private final AtomicReference<CanonicalJoinScopeV2> retained = new AtomicReference<>();
    private final AtomicReference<CanonicalConnectScopeEvidence> retainedEvidence =
        new AtomicReference<>();
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final OffsetClock clock = new OffsetClock();
    private final FreshTenantCreationEvidence source = source();
    private final SourceSelection head = new SourceSelection(SourceKind.NON_PAID_DEMO, event(1L));
    private final Account account = account();
    private final Target target = target(1L);
    private final Evaluation evaluation = evaluation(true);
    private final String token;
    private final AccountCanonicalPublicRealmScopeOwner owner;
    private Target secondTarget = target;
    private Runnable onSecondTargetRead = () -> {};
    private boolean corruptReadback;
    private boolean corruptDigest;

    private Fixture(long bootstrapLifetime) {
      token =
          jwt.generateToken(
              ACCOUNT.toString(),
              bootstrapLifetime,
              Map.of(
                  "aud",
                  "player-bootstrap",
                  "accountId",
                  ACCOUNT.toString(),
                  "jti",
                  UUID.randomUUID().toString()));
      when(accounts.findByAccountUuid(ACCOUNT))
          .thenAnswer(
              invocation -> {
                assertOutsideSql();
                return Optional.of(account);
              });
      when(accounts.findByAccountUuidForUpdate(ACCOUNT))
          .thenAnswer(
              invocation -> {
                assertInsideSql();
                return Optional.of(account);
              });
      when(sessions.isAccountSessionActive(PRIVATE_ACCOUNT, token))
          .thenAnswer(
              invocation -> {
                assertOutsideSql();
                registryChecks.incrementAndGet();
                return active.get();
              });
      when(tenants.read(TENANT))
          .thenAnswer(
              invocation -> {
                assertInsideSql();
                return Optional.of(source);
              });
      when(heads.readCurrentSourceSelection(TENANT))
          .thenAnswer(
              invocation -> {
                assertInsideSql();
                return head;
              });
      when(memberships.findFreshMembershipForUpdate(ACCOUNT, TENANT))
          .thenAnswer(
              invocation -> {
                assertInsideSql();
                return member.get();
              });
      when(entitlements.evaluate(TENANT))
          .thenAnswer(
              invocation -> {
                assertOutsideSql();
                return evaluation;
              });
      when(targets.listVisiblePublicProductionAdmissionTargets(any()))
          .thenAnswer(
              invocation -> {
                assertOutsideSql();
                int count = targetReads.incrementAndGet();
                if (count == 2) onSecondTargetRead.run();
                var request =
                    new DiscoveryRequest(invocation.getArgument(0, UUID.class), NAMESPACE);
                List<Target> values = List.of(count == 1 ? target : secondTarget);
                return CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromDiscoveryResponse(
                    request,
                    CanonicalGameplayAdmissionDecisionReadGrpcCodec.toDiscoveryResponse(
                        request, values));
              });
      doAnswer(
              invocation -> {
                assertInsideSql();
                CanonicalJoinScopeV2 scope = invocation.getArgument(1);
                retained.set(scope);
                retainedEvidence.set(evidence(scope));
                return null;
              })
          .when(scopes)
          .insertCanonical(anyLong(), any(), any());
      when(scopes.findCanonical(any()))
          .thenAnswer(
              invocation -> {
                assertInsideSql();
                CanonicalJoinScopeV2 original = retained.get();
                if (corruptReadback)
                  return Optional.of(
                      new CanonicalJoinScopeV2(
                          original.connectScopeId(),
                          original.accountId(),
                          original.tenantId(),
                          UUID.randomUUID(),
                          original.tenantSlug(),
                          original.worldSlug(),
                          original.realmSlug(),
                          original.playableStateNamespaceId(),
                          original.playableStateScope(),
                          original.gameInstanceId(),
                          original.catalogRevision(),
                          original.pointerVersion(),
                          original.evaluatedAt(),
                          original.connectScopeExpiresAt()));
                return Optional.of(original);
              });
      when(scopes.findCanonicalEvidenceByTokenHash(any()))
          .thenAnswer(
              invocation -> {
                assertInsideSql();
                CanonicalConnectScopeEvidence original = retainedEvidence.get();
                if (corruptDigest)
                  return Optional.of(
                      new CanonicalConnectScopeEvidence(
                          original.scopeTokenHash(),
                          original.privateAccountId(),
                          original.targetClass(),
                          original.accountUuid(),
                          original.tenantUuid(),
                          original.realmId(),
                          original.tenantSlug(),
                          original.worldSlug(),
                          original.realmSlug(),
                          original.playableStateNamespaceUuid(),
                          original.playableStateScope(),
                          original.gameInstanceUuid(),
                          original.catalogRevision(),
                          original.pointerVersion(),
                          original.evaluatedAt(),
                          original.connectScopeExpiresAt(),
                          2,
                          "sha256:" + "b".repeat(64),
                          original.tenantProvenance()));
                return Optional.of(original);
              });
      var properties = new AccountTokenProperties();
      properties.setConnectScopeExpirationMs(120000L);
      var caller = new AccountPlayerBootstrapCallerVerifier(jwt, accounts, sessions);
      owner =
          new AccountCanonicalPublicRealmScopeOwner(
              caller,
              targets,
              entitlements,
              accounts,
              memberships,
              tenants,
              heads,
              scopes,
              jwt,
              properties,
              clock,
              transactions,
              NAMESPACE);
    }

    private Evaluation evaluation(boolean gameplay) {
      Instant observed = clock.instant().minusSeconds(1);
      return new Evaluation(
          TENANT,
          SourceKind.NON_PAID_DEMO,
          gameplay,
          false,
          1,
          1,
          observed,
          observed.plusSeconds(15));
    }

    private VerifiedTenantProvenance provenance() {
      return new VerifiedTenantProvenance(
          null,
          TenantProvenanceKind.FRESH_GAME_DESIGN,
          source.operationId(),
          source.evidenceDigest());
    }

    private CanonicalConnectScopeEvidence evidence(CanonicalJoinScopeV2 scope) {
      return new CanonicalConnectScopeEvidence(
          AccountJoinDigest.tokenHash(scope.connectScopeId()),
          PRIVATE_ACCOUNT,
          "PUBLIC_PRODUCTION",
          ACCOUNT,
          TENANT,
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
          provenance());
    }

    private AccountTenantMembership membership(String lifecycle) {
      AccountTenantMembership value = new AccountTenantMembership();
      value.setId(52L);
      value.setAccount(account);
      value.setTenantUuid(TENANT);
      value.setTenantProvenanceKind(TenantProvenanceKind.FRESH_GAME_DESIGN.name());
      value.setTenantSourceOperationId(source.operationId());
      value.setTenantProvenanceDigest(source.evidenceDigest());
      value.setLifecycleState(lifecycle);
      value.setGameplayAdmissionAllowed(lifecycle.equals("ACTIVE"));
      value.setMembershipVersion(7L);
      value.setMembershipAuthorityGeneration(3L);
      return value;
    }

    private TenantAuthorityEventV1Codec.Event event(long sequence) {
      UUID requestId = UUID.randomUUID();
      var request =
          new TenantAuthorityEventV1Codec.AuthorityRequest(
              requestId,
              "sha256:" + HASH,
              TENANT,
              source.creationRequestId(),
              source.requestDigest());
      var receipt =
          new TenantAuthorityEventV1Codec.BillingReceipt(
              TENANT,
              requestId,
              "sha256:" + HASH,
              TenantAuthorityEventV1Codec.tenantBillingStreamKey(TENANT),
              sequence,
              TenantAuthorityEventV1Codec.tenantBillingEventId(requestId),
              "sha256:" + HASH);
      return TenantAuthorityEventV1Codec.seal(
          request, source, sequence + 1, sequence + 1, sequence, receipt);
    }
  }

  private static Account account() {
    Account account = new Account();
    account.setId(PRIVATE_ACCOUNT);
    account.setAccountUuid(ACCOUNT);
    account.setAccountUuidSourceNumericId(PRIVATE_ACCOUNT);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setLifecycleState(AccountLifecycleState.ACTIVE);
    return account;
  }

  private static FreshTenantCreationEvidence source() {
    UUID creation = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    String digest =
        GameTenantCreationDigest.requestDigest(
            NAMESPACE, creation, "controlled-world", "World", null);
    return new FreshTenantCreationEvidence(
        1,
        NAMESPACE,
        creation,
        operation,
        digest,
        TENANT,
        91L,
        "controlled-world",
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            NAMESPACE,
            creation,
            operation,
            digest,
            TENANT,
            91L,
            "controlled-world",
            "NEW_GAME_ROW"));
  }

  private static Target target(long pointer) {
    UUID realm = UUID.randomUUID();
    UUID playableNamespace = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    UUID holdId = UUID.randomUUID();
    UUID holdFence = UUID.randomUUID();
    String initial = UUID.randomUUID().toString();
    var request =
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            TENANT,
            "world",
            realm,
            playableNamespace,
            "SHARED",
            instance,
            version,
            1,
            initial,
            HASH,
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1,
            null);
    var hold = new WorldCanonicalInitialAdmissionHold.HoldIdentity(request, holdId, holdFence);
    return new Target(
        NAMESPACE,
        "tenant",
        TENANT,
        "world",
        "World",
        realm,
        "main",
        "Main",
        11,
        44,
        playableNamespace,
        "SHARED",
        instance,
        version,
        1,
        1,
        pointer,
        HASH,
        1,
        initial,
        HASH,
        OriginKind.NO_PRIOR_POINTER,
        null,
        holdId,
        holdFence,
        hold.holdBindingDigest(),
        1,
        "sha256:" + HASH,
        OwnerProofOutcome.COMMITTED,
        false,
        Instant.now(),
        "PRESEEDED_ONLY");
  }

  private static void assertOutsideSql() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  private static void assertInsideSql() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
  }

  private static final class RecordingTransactions implements PlatformTransactionManager {
    private int started;
    private int commitAttempts;
    private int commits;
    private int rollbacks;
    private boolean failFinalCommit;
    private Runnable afterFinalCommit = () -> {};

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      TransactionDefinition actual =
          definition == null ? TransactionDefinition.withDefaults() : definition;
      assertThat(actual.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(actual.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
      assertThat(actual.isReadOnly()).isFalse();
      started++;
      TransactionSynchronizationManager.setActualTransactionActive(true);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      commitAttempts++;
      TransactionSynchronizationManager.setActualTransactionActive(false);
      if (commitAttempts == 2 && failFinalCommit)
        throw new IllegalStateException("controlled commit failure");
      commits++;
      if (commitAttempts == 2) afterFinalCommit.run();
    }

    @Override
    public void rollback(TransactionStatus status) {
      rollbacks++;
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  private static final class OffsetClock extends Clock {
    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.offset(Clock.system(zone), offset.get());
    }

    @Override
    public Instant instant() {
      return Instant.now().plus(offset.get());
    }
  }
}
