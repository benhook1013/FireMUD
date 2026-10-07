package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.AccountAuditTenantIdentity;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinTerminalProof;
import net.firedevops.firemud.accountservice.repository.AccountLifecyclePendingDenialReader;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountCanonicalFirstJoinTerminalCoordinatorTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("f4df3b0a-5fc6-4f86-9efc-0f053103b200");
  private static final UUID OTHER_ACCOUNT_ID =
      UUID.fromString("f4df3b0a-5fc6-4f86-9efc-0f053103b201");
  private static final UUID TENANT_ID = UUID.fromString("4347218b-6914-4d50-a5ce-1b4836034f45");
  private static final UUID REALM_ID = UUID.fromString("5f44a632-d730-4f58-937f-f584cf0ddfee");
  private static final UUID NAMESPACE_ID = UUID.fromString("bfad3562-e170-4485-ae45-99d33056718d");
  private static final UUID INSTANCE_ID = UUID.fromString("86567aa4-02e6-4fc9-8e2e-60738ed8fdb8");
  private static final UUID TENANT_OPERATION_ID =
      UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final String REQUEST_ID = "global-request-1";
  private static final String CALLER_BINDING = "server-verified-caller";
  private static final String EVENT_STREAM =
      MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
          + "membership/"
          + ACCOUNT_ID
          + "/"
          + TENANT_ID;
  private static final Instant AUDIT_OCCURRED_AT = Instant.parse("2026-10-04T00:01:00Z");

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void pendingFirstJoinStagesSourcesAppendsAuditAndCommitsExactTerminalProof() {
    Fixture fixture = new Fixture();
    fixture.arrangePending();
    fixture.startWritableTransaction();

    CanonicalJoinTerminalProof result =
        fixture.coordinator.commitCanonicalFirstJoin(fixture.scope, REQUEST_ID, CALLER_BINDING);

    assertThat(result).isEqualTo(fixture.expectedProof);
    InOrder ordered =
        inOrder(
            fixture.accounts,
            fixture.operations,
            fixture.pendingReader,
            fixture.memberships,
            fixture.roles,
            fixture.eventProducer);
    ordered.verify(fixture.accounts).findByAccountUuid(ACCOUNT_ID);
    ordered.verify(fixture.operations).lockAccount(17L);
    ordered.verify(fixture.operations).findCanonicalEvidenceForUpdateByRequestId(REQUEST_ID);
    ordered.verify(fixture.pendingReader).requireNoPending(ACCOUNT_ID, TENANT_ID);
    ordered.verify(fixture.memberships).createFreshMembershipForJoin(ACCOUNT_ID, TENANT_ID);
    ordered
        .verify(fixture.roles)
        .replaceCanonical(
            fixture.membership, ACCOUNT_ID, TENANT_ID, fixture.provenance, 2L, List.of("player"));
    ordered
        .verify(fixture.eventProducer)
        .publishCanonicalFirstJoinMembershipChange(fixture.scope, REQUEST_ID, CALLER_BINDING);
    verify(fixture.audit)
        .appendCanonicalTenant(
            fixture.expectedProof.auditEventId(),
            TENANT_ID.toString(),
            "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
            fixture.auditPayload);
    verify(fixture.operations)
        .commitCanonicalFirstJoin(REQUEST_ID, fixture.scope, CALLER_BINDING, fixture.expectedProof);
  }

  @Test
  void committedExactRetryReadsReceiptWithoutCreatingMembershipOrAppendingAudit() {
    Fixture fixture = new Fixture();
    fixture.arrangeCommittedReplay();
    fixture.startWritableTransaction();

    CanonicalJoinTerminalProof result =
        fixture.coordinator.commitCanonicalFirstJoin(fixture.scope, REQUEST_ID, CALLER_BINDING);

    assertThat(result).isEqualTo(fixture.expectedProof);
    assertThat(fixture.minimalAuditEnvelope.payload()).isNull();
    assertThat(fixture.minimalAuditEnvelope.auditEventId())
        .isEqualTo(fixture.expectedProof.auditEventId());
    assertThat(fixture.minimalAuditEnvelope.payloadDigest())
        .isEqualTo(fixture.expectedProof.auditPayloadDigest());
    verify(fixture.memberships, never()).createFreshMembershipForJoin(any(), any());
    verify(fixture.roles, never()).replaceCanonical(any(), any(), any(), any(), anyLong(), any());
    verify(fixture.audit, never())
        .appendCanonicalTenant(any(), anyString(), anyString(), anyString());
    verify(fixture.pendingReader, never()).requireNoPending(any(), any());
    verify(fixture.audit)
        .findCanonicalTenantEnvelopeForUpdate(fixture.expectedProof.auditEventId());
    verify(fixture.operations)
        .commitCanonicalFirstJoin(REQUEST_ID, fixture.scope, CALLER_BINDING, fixture.expectedProof);
  }

  @Test
  void pendingLifecycleOperationDeniesBeforeAnyJoinMutationOrPublication() {
    Fixture fixture = new Fixture();
    fixture.arrangePending();
    doThrow(
            new AccountLifecyclePendingDenialReader.PendingOperationException(
                "Account lifecycle invalidation is unresolved for this Account and tenant"))
        .when(fixture.pendingReader)
        .requireNoPending(ACCOUNT_ID, TENANT_ID);
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.coordinator.commitCanonicalFirstJoin(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(AccountLifecyclePendingDenialReader.PendingOperationException.class)
        .hasMessage("Account lifecycle invalidation is unresolved for this Account and tenant");

    InOrder ordered = inOrder(fixture.operations, fixture.pendingReader);
    ordered.verify(fixture.operations).lockAccount(17L);
    ordered.verify(fixture.operations).findCanonicalEvidenceForUpdateByRequestId(REQUEST_ID);
    ordered.verify(fixture.pendingReader).requireNoPending(ACCOUNT_ID, TENANT_ID);
    verifyNoInteractions(
        fixture.memberships,
        fixture.roles,
        fixture.outbox,
        fixture.pairs,
        fixture.audit,
        fixture.eventProducer);
    verify(fixture.operations, never())
        .commitCanonicalFirstJoin(anyString(), any(), anyString(), any());
  }

  @Test
  void changedCallerUnderGlobalRequestIdConflictsBeforeReturningAnyReceipt() {
    Fixture fixture = new Fixture();
    fixture.arrangePending();
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.coordinator.commitCanonicalFirstJoin(
                    fixture.scope, REQUEST_ID, "different-caller-binding"))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class)
        .hasMessage("Canonical JOIN intent conflicts")
        .satisfies(
            exception ->
                assertThat(exception.getMessage())
                    .doesNotContain(CALLER_BINDING, ACCOUNT_ID.toString()));
    verify(fixture.memberships, never()).createFreshMembershipForJoin(any(), any());
    verify(fixture.eventProducer, never())
        .publishCanonicalFirstJoinMembershipChange(any(), anyString(), anyString());
    verify(fixture.audit, never())
        .appendCanonicalTenant(any(), anyString(), anyString(), anyString());
  }

  @Test
  void changedAccountUnderGlobalRequestIdConflictsBeforeReturningAnyReceipt() {
    Fixture fixture = new Fixture();
    fixture.arrangePending();
    Account otherAccount = fixture.account(18L, OTHER_ACCOUNT_ID);
    CanonicalJoinScopeV2 otherScope = fixture.scope(OTHER_ACCOUNT_ID);
    when(fixture.accounts.findByAccountUuid(OTHER_ACCOUNT_ID))
        .thenReturn(Optional.of(otherAccount));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.coordinator.commitCanonicalFirstJoin(
                    otherScope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(AccountJoinOperationRepository.CanonicalJoinOperationConflictException.class)
        .hasMessage("Canonical JOIN intent conflicts")
        .satisfies(
            exception ->
                assertThat(exception.getMessage())
                    .doesNotContain(ACCOUNT_ID.toString(), OTHER_ACCOUNT_ID.toString()));
    verify(fixture.memberships, never()).createFreshMembershipForJoin(any(), any());
    verify(fixture.eventProducer, never())
        .publishCanonicalFirstJoinMembershipChange(any(), anyString(), anyString());
    verify(fixture.audit, never())
        .appendCanonicalTenant(any(), anyString(), anyString(), anyString());
  }

  @Test
  void pendingOperationCannotTurnAnAlreadyPositiveMembershipIntoTerminalSuccess() {
    Fixture fixture = new Fixture();
    fixture.arrangePending();
    when(fixture.memberships.createFreshMembershipForJoin(ACCOUNT_ID, TENANT_ID))
        .thenThrow(new IllegalStateException("fresh membership already exists"));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.coordinator.commitCanonicalFirstJoin(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("fresh membership already exists");
    verify(fixture.eventProducer, never())
        .publishCanonicalFirstJoinMembershipChange(any(), anyString(), anyString());
    verify(fixture.audit, never())
        .appendCanonicalTenant(any(), anyString(), anyString(), anyString());
    verify(fixture.operations, never())
        .commitCanonicalFirstJoin(anyString(), any(), anyString(), any());
  }

  @Test
  void mismatchedEventReadbackCannotProduceAnAuditOrTerminalReceipt() {
    Fixture fixture = new Fixture();
    fixture.arrangePending();
    Event changed = fixture.event("other-global-request");
    when(fixture.outbox.findEvent(EVENT_STREAM, 1L)).thenReturn(Optional.of(changed));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.coordinator.commitCanonicalFirstJoin(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical first-JOIN event differs from checkpoint");
    verify(fixture.audit, never())
        .appendCanonicalTenant(any(), anyString(), anyString(), anyString());
    verify(fixture.operations, never())
        .commitCanonicalFirstJoin(anyString(), any(), anyString(), any());
  }

  @Test
  void mismatchedPairReadbackCannotProduceAnAuditOrTerminalReceipt() {
    Fixture fixture = new Fixture();
    fixture.arrangePending();
    when(fixture.pairs.readForUpdate(ACCOUNT_ID, TENANT_ID))
        .thenReturn(
            Optional.of(fixture.positivePair(fixture.event.eventId(), "sha256:" + "f".repeat(64))));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.coordinator.commitCanonicalFirstJoin(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical first-JOIN pair differs from its event");
    verify(fixture.audit, never())
        .appendCanonicalTenant(any(), anyString(), anyString(), anyString());
    verify(fixture.operations, never())
        .commitCanonicalFirstJoin(anyString(), any(), anyString(), any());
  }

  @Test
  void mismatchedAuditReadbackCannotCommitTheGlobalOperation() {
    Fixture fixture = new Fixture();
    fixture.arrangePending();
    AccountAuditEnvelope wrongTenant =
        fixture.auditEnvelope(OTHER_ACCOUNT_ID, fixture.auditPayload);
    when(fixture.audit.findExactCanonicalTenantEnvelopeForUpdate(any(AccountAuditEnvelope.class)))
        .thenReturn(Optional.of(wrongTenant));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.coordinator.commitCanonicalFirstJoin(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical JOIN audit envelope differs from its receipt");
    verify(fixture.operations, never())
        .commitCanonicalFirstJoin(anyString(), any(), anyString(), any());
  }

  @Test
  void readOnlyTransactionIsRejectedBeforeOwnerRepositoriesAreTouched() {
    Fixture fixture = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

    assertThatThrownBy(
            () ->
                fixture.coordinator.commitCanonicalFirstJoin(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable Account owner transaction");
    verifyNoInteractions(
        fixture.accounts,
        fixture.operations,
        fixture.memberships,
        fixture.roles,
        fixture.outbox,
        fixture.pairs,
        fixture.audit,
        fixture.eventProducer,
        fixture.pendingReader);
  }

  @Test
  void inactiveTransactionIsRejectedBeforeOwnerRepositoriesAreTouched() {
    Fixture fixture = new Fixture();

    assertThatThrownBy(
            () ->
                fixture.coordinator.commitCanonicalFirstJoin(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable Account owner transaction");
    verifyNoInteractions(
        fixture.accounts,
        fixture.operations,
        fixture.memberships,
        fixture.roles,
        fixture.outbox,
        fixture.pairs,
        fixture.audit,
        fixture.eventProducer,
        fixture.pendingReader);
  }

  @Test
  void auditPreimageUsesCanonicalUuidScopeAndTheExactPositiveCheckpoint() {
    Fixture fixture = new Fixture();

    String payload =
        AccountCanonicalFirstJoinTerminalCoordinator.canonicalAuditPayload(
            fixture.scope,
            REQUEST_ID,
            fixture.operation.intentDigest(),
            fixture.operation.requestDigest(),
            5L,
            fixture.checkpoint,
            fixture.event);

    assertThat(payload).isEqualTo(fixture.auditPayload);
    assertThat(AccountAuditDigest.ofPayload(payload)).matches("sha256:[0-9a-f]{64}");
  }

  @Test
  void auditPreimageRejectsAnEventNotBoundToTheCheckpoint() {
    Fixture fixture = new Fixture();
    Event changed = fixture.event("other-global-request");

    assertThatThrownBy(
            () ->
                AccountCanonicalFirstJoinTerminalCoordinator.canonicalAuditPayload(
                    fixture.scope,
                    REQUEST_ID,
                    fixture.operation.intentDigest(),
                    fixture.operation.requestDigest(),
                    5L,
                    fixture.checkpoint,
                    changed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact event checkpoint");
  }

  private static final class Fixture {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountJoinOperationRepository operations =
        mock(AccountJoinOperationRepository.class);
    private final AccountTenantMembershipRepository memberships =
        mock(AccountTenantMembershipRepository.class);
    private final AccountTenantMembershipRoleSnapshotRepository roles =
        mock(AccountTenantMembershipRoleSnapshotRepository.class);
    private final AccountAuthorityOutboxRepository outbox =
        mock(AccountAuthorityOutboxRepository.class);
    private final AccountMembershipPairAuthorityRepository pairs =
        mock(AccountMembershipPairAuthorityRepository.class);
    private final AccountAuditOutboxRepository audit = mock(AccountAuditOutboxRepository.class);
    private final AccountMembershipAuthorityEventProducer eventProducer =
        mock(AccountMembershipAuthorityEventProducer.class);
    private final AccountLifecyclePendingDenialReader pendingReader =
        mock(AccountLifecyclePendingDenialReader.class);
    private final AccountCanonicalFirstJoinTerminalCoordinator coordinator =
        new AccountCanonicalFirstJoinTerminalCoordinator(
            accounts,
            operations,
            memberships,
            roles,
            outbox,
            pairs,
            audit,
            eventProducer,
            pendingReader);
    private final CanonicalJoinScopeV2 scope = scope(ACCOUNT_ID);
    private final VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            TENANT_OPERATION_ID,
            "sha256:" + "4".repeat(64));
    private final Account account = account(17L, ACCOUNT_ID);
    private final AccountTenantMembership membership = membership(account);
    private final RoleSnapshot roleSnapshot =
        new RoleSnapshot(17L, null, 73L, 2L, List.of("player"), ACCOUNT_ID, TENANT_ID, provenance);
    private final CanonicalJoinOperationEvidence operation = operation(scope, CALLER_BINDING);
    private final Event event = event(REQUEST_ID);
    private final Checkpoint checkpoint =
        new Checkpoint(EVENT_STREAM, 1L, event.eventId(), event.eventDigest());
    private final String auditPayload =
        AccountCanonicalFirstJoinTerminalCoordinator.canonicalAuditPayload(
            scope,
            REQUEST_ID,
            operation.intentDigest(),
            operation.requestDigest(),
            5L,
            checkpoint,
            event);
    private final AccountAuditEnvelope auditEnvelope = auditEnvelope(TENANT_ID, auditPayload);
    private final AccountAuditEnvelope minimalAuditEnvelope =
        new AccountAuditEnvelope(
            auditEnvelope.auditEventId(),
            auditEnvelope.scope(),
            auditEnvelope.tenantIdentity(),
            auditEnvelope.producerService(),
            auditEnvelope.eventType(),
            auditEnvelope.occurredAt(),
            auditEnvelope.schemaVersion(),
            auditEnvelope.payloadDigestVersion(),
            auditEnvelope.payloadDigest(),
            null);
    private final CanonicalJoinTerminalProof expectedProof =
        new CanonicalJoinTerminalProof(
            EVENT_STREAM,
            1L,
            event.eventId(),
            event.eventDigest(),
            auditEnvelope.auditEventId(),
            auditEnvelope.payloadDigest(),
            auditEnvelope.occurredAt(),
            5L,
            73L,
            2L,
            1L);

    private void arrangePending() {
      when(accounts.findByAccountUuid(ACCOUNT_ID)).thenReturn(Optional.of(account));
      when(operations.findCanonicalEvidenceForUpdateByRequestId(REQUEST_ID))
          .thenReturn(Optional.of(operation));
      when(memberships.createFreshMembershipForJoin(ACCOUNT_ID, TENANT_ID)).thenReturn(membership);
      when(memberships.findFreshJoinForPublicationForUpdate(ACCOUNT_ID, TENANT_ID))
          .thenReturn(Optional.of(membership));
      when(roles.replaceCanonical(
              membership, ACCOUNT_ID, TENANT_ID, provenance, 2L, List.of("player")))
          .thenReturn(roleSnapshot);
      when(roles.findForCanonicalUpdate(ACCOUNT_ID, TENANT_ID, provenance, 73L, 2L))
          .thenReturn(Optional.of(roleSnapshot));
      when(eventProducer.publishCanonicalFirstJoinMembershipChange(
              scope, REQUEST_ID, CALLER_BINDING))
          .thenReturn(checkpoint);
      when(outbox.findEvent(EVENT_STREAM, 1L)).thenReturn(Optional.of(event));
      when(pairs.readForUpdate(ACCOUNT_ID, TENANT_ID))
          .thenReturn(Optional.of(positivePair(event.eventId(), event.eventDigest())));
      when(audit.appendCanonicalTenant(
              any(UUID.class),
              eq(TENANT_ID.toString()),
              eq("ACCOUNT_JOINED_PUBLIC_PRODUCTION"),
              anyString()))
          .thenReturn(auditEnvelope);
      when(audit.findExactCanonicalTenantEnvelopeForUpdate(auditEnvelope))
          .thenReturn(Optional.of(auditEnvelope));
      when(operations.commitCanonicalFirstJoin(REQUEST_ID, scope, CALLER_BINDING, expectedProof))
          .thenReturn(committed(operation, expectedProof));
    }

    private void arrangeCommittedReplay() {
      when(accounts.findByAccountUuid(ACCOUNT_ID)).thenReturn(Optional.of(account));
      when(operations.findCanonicalEvidenceForUpdateByRequestId(REQUEST_ID))
          .thenReturn(Optional.of(committed(operation, expectedProof)));
      when(memberships.findFreshJoinForPublicationForUpdate(ACCOUNT_ID, TENANT_ID))
          .thenReturn(Optional.of(membership));
      when(roles.findForCanonicalUpdate(ACCOUNT_ID, TENANT_ID, provenance, 73L, 2L))
          .thenReturn(Optional.of(roleSnapshot));
      when(eventProducer.publishCanonicalFirstJoinMembershipChange(
              scope, REQUEST_ID, CALLER_BINDING))
          .thenReturn(checkpoint);
      when(outbox.findEvent(EVENT_STREAM, 1L)).thenReturn(Optional.of(event));
      when(pairs.readForUpdate(ACCOUNT_ID, TENANT_ID))
          .thenReturn(Optional.of(positivePair(event.eventId(), event.eventDigest())));
      when(audit.findCanonicalTenantEnvelopeForUpdate(expectedProof.auditEventId()))
          .thenReturn(Optional.of(minimalAuditEnvelope));
      when(operations.commitCanonicalFirstJoin(REQUEST_ID, scope, CALLER_BINDING, expectedProof))
          .thenReturn(committed(operation, expectedProof));
    }

    private void startWritableTransaction() {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private PairAuthority positivePair(String eventId, String digest) {
      return new PairAuthority(
          ACCOUNT_ID, TENANT_ID, provenance, true, 2L, 1L, 1L, eventId, digest, false);
    }

    private Account account(long privateId, UUID accountUuid) {
      Account value = new Account();
      value.setId(privateId);
      value.setAccountUuid(accountUuid);
      value.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_V29_MIGRATION);
      value.setAccountUuidSourceNumericId(privateId);
      return value;
    }

    private AccountTenantMembership membership(Account owner) {
      AccountTenantMembership value = new AccountTenantMembership();
      value.setId(73L);
      value.setAccount(owner);
      value.setTenantUuid(TENANT_ID);
      value.setTenantProvenanceKind(TenantProvenanceKind.FRESH_GAME_DESIGN.name());
      value.setTenantSourceOperationId(TENANT_OPERATION_ID);
      value.setTenantProvenanceDigest(provenance.digest());
      value.setLifecycleState("ACTIVE");
      value.setMembershipVersion(2L);
      value.setMembershipAuthorityGeneration(1L);
      value.setAuthorityProvenance("EXPLICIT_JOIN");
      value.setGameplayAdmissionAllowed(true);
      return value;
    }

    private AccountAuditEnvelope auditEnvelope(UUID tenantUuid, String payload) {
      return new AccountAuditEnvelope(
          auditEventId(),
          "tenant",
          AccountAuditTenantIdentity.canonicalTenantV2(tenantUuid.toString()),
          "account-service",
          "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
          AUDIT_OCCURRED_AT,
          1,
          1,
          AccountAuditDigest.ofPayload(payload),
          payload);
    }

    private static CanonicalJoinOperationEvidence operation(
        CanonicalJoinScopeV2 scope, String callerBinding) {
      VerifiedTenantProvenance source =
          new VerifiedTenantProvenance(
              null,
              TenantProvenanceKind.FRESH_GAME_DESIGN,
              TENANT_OPERATION_ID,
              "sha256:" + "4".repeat(64));
      var scopeEvidence =
          new AccountConnectScopeRepository.CanonicalConnectScopeEvidence(
              AccountJoinDigest.tokenHash(scope.connectScopeId()),
              17L,
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
              source);
      return new CanonicalJoinOperationEvidence(
          REQUEST_ID,
          17L,
          callerBinding,
          AccountJoinDigest.tokenHash(scope.connectScopeId()),
          AccountJoinDigest.scopeV2(scope),
          2,
          2,
          2,
          AccountJoinDigest.intentV2(REQUEST_ID, scope, callerBinding),
          "AVAILABLE",
          true,
          5L,
          2,
          AccountJoinDigest.requestV2(
              scope,
              callerBinding,
              AccountJoinDigest.EntitlementAvailabilityV2.AVAILABLE,
              true,
              5L),
          "AVAILABLE",
          null,
          false,
          "PENDING",
          scopeEvidence);
    }

    private static CanonicalJoinOperationEvidence committed(
        CanonicalJoinOperationEvidence pending, CanonicalJoinTerminalProof proof) {
      return new CanonicalJoinOperationEvidence(
          pending.requestId(),
          pending.privateAccountId(),
          pending.callerBinding(),
          pending.scopeTokenHash(),
          pending.connectScopeDigest(),
          pending.operationRepresentationVersion(),
          pending.scopeDigestVersion(),
          pending.intentDigestVersion(),
          pending.intentDigest(),
          pending.entitlementAuthorityAvailability(),
          pending.allowPublicJoin(),
          pending.entitlementVersion(),
          pending.requestDigestVersion(),
          pending.requestDigest(),
          pending.lastAttemptAuthorityAvailability(),
          pending.lastAttemptFailureCode(),
          pending.callerBoundAuthorityInvalidated(),
          "COMMITTED",
          "JOINED",
          proof.membershipId(),
          proof.membershipVersion(),
          proof.membershipAuthorityGeneration(),
          proof,
          pending.scopeEvidence());
    }

    private static CanonicalJoinScopeV2 scope(UUID accountUuid) {
      return new CanonicalJoinScopeV2(
          "scope-token",
          accountUuid,
          TENANT_ID,
          REALM_ID,
          "tenant",
          "world",
          "production",
          NAMESPACE_ID,
          "SHARED",
          INSTANCE_ID,
          7L,
          3L,
          "2026-10-04T00:00:00Z",
          "2026-10-04T00:02:00Z");
    }

    private Event event(String requestId) {
      String eventId =
          UUID.nameUUIDFromBytes(
                  (MembershipAuthorityEventV1Codec.SCHEMA_VERSION + ":" + requestId)
                      .getBytes(StandardCharsets.UTF_8))
              .toString();
      MembershipEvent sealed =
          MembershipAuthorityEventV1Codec.seal(
              Map.ofEntries(
                  Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
                  Map.entry("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE),
                  Map.entry("eventId", eventId),
                  Map.entry("requestId", requestId),
                  Map.entry("outboxStreamKey", EVENT_STREAM),
                  Map.entry("outboxSequence", "1"),
                  Map.entry("sourceScope", "membership/" + ACCOUNT_ID + "/" + TENANT_ID),
                  Map.entry("accountId", ACCOUNT_ID.toString()),
                  Map.entry("tenantId", TENANT_ID.toString()),
                  Map.entry("membershipExists", true),
                  Map.entry("membershipLifecycleState", "ACTIVE"),
                  Map.entry("membershipVersion", Map.of(TENANT_ID.toString(), "2")),
                  Map.entry("membershipAuthorityGeneration", "1"),
                  Map.entry(
                      "authorityTuple",
                      Map.of(
                          "issuerAuthGeneration",
                          "1",
                          "accountAuthorityGeneration",
                          "1",
                          "tenantAuthorityGeneration",
                          Map.of(TENANT_ID.toString(), "1"),
                          "membershipAuthorityGeneration",
                          Map.of(TENANT_ID.toString(), "1"),
                          "privateRealmGrantVersions",
                          List.of())),
                  Map.entry("issuanceFence", "1"),
                  Map.entry("roles", List.of("player")),
                  Map.entry("gameplayAdmissionAllowed", true),
                  Map.entry("callerBoundAuthorityInvalidated", false)));
      return new Event(
          EVENT_STREAM,
          requestId,
          1L,
          sealed.eventId(),
          sealed.eventDigest(),
          sealed.canonicalJsonUtf8());
    }

    private static UUID auditEventId() {
      return UUID.nameUUIDFromBytes(
          ("account-join-audit/v1:" + REQUEST_ID).getBytes(StandardCharsets.UTF_8));
    }
  }
}
