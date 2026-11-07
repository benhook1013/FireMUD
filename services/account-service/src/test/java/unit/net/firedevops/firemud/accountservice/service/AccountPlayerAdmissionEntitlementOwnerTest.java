package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceKind;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceSelection;
import net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner;
import net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner.Request;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier.VerifiedCaller;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner.AuthenticatedSignerCorrespondence;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Checkpoint;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import org.jooq.ConnectionRunnable;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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
        .containsEntry("verifiedConnectScopeId", f.scope.connectScopeId())
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
        .hasMessageContaining("retained");
    verifyNoInteractions(f.demos, f.hosting, f.observer);
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
            AccountPlayerAdmissionEntitlementOwner.SourceUnavailableException.class);

    org.mockito.Mockito.verify(f.targets, org.mockito.Mockito.never())
        .readPublishedRealmAdmissionOwner(any());
    verifyNoInteractions(f.demos, f.hosting, f.observer);
    assertThat(f.transactions.events).containsExactly("begin", "commit");
  }

  @Test
  void isolatedRetainedScopeFailsBeforeProtectedTargetOrEntitlementCapture() throws Exception {
    Fixture f = new Fixture(false, "ISOLATED");

    assertThatThrownBy(() -> f.owner.evaluate(f.request, "controlled-token"))
        .isExactlyInstanceOf(
            AccountPlayerAdmissionEntitlementOwner.SourceUnavailableException.class);

    verifyNoInteractions(f.targets, f.sources, f.demos, f.hosting, f.observer);
    assertThat(f.transactions.events).containsExactly("begin", "rollback");
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
            AccountPlayerAdmissionEntitlementOwner.SourceUnavailableException.class);

    verifyNoInteractions(f.demos, f.hosting, f.observer);
    assertThat(f.transactions.events).containsExactly("begin", "commit");
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
