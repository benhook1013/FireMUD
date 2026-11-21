package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceFixtureActivationRequest;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionHoldRequest;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionTerminalProof;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionTerminalProof.Outcome;
import net.firedevops.firemud.worldmanagement.client.GameSessionFixtureInitialAdmissionOwnerClient;
import net.firedevops.firemud.worldmanagement.data.RunOwnedCanonicalWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.data.RunOwnedCanonicalWorldLaunchEvidenceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Controlled collaborator proofs only; these mocks establish no physical commit or authentication.
 */
class WorldFixtureInitialAdmissionHoldOwnerTest {
  private static final String PEER = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String LEAF = "a".repeat(64);
  private final WorldFixtureInitialAdmissionHoldRepository repository =
      mock(WorldFixtureInitialAdmissionHoldRepository.class);
  private final RunOwnedCanonicalWorldLaunchEvidenceProvider sources =
      mock(RunOwnedCanonicalWorldLaunchEvidenceProvider.class);
  private final WorldCanonicalInstanceFixtureActivationOwner activation =
      mock(WorldCanonicalInstanceFixtureActivationOwner.class);
  private final GameSessionFixtureInitialAdmissionOwnerClient terminal =
      mock(GameSessionFixtureInitialAdmissionOwnerClient.class);
  private final WorldFixtureInitialAdmissionHoldOwner owner =
      new WorldFixtureInitialAdmissionHoldOwner(repository, sources, activation, terminal);

  @AfterEach
  void clearContext() {
    TransactionSynchronizationManager.clear();
    SessionContext.clear();
  }

  @Test
  void closedOriginDeniesEveryEntryBeforeSourceOrStorageInteraction() {
    var request = request(true, "attempt-1");
    var identity = identity(request);
    for (Runnable action :
        List.<Runnable>of(
            () -> owner.acquire(request),
            () -> owner.readIdentity(request),
            () -> owner.readState(request, identity),
            () -> owner.settleFromAuthenticatedOwnerRead(request, identity))) {
      assertThatThrownBy(action::run)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("NO_PRIOR_POINTER");
    }
    verifyNoInteractions(repository, sources, activation, terminal);
  }

  @Test
  void ambientTransactionOrSynchronizationDeniesBeforeAllCollaborators() {
    var request = request(false, "attempt-1");
    for (boolean synchronization : List.of(false, true)) {
      if (synchronization) TransactionSynchronizationManager.initSynchronization();
      else TransactionSynchronizationManager.setActualTransactionActive(true);
      for (Runnable action :
          List.<Runnable>of(
              () -> owner.acquire(request),
              () -> owner.readIdentity(request),
              () -> owner.readState(request, identity(request)),
              () -> owner.settleFromAuthenticatedOwnerRead(request, identity(request)),
              () -> owner.reconcilePending(3))) {
        assertThatThrownBy(action::run)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ambient");
      }
      TransactionSynchronizationManager.clear();
    }
    verifyNoInteractions(repository, sources, activation, terminal);
  }

  @Test
  void fixedPeerUriNamespaceLeafAndCallerContextRefuseBeforeRepository() {
    var request = request(false, "attempt-1");
    configured(request);
    for (String uri :
        List.of(
            "spiffe://firemud/ns/other/sa/game-session-service",
            "spiffe://firemud/ns/test/sa/world-management-service")) {
      withPeer(
          uri,
          LEAF,
          () ->
              assertThatThrownBy(() -> owner.readIdentity(request))
                  .isInstanceOf(SecurityException.class));
    }
    withPeer(
        PEER,
        "b".repeat(64),
        () ->
            assertThatThrownBy(() -> owner.readIdentity(request))
                .isInstanceOf(SecurityException.class));
    assertThatThrownBy(() -> owner.readIdentity(request)).isInstanceOf(SecurityException.class);
    SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
    withPeer(
        PEER,
        LEAF,
        () ->
            assertThatThrownBy(() -> owner.readIdentity(request))
                .isInstanceOf(SecurityException.class));
    verifyNoInteractions(repository, activation, terminal);
  }

  @Test
  void exactFixedPeerCanReadHistoricalIdentityWithoutRecapturingActivation() {
    var request = request(false, "attempt-1");
    var identity = identity(request);
    configured(request);
    when(repository.readIdentity(request)).thenReturn(Optional.of(identity));
    withPeer(PEER, LEAF, () -> assertThat(owner.readIdentity(request)).contains(identity));
    verify(repository).readIdentity(request);
    verifyNoInteractions(activation, terminal);
  }

  @Test
  void changedConfiguredSealAndIdentityRefuseBeforeHoldLookup() {
    var request = request(false, "attempt-1");
    var receipt = configured(request);
    when(receipt.fixturePolicySeal().evidenceDigest()).thenReturn("sha256:" + "f".repeat(64));
    withPeer(
        PEER,
        LEAF,
        () ->
            assertThatThrownBy(() -> owner.readIdentity(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixed original receipt"));
    assertThatThrownBy(() -> owner.readState(request, identity(request(false, "other-attempt"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed its request");
    verifyNoInteractions(repository, activation, terminal);
  }

  @Test
  void unauthenticatedOrMismatchedAllocationCannotReachAssociationActivationOrHold() {
    var request = request(false, "attempt-1");
    var evidence = mock(RunOwnedCanonicalWorldLaunchEvidence.class, RETURNS_DEEP_STUBS);
    when(sources.requireAuthenticatedEvidence()).thenReturn(evidence);
    doThrow(new SecurityException("controlled fixed peer denial"))
        .when(evidence)
        .requireFixedGameSessionPeer();
    assertThatThrownBy(() -> owner.acquire(request)).isInstanceOf(SecurityException.class);
    verifyNoInteractions(repository, activation, terminal);
  }

  @Test
  void wrongAuthenticatedAllocationNamespaceCannotReachStorage() {
    var request = request(false, "attempt-1");
    var evidence = mock(RunOwnedCanonicalWorldLaunchEvidence.class, RETURNS_DEEP_STUBS);
    when(sources.requireAuthenticatedEvidence()).thenReturn(evidence);
    when(evidence.gameSessionAssociation().request().targetNamespace()).thenReturn("other");
    assertThatThrownBy(() -> owner.acquire(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authenticated owner allocation");
    verify(evidence).requireFixedGameSessionPeer();
    verifyNoInteractions(repository, activation, terminal);
  }

  @Test
  void changedRetainedHoldFenceDeniesBeforeTerminalOwnerRead() {
    var request = request(false, "attempt-1");
    var identity = identity(request);
    configured(request);
    when(repository.readIdentity(request))
        .thenReturn(
            Optional.of(
                new HoldIdentity(request.selector(), identity.holdId(), UUID.randomUUID())));
    assertThatThrownBy(() -> owner.settleFromAuthenticatedOwnerRead(request, identity))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("retained identity");
    verifyNoInteractions(terminal);
    verify(repository, never()).settle(any(), any());
  }

  @Test
  void originalFixtureOperationCannotBeReusedAsReadCorrelationBeforeStorage() {
    var request = request(false, "attempt-1");
    var receipt = configured(request);
    var operation = UUID.randomUUID();
    when(receipt.fixtureOperationId()).thenReturn(operation.toString());
    withPeer(
        PEER,
        LEAF,
        () ->
            assertThatThrownBy(() -> owner.requireReadCorrelation(request, operation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fresh read"));
    verifyNoInteractions(repository, activation, terminal);
  }

  @Test
  void terminalFailureAndBindingSubstitutionNeverReachSettlement() {
    var request = request(false, "attempt-1");
    var identity = identity(request);
    configured(request);
    when(repository.readIdentity(request)).thenReturn(Optional.of(identity));
    when(terminal.readFixture(request, identity, LEAF))
        .thenThrow(new IllegalStateException("owner unavailable"));
    assertThatThrownBy(() -> owner.settleFromAuthenticatedOwnerRead(request, identity))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("owner unavailable");
    var changed =
        new WorldFixtureInitialAdmissionTerminalProof(
            request,
            identity.holdId(),
            UUID.randomUUID(),
            Outcome.PENDING,
            null,
            null,
            false,
            null);
    org.mockito.Mockito.doReturn(changed).when(terminal).readFixture(request, identity, LEAF);
    assertThatThrownBy(() -> owner.settleFromAuthenticatedOwnerRead(request, identity))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("original binding");
    verify(repository, never()).settle(any(), any());
  }

  @Test
  void boundedRecoveryContinuesAfterIndependentFailureAndForwardsPendingWithoutInventingAbort() {
    var first = request(false, "attempt-1");
    var second = request(false, "attempt-2");
    configured(first);
    var firstIdentity = identity(first);
    var secondIdentity = identity(second);
    when(repository.pendingFixtureRequests(2)).thenReturn(List.of(first, second));
    when(repository.readIdentity(first)).thenReturn(Optional.of(firstIdentity));
    when(repository.readIdentity(second)).thenReturn(Optional.of(secondIdentity));
    when(terminal.readFixture(first, firstIdentity, LEAF))
        .thenThrow(new IllegalStateException("unavailable"));
    var pending =
        new WorldFixtureInitialAdmissionTerminalProof(
            second,
            secondIdentity.holdId(),
            secondIdentity.holdFence(),
            Outcome.PENDING,
            null,
            null,
            false,
            null);
    when(terminal.readFixture(second, secondIdentity, LEAF)).thenReturn(pending);
    owner.reconcilePending(2);
    verify(repository).pendingFixtureRequests(2);
    verify(repository, never()).settle(org.mockito.ArgumentMatchers.eq(firstIdentity), any());
    verify(repository).settle(secondIdentity, pending);
    assertThat(pending.positiveDurableAbort()).isFalse();
    assertThat(pending.terminalAt()).isNull();
    // The actual repository's PENDING branch is a no-op; physical exclusion is proved separately.
  }

  private SourceReceipt configured(WorldFixtureInitialAdmissionHoldRequest request) {
    var receipt = mock(SourceReceipt.class, RETURNS_DEEP_STUBS);
    when(sources.requireConfiguredFixtureReceipt()).thenReturn(receipt);
    when(receipt.sourceEvidence().targetNamespace())
        .thenReturn(request.selector().targetNamespace());
    when(receipt.sourceEvidence().canonicalTenantId())
        .thenReturn(request.selector().canonicalTenantId());
    when(receipt.sourceEvidence().worldSlug()).thenReturn(request.selector().worldSlug());
    when(receipt.launchReadEvidence().publishedReleaseBundle().getCanonicalVersionId())
        .thenReturn(request.selector().canonicalVersionId().toString());
    when(receipt.fixturePolicySeal().evidenceDigest())
        .thenReturn(request.activation().fixturePolicySealEvidenceDigest());
    when(receipt.gameSessionUriSan()).thenReturn(PEER);
    when(receipt.gameSessionLeafSha256()).thenReturn(LEAF);
    return receipt;
  }

  static void withPeer(String uri, String digest, Runnable action) {
    var leaf = mock(GrpcPeerCertificateEvidence.class);
    when(leaf.leafSha256()).thenReturn(digest);
    var context =
        Context.current()
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(uri).orElseThrow())
            .withValue(GrpcPeerCertificateEvidence.CONTEXT_KEY, leaf);
    var previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }

  static HoldIdentity identity(WorldFixtureInitialAdmissionHoldRequest request) {
    return new HoldIdentity(
        request.selector(),
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        UUID.fromString("55555555-5555-4555-8555-555555555555"));
  }

  static WorldFixtureInitialAdmissionHoldRequest request(boolean closed, String attempt) {
    UUID tenant = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID instance = UUID.fromString("22222222-2222-4222-8222-222222222222");
    var selector =
        new WorldCanonicalInitialAdmissionHold.Request(
            "test",
            tenant,
            "green-hollow",
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            "SHARED",
            instance,
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
            2L,
            attempt,
            "b".repeat(64),
            closed ? InitialAdmissionOrigin.EXPECT_CLOSED : InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1L,
            closed ? 7L : null);
    return new WorldFixtureInitialAdmissionHoldRequest(
        selector,
        new WorldCanonicalInstanceFixtureActivationRequest(
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            tenant,
            instance,
            "sha256:" + "a".repeat(64),
            1L,
            0L));
  }
}
