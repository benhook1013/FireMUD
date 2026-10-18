package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationClient;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled mocked service-boundary proof; no physical owner, transport or authority claim. */
class AccountStartSessionActivationProtectionAcquisitionServiceTest {
  private static final String NAMESPACE = "test";

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void requiresExactWorldWorkloadAndNoEndUserBeforeInspectingInputOrOwners() {
    var h = new Harness();
    assertCode(Status.Code.UNAUTHENTICATED, () -> h.service.acquire(null));
    withPeer(
        "game-session-service",
        () -> assertCode(Status.Code.PERMISSION_DENIED, () -> h.service.acquire(null)));
    withPeerInNamespace(
        "other-runtime",
        () -> assertCode(Status.Code.PERMISSION_DENIED, () -> h.service.acquire(null)));
    SessionContext.setContext("123", List.of(), Map.of());
    withWorldPeer(() -> assertCode(Status.Code.PERMISSION_DENIED, () -> h.service.acquire(null)));

    verifyNoInteractions(
        h.issuer,
        h.participation,
        h.attempts,
        h.protections,
        h.launchReader,
        h.currentAttemptReader);
  }

  @Test
  void rejectsAmbientAccountTransactionBeforeParsingOrRemoteOwnerRead() {
    var h = new Harness();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    withWorldPeer(() -> assertCode(Status.Code.FAILED_PRECONDITION, () -> h.service.acquire(null)));

    verifyNoInteractions(
        h.issuer,
        h.participation,
        h.attempts,
        h.protections,
        h.launchReader,
        h.currentAttemptReader);
  }

  @Test
  void rejectsInvalidAccountNamespaceAtConstruction() {
    var h = new Harness();
    assertThatThrownBy(
            () ->
                new AccountStartSessionActivationProtectionAcquisitionService(
                    h.issuer,
                    h.participation,
                    h.attempts,
                    h.protections,
                    h.launchReader,
                    h.currentAttemptReader,
                    h.transactions,
                    "World_Runtime"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Canonical Account workload namespace");
  }

  @Test
  void committedParticipationUsesHistoricalWitnessBeforeProtectionStorage() throws Exception {
    var h = new Harness();
    var input = h.committedInput();
    var admitted = new IllegalStateException("controlled stop at protection storage");
    doThrow(admitted).when(h.protections).acquireOrReadExact(any(), any(), any());

    withWorldPeer(() -> assertThatThrownBy(() -> h.service.acquire(input)).isSameAs(admitted));

    verify(h.attempts).findHistoricalExact(eq(h.retained), any());
    verify(h.attempts, never()).findCurrentExact(any(), any());
    verify(h.attempts, never()).retainCurrentExact(any(), any());
    verify(h.currentAttemptReader).read(any());
    var selector =
        org.mockito.ArgumentCaptor.forClass(
            CanonicalGameInstanceLaunchAssociationReadEvidence.Request.class);
    verify(h.launchReader).read(selector.capture());
    assertThat(selector.getValue().originalOperation().originalPostAuthorizationTuple())
        .containsExactly(input.originalPostAuthorizationTuple());
    assertThat(selector.getValue().originalOperation().ownerMutationId())
        .isEqualTo(input.gameSessionOwnerMutationId());
    assertThat(selector.getValue().originalOperation().ownerAttemptId())
        .isEqualTo(input.gameSessionOwnerAttemptId());
    assertThat(selector.getValue().originalOperation().ownerFence())
        .isEqualTo(input.gameSessionOwnerFence());
    verify(h.issuer)
        .withCurrentWorldReceivingParticipationCurrentness(
            any(), eq(input.gameSessionOwnerAttemptId()), eq(input.gameSessionOwnerFence()), any());
    verify(h.protections).acquireOrReadExact(any(), any(), eq(h.retained));
  }

  @Test
  void absentHistoricalWitnessCannotAcquireProtectionForCommittedParticipation() throws Exception {
    var h = new Harness();
    var input = h.committedInput();
    doReturn(Optional.empty()).when(h.attempts).findHistoricalExact(any(), any());

    withWorldPeer(
        () -> assertCode(Status.Code.FAILED_PRECONDITION, () -> h.service.acquire(input)));

    verify(h.attempts).findHistoricalExact(eq(h.retained), any());
    verify(h.attempts, never()).findCurrentExact(any(), any());
    verifyNoInteractions(h.protections);
  }

  @Test
  void changedRetainedLeaseCannotReplaceFreshCurrentObservation() throws Exception {
    var h = new Harness();
    var input = h.committedInput();
    doAnswer(
            invocation -> {
              var request =
                  (OriginalStartSessionCurrentAttemptEvidence.Request) invocation.getArgument(1);
              return Optional.of(h.witness(request, h.lease.plusSeconds(1)));
            })
        .when(h.attempts)
        .findHistoricalExact(any(), any());

    withWorldPeer(
        () -> assertCode(Status.Code.FAILED_PRECONDITION, () -> h.service.acquire(input)));

    verify(h.attempts).findHistoricalExact(eq(h.retained), any());
    verifyNoInteractions(h.protections);
  }

  @Test
  void historicalWitnessCannotSubstituteForDeniedFreshCurrentAttempt() throws Exception {
    var h = new Harness();
    var input = h.committedInput();
    doThrow(Status.FAILED_PRECONDITION.asRuntimeException())
        .when(h.currentAttemptReader)
        .read(any());

    withWorldPeer(
        () -> assertCode(Status.Code.FAILED_PRECONDITION, () -> h.service.acquire(input)));

    verifyNoInteractions(h.attempts, h.protections, h.issuer, h.launchReader);
  }

  @Test
  void foreignCurrentAttemptStatusesAreClassifiedAtTheAccountDependencyBoundary() throws Exception {
    var preserved = infrastructureCodes();
    for (Status.Code sourceCode : Status.Code.values()) {
      var h = new Harness();
      var input = h.committedInput();
      doThrow(foreignFailure(sourceCode)).when(h.currentAttemptReader).read(any());

      var failure = statusFailure(() -> withWorldPeer(() -> h.service.acquire(input)));

      assertDependencyFailure(
          sourceCode,
          preserved,
          "Account original Game Session current-attempt dependency failed",
          failure);
      verify(h.currentAttemptReader).read(any());
      verifyNoInteractions(h.attempts, h.protections, h.issuer, h.launchReader);
    }
  }

  @Test
  void foreignLaunchAssociationStatusesAreClassifiedBeforeProtectionStorage() throws Exception {
    var preserved = infrastructureCodes();
    for (Status.Code sourceCode : Status.Code.values()) {
      var h = new Harness();
      var input = h.committedInput();
      doThrow(foreignFailure(sourceCode)).when(h.launchReader).read(any());

      var failure = statusFailure(() -> withWorldPeer(() -> h.service.acquire(input)));

      assertDependencyFailure(
          sourceCode,
          preserved,
          "Account Game Session launch-association dependency failed",
          failure);
      verify(h.currentAttemptReader).read(any());
      verify(h.launchReader).read(any());
      verifyNoInteractions(h.attempts, h.protections, h.issuer);
    }
  }

  private static Set<Status.Code> infrastructureCodes() {
    return Set.of(
        Status.Code.UNAVAILABLE,
        Status.Code.DEADLINE_EXCEEDED,
        Status.Code.CANCELLED,
        Status.Code.RESOURCE_EXHAUSTED,
        Status.Code.INTERNAL,
        Status.Code.UNKNOWN);
  }

  private static StatusRuntimeException foreignFailure(Status.Code code) {
    var trailers = new Metadata();
    trailers.put(
        Metadata.Key.of("private-owner-detail", Metadata.ASCII_STRING_MARSHALLER),
        "private downstream trailer");
    return Status.fromCode(code)
        .withDescription("private downstream description")
        .withCause(new IllegalStateException("private downstream cause"))
        .asRuntimeException(trailers);
  }

  private static StatusRuntimeException statusFailure(Runnable work) {
    var thrown = org.assertj.core.api.Assertions.catchThrowable(work::run);
    assertThat(thrown).isInstanceOf(StatusRuntimeException.class);
    return (StatusRuntimeException) thrown;
  }

  private static void assertDependencyFailure(
      Status.Code sourceCode,
      Set<Status.Code> preserved,
      String expectedDescription,
      StatusRuntimeException failure) {
    Status.Code expectedCode =
        preserved.contains(sourceCode) ? sourceCode : Status.Code.FAILED_PRECONDITION;
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expectedCode);
    assertThat(Status.fromThrowable(failure).getDescription()).isEqualTo(expectedDescription);
    assertThat(failure.getCause()).isNull();
    assertThat(failure.getTrailers()).isNull();
  }

  private static void assertCode(Status.Code expected, Runnable work) {
    assertThatThrownBy(work::run)
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private static void withWorldPeer(Runnable work) {
    withPeer("world-management-service", work);
  }

  private static void withPeer(String service, Runnable work) {
    withIdentity(NAMESPACE, "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + service, service, work);
  }

  private static void withPeerInNamespace(String namespace, Runnable work) {
    withIdentity(
        namespace,
        "spiffe://firemud/ns/" + namespace + "/sa/world-management-service",
        "world-management-service",
        work);
  }

  private static void withIdentity(String namespace, String uri, String service, Runnable work) {
    var peer = new GrpcPeerIdentity(uri, namespace, service);
    var next = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    var prior = next.attach();
    try {
      work.run();
    } finally {
      next.detach(prior);
    }
  }

  private static final class Harness {
    final Instant lease = Instant.parse("2030-01-01T00:04:00Z");
    AccountStartSessionWorldParticipationRepository.StoredParticipation retained;
    StartSessionPostAuthorizationExecutionTuple tuple;

    AccountStartSessionActivationProtectionAcquisitionInput committedInput() throws Exception {
      var preparing = AuthoringFixtures.lifecycleEvidence("PREPARING", 1L);
      var world = preparing.request();
      var descriptor = preparing.launchBinding().descriptor();
      UUID attempt = UUID.randomUUID();
      UUID mutation = UUID.randomUUID();
      long fence = 7L;
      var pre =
          StartSessionPreAuthorizationReservationTuple.createHuman(
              world.controlPlaneRequestId(),
              UUID.randomUUID(),
              new StartSessionOperatorAction(
                  StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                  StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                  new StartSessionOperatorAction.Scope(world.canonicalTenantId(), NAMESPACE),
                  new StartSessionOperatorAction.Target(
                      descriptor.gameTemplateId(), UUID.randomUUID()),
                  StartSessionOperatorAction.ExpectedVersion.ABSENT,
                  new StartSessionOperatorAction.Mutation(
                      StartSessionOperatorAction.ClientIp.absent()),
                  "TEST_ONLY mocked activation acquisition"));
      var bundle =
          StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
              pre,
              new StartSessionAuthorityEvidenceBundleFixture.Values(
                  UUID.randomUUID(),
                  "sha256:" + "a".repeat(64),
                  "1",
                  "CURRENT",
                  Instant.parse("2030-01-01T00:00:00Z"),
                  Instant.parse("2030-01-01T00:05:00Z"),
                  1,
                  1,
                  1,
                  1,
                  1,
                  1,
                  UUID.randomUUID(),
                  "1",
                  "1"));
      tuple =
          StartSessionPostAuthorizationExecutionTuple.createHuman(
              pre,
              "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
              "arfp/v1/test-key/" + "b".repeat(64),
              UUID.randomUUID(),
              1L,
              bundle,
              new StartSessionAuthorityEvidenceBundle.BundleReference(
                  StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "1", "1", "1"));
      var input =
          new AccountStartSessionActivationProtectionAcquisitionInput(
              tuple.canonicalBytes(),
              mutation,
              attempt,
              fence,
              new WorldCanonicalInstanceActivation.Request(UUID.randomUUID(), preparing));
      retained =
          new AccountStartSessionWorldParticipationRepository.StoredParticipation(
              UUID.randomUUID(),
              1L,
              tuple.controlPlaneRequestId(),
              tuple.canonicalBytes(),
              NAMESPACE,
              world.canonicalTenantId(),
              world.canonicalGameInstanceId(),
              attempt,
              fence,
              "controlled preparation input",
              preparing.preparationInputDigest(),
              1L,
              OffsetDateTime.parse("2030-01-01T00:00:00Z"),
              List.of());
      when(participation.findHistoricalForOriginalTuple(any())).thenReturn(Optional.of(retained));
      when(participation.findSettlementExact(
              retained.participationId(), retained.participationFence()))
          .thenReturn(
              Optional.of(
                  new AccountStartSessionWorldParticipationRepository.StoredSettlement(
                      retained.participationId(),
                      WorldStartSessionExecutionTerminal.Outcome.COMMITTED,
                      1L,
                      new byte[] {1},
                      "sha256:" + "c".repeat(64),
                      OffsetDateTime.parse("2030-01-01T00:00:01Z"))));
      when(currentAttemptReader.read(any()))
          .thenAnswer(
              invocation ->
                  new OriginalStartSessionCurrentAttemptEvidence.Result(
                      invocation.getArgument(0), lease, tuple.canonicalBytes()));
      // Explicit controlled GS realm/catalog inputs; these mocks establish no owner provenance.
      when(launchReader.read(any()))
          .thenAnswer(
              invocation ->
                  new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
                      invocation.getArgument(0),
                      world.playableStateNamespaceId(),
                      RealmEntryPolicy.StateScope.SHARED,
                      true,
                      CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus
                          .STARTING,
                      0L,
                      attempt,
                      fence,
                      UUID.fromString("e8e8e8e8-e8e8-48e8-88e8-e8e8e8e8e8e8"),
                      17L,
                      preparing.launchBinding()));
      when(attempts.findHistoricalExact(any(), any()))
          .thenAnswer(invocation -> Optional.of(witness(invocation.getArgument(1), lease)));
      doAnswer(
              invocation -> {
                Function<AccountStartSessionAuthorityCapture, Object> callback =
                    invocation.getArgument(3);
                return callback.apply(null);
              })
          .when(issuer)
          .withCurrentWorldReceivingParticipationCurrentness(any(), any(), anyLong(), any());
      return input;
    }

    AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence witness(
        OriginalStartSessionCurrentAttemptEvidence.Request request, Instant expiry) {
      var result =
          new OriginalStartSessionCurrentAttemptEvidence.Result(
              request, expiry, tuple.canonicalBytes());
      byte[] bytes =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(result).toByteArray();
      return new AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence(
          retained.participationId(),
          request.expectedOwnerMutationId(),
          expiry,
          bytes,
          net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence
              .sha256(bytes),
          result);
    }

    final AccountStartSessionOperatorAuthorizationService issuer =
        mock(AccountStartSessionOperatorAuthorizationService.class);
    final AccountStartSessionWorldParticipationRepository participation =
        mock(AccountStartSessionWorldParticipationRepository.class);
    final AccountStartSessionWorldOriginalAttemptEvidenceRepository attempts =
        mock(AccountStartSessionWorldOriginalAttemptEvidenceRepository.class);
    final AccountStartSessionActivationProtectionRepository protections =
        mock(AccountStartSessionActivationProtectionRepository.class);
    final CanonicalGameInstanceLaunchAssociationClient launchReader =
        mock(CanonicalGameInstanceLaunchAssociationClient.class);
    final OriginalStartSessionCurrentAttemptClient currentAttemptReader =
        mock(OriginalStartSessionCurrentAttemptClient.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final AccountStartSessionActivationProtectionAcquisitionService service =
        new AccountStartSessionActivationProtectionAcquisitionService(
            issuer,
            participation,
            attempts,
            protections,
            launchReader,
            currentAttemptReader,
            transactions,
            NAMESPACE);
  }
}
