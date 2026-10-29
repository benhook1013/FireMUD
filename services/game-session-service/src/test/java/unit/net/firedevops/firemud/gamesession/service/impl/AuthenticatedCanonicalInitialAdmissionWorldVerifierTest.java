package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState.HoldStatus;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.client.WorldCanonicalInitialAdmissionHoldClient;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot.IntentState;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot.SourceBinding;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AuthenticatedCanonicalInitialAdmissionWorldVerifierTest {
  private static final String NAMESPACE = "hold-unit";
  private static final UUID TENANT = uuid(1);
  private static final UUID REALM = uuid(2);
  private static final UUID PLAYABLE_NAMESPACE = uuid(3);
  private static final UUID INSTANCE = uuid(4);
  private static final UUID VERSION = uuid(5);
  private static final UUID HOLD_ID = uuid(6);
  private static final UUID HOLD_FENCE = uuid(7);
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void returnsProofFromAuthenticatedWorldIdentityAndFreshActiveStateRead() {
    Fixtures fixtures = new Fixtures();
    CanonicalInitialAdmissionIntentSnapshot retained = attachedSnapshot();
    CanonicalInitialAdmissionRequest expected = expectedRequest();
    when(fixtures.repository.read(NAMESPACE, expected.initialAdmissionRequestId()))
        .thenReturn(Optional.of(retained));
    when(fixtures.worldClient.readIdentity(retained.holdRequest()))
        .thenReturn(retained.holdIdentity());
    when(fixtures.worldClient.readState(any(), any()))
        .thenAnswer(
            invocation ->
                worldState(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    HoldStatus.PENDING,
                    "ACTIVE",
                    2L));

    CanonicalInitialAdmissionWorldProof proof = fixtures.verifier.verify(expected);

    proof.requireMatches(expected);
    assertThat(proof.lifecycleState()).isEqualTo("ACTIVE");
    assertThat(proof.activeLifecycleEpoch()).isEqualTo(2L);
    verify(fixtures.worldClient).readIdentity(retained.holdRequest());
    var lifecycleCaptor =
        org.mockito.ArgumentCaptor.forClass(WorldCanonicalInstanceLifecycleEvidence.Request.class);
    verify(fixtures.worldClient)
        .readState(
            org.mockito.ArgumentMatchers.eq(retained.holdIdentity()), lifecycleCaptor.capture());
    assertThat(lifecycleCaptor.getValue().readRequestId())
        .isNotEqualTo(retained.lifecycleRequest().readRequestId());
    assertThat(lifecycleCaptor.getValue().canonicalTenantId()).isEqualTo(TENANT);
    assertThat(lifecycleCaptor.getValue().canonicalVersionId()).isEqualTo(VERSION);
  }

  @Test
  void rejectsWrongConfiguredNamespaceBeforeOwnerLookup() {
    Fixtures fixtures = new Fixtures();

    assertThatThrownBy(() -> fixtures.verifier.verify(expectedRequest("other")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured workload namespace");

    verifyNoInteractions(fixtures.repository, fixtures.worldClient);
  }

  @Test
  void rejectsMissingOrUnattachedIntentBeforeWorldAccess() {
    Fixtures missing = new Fixtures();
    when(missing.repository.read(NAMESPACE, "request-1")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> missing.verifier.verify(expectedRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Retained initial-admission intent is unavailable");
    verifyNoInteractions(missing.worldClient);

    Fixtures unattached = new Fixtures();
    when(unattached.repository.read(NAMESPACE, "request-1"))
        .thenReturn(Optional.of(snapshot(IntentState.PENDING_HOLD, null, null, sourceBinding())));
    assertThatThrownBy(() -> unattached.verifier.verify(expectedRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no attached immutable World hold");
    verifyNoInteractions(unattached.worldClient);

    Fixtures terminal = new Fixtures();
    when(terminal.repository.read(NAMESPACE, "request-1"))
        .thenReturn(
            Optional.of(
                snapshot(IntentState.TERMINAL, holdIdentity(), Instant.now(), sourceBinding())));
    assertThatThrownBy(() -> terminal.verifier.verify(expectedRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no attached immutable World hold");
    verifyNoInteractions(terminal.worldClient);
  }

  @Test
  void rejectsMismatchedRetainedSourceBindingBeforeWorldAccess() {
    Fixtures fixtures = new Fixtures();
    CanonicalInitialAdmissionIntentSnapshot malformed =
        mock(CanonicalInitialAdmissionIntentSnapshot.class);
    when(malformed.holdRequest()).thenReturn(holdRequest());
    when(malformed.lifecycleRequest()).thenReturn(lifecycleRequest());
    when(malformed.state()).thenReturn(IntentState.HOLD_ATTACHED);
    when(malformed.holdIdentity()).thenReturn(holdIdentity());
    when(malformed.sourceBinding()).thenReturn(sourceBindingWithCatalogRevision(13L));
    when(fixtures.repository.read(NAMESPACE, "request-1")).thenReturn(Optional.of(malformed));

    assertThatThrownBy(() -> fixtures.verifier.verify(expectedRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not bound to its canonical owner sources");

    verifyNoInteractions(fixtures.worldClient);
  }

  @Test
  void rejectsAnyChangedExpectedRequestFieldBeforeWorldAccess() {
    Fixtures fixtures = new Fixtures();
    CanonicalInitialAdmissionIntentSnapshot retained = attachedSnapshot();
    when(fixtures.repository.read(NAMESPACE, "request-1")).thenReturn(Optional.of(retained));

    for (CanonicalInitialAdmissionRequest mismatch :
        Stream.of(
                expectedRequestWith(2L, 13L, OriginKind.NO_PRIOR_POINTER, null, HOLD_FENCE),
                expectedRequestWith(3L, 12L, OriginKind.NO_PRIOR_POINTER, null, HOLD_FENCE),
                expectedRequestWith(2L, 12L, OriginKind.EXPECT_CLOSED, 1L, HOLD_FENCE),
                expectedRequestWith(2L, 12L, OriginKind.NO_PRIOR_POINTER, null, uuid(8)))
            .toList()) {
      assertThatThrownBy(() -> fixtures.verifier.verify(mismatch))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("complete expected request");
    }

    verifyNoInteractions(fixtures.worldClient);
  }

  @ParameterizedTest
  @EnumSource(
      value = HoldStatus.class,
      names = {"RECONCILIATION_REQUIRED", "COMMITTED", "ABORTED"})
  void rejectsTerminalOrUnresolvedWorldHoldState(HoldStatus status) {
    Fixtures fixtures = configuredFixtures(status, "ACTIVE", 2L);

    assertThatThrownBy(() -> fixtures.verifier.verify(expectedRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not pending at its exact ACTIVE lifecycle epoch");
  }

  @ParameterizedTest
  @MethodSource("wrongLifecycleStates")
  void rejectsNonActiveOrChangedLifecycleEpoch(String status, long epoch) {
    Fixtures fixtures = configuredFixtures(HoldStatus.PENDING, status, epoch);

    assertThatThrownBy(() -> fixtures.verifier.verify(expectedRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not pending at its exact ACTIVE lifecycle epoch");
  }

  private static Stream<org.junit.jupiter.params.provider.Arguments> wrongLifecycleStates() {
    return Stream.of(
        org.junit.jupiter.params.provider.Arguments.of("TERMINATING", 2L),
        org.junit.jupiter.params.provider.Arguments.of("TERMINATED", 2L),
        org.junit.jupiter.params.provider.Arguments.of("ACTIVE", 3L));
  }

  @Test
  void rejectsAmbientTransactionOrSynchronizationBeforeIntentLookup() {
    Fixtures transaction = new Fixtures();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> transaction.verifier.verify(expectedRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient owner transaction");
    verifyNoInteractions(transaction.repository, transaction.worldClient);

    TransactionSynchronizationManager.clear();
    Fixtures synchronization = new Fixtures();
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> synchronization.verifier.verify(expectedRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient owner transaction");
    verifyNoInteractions(synchronization.repository, synchronization.worldClient);
  }

  @Test
  void rechecksAmbientTransactionAfterIdentityRpcBeforeStateRpc() {
    Fixtures fixtures = new Fixtures();
    CanonicalInitialAdmissionIntentSnapshot retained = attachedSnapshot();
    when(fixtures.repository.read(NAMESPACE, "request-1")).thenReturn(Optional.of(retained));
    when(fixtures.worldClient.readIdentity(retained.holdRequest()))
        .thenAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              return retained.holdIdentity();
            });

    assertThatThrownBy(() -> fixtures.verifier.verify(expectedRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ambient owner transaction");

    verify(fixtures.worldClient, never()).readState(any(), any());
  }

  @Test
  void rejectsAuthenticatedWorldIdentityThatDiffersFromRetainedIdentity() {
    Fixtures fixtures = new Fixtures();
    CanonicalInitialAdmissionIntentSnapshot retained = attachedSnapshot();
    when(fixtures.repository.read(NAMESPACE, "request-1")).thenReturn(Optional.of(retained));
    when(fixtures.worldClient.readIdentity(retained.holdRequest()))
        .thenReturn(new HoldIdentity(retained.holdRequest(), HOLD_ID, uuid(8)));

    assertThatThrownBy(() -> fixtures.verifier.verify(expectedRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differs from the retained Game Session identity");

    verify(fixtures.worldClient, never()).readState(any(), any());
  }

  private static Fixtures configuredFixtures(
      HoldStatus status, String lifecycleStatus, long lifecycleEpoch) {
    Fixtures fixtures = new Fixtures();
    CanonicalInitialAdmissionIntentSnapshot retained = attachedSnapshot();
    when(fixtures.repository.read(NAMESPACE, "request-1")).thenReturn(Optional.of(retained));
    when(fixtures.worldClient.readIdentity(retained.holdRequest()))
        .thenReturn(retained.holdIdentity());
    when(fixtures.worldClient.readState(any(), any()))
        .thenAnswer(
            invocation ->
                worldState(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    status,
                    lifecycleStatus,
                    lifecycleEpoch));
    return fixtures;
  }

  private static WorldCanonicalInitialAdmissionHoldState worldState(
      HoldIdentity identity,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest,
      HoldStatus status,
      String lifecycleStatus,
      long lifecycleEpoch) {
    WorldCanonicalInitialAdmissionHoldState state =
        mock(WorldCanonicalInitialAdmissionHoldState.class);
    WorldCanonicalInstanceLifecycleEvidence lifecycle =
        mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(state.holdIdentity()).thenReturn(identity);
    when(state.holdStatus()).thenReturn(status);
    when(state.lifecycleEvidence()).thenReturn(lifecycle);
    when(lifecycle.request()).thenReturn(lifecycleRequest);
    when(lifecycle.lifecycleStatus()).thenReturn(lifecycleStatus);
    when(lifecycle.lifecycleEpoch()).thenReturn(lifecycleEpoch);
    return state;
  }

  private static CanonicalInitialAdmissionIntentSnapshot attachedSnapshot() {
    return snapshot(IntentState.HOLD_ATTACHED, holdIdentity(), null, sourceBinding());
  }

  private static CanonicalInitialAdmissionIntentSnapshot snapshot(
      IntentState state, HoldIdentity identity, Instant terminalAt, SourceBinding source) {
    return new CanonicalInitialAdmissionIntentSnapshot(
        holdRequest(),
        lifecycleRequest(),
        state,
        identity,
        source,
        Instant.EPOCH,
        Instant.EPOCH,
        terminalAt);
  }

  private static SourceBinding sourceBinding() {
    return sourceBindingWithCatalogRevision(12L);
  }

  private static SourceBinding sourceBindingWithCatalogRevision(long catalogRevision) {
    return new SourceBinding(
        NAMESPACE,
        TENANT,
        "world",
        REALM,
        PLAYABLE_NAMESPACE,
        "SHARED",
        true,
        true,
        catalogRevision,
        uuid(9),
        DIGEST,
        DIGEST,
        uuid(10),
        1L,
        INSTANCE,
        1L,
        VERSION,
        1L,
        "control-request-1",
        "descriptor-1",
        0L,
        0L,
        DIGEST,
        DIGEST,
        DIGEST);
  }

  private static CanonicalInitialAdmissionRequest expectedRequest() {
    return expectedRequest("hold-unit");
  }

  private static CanonicalInitialAdmissionRequest expectedRequest(String namespace) {
    return expectedRequestWith(2L, 12L, OriginKind.NO_PRIOR_POINTER, null, HOLD_FENCE, namespace);
  }

  private static CanonicalInitialAdmissionRequest expectedRequestWith(
      long epoch, long catalogRevision, OriginKind origin, Long priorVersion, UUID fence) {
    return expectedRequestWith(epoch, catalogRevision, origin, priorVersion, fence, NAMESPACE);
  }

  private static CanonicalInitialAdmissionRequest expectedRequestWith(
      long epoch,
      long catalogRevision,
      OriginKind origin,
      Long priorVersion,
      UUID fence,
      String namespace) {
    String requestId = "request-1";
    String digest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            namespace,
            TENANT,
            "world",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            VERSION,
            epoch,
            catalogRevision,
            origin,
            priorVersion,
            requestId);
    return new CanonicalInitialAdmissionRequest(
        namespace,
        TENANT,
        "world",
        REALM,
        PLAYABLE_NAMESPACE,
        "SHARED",
        INSTANCE,
        VERSION,
        epoch,
        catalogRevision,
        origin,
        priorVersion,
        requestId,
        digest,
        HOLD_ID,
        fence,
        holdIdentity().holdBindingDigest());
  }

  private static Request holdRequest() {
    return new Request(
        NAMESPACE,
        TENANT,
        "world",
        REALM,
        PLAYABLE_NAMESPACE,
        "SHARED",
        INSTANCE,
        VERSION,
        2L,
        "request-1",
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            NAMESPACE,
            TENANT,
            "world",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            VERSION,
            2L,
            12L,
            OriginKind.NO_PRIOR_POINTER,
            null,
            "request-1"),
        InitialAdmissionOrigin.NO_PRIOR_POINTER,
        12L,
        null);
  }

  private static HoldIdentity holdIdentity() {
    return new HoldIdentity(holdRequest(), HOLD_ID, HOLD_FENCE);
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest() {
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        1,
        uuid(11),
        NAMESPACE,
        TENANT,
        "world",
        INSTANCE,
        PLAYABLE_NAMESPACE,
        "SHARED",
        true,
        "control-request-1",
        VERSION,
        DIGEST,
        DIGEST,
        DIGEST);
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private static final class Fixtures {
    private final CanonicalInitialAdmissionIntentRepository repository =
        mock(CanonicalInitialAdmissionIntentRepository.class);
    private final WorldCanonicalInitialAdmissionHoldClient worldClient =
        mock(WorldCanonicalInitialAdmissionHoldClient.class);
    private final AuthenticatedCanonicalInitialAdmissionWorldVerifier verifier =
        new AuthenticatedCanonicalInitialAdmissionWorldVerifier(worldClient, repository, NAMESPACE);
  }
}
