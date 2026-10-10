package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AuthorityEvidenceBundleReference;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionResponse;
import net.firedevops.firemud.common.account.StartSessionRedeemedOperationProjectionClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorReadClient;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Association;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.InitialConfigured;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionLaunchDescriptorRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionLaunchDescriptorRepository.PinnedLaunchDescriptorSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.EvidenceContinuation;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.ReservationResult;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository.PinnedAssociationSnapshot;
import net.firedevops.firemud.gamesession.service.GameSessionStartSessionLaunchDescriptorContinuationService;
import net.firedevops.firemud.gamesession.service.GameSessionStartSessionLaunchDescriptorContinuationService.EvidenceContinuationUnavailableException;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof that a descriptor outcome is immutable and tied to the original association. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionStartSessionLaunchDescriptorRepositoryIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void pinsCanonicalDescriptorAndExactRepeatReturnsOriginalWireEvidence() {
    Fixture fixture = fixture();
    var retained = retainAssociation(fixture, "descriptor-pin-success");
    var candidate = descriptor(retained, "original-descriptor");

    PinnedLaunchDescriptorSnapshot first = fixture.pin(retained.claim(), candidate);
    PinnedLaunchDescriptorSnapshot replay = fixture.pin(retained.claim(), candidate);
    Result association = candidate.associationRead();
    var laterPhaseResult =
        new Result(
            association.request(),
            association.association(),
            association.releaseBundle(),
            association.worldPublishedStartLocationEvidence(),
            association.phaseEpoch() + 1);
    var laterPhaseReplay =
        new StartSessionLaunchDescriptorGrpcCodec.Resolved(laterPhaseResult, candidate.outcome());
    PinnedLaunchDescriptorSnapshot phaseReplay = fixture.pin(retained.claim(), laterPhaseReplay);
    PinnedLaunchDescriptorSnapshot read = fixture.find(retained.claim()).orElseThrow();

    assertThat(first.requestWire())
        .isEqualTo(replay.requestWire())
        .isEqualTo(phaseReplay.requestWire())
        .isEqualTo(read.requestWire());
    assertThat(first.responseWire())
        .isEqualTo(replay.responseWire())
        .isEqualTo(phaseReplay.responseWire())
        .isEqualTo(read.responseWire());
    assertThat(read.resolved()).isEqualTo(candidate);
    assertThat(fixture.pinCount()).isEqualTo(1);

    assertThatThrownBy(
            () -> fixture.pin(retained.claim(), descriptor(retained, "substituted-descriptor")))
        .isInstanceOf(
            GameSessionStartSessionLaunchDescriptorRepository
                .StartSessionLaunchDescriptorConflictException.class)
        .hasMessageContaining("original immutable StartSession pin");
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE game_session_start_session_launch_descriptor_pin "
                        + "SET descriptor_response_digest = ? WHERE target_namespace = ? "
                        + "AND control_plane_request_id = ?",
                    "sha256:" + "c".repeat(64),
                    retained.claim().targetNamespace(),
                    retained.claim().controlPlaneRequestId()))
        .isInstanceOf(RuntimeException.class);
    assertThat(fixture.pinCount()).isEqualTo(1);
  }

  @Test
  void databaseRejectsDescriptorPinsWithSubstitutedAssociationOwnerTuple() {
    Fixture fixture = fixture();
    RetainedAssociation retained = retainAssociation(fixture, "descriptor-pin-owner-tuple");
    var association = retained.association().result();
    var ownerRequest = association.request();
    UUID canonicalTenantId = association.association().canonicalTenantId();

    assertDescriptorPinRejectedForOwnerTuple(
        fixture,
        retained,
        UUID.randomUUID(),
        ownerRequest.ownerAttemptId(),
        ownerRequest.ownerFence());
    assertDescriptorPinRejectedForOwnerTuple(
        fixture, retained, canonicalTenantId, UUID.randomUUID(), ownerRequest.ownerFence());
    assertDescriptorPinRejectedForOwnerTuple(
        fixture,
        retained,
        canonicalTenantId,
        ownerRequest.ownerAttemptId(),
        ownerRequest.ownerFence() + 1);
    assertThat(fixture.pinCount()).isZero();

    PinnedLaunchDescriptorSnapshot valid =
        fixture.pin(retained.claim(), descriptor(retained, "valid-owner-tuple-descriptor"));
    PinnedLaunchDescriptorSnapshot read = fixture.find(retained.claim()).orElseThrow();
    assertThat(read.requestWire()).containsExactly(valid.requestWire());
    assertThat(read.responseWire()).containsExactly(valid.responseWire());
    assertThat(read.requestDigest()).isEqualTo(valid.requestDigest());
    assertThat(read.responseDigest()).isEqualTo(valid.responseDigest());
    assertThat(fixture.pinCount()).isOne();
  }

  @Test
  void pinsAClosedApplicationFailureAsTheFinalDescriptorOutcome() {
    Fixture fixture = fixture();
    var retained = retainAssociation(fixture, "descriptor-pin-application-failure");
    Request exactRequest = exactReplayRequest(retained.association().result());
    Result exactAssociation = exactReplayAssociation(retained.association().result(), exactRequest);
    var outcome =
        new StartSessionLaunchDescriptorGrpcCodec.ApplicationFailure(
            "RELEASE_BUNDLE_NOT_FOUND", "RELEASE_BUNDLE_NOT_FOUND: release is unavailable");
    var candidate = new StartSessionLaunchDescriptorGrpcCodec.Resolved(exactAssociation, outcome);

    PinnedLaunchDescriptorSnapshot pinned = fixture.pin(retained.claim(), candidate);
    PinnedLaunchDescriptorSnapshot read = fixture.find(retained.claim()).orElseThrow();

    assertThat(pinned.resolved().outcome()).isEqualTo(outcome);
    assertThat(read.responseWire()).isEqualTo(pinned.responseWire());
    assertThat(fixture.pinCount()).isEqualTo(1);
  }

  @Test
  void continuationCapabilityCanBeRecreatedFromPersistedAttemptWithoutReselecting() {
    Fixture fixture = fixture();
    RetainedAssociation retained = retainAssociation(fixture, "descriptor-continuation-restart");
    var candidate = descriptor(retained, "restart-descriptor");
    EvidenceContinuation originalCapability = fixture.continueEvidence(retained.tuple());

    PinnedLaunchDescriptorSnapshot first = fixture.pin(originalCapability, candidate);
    EvidenceContinuation recreatedCapability = fixture.continueEvidence(retained.tuple());
    PinnedLaunchDescriptorSnapshot replay = fixture.pin(recreatedCapability, candidate);

    assertThat(recreatedCapability).isNotSameAs(originalCapability);
    assertThat(replay.requestWire()).isEqualTo(first.requestWire());
    assertThat(replay.responseWire()).isEqualTo(first.responseWire());
    assertThat(replay.resolved()).isEqualTo(first.resolved());
    assertThat(fixture.pinCount()).isEqualTo(1);
  }

  @Test
  void continuationCannotPinAReselectedAssociationDigest() {
    Fixture fixture = fixture();
    RetainedAssociation retained =
        retainAssociation(fixture, "descriptor-continuation-reselection");
    EvidenceContinuation continuation = fixture.continueEvidence(retained.tuple());
    var original = descriptor(retained, "reselection-descriptor");
    var originalAssociation = original.associationRead();
    Association selected = originalAssociation.association();
    Association substituted =
        new Association(
            selected.canonicalTenantId(),
            selected.templateId(),
            selected.canonicalVersionId(),
            selected.selectedCommitId(),
            selected.publishWorkflowId(),
            selected.publicationSelectionDigest(),
            "sha256:" + "c".repeat(64),
            selected.targetNamespace(),
            selected.intakeRequestId(),
            selected.worldOperationId(),
            selected.sourceOperationId(),
            selected.worldSlug(),
            selected.sourceEvidenceDigest(),
            selected.worldReadRequest(),
            selected.worldReceipt());
    Request originalRequest = originalAssociation.request();
    Request substitutedRequest =
        new Request(
            originalRequest.schemaVersion(),
            originalRequest.targetNamespace(),
            originalRequest.readRequestId(),
            originalRequest.canonicalPostAuthorizationTuple(),
            originalRequest.ownerAttemptId(),
            originalRequest.ownerFence(),
            new ExactReplay(
                substituted.canonicalVersionId(),
                substituted.selectedCommitId(),
                substituted.publishWorkflowId(),
                substituted.associationDigest()));
    Result substitutedAssociation =
        new Result(
            substitutedRequest,
            substituted,
            originalAssociation.releaseBundle(),
            originalAssociation.worldPublishedStartLocationEvidence(),
            originalAssociation.phaseEpoch());

    assertThatThrownBy(
            () ->
                fixture.pin(
                    continuation,
                    new StartSessionLaunchDescriptorGrpcCodec.Resolved(
                        substitutedAssociation, original.outcome())))
        .isInstanceOf(
            GameSessionStartSessionLaunchDescriptorRepository
                .StartSessionLaunchDescriptorConflictException.class)
        .hasMessageContaining("retained exact");
    assertThat(fixture.pinCount()).isZero();
  }

  @Test
  void expiredContinuationCannotCreateDescriptorPin() throws Exception {
    Fixture fixture = fixture(Duration.ofSeconds(5));
    RetainedAssociation retained = retainAssociation(fixture, "descriptor-continuation-expired");
    EvidenceContinuation continuation = fixture.continueEvidence(retained.tuple());
    var candidate = descriptor(retained, "expired-descriptor");
    Thread.sleep(5_250L);

    assertThatThrownBy(() -> fixture.pin(continuation, candidate))
        .isInstanceOf(
            GameSessionStartSessionOperatorAttemptRepository
                .StaleStartSessionOperatorAttemptClaimException.class)
        .hasMessageContaining("expired");
    assertThat(fixture.pinCount()).isZero();
  }

  @Test
  void continuationReplayRejectsAClaimThatExpiresWhileTheDescriptorReadWaits() throws Exception {
    Fixture fixture = fixture(Duration.ofSeconds(5));
    RetainedAssociation retained =
        retainAssociation(fixture, "descriptor-continuation-read-expiry");
    fixture.pin(retained.claim(), descriptor(retained, "descriptor-read-expiry"));
    EvidenceContinuation continuation = fixture.continueEvidence(retained.tuple());
    CountDownLatch blockerReady = new CountDownLatch(1);
    CountDownLatch releaseBlocker = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.CompletableFuture<Integer> blockerPid =
          new java.util.concurrent.CompletableFuture<>();
      Future<?> blocker =
          executor.submit(
              () ->
                  fixture.transactions.executeWithoutResult(
                      status -> {
                        int backendPid =
                            Objects.requireNonNull(
                                Objects.requireNonNull(
                                        fixture.dsl.fetchOne("SELECT pg_backend_pid()"))
                                    .get(0, Integer.class));
                        fixture.dsl.fetchOne(
                            "SELECT 1 FROM game_session_start_session_launch_descriptor_pin "
                                + "WHERE target_namespace = ? AND control_plane_request_id = ? "
                                + "FOR UPDATE",
                            retained.claim().targetNamespace(),
                            retained.claim().controlPlaneRequestId());
                        blockerPid.complete(backendPid);
                        blockerReady.countDown();
                        awaitLatch(releaseBlocker, "descriptor read lock release");
                      }));
      assertThat(blockerReady.await(15, TimeUnit.SECONDS)).isTrue();

      java.util.concurrent.CompletableFuture<Integer> waiterPid =
          new java.util.concurrent.CompletableFuture<>();
      Future<RuntimeException> waiter =
          executor.submit(
              () ->
                  fixture.transactions.execute(
                      status -> {
                        waiterPid.complete(
                            Objects.requireNonNull(
                                Objects.requireNonNull(
                                        fixture.dsl.fetchOne("SELECT pg_backend_pid()"))
                                    .get(0, Integer.class)));
                        try {
                          fixture.descriptors.findPinned(continuation);
                          return null;
                        } catch (RuntimeException failure) {
                          return failure;
                        }
                      }));
      int readBackendPid = waiterPid.get(15, TimeUnit.SECONDS);
      int blockerBackendPid = blockerPid.get(15, TimeUnit.SECONDS);
      awaitPostgresLockWait(fixture.dsl, readBackendPid, blockerBackendPid);
      Thread.sleep(5_250L);
      assertThat(fixture.ownerClaimLeaseLive(retained.claim().controlPlaneRequestId())).isFalse();
      releaseBlocker.countDown();

      RuntimeException failure = waiter.get(15, TimeUnit.SECONDS);
      assertThat(failure)
          .isInstanceOf(
              GameSessionStartSessionOperatorAttemptRepository
                  .StaleStartSessionOperatorAttemptClaimException.class)
          .hasMessageContaining("expired");
      blocker.get(15, TimeUnit.SECONDS);
      assertThat(fixture.pinCount()).isOne();
    } finally {
      releaseBlocker.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void liveOwnerClaimCannotCreateDescriptorPinAfterOriginalAuthorizationExpires() throws Exception {
    Fixture fixture = fixture(Duration.ofSeconds(30));
    Instant expiredAt =
        Instant.now().minusSeconds(1L).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    StartSessionPostAuthorizationExecutionTuple tuple =
        tupleWithAuthorizationExpiry("descriptor-original-authorization-expired", expiredAt);
    RetainedAssociation retained = retainAssociation(fixture, tuple);

    assertThat(fixture.ownerClaimLeaseLive(retained.claim().controlPlaneRequestId())).isTrue();
    assertThatThrownBy(
            () ->
                fixture.pin(
                    retained.claim(), descriptor(retained, "expired-original-auth-descriptor")))
        .isInstanceOf(
            GameSessionStartSessionOperatorAttemptRepository
                .StaleStartSessionOperatorAttemptClaimException.class)
        .hasMessageContaining("stale");
    assertThat(fixture.pinCount()).isZero();
  }

  @Test
  void continuationReplayRejectsOriginalReferenceThatExpiresWhileDescriptorReadWaits()
      throws Exception {
    Fixture fixture = fixture(Duration.ofSeconds(45));
    Instant expiresAt =
        Objects.requireNonNull(
                Objects.requireNonNull(
                        fixture.dsl.fetchOne("SELECT clock_timestamp() + interval '8 seconds'"))
                    .get(0, OffsetDateTime.class))
            .toInstant()
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    String originalAuthorizationExpiresAt = expiresAt.toString();
    StartSessionPostAuthorizationExecutionTuple tuple =
        tupleWithAuthorizationExpiry("descriptor-reference-expiry-during-read", expiresAt);
    RetainedAssociation retained = retainAssociation(fixture, tuple);
    fixture.pin(retained.claim(), descriptor(retained, "reference-expiry-descriptor"));
    EvidenceContinuation continuation = fixture.continueEvidence(retained.tuple());
    assertThat(fixture.originalAuthorizationReferenceLive(originalAuthorizationExpiresAt)).isTrue();

    CountDownLatch blockerReady = new CountDownLatch(1);
    CountDownLatch releaseBlocker = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.CompletableFuture<Integer> blockerPid =
          new java.util.concurrent.CompletableFuture<>();
      Future<?> blocker =
          executor.submit(
              () ->
                  fixture.transactions.executeWithoutResult(
                      status -> {
                        int backendPid =
                            Objects.requireNonNull(
                                Objects.requireNonNull(
                                        fixture.dsl.fetchOne("SELECT pg_backend_pid()"))
                                    .get(0, Integer.class));
                        fixture.dsl.fetchOne(
                            "SELECT 1 FROM game_session_start_session_launch_descriptor_pin "
                                + "WHERE target_namespace = ? AND control_plane_request_id = ? "
                                + "FOR UPDATE",
                            retained.claim().targetNamespace(),
                            retained.claim().controlPlaneRequestId());
                        blockerPid.complete(backendPid);
                        blockerReady.countDown();
                        awaitLatch(releaseBlocker, "descriptor read lock release");
                      }));
      assertThat(blockerReady.await(15, TimeUnit.SECONDS)).isTrue();

      java.util.concurrent.CompletableFuture<Integer> waiterPid =
          new java.util.concurrent.CompletableFuture<>();
      Future<RuntimeException> waiter =
          executor.submit(
              () ->
                  fixture.transactions.execute(
                      status -> {
                        waiterPid.complete(
                            Objects.requireNonNull(
                                Objects.requireNonNull(
                                        fixture.dsl.fetchOne("SELECT pg_backend_pid()"))
                                    .get(0, Integer.class)));
                        try {
                          fixture.descriptors.findPinned(continuation);
                          return null;
                        } catch (RuntimeException failure) {
                          return failure;
                        }
                      }));
      int readBackendPid = waiterPid.get(15, TimeUnit.SECONDS);
      int blockerBackendPid = blockerPid.get(15, TimeUnit.SECONDS);
      awaitPostgresLockWait(fixture.dsl, readBackendPid, blockerBackendPid);
      awaitOriginalAuthorizationExpiry(fixture, originalAuthorizationExpiresAt);
      assertThat(fixture.ownerClaimLeaseLive(retained.claim().controlPlaneRequestId())).isTrue();
      releaseBlocker.countDown();

      RuntimeException failure = waiter.get(15, TimeUnit.SECONDS);
      assertThat(failure)
          .isInstanceOf(
              GameSessionStartSessionOperatorAttemptRepository
                  .StaleStartSessionOperatorAttemptClaimException.class)
          .hasMessageContaining("authorization reference expired during descriptor read");
      blocker.get(15, TimeUnit.SECONDS);
      assertThat(fixture.pinCount()).isOne();
    } finally {
      releaseBlocker.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentContinuationOutcomesConvergeOnOneImmutableDescriptorOrConflict() throws Exception {
    Fixture fixture = fixture();
    RetainedAssociation retained = retainAssociation(fixture, "descriptor-continuation-race");
    var firstCandidate = descriptor(retained, "race-first-descriptor");
    var secondCandidate = descriptor(retained, "race-second-descriptor");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> first =
          executor.submit(() -> continueAndPin(fixture, retained, firstCandidate, ready, start));
      Future<Boolean> second =
          executor.submit(() -> continueAndPin(fixture, retained, secondCandidate, ready, start));
      assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(fixture.pinCount()).isEqualTo(1);
      assertThat(fixture.find(fixture.continueEvidence(retained.tuple()))).isPresent();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void continuationServiceRequiresAnExistingPinAndDoesNotPinWhenGameDesignReadFails() {
    Fixture unselected = fixture();
    StartSessionPostAuthorizationExecutionTuple unselectedTuple =
        GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tuple(
            "descriptor-continuation-no-selection");
    unselected.reserveAndAttach(unselectedTuple);
    StartSessionRedeemedOperationProjectionClient unusedAccountClient =
        mock(StartSessionRedeemedOperationProjectionClient.class);
    StartSessionLaunchDescriptorReadClient unusedClient =
        mock(StartSessionLaunchDescriptorReadClient.class);
    var unselectedService = unselected.continuationService(unusedAccountClient, unusedClient);

    assertThatThrownBy(() -> unselectedService.continueOriginalEvidence(unselectedTuple))
        .isInstanceOf(EvidenceContinuationUnavailableException.class)
        .hasMessageContaining("missing");
    verifyNoInteractions(unusedAccountClient, unusedClient);
    assertThat(unselected.pinCount()).isZero();

    Fixture selected = fixture();
    RetainedAssociation retained =
        retainAssociation(selected, "descriptor-continuation-read-denied");
    StartSessionRedeemedOperationProjectionClient accountClient = projectionClient(retained);
    StartSessionLaunchDescriptorReadClient unavailableClient =
        mock(StartSessionLaunchDescriptorReadClient.class);
    when(unavailableClient.resolve(any(Request.class)))
        .thenThrow(new IllegalStateException("Game Design rejected changed Account authority"));
    var unavailableService = selected.continuationService(accountClient, unavailableClient);

    assertThatThrownBy(() -> unavailableService.continueOriginalEvidence(retained.tuple()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed Account authority");
    ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
    verify(unavailableClient).resolve(request.capture());
    var originalRequest = retained.association().result().request();
    verify(accountClient)
        .read(retained.tuple(), originalRequest.ownerAttemptId(), originalRequest.ownerFence());
    assertThat(request.getValue().selection()).isInstanceOf(ExactReplay.class);
    assertThat(request.getValue().readRequestId())
        .isEqualTo(retained.association().result().request().readRequestId());
    assertThat(selected.pinCount()).isZero();
    // This mocked transport exercises orchestration only; it is not live Account/mTLS proof.
  }

  @Test
  void continuationServiceCallsGameDesignOutsideSqlAndPinsItsExactReplayOutcome() {
    Fixture fixture = fixture();
    RetainedAssociation retained = retainAssociation(fixture, "descriptor-continuation-service");
    var candidate = descriptor(retained, "continuation-service-descriptor");
    StartSessionRedeemedOperationProjectionClient accountClient = projectionClient(retained);
    StartSessionLaunchDescriptorReadClient client =
        mock(StartSessionLaunchDescriptorReadClient.class);
    when(client.resolve(any(Request.class)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              return candidate;
            });
    var service = fixture.continuationService(accountClient, client);

    PinnedLaunchDescriptorSnapshot pinned = service.continueOriginalEvidence(retained.tuple());

    ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
    verify(client).resolve(request.capture());
    var originalRequest = retained.association().result().request();
    verify(accountClient, org.mockito.Mockito.times(2))
        .read(retained.tuple(), originalRequest.ownerAttemptId(), originalRequest.ownerFence());
    assertThat(request.getValue()).isEqualTo(candidate.associationRead().request());
    assertThat(request.getValue().selection()).isInstanceOf(ExactReplay.class);
    assertThat(pinned.responseWire()).isNotEmpty();
    assertThat(fixture.pinCount()).isEqualTo(1);
    // The mock proves orchestration only, not Account or Game Design currentness authorization.
  }

  private static StartSessionRedeemedOperationProjectionClient projectionClient(
      RetainedAssociation retained) {
    var request = retained.association().result().request();
    StartSessionRedeemedOperationProjectionClient client =
        mock(StartSessionRedeemedOperationProjectionClient.class);
    ReadRedeemedOperationProjectionResponse response =
        redeemedProjection(retained.tuple(), request.ownerAttemptId(), request.ownerFence());
    when(client.read(retained.tuple(), request.ownerAttemptId(), request.ownerFence()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              return response;
            });
    return client;
  }

  private static ReadRedeemedOperationProjectionResponse redeemedProjection(
      StartSessionPostAuthorizationExecutionTuple tuple, UUID attemptId, long ownerFence) {
    // Complete deterministic wire fixture for mocked orchestration; it is not Account server proof.
    var preTuple = tuple.preAuthorizationTuple();
    var bundle = StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
    var bundleReference = tuple.bundleReference();
    Instant referenceExpiresAt = Instant.parse(bundle.authorizationExpiresAt());
    return ReadRedeemedOperationProjectionResponse.newBuilder()
        .setControlPlaneRequestId(preTuple.controlPlaneRequestId())
        .setCanonicalPreAuthorizationTupleBytes(
            ByteString.copyFrom(preTuple.canonicalJson().getBytes(StandardCharsets.UTF_8)))
        .setMutationDigest(preTuple.mutationDigest())
        .setAuthorizationReferenceFingerprint(tuple.authorizationReferenceFingerprint())
        .setReservationOwnerId(tuple.reservationOwnerId().toString())
        .setReservationClaimFence(tuple.reservationClaimFence())
        .setAuthenticatedRedeemerWorkloadIdentity(
            "spiffe://firemud/ns/world-runtime/sa/game-session-service")
        .setOwnerAttemptId(attemptId.toString())
        .setOwnerFence(ownerFence)
        .setReferenceExpiresAt(timestamp(referenceExpiresAt))
        .setRedeemedAt(timestamp(referenceExpiresAt.minusSeconds(60L)))
        .setIssuanceOperationId(bundle.issuanceOperationId().toString())
        .setIssuanceFence(Long.parseLong(bundle.issuanceFence()))
        .setBundleReference(
            AuthorityEvidenceBundleReference.newBuilder()
                .setBundleVersion(bundleReference.bundleVersion())
                .setSourceVersion(bundleReference.sourceVersion())
                .setSourceFence(bundleReference.sourceFence())
                .setLinearization(bundleReference.linearization())
                .build())
        .setAuthorityEvidenceBundle(ByteString.copyFrom(tuple.authorityEvidenceBundleBytes()))
        .build();
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  private static RetainedAssociation retainAssociation(Fixture fixture, String requestId) {
    StartSessionPostAuthorizationExecutionTuple tuple =
        GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tuple(requestId);
    return retainAssociation(fixture, tuple);
  }

  private static RetainedAssociation retainAssociation(
      Fixture fixture, StartSessionPostAuthorizationExecutionTuple tuple) {
    ReservationResult reservation = fixture.reserve(tuple);
    AttemptClaim claim = reservation.claim().orElseThrow();
    AccountRedemptionProjection projection =
        GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.projection(tuple);
    fixture.attach(claim, projection);
    Result initial =
        GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.result(
            tuple, claim, new InitialConfigured(), UUID.randomUUID());
    return new RetainedAssociation(tuple, claim, fixture.pinAssociation(claim, initial));
  }

  private static StartSessionPostAuthorizationExecutionTuple tupleWithAuthorizationExpiry(
      String requestId, Instant expiresAt) throws java.io.IOException {
    StartSessionPostAuthorizationExecutionTuple original =
        GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.tuple(requestId);
    byte[] originalBundle = original.authorityEvidenceBundleBytes();
    String originalExpiry =
        StartSessionAuthorityEvidenceBundle.decode(originalBundle).authorizationExpiresAt();
    String bundleJson = new String(originalBundle, StandardCharsets.UTF_8);
    String replacedBundleJson =
        bundleJson.replace("\"" + originalExpiry + "\"", "\"" + expiresAt + "\"");
    if (bundleJson.equals(replacedBundleJson)) {
      throw new IllegalStateException("Could not replace original authorization expiry fixture");
    }
    byte[] expiredBundle = Rfc8785CanonicalJson.canonicalizeUtf8(replacedBundleJson);
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        original.preAuthorizationTuple(),
        original.authenticatedWorkloadIdentity(),
        original.authorizationReferenceFingerprint(),
        original.reservationOwnerId(),
        original.reservationClaimFence(),
        expiredBundle,
        original.bundleReference());
  }

  private static void assertDescriptorPinRejectedForOwnerTuple(
      Fixture fixture,
      RetainedAssociation retained,
      UUID canonicalTenantId,
      UUID ownerAttemptId,
      long ownerFence) {
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "INSERT INTO game_session_start_session_launch_descriptor_pin "
                        + "(target_namespace, control_plane_request_id, canonical_tenant_id, "
                        + "owner_attempt_id, owner_fence, association_request_digest, "
                        + "association_response_digest, descriptor_request_wire, "
                        + "descriptor_response_wire, descriptor_request_digest, "
                        + "descriptor_response_digest) "
                        + "SELECT association.target_namespace, association.control_plane_request_id, "
                        + "?, ?, ?, association.association_request_digest, "
                        + "association.association_response_digest, ?, ?, ?, ? "
                        + "FROM game_session_start_session_template_association_pin association "
                        + "WHERE association.target_namespace = ? "
                        + "AND association.control_plane_request_id = ?",
                    canonicalTenantId,
                    ownerAttemptId,
                    ownerFence,
                    new byte[] {1},
                    new byte[] {1},
                    "sha256:" + "a".repeat(64),
                    "sha256:" + "b".repeat(64),
                    retained.claim().targetNamespace(),
                    retained.claim().controlPlaneRequestId()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("fk_gs_start_session_launch_descriptor_association");
  }

  private static boolean continueAndPin(
      Fixture fixture,
      RetainedAssociation retained,
      StartSessionLaunchDescriptorGrpcCodec.Resolved candidate,
      CountDownLatch ready,
      CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(15, TimeUnit.SECONDS)) {
      throw new IllegalStateException("descriptor continuation contender did not start");
    }
    try {
      EvidenceContinuation continuation = fixture.continueEvidence(retained.tuple());
      fixture.pin(continuation, candidate);
      return true;
    } catch (
        GameSessionStartSessionLaunchDescriptorRepository
                .StartSessionLaunchDescriptorConflictException
            conflict) {
      return false;
    }
  }

  private static void awaitPostgresLockWait(DSLContext observer, int waiterPid, int blockerPid)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    boolean blockedByExpectedBackend = false;
    while (System.nanoTime() < deadline) {
      Boolean blocked =
          Objects.requireNonNull(
                  observer.fetchOne(
                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ? "
                          + "AND wait_event_type = 'Lock' "
                          + "AND query ILIKE '%SELECT descriptor_pin.* FROM "
                          + "game_session_start_session_launch_descriptor_pin%' "
                          + "AND ? = ANY(pg_blocking_pids(pid)))",
                      waiterPid, blockerPid))
              .get(0, Boolean.class);
      if (Boolean.TRUE.equals(blocked)) {
        blockedByExpectedBackend = true;
        break;
      }
      Thread.sleep(10L);
    }
    assertThat(blockedByExpectedBackend)
        .as("descriptor replay SELECT waits on the held PostgreSQL row lock")
        .isTrue();
  }

  private static void awaitOriginalAuthorizationExpiry(Fixture fixture, String expiresAt)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      if (!fixture.originalAuthorizationReferenceLive(expiresAt)) {
        return;
      }
      Thread.sleep(10L);
    }
    assertThat(fixture.originalAuthorizationReferenceLive(expiresAt))
        .as("original Account authorization reference expires according to PostgreSQL time")
        .isFalse();
  }

  private static void awaitLatch(CountDownLatch latch, String awaitedState) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for " + awaitedState);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for " + awaitedState, interrupted);
    }
  }

  private static StartSessionLaunchDescriptorGrpcCodec.Resolved descriptor(
      RetainedAssociation retained, String descriptorId) {
    return descriptorFor(retained.association().result(), descriptorId);
  }

  static StartSessionLaunchDescriptorGrpcCodec.Resolved descriptorFor(
      Result association, String descriptorId) {
    Request request = exactReplayRequest(association);
    Result exactAssociation = exactReplayAssociation(association, request);
    var selection = association.association();
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            request.targetNamespace(),
            request.decodedTuple().controlPlaneRequestId(),
            selection.canonicalTenantId(),
            selection.worldSlug(),
            selection.sourceOperationId(),
            selection.sourceEvidenceDigest(),
            selection.templateId(),
            false,
            null,
            false,
            null,
            false,
            null,
            true,
            "{}");
    PublishedReleaseBundle release = association.releaseBundle();
    var evidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            descriptorId,
            release.getVersionId(),
            false,
            null,
            "{}",
            release.getGenerationConfigRevision(),
            5L,
            release.getId(),
            release.getPublishedReleaseBundleRef(),
            false,
            null);
    return new StartSessionLaunchDescriptorGrpcCodec.Resolved(
        exactAssociation, new StartSessionLaunchDescriptorGrpcCodec.DescriptorOutcome(evidence));
  }

  private static Request exactReplayRequest(Result original) {
    var association = original.association();
    Request first = original.request();
    return new Request(
        first.schemaVersion(),
        first.targetNamespace(),
        first.readRequestId(),
        first.canonicalPostAuthorizationTuple(),
        first.ownerAttemptId(),
        first.ownerFence(),
        new ExactReplay(
            association.canonicalVersionId(),
            association.selectedCommitId(),
            association.publishWorkflowId(),
            association.associationDigest()));
  }

  private static Result exactReplayAssociation(Result original, Request request) {
    return new Result(
        request,
        original.association(),
        original.releaseBundle(),
        original.worldPublishedStartLocationEvidence(),
        original.phaseEpoch());
  }

  private static Fixture fixture() {
    return fixture(Duration.ofSeconds(30));
  }

  private static Fixture fixture(Duration ownerClaimLease) {
    String schema = "gs_start_session_descriptor_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var attempts = new GameSessionStartSessionOperatorAttemptRepository(dsl, ownerClaimLease);
    var associations = new GameSessionStartSessionTemplateAssociationRepository(dsl, attempts);
    return new Fixture(
        dsl,
        transactions,
        transactionManager,
        attempts,
        associations,
        new GameSessionStartSessionLaunchDescriptorRepository(dsl, associations));
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate transactions,
      DataSourceTransactionManager transactionManager,
      GameSessionStartSessionOperatorAttemptRepository attempts,
      GameSessionStartSessionTemplateAssociationRepository associations,
      GameSessionStartSessionLaunchDescriptorRepository descriptors) {
    ReservationResult reserve(StartSessionPostAuthorizationExecutionTuple tuple) {
      return transactions.execute(status -> attempts.reserve(tuple));
    }

    AttemptClaim reserveAndAttach(StartSessionPostAuthorizationExecutionTuple tuple) {
      AttemptClaim claim = reserve(tuple).claim().orElseThrow();
      attach(
          claim,
          GameSessionStartSessionTemplateAssociationRepositoryIntegrationTest.projection(tuple));
      return claim;
    }

    EvidenceContinuation continueEvidence(StartSessionPostAuthorizationExecutionTuple tuple) {
      return transactions.execute(status -> attempts.beginEvidenceContinuation(tuple));
    }

    void attach(AttemptClaim claim, AccountRedemptionProjection projection) {
      transactions.executeWithoutResult(
          status -> attempts.attachAccountRedemptionProjection(claim, projection));
    }

    boolean ownerClaimLeaseLive(String requestId) {
      return Boolean.TRUE.equals(
          dsl.fetchValue(
              "SELECT lease_expires_at > clock_timestamp() FROM "
                  + "game_session_start_session_operator_attempt "
                  + "WHERE control_plane_request_id = ?",
              Boolean.class,
              requestId));
    }

    boolean originalAuthorizationReferenceLive(String expiresAt) {
      return Boolean.TRUE.equals(
          dsl.fetchValue("SELECT ?::timestamptz > clock_timestamp()", Boolean.class, expiresAt));
    }

    PinnedAssociationSnapshot pinAssociation(AttemptClaim claim, Result result) {
      return transactions.execute(
          status -> associations.pinInitialOrValidateExactReplay(claim, result));
    }

    PinnedLaunchDescriptorSnapshot pin(
        AttemptClaim claim, StartSessionLaunchDescriptorGrpcCodec.Resolved resolved) {
      return transactions.execute(status -> descriptors.pin(claim, resolved));
    }

    PinnedLaunchDescriptorSnapshot pin(
        EvidenceContinuation continuation,
        StartSessionLaunchDescriptorGrpcCodec.Resolved resolved) {
      return transactions.execute(status -> descriptors.pin(continuation, resolved));
    }

    java.util.Optional<PinnedLaunchDescriptorSnapshot> find(AttemptClaim claim) {
      return transactions.execute(status -> descriptors.findPinned(claim));
    }

    java.util.Optional<PinnedLaunchDescriptorSnapshot> find(EvidenceContinuation continuation) {
      return transactions.execute(status -> descriptors.findPinned(continuation));
    }

    GameSessionStartSessionLaunchDescriptorContinuationService continuationService(
        StartSessionRedeemedOperationProjectionClient accountClient,
        StartSessionLaunchDescriptorReadClient client) {
      return new GameSessionStartSessionLaunchDescriptorContinuationService(
          attempts,
          associations,
          descriptors,
          accountClient,
          client,
          transactionManager,
          "world-runtime");
    }

    int pinCount() {
      return dsl.fetchCount(
          DSL.table(DSL.name("game_session_start_session_launch_descriptor_pin")));
    }
  }

  private record RetainedAssociation(
      StartSessionPostAuthorizationExecutionTuple tuple,
      AttemptClaim claim,
      PinnedAssociationSnapshot association) {}
}
