package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest.EntitlementAvailabilityV2;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.EventEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceCheckpoint;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairTransition;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountMembershipAuthorityEventProducerCanonicalJoinTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_UUID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID REALM_UUID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID NAMESPACE_UUID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID INSTANCE_UUID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID TENANT_OPERATION_ID =
      UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final String REQUEST_ID = "global-request-1";
  private static final String CALLER_BINDING = "server-verified-caller";
  private static final String MEMBERSHIP_STREAM =
      "account:auth-authority:v1:membership/" + ACCOUNT_UUID + "/" + TENANT_UUID;
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void appendsCanonicalEventReadbackAndFirstPairCasFromLockedOwnerEvidence() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    AtomicReference<Event> committedEvent = new AtomicReference<>();
    AtomicInteger membershipCheckpointReads = new AtomicInteger();
    when(fixture.outbox.readCheckpoint(any(String.class)))
        .thenAnswer(
            invocation -> {
              String stream = invocation.getArgument(0);
              if (!MEMBERSHIP_STREAM.equals(stream)) {
                return Optional.empty();
              }
              return membershipCheckpointReads.getAndIncrement() == 0
                  ? Optional.empty()
                  : Optional.of(fixture.checkpoint(committedEvent.get()));
            });
    when(fixture.outbox.append(eq(MEMBERSHIP_STREAM), eq(REQUEST_ID), any(LongFunction.class)))
        .thenAnswer(
            invocation -> {
              LongFunction<EventEvidence> factory = invocation.getArgument(2);
              EventEvidence evidence = factory.apply(1L);
              Event event =
                  new Event(
                      MEMBERSHIP_STREAM,
                      REQUEST_ID,
                      1L,
                      evidence.eventId(),
                      evidence.eventDigest(),
                      evidence.payload());
              committedEvent.set(event);
              return event;
            });
    when(fixture.outbox.findEvent(MEMBERSHIP_STREAM, REQUEST_ID))
        .thenAnswer(invocation -> Optional.of(committedEvent.get()));
    when(fixture.outbox.findEvent(MEMBERSHIP_STREAM, 1L))
        .thenAnswer(invocation -> Optional.of(committedEvent.get()));
    // The pair event ID and digest are supplied by the event candidate, as in the real CAS.
    when(fixture.pairs.commitTransition(eq(fixture.absenceBaseline), any(PairTransition.class)))
        .thenAnswer(
            invocation -> {
              PairTransition transition = invocation.getArgument(1);
              return fixture.positivePair(transition.eventId(), transition.eventDigest());
            });
    fixture.startWritableTransaction();

    Checkpoint checkpoint =
        fixture.producer.publishCanonicalFirstJoinMembershipChange(
            fixture.scope, REQUEST_ID, CALLER_BINDING);

    MembershipEvent event =
        MembershipAuthorityEventV1Codec.verify(
            new String(committedEvent.get().payload(), StandardCharsets.UTF_8));
    assertThat(checkpoint).isEqualTo(fixture.checkpoint(committedEvent.get()));
    assertThat(event.eventId()).isEqualTo(fixture.expectedEventId());
    assertThat(event.membershipVersion()).isEqualTo(Map.of(TENANT_UUID.toString(), "2"));
    assertThat(event.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(event.roles()).containsExactly("player");
    assertThat(event.issuanceFence()).isEqualTo("1");
    assertThat(event.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(Map.of(TENANT_UUID.toString(), "1"));
    assertThat(event.authorityTuple().accountSecurityCutoff()).isEmpty();
    assertThat(event.authorityTuple().tenantBillingCutoff()).isEmpty();

    var order = inOrder(fixture.accounts, fixture.joinOperations, fixture.memberships);
    order.verify(fixture.accounts).findByAccountUuid(ACCOUNT_UUID);
    order.verify(fixture.joinOperations).lockAccount(17L);
    order.verify(fixture.joinOperations).findCanonicalEvidenceForUpdateByRequestId(REQUEST_ID);
    order
        .verify(fixture.memberships)
        .findFreshJoinForPublicationForUpdate(ACCOUNT_UUID, TENANT_UUID);
    verify(fixture.pairs).commitTransition(eq(fixture.absenceBaseline), any(PairTransition.class));
  }

  @Test
  void exactPositiveReplayReturnsTheExistingCheckpointWithoutAnotherAppendOrCas() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    Event event = fixture.existingEvent();
    Checkpoint checkpoint = fixture.checkpoint(event);
    when(fixture.memberships.findFreshJoinForPublicationForUpdate(ACCOUNT_UUID, TENANT_UUID))
        .thenReturn(Optional.of(fixture.membership));
    when(fixture.pairs.readForUpdate(ACCOUNT_UUID, TENANT_UUID))
        .thenReturn(Optional.of(fixture.positivePair(event.eventId(), event.eventDigest())));
    when(fixture.outbox.readCheckpoint(any(String.class)))
        .thenAnswer(
            invocation ->
                MEMBERSHIP_STREAM.equals(invocation.getArgument(0))
                    ? Optional.of(checkpoint)
                    : Optional.empty());
    when(fixture.outbox.findEvent(MEMBERSHIP_STREAM, REQUEST_ID)).thenReturn(Optional.of(event));
    when(fixture.outbox.findEvent(MEMBERSHIP_STREAM, 1L)).thenReturn(Optional.of(event));
    fixture.startWritableTransaction();

    Checkpoint replay =
        fixture.producer.publishCanonicalFirstJoinMembershipChange(
            fixture.scope, REQUEST_ID, CALLER_BINDING);

    assertThat(replay).isEqualTo(checkpoint);
    verify(fixture.outbox, never())
        .append(eq(MEMBERSHIP_STREAM), eq(REQUEST_ID), any(LongFunction.class));
    verify(fixture.pairs, never()).commitTransition(any(), any());
  }

  @Test
  void generationOneRejectsAnIssuanceFenceWithAdvancedValueAndGenesisSourceVersion() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    when(fixture.authority.readCompositeSnapshot(
            "firemud-account-service", ACCOUNT_UUID, List.of(TENANT_UUID), List.of(TENANT_UUID)))
        .thenReturn(fixture.authoritySnapshot(2L, 1L));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("generation-one owner rows");

    verifyNoInteractions(fixture.outbox);
    verify(fixture.pairs, never()).commitTransition(any(), any());
  }

  @Test
  void conflictingCallerIsDeniedBeforeMembershipOrEventSourcesAreRead() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    when(fixture.joinOperations.findCanonicalEvidenceForUpdateByRequestId(REQUEST_ID))
        .thenReturn(Optional.of(fixture.operation("different-caller")));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("request, caller, scope, or available policy");
    verify(fixture.memberships, never())
        .findFreshJoinForPublicationForUpdate(ACCOUNT_UUID, TENANT_UUID);
    verify(fixture.roles, never())
        .findForCanonicalUpdate(any(), any(), any(), anyLong(), anyLong());
    verifyNoInteractions(fixture.pairs, fixture.authority, fixture.outbox, fixture.sourceEvidence);
  }

  @Test
  void eventPublicationRequiresWritableAccountTransactionBeforeAnyLookup() {
    Fixture fixture = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(false);

    assertThatThrownBy(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical Account JOIN requires an active owner transaction");
    verifyNoInteractions(
        fixture.accounts,
        fixture.joinOperations,
        fixture.memberships,
        fixture.roles,
        fixture.pairs,
        fixture.authority,
        fixture.outbox,
        fixture.sourceEvidence);
  }

  private static final class Fixture {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountJoinOperationRepository joinOperations =
        mock(AccountJoinOperationRepository.class);
    private final AccountTenantMembershipRepository memberships =
        mock(AccountTenantMembershipRepository.class);
    private final AccountTenantMembershipRoleSnapshotRepository roles =
        mock(AccountTenantMembershipRoleSnapshotRepository.class);
    private final AccountMembershipPairAuthorityRepository pairs =
        mock(AccountMembershipPairAuthorityRepository.class);
    private final AccountAuthorityGenerationRepository authority =
        mock(AccountAuthorityGenerationRepository.class);
    private final AccountAuthorityOutboxRepository outbox =
        mock(AccountAuthorityOutboxRepository.class);
    private final AccountAuthoritySourceEvidenceRepository sourceEvidence =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    private final AccountMembershipAuthorityEventProducer producer =
        new AccountMembershipAuthorityEventProducer(
            joinOperations, accounts, pairs, memberships, roles, authority, outbox, sourceEvidence);
    private final CanonicalJoinScopeV2 scope = scopeValue();
    private final VerifiedTenantProvenance provenance = provenanceValue();
    private final Account account = accountValue();
    private final AccountTenantMembership membership = membershipValue(account);
    private final PairAuthority absenceBaseline =
        new PairAuthority(
            ACCOUNT_UUID, TENANT_UUID, provenance, false, 1L, 1L, 0L, null, null, false);

    private void arrangePendingFirstJoin() {
      when(accounts.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(account));
      when(joinOperations.findCanonicalEvidenceForUpdateByRequestId(REQUEST_ID))
          .thenReturn(Optional.of(operation(CALLER_BINDING)));
      when(memberships.findFreshJoinForPublicationForUpdate(ACCOUNT_UUID, TENANT_UUID))
          .thenReturn(Optional.of(membership));
      when(roles.findForCanonicalUpdate(ACCOUNT_UUID, TENANT_UUID, provenance, 73L, 2L))
          .thenReturn(Optional.of(roleSnapshot()));
      when(pairs.readForUpdate(ACCOUNT_UUID, TENANT_UUID)).thenReturn(Optional.of(absenceBaseline));
      when(authority.readCompositeSnapshot(
              "firemud-account-service", ACCOUNT_UUID, List.of(TENANT_UUID), List.of(TENANT_UUID)))
          .thenReturn(authoritySnapshot());
      when(sourceEvidence.readCurrentIssuerAccountSources("firemud-account-service", ACCOUNT_UUID))
          .thenReturn(freshSourceSnapshot());
      when(outbox.readCheckpoint(any(String.class))).thenReturn(Optional.empty());
    }

    private IssuerAccountSourceSnapshot freshSourceSnapshot() {
      CurrentSourceEvidence issuerSource =
          new CurrentSourceEvidence(
              AuthorityScope.issuer("firemud-account-service"),
              1L,
              1L,
              null,
              new SourceCheckpoint(
                  "account:auth-authority:v1:issuer/firemud-account-service",
                  0L,
                  Optional.empty(),
                  Optional.empty()),
              Optional.empty(),
              "ISSUER_SCOPE_INSERT",
              null,
              null,
              17L,
              null);
      IssuanceFence accountFence = new IssuanceFence(ACCOUNT_UUID, 1L, 1L);
      CurrentSourceEvidence accountSource =
          new CurrentSourceEvidence(
              AuthorityScope.account(ACCOUNT_UUID),
              1L,
              1L,
              accountFence,
              new SourceCheckpoint(
                  "account:auth-authority:v1:account/" + ACCOUNT_UUID,
                  0L,
                  Optional.empty(),
                  Optional.empty()),
              Optional.empty(),
              "ACCOUNT_REPOSITORY_INSERT",
              17L,
              "ACCOUNT_REPOSITORY_INSERT",
              17L,
              17L);
      return new IssuerAccountSourceSnapshot(issuerSource, accountSource, accountFence);
    }

    private void startWritableTransaction() {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private Event existingEvent() {
      MembershipEvent event =
          MembershipAuthorityEventV1Codec.seal(
              Map.ofEntries(
                  Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
                  Map.entry("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE),
                  Map.entry("eventId", expectedEventId()),
                  Map.entry("requestId", REQUEST_ID),
                  Map.entry("outboxStreamKey", MEMBERSHIP_STREAM),
                  Map.entry("outboxSequence", "1"),
                  Map.entry("sourceScope", "membership/" + ACCOUNT_UUID + "/" + TENANT_UUID),
                  Map.entry("accountId", ACCOUNT_UUID.toString()),
                  Map.entry("tenantId", TENANT_UUID.toString()),
                  Map.entry("membershipExists", true),
                  Map.entry("membershipLifecycleState", "ACTIVE"),
                  Map.entry("membershipVersion", Map.of(TENANT_UUID.toString(), "2")),
                  Map.entry("membershipAuthorityGeneration", "1"),
                  Map.entry(
                      "authorityTuple",
                      Map.of(
                          "issuerAuthGeneration", "1",
                          "accountAuthorityGeneration", "1",
                          "tenantAuthorityGeneration", Map.of(TENANT_UUID.toString(), "1"),
                          "membershipAuthorityGeneration", Map.of(TENANT_UUID.toString(), "1"),
                          "privateRealmGrantVersions", List.of())),
                  Map.entry("issuanceFence", "1"),
                  Map.entry("roles", List.of("player")),
                  Map.entry("gameplayAdmissionAllowed", true),
                  Map.entry("callerBoundAuthorityInvalidated", false)));
      return new Event(
          MEMBERSHIP_STREAM,
          REQUEST_ID,
          1L,
          event.eventId(),
          event.eventDigest(),
          event.canonicalJsonUtf8());
    }

    private Checkpoint checkpoint(Event event) {
      return new Checkpoint(MEMBERSHIP_STREAM, 1L, event.eventId(), event.eventDigest());
    }

    private PairAuthority positivePair(String eventId, String digest) {
      return new PairAuthority(
          ACCOUNT_UUID, TENANT_UUID, provenance, true, 2L, 1L, 1L, eventId, digest, false);
    }

    private String expectedEventId() {
      return UUID.nameUUIDFromBytes(
              (MembershipAuthorityEventV1Codec.SCHEMA_VERSION + ":" + REQUEST_ID)
                  .getBytes(StandardCharsets.UTF_8))
          .toString();
    }

    private CompositeSnapshot authoritySnapshot() {
      return authoritySnapshot(1L, 1L);
    }

    private CompositeSnapshot authoritySnapshot(long fenceValue, long sourceVersion) {
      IssuanceFence fence = new IssuanceFence(ACCOUNT_UUID, fenceValue, sourceVersion);
      return new CompositeSnapshot(
          new ScopeState(AuthorityScope.issuer("firemud-account-service"), 1L, 1L, null),
          new ScopeState(AuthorityScope.account(ACCOUNT_UUID), 1L, 1L, fence),
          List.of(new ScopeState(AuthorityScope.tenant(TENANT_UUID), 1L, 1L, null)),
          List.of(
              new ScopeState(AuthorityScope.membership(ACCOUNT_UUID, TENANT_UUID), 1L, 1L, fence)),
          fence);
    }

    private CanonicalJoinOperationEvidence operation(String callerBinding) {
      var scopeEvidence =
          new AccountConnectScopeRepository.CanonicalConnectScopeEvidence(
              AccountJoinDigest.tokenHash(scope.connectScopeId()),
              17L,
              "PUBLIC_PRODUCTION",
              ACCOUNT_UUID,
              TENANT_UUID,
              REALM_UUID,
              "tenant",
              "world",
              "production",
              NAMESPACE_UUID,
              "SHARED",
              INSTANCE_UUID,
              8L,
              3L,
              scope.evaluatedAt(),
              scope.connectScopeExpiresAt(),
              2,
              AccountJoinDigest.scopeV2(scope),
              provenance);
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
              scope, callerBinding, EntitlementAvailabilityV2.AVAILABLE, true, 5L),
          "AVAILABLE",
          null,
          false,
          "PENDING",
          scopeEvidence);
    }

    private RoleSnapshot roleSnapshot() {
      return new RoleSnapshot(
          17L, null, 73L, 2L, List.of("player"), ACCOUNT_UUID, TENANT_UUID, provenance);
    }
  }

  private static CanonicalJoinScopeV2 scopeValue() {
    return new CanonicalJoinScopeV2(
        "opaque-canonical-scope-token",
        ACCOUNT_UUID,
        TENANT_UUID,
        REALM_UUID,
        "tenant",
        "world",
        "production",
        NAMESPACE_UUID,
        "SHARED",
        INSTANCE_UUID,
        8L,
        3L,
        "2026-10-03T00:00:00Z",
        "2026-10-03T00:01:00Z");
  }

  private static VerifiedTenantProvenance provenanceValue() {
    return new VerifiedTenantProvenance(
        null, TenantProvenanceKind.FRESH_GAME_DESIGN, TENANT_OPERATION_ID, DIGEST);
  }

  private static Account accountValue() {
    Account account = new Account();
    account.setId(17L);
    account.setAccountUuid(ACCOUNT_UUID);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setAccountUuidSourceNumericId(17L);
    return account;
  }

  private static AccountTenantMembership membershipValue(Account account) {
    AccountTenantMembership membership = new AccountTenantMembership();
    membership.setId(73L);
    membership.setAccount(account);
    membership.setTenantId(null);
    membership.setTenantUuid(TENANT_UUID);
    membership.setTenantProvenanceKind(TenantProvenanceKind.FRESH_GAME_DESIGN.name());
    membership.setTenantSourceOperationId(TENANT_OPERATION_ID);
    membership.setTenantProvenanceDigest(DIGEST);
    membership.setMembershipVersion(2L);
    membership.setMembershipAuthorityGeneration(1L);
    membership.setLifecycleState("ACTIVE");
    membership.setGameplayAdmissionAllowed(true);
    membership.setAuthorityProvenance("EXPLICIT_JOIN");
    return membership;
  }
}
