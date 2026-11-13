package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import net.firedevops.firemud.account.v1.ReadPlayerAdmissionEntitlementResponse;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountCanonicalRuntimeMembershipSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository.CanonicalConnectScopeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBinding;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.accountservice.repository.AccountHostingPlanQuote;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionCurrentReceipt;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy.Status;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ReadFence;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCurrentRecord;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionIntentRepository.FenceRejectedException;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceKind;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceSelection;
import net.firedevops.firemud.accountservice.security.AccountPlayerAdmissionEntitlementWorkloadGuard;
import net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner;
import net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner.Request;
import net.firedevops.firemud.accountservice.service.impl.AccountPlayerAdmissionEntitlementGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier.VerifiedCaller;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner.AuthenticatedSignerCorrespondence;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Checkpoint;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementGrpcCodec;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Controlled source doubles prove composition and denial, not authenticated custody or SQL locks.
 */
class AccountPlayerAdmissionEntitlementOwnerTest {
  private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String ISSUER = "firemud-account-service";
  private static final String PEER = "spiffe://firemud/ns/prod/sa/game-session-service";
  private static final String PREFIX = "account:auth-authority:v1:";
  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
  private static final String HASH = "a".repeat(64);

  @AfterEach
  void clearTransactionFlags() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void missingMembershipRemainsAnExplicitNonadmittingBaselineWithActualCallerAndTarget()
      throws Exception {
    Fixture f = new Fixture(false);
    var value = f.owner.evaluate(f.request, "controlled-token");
    assertThat(value.fields()).containsEntry("variant", "PLAYER_ADMISSION");
    assertThat(value.fields().get("requestId")).isEqualTo(f.request.requestId().toString());
    assertThat(value.fields().get("callerWorkload")).isEqualTo(PEER);
    assertThat(f.retainedCapacity).hasSize(1);
    assertThat(
            new String(value.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .contains(f.scope.connectScopeId()))
        .isFalse();
    assertThat(
            f.retainedCapacity
                .get(value.fields().get("evaluationIdentity"))
                .get("canonical_evidence", byte[].class))
        .containsExactly(value.canonicalBytes());
    assertThat(value.membershipSource().accountId()).isEqualTo(ACCOUNT.toString());
    assertThat(value.membershipSource().tenantId()).isEqualTo(TENANT.toString());
    assertThat(value.membershipSource().membershipExists()).isFalse();
    assertThat(value.membershipSource().membershipLifecycleState()).isEqualTo("MISSING");
    assertThat(value.membershipSource().gameplayAdmissionAllowed()).isFalse();
    assertThat(value.membershipSource().authorityTuple().issuerAuthGeneration()).isEqualTo("1");
    assertThat(value.membershipSource().authorityTuple().accountAuthorityGeneration())
        .isEqualTo("1");
    assertThat(value.membershipSource().authorityTuple().membershipAuthorityGeneration())
        .containsExactlyEntriesOf(Map.of(TENANT.toString(), "1"));
    assertThat(value.fields())
        .containsEntry(
            "verifiedConnectScopeTokenHash", AccountJoinDigest.tokenHash(f.scope.connectScopeId()))
        .doesNotContainKey("verifiedConnectScopeId")
        .containsEntry("sourceKind", "NON_PAID_DEMO");
    var binding = (Map<?, ?>) value.fields().get("target");
    assertThat(binding.get("realmId")).isEqualTo(f.scope.realmId().toString());
    assertThat(binding.get("pointerVersion")).isEqualTo("3");
    assertThat(binding.get("playableStateScope")).isEqualTo("SHARED");
    assertThat(binding.get("characterCreationPolicy")).isEqualTo("PRESEEDED_ONLY");
    assertThat(value.toString()).doesNotContain("controlled-token", f.scope.connectScopeId());
    assertThat(f.transactions.events).containsExactly("begin", "commit", "begin", "commit");
    verifyNoInteractions(f.hosting, f.observer);
  }

  @Test
  void capacityRetentionFailureRollsBackSourceCaptureWithoutReturningEvidence() throws Exception {
    Fixture f = new Fixture(true);
    f.refuseCapacityRetention = true;
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .isInstanceOf(DataAccessException.class)
        .hasMessage("Controlled capacity retention refusal");
    assertThat(f.retainedCapacity).isEmpty();
    assertThat(f.transactions.events).containsExactly("begin", "commit", "begin", "rollback");
  }

  @Test
  void missingCapacityRetentionReadbackRollsBackAsSourceUnavailable() throws Exception {
    Fixture f = new Fixture(true);
    f.missingCapacityRetentionReadback = true;
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .isInstanceOf(AccountPlayerAdmissionEntitlementOwner.SourceUnavailableException.class)
        .hasMessage("ENTITLEMENT_UNAVAILABLE: current player source evidence is unavailable")
        .hasNoCause();
    assertThat(f.retainedCapacity).isEmpty();
    assertThat(f.transactions.events).containsExactly("begin", "commit", "begin", "rollback");
  }

  @Test
  void existingMembershipCarriesItsIndependentVersionAndPositiveCheckpoint() throws Exception {
    Fixture f = new Fixture(true);
    var value = f.owner.evaluate(f.request, "controlled-token");
    assertThat(value.membershipSource().membershipExists()).isTrue();
    assertThat(value.membershipSource().membershipVersion())
        .containsExactlyEntriesOf(Map.of(TENANT.toString(), "1"));
    assertThat(value.membershipSource().gameplayAdmissionAllowed()).isTrue();
    assertThat(value.membershipSource().sourceEvidence())
        .anySatisfy(
            source -> {
              assertThat(source.eventId()).isEqualTo("membership-event-1");
              assertThat(source.eventDigest()).startsWith("sha256:");
            });
    var order = inOrder(f.accounts, f.issuances, f.memberships, f.scopes, f.demos);
    order.verify(f.accounts).findByAccountUuidForUpdate(ACCOUNT);
    order.verify(f.issuances).readCurrentCommittedCandidateByJti(f.identity.tokenJti());
    order.verify(f.memberships).readCurrent(ACCOUNT, TENANT);
    order.verify(f.scopes).findCanonical(f.scope.connectScopeId());
    order
        .verify(f.scopes)
        .findCanonicalEvidenceByTokenHash(AccountJoinDigest.tokenHash(f.scope.connectScopeId()));
    order.verify(f.demos).readCurrent(TENANT);
  }

  @ParameterizedTest
  @EnumSource(Status.class)
  void paidReadRunsProviderOutsideSqlThenCapturesCurrentReceiptAndPlayerInFinalTransaction(
      Status status) throws Exception {
    Fixture f = new Fixture(true);
    when(f.sources.readCurrentSourceSelection(TENANT))
        .thenReturn(new SourceSelection(SourceKind.PAID_HOSTING, f.tenantEvent));
    var fence = mock(ReadFence.class);
    var observed = mock(StripeHostingSubscriptionObserver.ObservedSubscription.class);
    when(f.hosting.prepareCurrentRead(TENANT))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return fence;
            });
    when(f.observer.observe(fence.binding()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return observed;
            });
    when(f.hosting.readCurrent(fence, observed))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return f.receipt(status);
            });
    var value = f.owner.evaluate(f.request, "controlled-token");
    assertThat(value.fields())
        .containsEntry("sourceKind", "PAID_HOSTING")
        .containsEntry("subscriptionStatus", status.storageValue())
        .containsEntry("tenantBillingSequence", "1")
        .containsEntry("tenantAuthorityGeneration", "2");
    boolean available = status != Status.SUSPENDED && status != Status.CANCELED;
    assertThat(value.fields())
        .containsEntry("gameplayAvailable", available)
        .containsEntry("allowPublicJoin", available && status != Status.GRACE)
        .containsEntry("allowNewGameplayBindings", available && status != Status.GRACE)
        .containsEntry("allowNewInstanceStarts", available && status != Status.GRACE);
    assertThat(f.transactions.events)
        .containsExactly("begin", "commit", "begin", "commit", "begin", "commit");
    var order = inOrder(f.observer, f.accounts, f.hosting);
    order.verify(f.hosting).prepareCurrentRead(TENANT);
    order.verify(f.observer).observe(fence.binding());
    order.verify(f.accounts).findByAccountUuidForUpdate(ACCOUNT);
    order.verify(f.hosting).readCurrent(fence, observed);
  }

  @ParameterizedTest
  @ValueSource(strings = {"prepare", "current"})
  void paidReadFenceRefusalsBecomeSanitizedUnavailableWithoutDemoFallback(String stage)
      throws Exception {
    Fixture f = new Fixture(true);
    when(f.sources.readCurrentSourceSelection(TENANT))
        .thenReturn(new SourceSelection(SourceKind.PAID_HOSTING, f.tenantEvent));
    var fence = mock(ReadFence.class);
    var observed = mock(StripeHostingSubscriptionObserver.ObservedSubscription.class);
    when(f.hosting.prepareCurrentRead(TENANT)).thenReturn(fence);
    when(f.observer.observe(fence.binding())).thenReturn(observed);
    var refusal = new FenceRejectedException("private paid read detail");
    if ("prepare".equals(stage)) {
      when(f.hosting.prepareCurrentRead(TENANT)).thenThrow(refusal);
    } else {
      when(f.hosting.readCurrent(fence, observed)).thenThrow(refusal);
    }

    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .isExactlyInstanceOf(
            AccountPlayerAdmissionEntitlementOwner.SourceUnavailableException.class)
        .hasMessage("ENTITLEMENT_UNAVAILABLE: current player source evidence is unavailable")
        .hasNoCause();
    assertThat(f.transactions.events).endsWith("rollback");
    verifyNoInteractions(f.demos);
    if ("prepare".equals(stage)) {
      verifyNoInteractions(f.observer, f.targets);
    } else {
      verify(f.hosting).readCurrent(fence, observed);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"prepare", "current"})
  void unrelatedPaidReadFaultsRemainUnexpectedForInternalClassification(String stage)
      throws Exception {
    Fixture f = new Fixture(true);
    when(f.sources.readCurrentSourceSelection(TENANT))
        .thenReturn(new SourceSelection(SourceKind.PAID_HOSTING, f.tenantEvent));
    var fence = mock(ReadFence.class);
    var observed = mock(StripeHostingSubscriptionObserver.ObservedSubscription.class);
    when(f.hosting.prepareCurrentRead(TENANT)).thenReturn(fence);
    when(f.observer.observe(fence.binding())).thenReturn(observed);
    var unexpected = new IllegalArgumentException("private unexpected paid read detail");
    if ("prepare".equals(stage)) {
      when(f.hosting.prepareCurrentRead(TENANT)).thenThrow(unexpected);
    } else {
      when(f.hosting.readCurrent(fence, observed)).thenThrow(unexpected);
    }

    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token")).isSameAs(unexpected);
    assertThat(f.transactions.events).endsWith("rollback");
    verifyNoInteractions(f.demos);
  }

  @Test
  void sourceTimeoutReachesJdbcLockingStatementAndRollsBackWithoutReturningEvidence()
      throws Exception {
    Fixture f = new Fixture(false);
    var dataSource = mock(DataSource.class);
    var connection = mock(Connection.class);
    try (var statement = mock(PreparedStatement.class)) {
      when(dataSource.getConnection()).thenReturn(connection);
      when(connection.getAutoCommit()).thenReturn(true, false);
      when(connection.getTransactionIsolation())
          .thenReturn(Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_SERIALIZABLE);
      when(connection.prepareStatement(anyString()))
          .thenAnswer(
              invocation -> {
                assertThat(invocation.getArgument(0, String.class)).endsWith("for update");
                return statement;
              });
      when(statement.execute()).thenThrow(new SQLTimeoutException("Controlled timeout", "57014"));
      // The real Spring proxy transfers the remaining transaction budget to jOOQ's JDBC statement.
      // The driver is controlled here; physical PostgreSQL cancellation is separate proof.
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
      var owner =
          new AccountPlayerAdmissionEntitlementOwner(
              dsl,
              new AccountRepository(dsl),
              f.callers,
              f.issuances,
              f.memberships,
              f.scopes,
              f.hosting,
              f.observer,
              f.demos,
              f.sources,
              f.targets,
              new DataSourceTransactionManager(dataSource),
              Clock.fixed(NOW, java.time.ZoneOffset.UTC),
              "prod");

      assertThatThrownBy(() -> owner.evaluate(f.request, "controlled-token"))
          .isExactlyInstanceOf(DataAccessException.class)
          .hasRootCauseExactlyInstanceOf(SQLTimeoutException.class);
      verify(statement).execute();
      verify(statement).close();
      verify(statement).setQueryTimeout(intThat(seconds -> seconds > 0 && seconds <= 5));
      verify(connection).rollback();
      verifyNoInteractions(
          f.issuances,
          f.memberships,
          f.scopes,
          f.hosting,
          f.observer,
          f.demos,
          f.sources,
          f.targets);
    }
  }

  @Test
  void scopeDeadlineReachedDuringFinalCommitReturnsNoValue() throws Exception {
    Fixture f = new Fixture(false);
    f.transactions.afterSecondCommit = () -> f.time.set(NOW.plusSeconds(60));
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .isExactlyInstanceOf(
            AccountPlayerAdmissionEntitlementOwner.SourceUnavailableException.class);
    assertThat(f.transactions.events).containsExactly("begin", "commit", "begin", "commit");
  }

  @Test
  void callerAccountMismatchFailsBeforeAnySourceRead() throws Exception {
    Fixture f = new Fixture(false);
    var original = f.request.target();
    var other =
        new CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector(
            original.readRequestId(),
            UUID.randomUUID(),
            original.targetNamespace(),
            original.tenantId(),
            original.tenantSlug(),
            original.worldSlug(),
            original.realmId(),
            original.realmSlug(),
            original.playableStateNamespaceId(),
            original.playableStateScope(),
            original.gameInstanceId(),
            original.catalogRevision(),
            original.pointerVersion());
    assertThatThrownBy(
            () ->
                f.owner.evaluate(
                    new Request(other, f.scope.connectScopeId(), PEER), "controlled-token"))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    verifyNoInteractions(
        f.accounts,
        f.issuances,
        f.memberships,
        f.scopes,
        f.sources,
        f.demos,
        f.hosting,
        f.observer,
        f.targets);
  }

  @Test
  void unavailableOrChangedAccountSourceHeadNeverChoosesAnotherFamily() throws Exception {
    Fixture f = new Fixture(false);
    when(f.sources.readCurrentSourceSelection(TENANT))
        .thenThrow(new IllegalStateException("controlled missing source"));
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .hasMessageContaining("controlled missing source");
    verifyNoInteractions(f.demos, f.hosting, f.observer, f.targets);

    Fixture changed = new Fixture(false);
    when(changed.sources.readCurrentSourceSelection(TENANT))
        .thenReturn(
            new SourceSelection(SourceKind.NON_PAID_DEMO, changed.tenantEvent),
            new SourceSelection(SourceKind.PAID_HOSTING, changed.tenantEvent));
    assertThatThrownBy(() -> changed.owner.evaluate(changed.request, "controlled-token"))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    verifyNoInteractions(changed.demos, changed.hosting, changed.observer);
  }

  @Test
  void currentCallerLossDoesNotBecomeTenantOrBillingOwnerAuthority() throws Exception {
    Fixture f = new Fixture(false);
    when(f.issuances.readCurrentCommittedCandidateByJti(f.identity.tokenJti()))
        .thenReturn(Optional.empty());
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    verifyNoInteractions(f.memberships, f.scopes, f.demos, f.hosting, f.observer);
    assertThat(f.transactions.events).containsExactly("begin", "rollback");
  }

  @Test
  void absentCanonicalScopeRefusesPrivatePlaytestAndNumericScopeAliases() throws Exception {
    Fixture f = new Fixture(false);
    when(f.scopes.findCanonical(f.scope.connectScopeId())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    verifyNoInteractions(f.demos, f.hosting, f.observer);
  }

  @Test
  void anotherAccountsScopeFailsBeforeEntitlementCapture() throws Exception {
    Fixture f = new Fixture(false);
    when(f.scopes.findCanonicalEvidenceByTokenHash(
            AccountJoinDigest.tokenHash(f.scope.connectScopeId())))
        .thenReturn(
            Optional.of(f.scopeEvidence(UUID.randomUUID(), f.scope.connectScopeExpiresAt())));
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    verifyNoInteractions(f.demos);
  }

  @Test
  void expiredScopeDoesNotReturnCapturedAuthority() throws Exception {
    Fixture f = new Fixture(false);
    when(f.scopes.findCanonicalEvidenceByTokenHash(
            AccountJoinDigest.tokenHash(f.scope.connectScopeId())))
        .thenReturn(Optional.of(f.scopeEvidence(ACCOUNT, NOW.toString())));
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    assertThat(f.transactions.events).containsExactly("begin", "commit");
  }

  @Test
  void changedTenantCheckpointCannotBeCombinedWithAnOlderEntitlement() throws Exception {
    Fixture f = new Fixture(false);
    var original = f.demo;
    var changed =
        new DemoTenantEntitlementSnapshot(
            TENANT,
            f.source,
            "NON_PAID_DEMO",
            "ACTIVE",
            null,
            false,
            true,
            true,
            true,
            true,
            original.quotas(),
            1,
            2,
            2,
            original.outboxStreamKey(),
            1,
            original.eventId(),
            original.eventDigest(),
            original.snapshotIdentity(),
            original.tenantAuthorityOutboxStreamKey(),
            2,
            original.tenantAuthorityEventId(),
            original.tenantAuthorityEventDigest());
    when(f.demos.readCurrent(TENANT)).thenReturn(changed);
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    assertThat(f.transactions.events).containsExactly("begin", "commit", "begin", "rollback");
  }

  @Test
  void commitFailureReturnsNoEvidence() throws Exception {
    Fixture f = new Fixture(false);
    f.transactions.failCommit = true;
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .hasMessageContaining("controlled commit failure");
    assertThat(f.transactions.events).containsExactly("begin", "commit", "begin", "commit");
  }

  @Test
  void wrongWorkloadAndAmbientTransactionFailBeforeCallerOrSourceReads() throws Exception {
    Fixture f = new Fixture(false);
    Request wrongPeer =
        new Request(
            f.request.target(),
            f.scope.connectScopeId(),
            "spiffe://firemud/ns/other/sa/game-session-service");
    assertThatThrownBy(() -> f.owner.evaluate(wrongPeer, "controlled-token"))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    verifyNoInteractions(
        f.callers,
        f.accounts,
        f.issuances,
        f.memberships,
        f.scopes,
        f.demos,
        f.hosting,
        f.observer);
  }

  @Test
  void unavailableProtectedTargetReturnsNoValueOrAlternateEntitlementSource() throws Exception {
    Fixture f = new Fixture(false);
    doThrow(new IllegalStateException("controlled target unavailable"))
        .when(f.targets)
        .resolvePublishedRealmAdmissionTarget(any());
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .hasMessageContaining("controlled target unavailable");
    verifyNoInteractions(f.demos, f.hosting, f.observer);
    assertThat(f.transactions.events).containsExactly("begin", "commit");
  }

  @ParameterizedTest
  @MethodSource("protectedTargetFailures")
  void protectedTargetFailuresKeepTheirClassificationThroughActualOwnerAndReceiver(
      String stage, RuntimeException failure, io.grpc.Status.Code expected) throws Exception {
    Fixture f = new Fixture(false);
    var resolved = f.targets.resolvePublishedRealmAdmissionTarget(f.request.target());
    // Keep the strict selector stable: the controlled resolver otherwise installs a fresh read.
    doReturn(resolved).when(f.targets).resolvePublishedRealmAdmissionTarget(f.request.target());
    if ("resolve".equals(stage)) {
      doThrow(failure).when(f.targets).resolvePublishedRealmAdmissionTarget(any());
    } else {
      doThrow(failure).when(f.targets).readPublishedRealmAdmissionOwner(any());
    }
    clearInvocations(f.targets);
    var direct = assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"));
    if (expected == io.grpc.Status.Code.UNAVAILABLE) {
      direct
          .isExactlyInstanceOf(
              AccountPlayerAdmissionEntitlementOwner.SourceUnavailableException.class)
          .hasMessage("ENTITLEMENT_UNAVAILABLE: current player source evidence is unavailable")
          .hasNoCause();
    } else if (expected == io.grpc.Status.Code.FAILED_PRECONDITION) {
      direct
          .isExactlyInstanceOf(
              AccountPlayerAdmissionEntitlementOwner.TargetPreconditionException.class)
          .hasNoCause();
    } else {
      direct.isSameAs(failure);
    }
    assertReceiverRefusal(f, expected);
    verify(f.targets, times(2)).resolvePublishedRealmAdmissionTarget(f.request.target());
    if ("strict".equals(stage)) {
      verify(f.targets, times(2)).readPublishedRealmAdmissionOwner(resolved.strictSelector());
    } else {
      verify(f.targets, never()).readPublishedRealmAdmissionOwner(any());
    }
    verifyNoInteractions(f.demos, f.hosting, f.observer);
    assertThat(f.transactions.events).containsExactly("begin", "commit", "begin", "commit");
  }

  @SuppressWarnings("unchecked")
  private static void assertReceiverRefusal(Fixture f, io.grpc.Status.Code expected)
      throws Exception {
    String token = "controlled-token.payload.signature";
    var caller = f.callers.verifyPresentedCompactToken("controlled-token");
    when(f.callers.verifyPresentedCompactToken(token)).thenReturn(caller);
    try (var guards =
        mockConstruction(
            AccountPlayerAdmissionEntitlementWorkloadGuard.class,
            (guard, context) ->
                when(guard.requireAuthenticatedGameSessionCaller()).thenReturn(PEER))) {
      var receiver = new AccountPlayerAdmissionEntitlementGrpcService(f.owner, "prod");
      StreamObserver<ReadPlayerAdmissionEntitlementResponse> observer = mock(StreamObserver.class);
      receiver.readPlayerAdmissionEntitlement(
          RuntimePlayerAdmissionEntitlementGrpcCodec.toRequest(
              new RuntimePlayerAdmissionEntitlementGrpcCodec.Request(
                  f.request.target(), f.request.connectScopeId()),
              token),
          observer);
      var refused = ArgumentCaptor.forClass(Throwable.class);
      verify(observer).onError(refused.capture());
      var status = StatusProto.fromThrowable(refused.getValue());
      assertThat(status).isNotNull();
      assertThat(status.getCode()).isEqualTo(expected.value());
      String message = "Player-admission entitlement evidence is unavailable";
      assertThat(status.getMessage()).isEqualTo(message);
      assertThat(status.getDetailsCount()).isOne();
      assertThat(status.getDetails(0).unpack(ErrorDetail.class))
          .isEqualTo(ErrorDetail.newBuilder().setCode(expected.name()).setMessage(message).build());
      assertThat(refused.getValue()).hasNoCause();
      assertThat(status.toString())
          .doesNotContain(token, f.scope.connectScopeId(), "private target detail");
      verify(observer, never()).onNext(any());
      verify(observer, never()).onCompleted();
    }
  }

  private static Stream<Arguments> protectedTargetFailures() {
    return Stream.of("resolve", "strict")
        .flatMap(
            stage -> {
              Stream<RuntimeException> outages =
                  Stream.of(
                      new CanonicalGameplayAdmissionDecisionReadClient.ClientUnavailableException(
                          new IllegalStateException("private target detail")),
                      io.grpc.Status.UNAVAILABLE
                          .withDescription("private target detail")
                          .asRuntimeException(),
                      io.grpc.Status.DEADLINE_EXCEEDED
                          .withDescription("private target detail")
                          .asRuntimeException(),
                      io.grpc.Status.CANCELLED
                          .withDescription("private target detail")
                          .asRuntimeException(),
                      io.grpc.Status.ABORTED
                          .withDescription("private target detail")
                          .asRuntimeException());
              Stream<RuntimeException> unexpected =
                  Stream.of(
                      io.grpc.Status.INVALID_ARGUMENT
                          .withDescription("private target detail")
                          .asRuntimeException(),
                      new IllegalArgumentException("private target detail"),
                      new IllegalStateException(
                          "Game Session returned invalid current target evidence",
                          new IllegalArgumentException("private target detail")),
                      new NullPointerException("private target detail"));
              return Stream.concat(
                  Stream.concat(
                      outages.map(
                          failure -> Arguments.of(stage, failure, io.grpc.Status.Code.UNAVAILABLE)),
                      Stream.of(
                          Arguments.of(
                              stage,
                              io.grpc.Status.FAILED_PRECONDITION
                                  .withDescription("private target detail")
                                  .asRuntimeException(),
                              io.grpc.Status.Code.FAILED_PRECONDITION))),
                  unexpected.map(
                      failure -> Arguments.of(stage, failure, io.grpc.Status.Code.INTERNAL)));
            });
  }

  @Test
  void changedOwnerPointerFailsExactRetainedTargetComparison() throws Exception {
    Fixture f = new Fixture(false);
    var selector =
        new CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector(
            f.request.requestId(),
            ACCOUNT,
            "prod",
            TENANT,
            f.scope.tenantSlug(),
            f.scope.worldSlug(),
            f.scope.realmId(),
            f.scope.realmSlug(),
            f.scope.playableStateNamespaceId(),
            "SHARED",
            f.scope.gameInstanceId(),
            f.scope.catalogRevision(),
            f.scope.pointerVersion());
    var resolved = f.targets.resolvePublishedRealmAdmissionTarget(selector);
    when(resolved.currentTarget().pointerVersion()).thenReturn(f.scope.pointerVersion() + 1);
    doReturn(resolved).when(f.targets).resolvePublishedRealmAdmissionTarget(selector);
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .isExactlyInstanceOf(
            AccountPlayerAdmissionEntitlementOwner.TargetPreconditionException.class)
        .hasNoCause();
    assertReceiverRefusal(f, io.grpc.Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(f.demos, f.hosting, f.observer);
  }

  @ParameterizedTest
  @ValueSource(strings = {"resolved-selector", "strict-selector", "strict-target", "current-scope"})
  void changedProtectedTargetComparisonsRemainPreconditionsThroughOwnerAndReceiver(String change)
      throws Exception {
    Fixture f = new Fixture(false);
    var resolved = f.targets.resolvePublishedRealmAdmissionTarget(f.request.target());
    var read = f.targets.readPublishedRealmAdmissionOwner(resolved.strictSelector());
    doReturn(resolved).when(f.targets).resolvePublishedRealmAdmissionTarget(f.request.target());
    clearInvocations(f.targets);
    switch (change) {
      case "resolved-selector" ->
          when(resolved.selector())
              .thenReturn(
                  mock(
                      CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector
                          .class));
      case "strict-selector" ->
          when(read.selector())
              .thenReturn(mock(CanonicalGameplayRosterOwnerReadEvidence.Request.class));
      case "strict-target" ->
          when(read.currentTarget())
              .thenReturn(mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target.class));
      case "current-scope" ->
          when(resolved.currentTarget().playableStateScope()).thenReturn("ISOLATED");
      default -> throw new AssertionError("Unknown controlled target change");
    }

    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .isExactlyInstanceOf(
            AccountPlayerAdmissionEntitlementOwner.TargetPreconditionException.class)
        .hasNoCause();
    assertReceiverRefusal(f, io.grpc.Status.Code.FAILED_PRECONDITION);
    verify(f.targets, times(2)).resolvePublishedRealmAdmissionTarget(f.request.target());
    if (change.startsWith("strict-")) {
      verify(f.targets, times(2)).readPublishedRealmAdmissionOwner(resolved.strictSelector());
    } else {
      verify(f.targets, never()).readPublishedRealmAdmissionOwner(any());
    }
    verifyNoInteractions(f.demos, f.hosting, f.observer);
    assertThat(f.transactions.events).containsExactly("begin", "commit", "begin", "commit");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"PLAYER_CREATED", "unknown", ""})
  void unsupportedCurrentPolicyCannotBecomePreseededPlayerEvidence(String policy) throws Exception {
    Fixture f = new Fixture(false);
    var resolved = f.targets.resolvePublishedRealmAdmissionTarget(f.request.target());
    when(resolved.currentTarget().characterCreationPolicy()).thenReturn(policy);
    doReturn(resolved).when(f.targets).resolvePublishedRealmAdmissionTarget(f.request.target());

    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .isExactlyInstanceOf(
            AccountPlayerAdmissionEntitlementOwner.TargetPreconditionException.class)
        .hasNoCause();
    assertReceiverRefusal(f, io.grpc.Status.Code.FAILED_PRECONDITION);

    org.mockito.Mockito.verify(f.targets, org.mockito.Mockito.never())
        .readPublishedRealmAdmissionOwner(any());
    verifyNoInteractions(f.demos, f.hosting, f.observer);
    assertThat(f.transactions.events).containsExactly("begin", "commit", "begin", "commit");
  }

  @Test
  void isolatedRetainedScopeFailsBeforeProtectedTargetOrEntitlementCapture() throws Exception {
    Fixture f = new Fixture(false, "ISOLATED");

    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .isExactlyInstanceOf(
            AccountPlayerAdmissionEntitlementOwner.TargetPreconditionException.class)
        .hasNoCause();
    assertReceiverRefusal(f, io.grpc.Status.Code.FAILED_PRECONDITION);

    verifyNoInteractions(f.targets, f.sources, f.demos, f.hosting, f.observer);
    assertThat(f.transactions.events).containsExactly("begin", "rollback", "begin", "rollback");
  }

  @Test
  void protectedReadCannotReplaceSupportedPolicyBeforeEvidenceCapture() throws Exception {
    Fixture f = new Fixture(false);
    var resolved = f.targets.resolvePublishedRealmAdmissionTarget(f.request.target());
    var original = f.targets.readPublishedRealmAdmissionOwner(resolved.strictSelector());
    doReturn(resolved).when(f.targets).resolvePublishedRealmAdmissionTarget(f.request.target());
    when(f.targets.readPublishedRealmAdmissionOwner(resolved.strictSelector()))
        .thenAnswer(
            invocation -> {
              when(original.currentTarget().characterCreationPolicy()).thenReturn("PLAYER_CREATED");
              return original;
            });

    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .isExactlyInstanceOf(
            AccountPlayerAdmissionEntitlementOwner.TargetPreconditionException.class)
        .hasNoCause();
    assertReceiverRefusal(f, io.grpc.Status.Code.FAILED_PRECONDITION);

    verifyNoInteractions(f.demos, f.hosting, f.observer);
    assertThat(f.transactions.events).containsExactly("begin", "commit", "begin", "commit");
  }

  @Test
  void scopeChangedDuringProtectedTargetReadCannotBindFinalAccountEvidence() throws Exception {
    Fixture f = new Fixture(false);
    var original = f.scopeEvidence(ACCOUNT, f.scope.connectScopeExpiresAt());
    var changed = f.scopeEvidence(ACCOUNT, NOW.plusSeconds(50).toString());
    when(f.scopes.findCanonicalEvidenceByTokenHash(
            AccountJoinDigest.tokenHash(f.scope.connectScopeId())))
        .thenReturn(Optional.of(original), Optional.of(changed));
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    verifyNoInteractions(f.demos);
    assertThat(f.transactions.events).containsExactly("begin", "commit", "begin", "rollback");
  }

  @Test
  void strictProtectedReadFailureNeverProducesCanonicalPlayerEvidence() throws Exception {
    Fixture f = new Fixture(false);
    var selector =
        new CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector(
            f.request.requestId(),
            ACCOUNT,
            "prod",
            TENANT,
            f.scope.tenantSlug(),
            f.scope.worldSlug(),
            f.scope.realmId(),
            f.scope.realmSlug(),
            f.scope.playableStateNamespaceId(),
            "SHARED",
            f.scope.gameInstanceId(),
            f.scope.catalogRevision(),
            f.scope.pointerVersion());
    var resolved = f.targets.resolvePublishedRealmAdmissionTarget(selector);
    when(f.targets.readPublishedRealmAdmissionOwner(resolved.strictSelector()))
        .thenThrow(new IllegalStateException("controlled World source stale"));
    // Keep that exact resolver result so the strict-read denial cannot be replaced by new stubbing.
    doReturn(resolved).when(f.targets).resolvePublishedRealmAdmissionTarget(selector);
    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .hasMessageContaining("controlled World source stale");
    verifyNoInteractions(f.demos, f.hosting, f.observer);
  }

  /** Controlled protected-client double: no physical GS/World source provenance is claimed. */
  private static void controlledTarget(CanonicalGameplayAdmissionDecisionReadClient targets) {
    when(targets.resolvePublishedRealmAdmissionTarget(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              var selector =
                  invocation.getArgument(
                      0,
                      CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector.class);
              var target = mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target.class);
              when(target.targetNamespace()).thenReturn(selector.targetNamespace());
              when(target.canonicalTenantId()).thenReturn(selector.tenantId());
              when(target.tenantSlug()).thenReturn(selector.tenantSlug());
              when(target.worldSlug()).thenReturn(selector.worldSlug());
              when(target.realmId()).thenReturn(selector.realmId());
              when(target.realmSlug()).thenReturn(selector.realmSlug());
              when(target.playableStateNamespaceId())
                  .thenReturn(selector.playableStateNamespaceId());
              when(target.playableStateScope()).thenReturn(selector.playableStateScope());
              when(target.canonicalGameInstanceId()).thenReturn(selector.gameInstanceId());
              when(target.catalogRevision()).thenReturn(selector.catalogRevision());
              when(target.pointerVersion()).thenReturn(selector.pointerVersion());
              when(target.characterCreationPolicy()).thenReturn("PRESEEDED_ONLY");
              when(target.ownerProofOutcome())
                  .thenReturn(
                      CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome.COMMITTED);
              UUID version = UUID.fromString("33333333-3333-4333-8333-333333333333");
              when(target.canonicalVersionId()).thenReturn(version);
              when(target.activeWorldEpoch()).thenReturn(5L);
              var strict =
                  new CanonicalGameplayRosterOwnerReadEvidence.Request(
                      selector.readRequestId(),
                      selector.accountId(),
                      selector.targetNamespace(),
                      selector.tenantId(),
                      selector.worldSlug(),
                      selector.realmId(),
                      selector.realmSlug(),
                      selector.playableStateNamespaceId(),
                      selector.playableStateScope(),
                      selector.gameInstanceId(),
                      version,
                      selector.catalogRevision(),
                      selector.pointerVersion(),
                      5L);
              var resolved =
                  mock(
                      CanonicalGameplayAdmissionDecisionReadGrpcCodec.ResolvedPublishedTarget
                          .class);
              when(resolved.selector()).thenReturn(selector);
              when(resolved.currentTarget()).thenReturn(target);
              when(resolved.strictSelector()).thenReturn(strict);
              var read =
                  mock(
                      CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmEvidence.class);
              when(read.selector()).thenReturn(strict);
              when(read.currentTarget()).thenReturn(target);
              var source = mock(CanonicalGameplayRosterOwnerReadEvidence.class);
              when(source.admissionPointerSnapshotDigest()).thenReturn("a".repeat(64));
              var policies = mock(PublishedRealmEntryPolicySetEvidence.class);
              when(policies.policySetDigest()).thenReturn("sha256:" + "b".repeat(64));
              when(source.publishedPolicySetEvidence()).thenReturn(policies);
              var ownerProof =
                  mock(
                      net.firedevops.firemud.common.world
                          .GameSessionCanonicalInitialAdmissionOwnerProof.class);
              when(ownerProof.proofDigest()).thenReturn("sha256:" + "c".repeat(64));
              when(source.gameSessionOwnerProof()).thenReturn(ownerProof);
              when(read.sourceEvidence()).thenReturn(source);
              var world = mock(WorldCanonicalInstanceLifecycleEvidence.class);
              when(world.canonicalBytes())
                  .thenReturn(
                      "controlled-complete-world-source"
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
              when(world.captureId())
                  .thenReturn(UUID.fromString("44444444-4444-4444-8444-444444444444"));
              when(world.rowVersion()).thenReturn(2L);
              when(read.worldLifecycleEvidence()).thenReturn(world);
              when(targets.readPublishedRealmAdmissionOwner(strict))
                  .thenAnswer(
                      readCall -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                        return read;
                      });
              return resolved;
            });
  }

  private static final class Fixture {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountGameplayAdmissionCallerVerifier callers =
        mock(AccountGameplayAdmissionCallerVerifier.class);
    private final AccountGameplayDelegationIssuanceRepository issuances =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    private final AccountCanonicalRuntimeMembershipSnapshotRepository memberships =
        mock(AccountCanonicalRuntimeMembershipSnapshotRepository.class);
    private final AccountConnectScopeRepository scopes = mock(AccountConnectScopeRepository.class);
    private final AccountHostingSubscriptionLifecycleRepository hosting =
        mock(AccountHostingSubscriptionLifecycleRepository.class);
    private final StripeHostingSubscriptionObserver observer =
        mock(StripeHostingSubscriptionObserver.class);
    private final AccountDemoTenantEntitlementRepository demos =
        mock(AccountDemoTenantEntitlementRepository.class);
    private final AccountTenantAuthorityEventRepository sources =
        mock(AccountTenantAuthorityEventRepository.class);
    private final CanonicalGameplayAdmissionDecisionReadClient targets =
        mock(CanonicalGameplayAdmissionDecisionReadClient.class);
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final Map<String, org.jooq.Record> retainedCapacity = new LinkedHashMap<>();
    private boolean refuseCapacityRetention;
    private boolean missingCapacityRetentionReadback;
    private final java.util.concurrent.atomic.AtomicReference<Instant> time =
        new java.util.concurrent.atomic.AtomicReference<>(NOW);
    private final FreshTenantCreationEvidence source = source();
    private final TenantAuthorityEventV1Codec.Event tenantEvent = tenantEvent(source);
    private final CanonicalJoinScopeV2 scope;
    private final AccountGameplayDelegationPendingIdentity identity =
        new AccountGameplayDelegationPendingIdentity(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ACCOUNT,
            PEER,
            UUID.randomUUID(),
            mock(AccountGameplayCredentialRequestBinding.class),
            HASH,
            UUID.randomUUID(),
            NOW.minusSeconds(10).getEpochSecond(),
            NOW.minusSeconds(10).getEpochSecond(),
            NOW.plusSeconds(120).getEpochSecond());
    private final DemoTenantEntitlementSnapshot demo =
        new DemoTenantEntitlementSnapshot(
            TENANT,
            source,
            "NON_PAID_DEMO",
            "ACTIVE",
            null,
            false,
            true,
            true,
            true,
            true,
            new DemoTenantEntitlementRequest.Quotas(3, 2, 4096),
            1,
            2,
            2,
            tenantEvent.tenantBillingStreamKey(),
            1,
            tenantEvent.tenantBillingEventId(),
            tenantEvent.tenantBillingEventDigest(),
            tenantEvent.tenantBillingEventDigest(),
            tenantEvent.outboxStreamKey(),
            1,
            tenantEvent.eventId(),
            tenantEvent.eventDigest());
    private final Request request;
    private final AccountPlayerAdmissionEntitlementOwner owner;

    private Fixture(boolean exists) throws Exception {
      this(exists, "SHARED");
    }

    private Fixture(boolean exists, String stateScope) throws Exception {
      scope =
          new CanonicalJoinScopeV2(
              "private-bearer-" + UUID.randomUUID(),
              ACCOUNT,
              TENANT,
              UUID.randomUUID(),
              "tenant",
              "world",
              "production",
              UUID.randomUUID(),
              stateScope,
              UUID.randomUUID(),
              7,
              3,
              NOW.minusSeconds(20).toString(),
              NOW.plusSeconds(60).toString());
      request =
          new Request(
              new CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector(
                  UUID.randomUUID(),
                  ACCOUNT,
                  "prod",
                  TENANT,
                  scope.tenantSlug(),
                  scope.worldSlug(),
                  scope.realmId(),
                  scope.realmSlug(),
                  scope.playableStateNamespaceId(),
                  "SHARED",
                  scope.gameInstanceId(),
                  scope.catalogRevision(),
                  scope.pointerVersion()),
              scope.connectScopeId(),
              PEER);
      controlledTarget(targets);
      var dsl = mock(DSLContext.class);
      // Controlled retention content, not physical atomicity or Account source authentication.
      when(dsl.execute(anyString(), any(Object[].class)))
          .thenAnswer(
              invocation -> {
                String sql = invocation.getArgument(0, String.class);
                if (!sql.startsWith("INSERT INTO account_tenant_capacity_evaluations "))
                  throw new AssertionError(sql);
                if (refuseCapacityRetention)
                  throw new DataAccessException("Controlled capacity retention refusal");
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                if (missingCapacityRetentionReadback) return 0;
                Object[] values = (Object[]) invocation.getRawArguments()[1];
                String id = (String) values[0];
                if (retainedCapacity.containsKey(id)) return 0;
                var row = mock(org.jooq.Record.class);
                when(row.get("evaluation_identity", String.class)).thenReturn(id);
                when(row.get("snapshot_identity", String.class)).thenReturn((String) values[1]);
                when(row.get("tenant_uuid", UUID.class)).thenReturn((UUID) values[2]);
                when(row.get("caller_workload", String.class)).thenReturn((String) values[3]);
                when(row.get("owner_service", String.class)).thenReturn((String) values[4]);
                when(row.get("canonical_evidence", byte[].class))
                    .thenReturn(((byte[]) values[5]).clone());
                when(row.get("evaluated_at", java.time.OffsetDateTime.class))
                    .thenReturn((java.time.OffsetDateTime) values[6]);
                when(row.get("expires_at", java.time.OffsetDateTime.class))
                    .thenReturn((java.time.OffsetDateTime) values[7]);
                retainedCapacity.put(id, row);
                return 1;
              });
      when(dsl.fetchOne(anyString(), any(Object[].class)))
          .thenAnswer(
              invocation -> {
                String sql = invocation.getArgument(0, String.class);
                if (!sql.startsWith("SELECT * FROM account_tenant_capacity_evaluations "))
                  throw new AssertionError(sql);
                Object[] values = (Object[]) invocation.getRawArguments()[1];
                return retainedCapacity.get((String) values[0]);
              });
      var connection = mock(Connection.class);
      when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);
      doAnswer(
              invocation -> {
                invocation.getArgument(0, ConnectionRunnable.class).run(connection);
                return null;
              })
          .when(dsl)
          .connection(any(ConnectionRunnable.class));
      var candidate = mock(CommittedCandidateVerificationData.class);
      when(candidate.identity()).thenReturn(identity);
      when(candidate.authoritySnapshot())
          .thenReturn(new AccountAuthoritySnapshot(ACCOUNT, 1, 1, 1, 1, 1, 1, Optional.empty()));
      when(candidate.evidenceBundleReference()).thenReturn(mock(EvidenceBundleReference.class));
      when(candidate.signerCorrespondence())
          .thenReturn(mock(AuthenticatedSignerCorrespondence.class));
      when(candidate.tokenSha256()).thenReturn(HASH);
      when(candidate.signerKid()).thenReturn("controlled-kid");
      when(candidate.signerGeneration()).thenReturn("1");
      when(candidate.canonicalRegistryRecordSha256()).thenReturn(HASH);
      when(candidate.commitProofSha256()).thenReturn(HASH);
      when(candidate.envelopeKeyId()).thenReturn("controlled-envelope-key");
      when(candidate.envelopeSha256()).thenReturn(HASH);
      var caller = mock(VerifiedCaller.class);
      when(caller.identity()).thenReturn(identity);
      when(caller.currentCandidate()).thenReturn(candidate);
      when(callers.verifyPresentedCompactToken("controlled-token"))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return caller;
              });
      Account account = new Account();
      account.setId(10L);
      account.setAccountUuid(ACCOUNT);
      when(accounts.findByAccountUuidForUpdate(ACCOUNT)).thenReturn(Optional.of(account));
      when(issuances.readCurrentCommittedCandidateByJti(identity.tokenJti()))
          .thenReturn(Optional.of(candidate));
      when(memberships.readCurrent(ACCOUNT, TENANT)).thenReturn(snapshot(exists));
      when(scopes.findCanonical(scope.connectScopeId())).thenReturn(Optional.of(scope));
      when(scopes.findCanonicalEvidenceByTokenHash(
              AccountJoinDigest.tokenHash(scope.connectScopeId())))
          .thenReturn(Optional.of(scopeEvidence(ACCOUNT, scope.connectScopeExpiresAt())));
      when(demos.readCurrent(TENANT)).thenReturn(demo);
      when(sources.readCurrentSourceSelection(TENANT))
          .thenReturn(new SourceSelection(SourceKind.NON_PAID_DEMO, tenantEvent));
      Clock clock = mock(Clock.class);
      when(clock.instant()).thenAnswer(invocation -> time.get());
      owner =
          new AccountPlayerAdmissionEntitlementOwner(
              dsl,
              accounts,
              callers,
              issuances,
              memberships,
              scopes,
              hosting,
              observer,
              demos,
              sources,
              targets,
              transactions,
              clock,
              "prod");
    }

    private CanonicalConnectScopeEvidence scopeEvidence(UUID account, String expiry) {
      return new CanonicalConnectScopeEvidence(
          AccountJoinDigest.tokenHash(scope.connectScopeId()),
          10,
          "PUBLIC_PRODUCTION",
          account,
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
          expiry,
          2,
          AccountJoinDigest.scopeV2(scope),
          new VerifiedTenantProvenance(
              null,
              TenantProvenanceKind.FRESH_GAME_DESIGN,
              source.operationId(),
              source.evidenceDigest()));
    }

    private AccountHostingSubscriptionCurrentReceipt receipt(Status status) {
      var current = mock(AccountSubscriptionCurrentRecord.class);
      when(current.tenantId()).thenReturn(TENANT);
      when(current.subscriptionId()).thenReturn(UUID.randomUUID());
      when(current.rowVersion()).thenReturn(2L);
      when(current.planQuote())
          .thenReturn(new AccountHostingPlanQuote("standard", "price_Test", 1, 3, 2, 4096));
      return new AccountHostingSubscriptionCurrentReceipt(
          current,
          UUID.randomUUID(),
          status,
          status != Status.SUSPENDED && status != Status.CANCELED,
          status != Status.SUSPENDED && status != Status.CANCELED && status != Status.GRACE,
          status != Status.SUSPENDED && status != Status.CANCELED && status != Status.GRACE,
          status != Status.SUSPENDED && status != Status.CANCELED && status != Status.GRACE,
          1,
          1,
          tenantEvent.tenantBillingEventId(),
          tenantEvent.tenantBillingEventDigest(),
          tenantEvent,
          "active",
          NOW.minusSeconds(100),
          NOW.plusSeconds(1000),
          false,
          null,
          null,
          NOW,
          NOW.plusSeconds(60));
    }

    private Snapshot snapshot(boolean exists) {
      var cutoff = new TenantBillingCutoff("2", "1", tenantEvent.outboxStreamKey(), "1");
      var tuple =
          new AuthorityTuple(
              "1",
              "1",
              Map.of(TENANT.toString(), "2"),
              Map.of(TENANT.toString(), "1"),
              List.of(),
              Optional.empty(),
              Optional.of(Map.of(TENANT.toString(), cutoff)));
      List<SourceEvidence> events = new ArrayList<>();
      if (exists) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
        event.put("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE);
        event.put("eventId", "membership-event-1");
        event.put("requestId", "membership-request-1");
        event.put("outboxStreamKey", memberStream());
        event.put("outboxSequence", "1");
        event.put("sourceScope", "membership/" + ACCOUNT + "/" + TENANT);
        event.put("accountId", ACCOUNT.toString());
        event.put("tenantId", TENANT.toString());
        event.put("membershipExists", true);
        event.put("membershipLifecycleState", "ACTIVE");
        event.put("membershipVersion", Map.of(TENANT.toString(), "1"));
        event.put("membershipAuthorityGeneration", "1");
        event.put(
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration",
                "1",
                "accountAuthorityGeneration",
                "1",
                "tenantAuthorityGeneration",
                Map.of(TENANT.toString(), "2"),
                "membershipAuthorityGeneration",
                Map.of(TENANT.toString(), "1"),
                "privateRealmGrantVersions",
                List.of(),
                "tenantBillingCutoff",
                Map.of(
                    TENANT.toString(),
                    Map.of(
                        "tenantAuthorityGeneration",
                        "2",
                        "tenantBillingSequence",
                        "1",
                        "outboxStreamKey",
                        tenantEvent.outboxStreamKey(),
                        "outboxSequence",
                        "1"))));
        event.put("issuanceFence", "1");
        event.put("roles", List.of("player"));
        event.put("gameplayAdmissionAllowed", true);
        event.put("callerBoundAuthorityInvalidated", false);
        var sealed = MembershipAuthorityEventV1Codec.seal(event);
        events.add(
            new SourceEvidence(
                memberStream(),
                "1",
                sealed.eventId(),
                sealed.eventDigest(),
                sealed.canonicalJson()));
      }
      events.add(
          new SourceEvidence(
              tenantEvent.outboxStreamKey(),
              "1",
              tenantEvent.eventId().toString(),
              tenantEvent.eventDigest(),
              new String(tenantEvent.payload(), java.nio.charset.StandardCharsets.UTF_8)));
      return new Snapshot(
          ISSUER,
          ACCOUNT.toString(),
          TENANT.toString(),
          exists,
          exists ? "ACTIVE" : "MISSING",
          exists,
          Map.of(TENANT.toString(), "1"),
          "1",
          exists ? List.of("player") : List.of(),
          tuple,
          "1",
          List.of(
              new Checkpoint(PREFIX + "account/" + ACCOUNT, "0"),
              new Checkpoint(PREFIX + "issuer/" + ISSUER, "0"),
              new Checkpoint(memberStream(), exists ? "1" : "0"),
              new Checkpoint(tenantEvent.outboxStreamKey(), "1")),
          events);
    }
  }

  private static String memberStream() {
    return PREFIX + "membership/" + ACCOUNT + "/" + TENANT;
  }

  private static FreshTenantCreationEvidence source() {
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String requestDigest =
        GameTenantCreationDigest.requestDigest("prod", requestId, "world", "World", null);
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        requestId,
        operationId,
        requestDigest,
        TENANT,
        17,
        "world",
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            "prod", requestId, operationId, requestDigest, TENANT, 17, "world", "NEW_GAME_ROW"));
  }

  private static TenantAuthorityEventV1Codec.Event tenantEvent(FreshTenantCreationEvidence source) {
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
            1,
            TenantAuthorityEventV1Codec.tenantBillingEventId(requestId),
            "sha256:" + HASH);
    return TenantAuthorityEventV1Codec.seal(request, source, 2, 2, 1, receipt);
  }

  private static final class RecordingTransactions implements PlatformTransactionManager {
    private final List<String> events = new ArrayList<>();
    private boolean failCommit;
    private Runnable afterSecondCommit = () -> {};

    @Override
    public TransactionStatus getTransaction(
        @org.jspecify.annotations.Nullable TransactionDefinition definition) {
      if (definition != null) {
        assertThat(definition.getIsolationLevel())
            .isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThat(definition.isReadOnly()).isFalse();
        assertThat(definition.getPropagationBehavior())
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getTimeout()).isEqualTo(5);
      }
      events.add("begin");
      TransactionSynchronizationManager.setActualTransactionActive(true);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      events.add("commit");
      TransactionSynchronizationManager.setActualTransactionActive(false);
      if (events.size() == 4) afterSecondCommit.run();
      if (failCommit && events.size() == 4)
        throw new IllegalStateException("controlled commit failure");
    }

    @Override
    public void rollback(TransactionStatus status) {
      events.add("rollback");
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }
}
