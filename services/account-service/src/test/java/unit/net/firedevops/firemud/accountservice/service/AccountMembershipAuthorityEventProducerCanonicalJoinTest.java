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
import java.time.Instant;
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
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec;
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
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository.CanonicalJoinTerminalProof;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairTransition;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
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
  private static final String TENANT_SOURCE_NAMESPACE = "account-service";
  private static final FreshTenantCreationEvidence CURRENT_TENANT_SOURCE =
      freshTenantEvidence(TENANT_UUID, TENANT_OPERATION_ID);
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
    DemoTenantEntitlementSnapshot currentEntitlement =
        fixture.entitlementSnapshot(fixture.currentTenantEvent());
    assertThat(currentEntitlement.entitlementVersion()).isEqualTo(5L);
    assertThat(currentEntitlement.tenantAuthorityGeneration()).isEqualTo(2L);
    assertThat(currentEntitlement.entitlementVersion())
        .isNotEqualTo(currentEntitlement.tenantAuthorityGeneration());
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
        .isEqualTo(Map.of(TENANT_UUID.toString(), "2"));
    assertThat(event.authorityTuple().accountSecurityCutoff()).isEmpty();
    assertThat(event.authorityTuple().tenantBillingCutoff())
        .contains(
            Map.of(
                TENANT_UUID.toString(),
                new MembershipAuthorityEventV1Codec.TenantBillingCutoff(
                    "2", "1", "account:auth-authority:v1:tenant/" + TENANT_UUID, "1")));
    verify(fixture.tenantAuthorityEvents).readCurrentByTenant(TENANT_UUID);
    verify(fixture.entitlements).readCurrent(TENANT_UUID);

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
    when(fixture.joinOperations.findCanonicalEvidenceForUpdateByRequestId(REQUEST_ID))
        .thenReturn(Optional.of(fixture.committedOperation(CALLER_BINDING, event)));
    when(fixture.authority.readCompositeSnapshot(
            "firemud-account-service", ACCOUNT_UUID, List.of(TENANT_UUID), List.of(TENANT_UUID)))
        .thenReturn(fixture.authoritySnapshot(5L, 4L, 3L, 1L));
    when(fixture.sourceEvidence.readCurrentIssuerAccountSources(
            "firemud-account-service", ACCOUNT_UUID))
        .thenReturn(fixture.sourceSnapshot(5L, 4L));
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
    when(fixture.entitlements.readCurrent(TENANT_UUID))
        .thenThrow(new IllegalStateException("current entitlement is no longer available"));
    fixture.startWritableTransaction();

    Checkpoint replay =
        fixture.producer.publishCanonicalFirstJoinMembershipChange(
            fixture.scope, REQUEST_ID, CALLER_BINDING);

    assertThat(replay).isEqualTo(checkpoint);
    verify(fixture.outbox, never())
        .append(eq(MEMBERSHIP_STREAM), eq(REQUEST_ID), any(LongFunction.class));
    verify(fixture.pairs, never()).commitTransition(any(), any());
    verify(fixture.authority, never())
        .readCompositeSnapshot(
            "firemud-account-service", ACCOUNT_UUID, List.of(TENANT_UUID), List.of(TENANT_UUID));
    verifyNoInteractions(fixture.sourceEvidence);
    verify(fixture.entitlements, never()).readCurrent(TENANT_UUID);
  }

  @Test
  void firstJoinUsesCurrentAccountAndIssuerAuthorityAfterPriorSourceMutation() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    when(fixture.authority.readCompositeSnapshot(
            "firemud-account-service", ACCOUNT_UUID, List.of(TENANT_UUID), List.of(TENANT_UUID)))
        .thenReturn(fixture.authoritySnapshot(3L, 3L, 2L, 1L));
    when(fixture.sourceEvidence.readCurrentIssuerAccountSources(
            "firemud-account-service", ACCOUNT_UUID))
        .thenReturn(fixture.sourceSnapshot(3L, 3L));
    fixture.startWritableTransaction();

    AtomicReference<Event> committedEvent = new AtomicReference<>();
    AtomicInteger membershipCheckpointReads = new AtomicInteger();
    when(fixture.outbox.readCheckpoint(any(String.class)))
        .thenAnswer(
            invocation -> {
              String stream = invocation.getArgument(0);
              if (!MEMBERSHIP_STREAM.equals(stream)) {
                return Optional.of(new Checkpoint(stream, 1L, "tenant-event-1", DIGEST));
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
    when(fixture.pairs.commitTransition(eq(fixture.absenceBaseline), any(PairTransition.class)))
        .thenAnswer(
            invocation -> {
              PairTransition transition = invocation.getArgument(1);
              return fixture.positivePair(transition.eventId(), transition.eventDigest());
            });

    fixture.producer.publishCanonicalFirstJoinMembershipChange(
        fixture.scope, REQUEST_ID, CALLER_BINDING);

    MembershipEvent event =
        MembershipAuthorityEventV1Codec.verify(
            new String(committedEvent.get().payload(), StandardCharsets.UTF_8));
    assertThat(event.authorityTuple().issuerAuthGeneration()).isEqualTo("3");
    assertThat(event.authorityTuple().accountAuthorityGeneration()).isEqualTo("3");
    assertThat(event.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(Map.of(TENANT_UUID.toString(), "2"));
    assertThat(event.authorityTuple().membershipAuthorityGeneration())
        .isEqualTo(Map.of(TENANT_UUID.toString(), "1"));
    assertThat(event.issuanceFence()).isEqualTo("3");
    assertThat(event.authorityTuple().accountSecurityCutoff()).isPresent();
    assertThat(event.authorityTuple().accountSecurityCutoff().orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(
            new AccountSecurityCutoff(
                "3", "account:auth-authority:v1:account/" + ACCOUNT_UUID, "2"));
    verify(fixture.memberships).findFreshJoinForPublicationForUpdate(ACCOUNT_UUID, TENANT_UUID);
    verify(fixture.outbox, never())
        .readCheckpoint("account:auth-authority:v1:tenant/" + TENANT_UUID);
  }

  @Test
  void firstJoinAcceptsLaterTenantAuthorityOnlyWithExactCurrentTenantSourceAndBillingCutoff() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    when(fixture.authority.readCompositeSnapshot(
            "firemud-account-service", ACCOUNT_UUID, List.of(TENANT_UUID), List.of(TENANT_UUID)))
        .thenReturn(fixture.authoritySnapshot(1L, 1L, 2L, 1L));
    TenantAuthorityEventV1Codec.Event tenantSourceEvent =
        tenantAuthorityEvent(TENANT_UUID, TENANT_OPERATION_ID, 2L, 2L, 9L, 7L);
    when(fixture.tenantAuthorityEvents.readCurrentByTenant(TENANT_UUID))
        .thenReturn(tenantSourceEvent);
    when(fixture.entitlements.readCurrent(TENANT_UUID))
        .thenReturn(fixture.entitlementSnapshot(tenantSourceEvent));
    fixture.startWritableTransaction();

    AtomicReference<Event> committedEvent = new AtomicReference<>();
    AtomicInteger membershipCheckpointReads = new AtomicInteger();
    when(fixture.outbox.readCheckpoint(any(String.class)))
        .thenAnswer(
            invocation ->
                membershipCheckpointReads.getAndIncrement() == 0
                    ? Optional.empty()
                    : Optional.of(fixture.checkpoint(committedEvent.get())));
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
    when(fixture.pairs.commitTransition(eq(fixture.absenceBaseline), any(PairTransition.class)))
        .thenAnswer(
            invocation -> {
              PairTransition transition = invocation.getArgument(1);
              return fixture.positivePair(transition.eventId(), transition.eventDigest());
            });

    fixture.producer.publishCanonicalFirstJoinMembershipChange(
        fixture.scope, REQUEST_ID, CALLER_BINDING);

    MembershipEvent event =
        MembershipAuthorityEventV1Codec.verify(
            new String(committedEvent.get().payload(), StandardCharsets.UTF_8));
    assertThat(event.authorityTuple().tenantAuthorityGeneration())
        .isEqualTo(Map.of(TENANT_UUID.toString(), "2"));
    assertThat(event.authorityTuple().tenantBillingCutoff())
        .contains(
            Map.of(
                TENANT_UUID.toString(),
                new MembershipAuthorityEventV1Codec.TenantBillingCutoff(
                    "2", "7", "account:auth-authority:v1:tenant/" + TENANT_UUID, "9")));
    verify(fixture.tenantAuthorityEvents).readCurrentByTenant(TENANT_UUID);
  }

  @Test
  void firstJoinDeniesBareGenerationOneTenantBaselineBeforeEntitlementLookupOrMutation() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    when(fixture.authority.readCompositeSnapshot(
            "firemud-account-service", ACCOUNT_UUID, List.of(TENANT_UUID), List.of(TENANT_UUID)))
        .thenReturn(fixture.authoritySnapshot(1L, 1L, 1L, 1L));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "Canonical first JOIN requires exact current public-JOIN entitlement and tenant authority evidence");

    verify(fixture.tenantAuthorityEvents, never()).readCurrentByTenant(TENANT_UUID);
    verify(fixture.entitlements, never()).readCurrent(TENANT_UUID);
    verify(fixture.outbox, never())
        .append(eq(MEMBERSHIP_STREAM), eq(REQUEST_ID), any(LongFunction.class));
    verify(fixture.pairs, never()).commitTransition(any(), any());
  }

  @Test
  void firstJoinDeniesAStalePendingEntitlementVersionWithoutMutation() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    TenantAuthorityEventV1Codec.Event currentEvent =
        tenantAuthorityEvent(TENANT_UUID, TENANT_OPERATION_ID, 2L, 2L, 1L, 1L, 6L, true, true);
    when(fixture.tenantAuthorityEvents.readCurrentByTenant(TENANT_UUID)).thenReturn(currentEvent);
    when(fixture.entitlements.readCurrent(TENANT_UUID))
        .thenReturn(fixture.entitlementSnapshot(currentEvent, 6L, true, true));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact current public-join entitlement evidence");

    verify(fixture.outbox, never())
        .append(eq(MEMBERSHIP_STREAM), eq(REQUEST_ID), any(LongFunction.class));
    verify(fixture.pairs, never()).commitTransition(any(), any());
  }

  @Test
  void firstJoinDeniesCurrentEntitlementWhenPublicJoinIsNoLongerAllowed() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    TenantAuthorityEventV1Codec.Event currentEvent =
        tenantAuthorityEvent(TENANT_UUID, TENANT_OPERATION_ID, 2L, 2L, 1L, 1L, 5L, true, false);
    when(fixture.tenantAuthorityEvents.readCurrentByTenant(TENANT_UUID)).thenReturn(currentEvent);
    when(fixture.entitlements.readCurrent(TENANT_UUID))
        .thenReturn(fixture.entitlementSnapshot(currentEvent, 5L, true, false));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact current public-join entitlement evidence");

    verify(fixture.outbox, never())
        .append(eq(MEMBERSHIP_STREAM), eq(REQUEST_ID), any(LongFunction.class));
    verify(fixture.pairs, never()).commitTransition(any(), any());
  }

  @Test
  void firstJoinDeniesCurrentEntitlementWhenGameplayIsUnavailable() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    TenantAuthorityEventV1Codec.Event currentEvent =
        tenantAuthorityEvent(TENANT_UUID, TENANT_OPERATION_ID, 2L, 2L, 1L, 1L, 5L, false, true);
    when(fixture.tenantAuthorityEvents.readCurrentByTenant(TENANT_UUID)).thenReturn(currentEvent);
    when(fixture.entitlements.readCurrent(TENANT_UUID))
        .thenReturn(fixture.entitlementSnapshot(currentEvent, 5L, false, true));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact current public-join entitlement evidence");

    verify(fixture.outbox, never())
        .append(eq(MEMBERSHIP_STREAM), eq(REQUEST_ID), any(LongFunction.class));
    verify(fixture.pairs, never()).commitTransition(any(), any());
  }

  @Test
  void firstJoinFailsClosedWhenCurrentEntitlementReadIsUnavailable() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    when(fixture.entitlements.readCurrent(TENANT_UUID))
        .thenThrow(new IllegalStateException("current entitlement unavailable"));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("current entitlement unavailable");

    verify(fixture.outbox, never())
        .append(eq(MEMBERSHIP_STREAM), eq(REQUEST_ID), any(LongFunction.class));
    verify(fixture.pairs, never()).commitTransition(any(), any());
  }

  @Test
  void firstJoinDeniesEntitlementSnapshotWithDifferentCurrentAuthorityEventBinding() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    TenantAuthorityEventV1Codec.Event differentCurrentEvent =
        tenantAuthorityEvent(TENANT_UUID, TENANT_OPERATION_ID, 2L, 2L, 9L, 1L);
    when(fixture.entitlements.readCurrent(TENANT_UUID))
        .thenReturn(fixture.entitlementSnapshot(differentCurrentEvent));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact current public-join entitlement evidence");

    verify(fixture.outbox, never())
        .append(eq(MEMBERSHIP_STREAM), eq(REQUEST_ID), any(LongFunction.class));
    verify(fixture.pairs, never()).commitTransition(any(), any());
  }

  @Test
  void firstJoinDeniesAdvancedTenantAuthorityWithoutTenantSourceEventReadback() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    when(fixture.authority.readCompositeSnapshot(
            "firemud-account-service", ACCOUNT_UUID, List.of(TENANT_UUID), List.of(TENANT_UUID)))
        .thenReturn(fixture.authoritySnapshot(3L, 3L, 2L, 1L));
    when(fixture.sourceEvidence.readCurrentIssuerAccountSources(
            "firemud-account-service", ACCOUNT_UUID))
        .thenReturn(fixture.sourceSnapshot(3L, 3L));
    when(fixture.tenantAuthorityEvents.readCurrentByTenant(TENANT_UUID)).thenReturn(null);
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact current Account, source, and tenant authority evidence");

    verify(fixture.tenantAuthorityEvents).readCurrentByTenant(TENANT_UUID);

    verify(fixture.outbox, never())
        .append(eq(MEMBERSHIP_STREAM), eq(REQUEST_ID), any(LongFunction.class));
    verify(fixture.pairs, never()).commitTransition(any(), any());
  }

  @Test
  void firstJoinDeniesCurrentTenantSourceForAnotherTenantWithoutMutation() {
    denyLaterTenantSource(
        tenantAuthorityEvent(
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
            TENANT_OPERATION_ID,
            2L,
            2L,
            9L,
            7L));
  }

  @Test
  void firstJoinDeniesCurrentTenantSourceWithDifferentFreshGameDesignProvenance() {
    denyLaterTenantSource(
        tenantAuthorityEvent(
            TENANT_UUID, UUID.fromString("77777777-7777-4777-8777-777777777777"), 2L, 2L, 9L, 7L));
  }

  @Test
  void firstJoinDeniesTenantSourceGenerationThatDiffersFromCompositeSnapshot() {
    denyLaterTenantSource(tenantAuthorityEvent(TENANT_UUID, TENANT_OPERATION_ID, 3L, 2L, 9L, 7L));
  }

  @Test
  void firstJoinDeniesTenantSourceVersionThatDiffersFromCompositeSnapshot() {
    denyLaterTenantSource(tenantAuthorityEvent(TENANT_UUID, TENANT_OPERATION_ID, 2L, 3L, 9L, 7L));
  }

  @Test
  void firstJoinDeniesWhenTenantSourceRepositoryRejectsMissingOrMismatchedBillingEvidence() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    when(fixture.authority.readCompositeSnapshot(
            "firemud-account-service", ACCOUNT_UUID, List.of(TENANT_UUID), List.of(TENANT_UUID)))
        .thenReturn(fixture.authoritySnapshot(1L, 1L, 2L, 1L));
    when(fixture.tenantAuthorityEvents.readCurrentByTenant(TENANT_UUID))
        .thenThrow(
            new IllegalStateException("Current tenant billing event is absent or mismatched"));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("billing event is absent or mismatched");

    verify(fixture.outbox, never())
        .append(eq(MEMBERSHIP_STREAM), eq(REQUEST_ID), any(LongFunction.class));
    verify(fixture.pairs, never()).commitTransition(any(), any());
  }

  @Test
  void firstJoinDeniesWhenCurrentAccountSourceIdentityDoesNotMatchLockedAccount() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    when(fixture.sourceEvidence.readCurrentIssuerAccountSources(
            "firemud-account-service", ACCOUNT_UUID))
        .thenReturn(fixture.sourceSnapshot(1L, 1L, 18L));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact current Account, source, and tenant authority evidence");

    verify(fixture.outbox, never())
        .append(eq(MEMBERSHIP_STREAM), eq(REQUEST_ID), any(LongFunction.class));
    verify(fixture.pairs, never()).commitTransition(any(), any());
  }

  @Test
  void firstJoinDeniesWhenLockedMembershipTenantProvenanceDiffersFromItsOperation() {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    fixture.membership.setTenantProvenanceDigest("sha256:" + "b".repeat(64));
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("locked Account or tenant provenance");

    verify(fixture.roles, never())
        .findForCanonicalUpdate(any(), any(), any(), anyLong(), anyLong());
    verifyNoInteractions(fixture.authority, fixture.outbox, fixture.sourceEvidence);
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
        fixture.sourceEvidence,
        fixture.tenantAuthorityEvents);
  }

  private void denyLaterTenantSource(TenantAuthorityEventV1Codec.Event event) {
    Fixture fixture = new Fixture();
    fixture.arrangePendingFirstJoin();
    when(fixture.authority.readCompositeSnapshot(
            "firemud-account-service", ACCOUNT_UUID, List.of(TENANT_UUID), List.of(TENANT_UUID)))
        .thenReturn(fixture.authoritySnapshot(1L, 1L, 2L, 1L));
    when(fixture.tenantAuthorityEvents.readCurrentByTenant(TENANT_UUID)).thenReturn(event);
    fixture.startWritableTransaction();

    assertThatThrownBy(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, REQUEST_ID, CALLER_BINDING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact current Account, source, and tenant authority evidence");

    verify(fixture.outbox, never())
        .append(eq(MEMBERSHIP_STREAM), eq(REQUEST_ID), any(LongFunction.class));
    verify(fixture.pairs, never()).commitTransition(any(), any());
  }

  private static TenantAuthorityEventV1Codec.Event tenantAuthorityEvent(
      UUID tenantUuid,
      UUID sourceOperationId,
      long tenantGeneration,
      long tenantSourceVersion,
      long authorityOutboxSequence,
      long billingSequence) {
    return tenantAuthorityEvent(
        tenantUuid,
        sourceOperationId,
        tenantGeneration,
        tenantSourceVersion,
        authorityOutboxSequence,
        billingSequence,
        5L,
        true,
        true);
  }

  private static TenantAuthorityEventV1Codec.Event tenantAuthorityEvent(
      UUID tenantUuid,
      UUID sourceOperationId,
      long tenantGeneration,
      long tenantSourceVersion,
      long authorityOutboxSequence,
      long billingSequence,
      long entitlementVersion,
      boolean gameplayAvailable,
      boolean allowPublicJoin) {
    FreshTenantCreationEvidence source = freshTenantEvidence(tenantUuid, sourceOperationId);
    UUID entitlementRequestId =
        UUID.nameUUIDFromBytes(
            ("tenant-entitlement:"
                    + tenantUuid
                    + ":"
                    + tenantGeneration
                    + ":"
                    + tenantSourceVersion)
                .getBytes(StandardCharsets.UTF_8));
    DemoTenantEntitlementRequest request =
        new DemoTenantEntitlementRequest(
            entitlementRequestId,
            tenantUuid,
            source.creationRequestId(),
            source.requestDigest(),
            entitlementVersion - 1L,
            tenantGeneration - 1L,
            tenantSourceVersion - 1L,
            gameplayAvailable,
            allowPublicJoin,
            false,
            false,
            new DemoTenantEntitlementRequest.Quotas(3L, 2L, 4096L));
    var billingEvent =
        DemoTenantEntitlementEventV1Codec.seal(
            request,
            source,
            entitlementVersion,
            tenantGeneration,
            tenantSourceVersion,
            billingSequence);
    return TenantAuthorityEventV1Codec.seal(
        request,
        source,
        tenantGeneration,
        tenantSourceVersion,
        authorityOutboxSequence,
        billingEvent);
  }

  private static FreshTenantCreationEvidence freshTenantEvidence(
      UUID tenantUuid, UUID sourceOperationId) {
    UUID creationRequestId =
        UUID.nameUUIDFromBytes(
            ("tenant-create:" + tenantUuid + ":" + sourceOperationId)
                .getBytes(StandardCharsets.UTF_8));
    String sourceGameTenantKey =
        "fresh-"
            + tenantUuid.toString().substring(0, 8)
            + "-"
            + sourceOperationId.toString().substring(0, 8);
    String provenanceKind = "NEW_GAME_ROW";
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            TENANT_SOURCE_NAMESPACE,
            creationRequestId,
            sourceGameTenantKey,
            "Canonical JOIN tenant source",
            null);
    long sourceGameRowId = 91L;
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            TENANT_SOURCE_NAMESPACE,
            creationRequestId,
            sourceOperationId,
            requestDigest,
            tenantUuid,
            sourceGameRowId,
            sourceGameTenantKey,
            provenanceKind);
    return new FreshTenantCreationEvidence(
        1,
        TENANT_SOURCE_NAMESPACE,
        creationRequestId,
        sourceOperationId,
        requestDigest,
        tenantUuid,
        sourceGameRowId,
        sourceGameTenantKey,
        provenanceKind,
        evidenceDigest);
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
    private final AccountTenantAuthorityEventRepository tenantAuthorityEvents =
        mock(AccountTenantAuthorityEventRepository.class);
    private final AccountDemoTenantEntitlementRepository entitlements =
        mock(AccountDemoTenantEntitlementRepository.class);
    private final AccountMembershipAuthorityEventProducer producer =
        new AccountMembershipAuthorityEventProducer(
            joinOperations,
            accounts,
            pairs,
            memberships,
            roles,
            authority,
            outbox,
            sourceEvidence,
            tenantAuthorityEvents,
            entitlements);
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
      TenantAuthorityEventV1Codec.Event tenantEvent = currentTenantEvent();
      when(tenantAuthorityEvents.readCurrentByTenant(TENANT_UUID)).thenReturn(tenantEvent);
      when(entitlements.readCurrent(TENANT_UUID)).thenReturn(entitlementSnapshot(tenantEvent));
      when(outbox.readCheckpoint(any(String.class))).thenReturn(Optional.empty());
    }

    private TenantAuthorityEventV1Codec.Event currentTenantEvent() {
      return tenantAuthorityEvent(TENANT_UUID, TENANT_OPERATION_ID, 2L, 2L, 1L, 1L);
    }

    private DemoTenantEntitlementSnapshot entitlementSnapshot(
        TenantAuthorityEventV1Codec.Event event) {
      return entitlementSnapshot(event, 5L, true, true);
    }

    private DemoTenantEntitlementSnapshot entitlementSnapshot(
        TenantAuthorityEventV1Codec.Event event,
        long entitlementVersion,
        boolean gameplayAvailable,
        boolean allowPublicJoin) {
      return new DemoTenantEntitlementSnapshot(
          TENANT_UUID,
          event.sourceEvidence(),
          "NON_PAID_DEMO",
          "ACTIVE",
          null,
          false,
          gameplayAvailable,
          allowPublicJoin,
          true,
          true,
          new DemoTenantEntitlementRequest.Quotas(3L, 2L, 4096L),
          entitlementVersion,
          event.tenantAuthorityGeneration(),
          event.tenantAuthoritySourceVersion(),
          event.tenantBillingStreamKey(),
          event.tenantBillingSequence(),
          event.tenantBillingEventId(),
          event.tenantBillingEventDigest(),
          event.tenantBillingEventDigest(),
          event.outboxStreamKey(),
          event.outboxSequence(),
          event.eventId(),
          event.eventDigest());
    }

    private IssuerAccountSourceSnapshot freshSourceSnapshot() {
      return sourceSnapshot(1L, 1L);
    }

    private IssuerAccountSourceSnapshot sourceSnapshot(
        long issuerGeneration, long accountGeneration) {
      return sourceSnapshot(issuerGeneration, accountGeneration, 17L);
    }

    private IssuerAccountSourceSnapshot sourceSnapshot(
        long issuerGeneration, long accountGeneration, long sourceAccountId) {
      long issuerSequence = issuerGeneration - 1L;
      long accountSequence = accountGeneration - 1L;
      String issuerStream = "account:auth-authority:v1:issuer/firemud-account-service";
      String accountStream = "account:auth-authority:v1:account/" + ACCOUNT_UUID;
      CurrentSourceEvidence issuerSource =
          new CurrentSourceEvidence(
              AuthorityScope.issuer("firemud-account-service"),
              issuerGeneration,
              issuerGeneration,
              null,
              new SourceCheckpoint(
                  issuerStream,
                  issuerSequence,
                  issuerSequence == 0L
                      ? Optional.empty()
                      : Optional.of("issuer-event-" + issuerSequence),
                  issuerSequence == 0L ? Optional.empty() : Optional.of(DIGEST)),
              Optional.empty(),
              "ISSUER_SCOPE_INSERT",
              null,
              null,
              17L,
              null);
      IssuanceFence accountFence =
          new IssuanceFence(ACCOUNT_UUID, accountGeneration, accountGeneration);
      CurrentSourceEvidence accountSource =
          new CurrentSourceEvidence(
              AuthorityScope.account(ACCOUNT_UUID),
              accountGeneration,
              accountGeneration,
              accountFence,
              new SourceCheckpoint(
                  accountStream,
                  accountSequence,
                  accountSequence == 0L
                      ? Optional.empty()
                      : Optional.of("account-event-" + accountSequence),
                  accountSequence == 0L ? Optional.empty() : Optional.of(DIGEST)),
              accountSequence == 0L
                  ? Optional.empty()
                  : Optional.of(
                      new AccountSecurityCutoff(
                          Long.toString(accountGeneration),
                          accountStream,
                          Long.toString(accountSequence))),
              "ACCOUNT_REPOSITORY_INSERT",
              sourceAccountId,
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
      return authoritySnapshot(1L, 1L, 2L, 1L);
    }

    private CompositeSnapshot authoritySnapshot(
        long issuerGeneration,
        long accountGeneration,
        long tenantGeneration,
        long membershipGeneration) {
      IssuanceFence fence = new IssuanceFence(ACCOUNT_UUID, accountGeneration, accountGeneration);
      return new CompositeSnapshot(
          new ScopeState(
              AuthorityScope.issuer("firemud-account-service"),
              issuerGeneration,
              issuerGeneration,
              null),
          new ScopeState(
              AuthorityScope.account(ACCOUNT_UUID), accountGeneration, accountGeneration, fence),
          List.of(
              new ScopeState(
                  AuthorityScope.tenant(TENANT_UUID), tenantGeneration, tenantGeneration, null)),
          List.of(
              new ScopeState(
                  AuthorityScope.membership(ACCOUNT_UUID, TENANT_UUID),
                  membershipGeneration,
                  membershipGeneration,
                  fence)),
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

    private CanonicalJoinOperationEvidence committedOperation(String callerBinding, Event event) {
      CanonicalJoinOperationEvidence pending = operation(callerBinding);
      CanonicalJoinTerminalProof proof =
          new CanonicalJoinTerminalProof(
              MEMBERSHIP_STREAM,
              1L,
              event.eventId(),
              event.eventDigest(),
              UUID.nameUUIDFromBytes(("audit:" + REQUEST_ID).getBytes(StandardCharsets.UTF_8)),
              DIGEST,
              Instant.parse("2026-10-03T00:00:00Z"),
              5L,
              73L,
              2L,
              1L);
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
          73L,
          2L,
          1L,
          proof,
          pending.scopeEvidence());
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
        null,
        TenantProvenanceKind.FRESH_GAME_DESIGN,
        CURRENT_TENANT_SOURCE.operationId(),
        CURRENT_TENANT_SOURCE.evidenceDigest());
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
    membership.setTenantSourceOperationId(CURRENT_TENANT_SOURCE.operationId());
    membership.setTenantProvenanceDigest(CURRENT_TENANT_SOURCE.evidenceDigest());
    membership.setMembershipVersion(2L);
    membership.setMembershipAuthorityGeneration(1L);
    membership.setLifecycleState("ACTIVE");
    membership.setGameplayAdmissionAllowed(true);
    membership.setAuthorityProvenance("EXPLICIT_JOIN");
    return membership;
  }
}
