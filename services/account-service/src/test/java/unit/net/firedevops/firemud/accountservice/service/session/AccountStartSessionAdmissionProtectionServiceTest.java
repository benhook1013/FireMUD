package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository.StoredProtection;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository.StoredSettlement;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadClient;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadEvidence;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadInput;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionSettlementEvidence;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Controlled Account composition tests; mocked owner reads prove no upstream custody. */
class AccountStartSessionAdmissionProtectionServiceTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACCOUNT = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final Instant EXPIRY = Instant.parse("2030-01-01T00:05:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void acquisitionRequiresExactGameSessionWorkloadAndNoAmbientTransaction() {
    var h = new AcquisitionHarness();
    assertCode(Status.Code.UNAUTHENTICATED, () -> h.service.acquire(null));
    withPeer(
        "world-management-service",
        () -> assertCode(Status.Code.PERMISSION_DENIED, () -> h.service.acquire(null)));
    withPeerInNamespace(
        "other", () -> assertCode(Status.Code.PERMISSION_DENIED, () -> h.service.acquire(null)));
    SessionContext.setContext("123", List.of(), Map.of());
    withGameSessionPeer(
        () -> assertCode(Status.Code.PERMISSION_DENIED, () -> h.service.acquire(null)));
    SessionContext.clear();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    withGameSessionPeer(
        () -> assertCode(Status.Code.FAILED_PRECONDITION, () -> h.service.acquire(null)));

    verifyNoInteractions(
        h.issuer, h.participation, h.attempts, h.protections, h.currentAttemptReader);
  }

  @Test
  void acquisitionReadsGameSessionOutsideSqlThenUsesAccountCurrentnessAndExactReadback() {
    var h = new AcquisitionHarness();
    var fixture = fixture("admission-acquire");
    var storedProtection = new StoredProtection(fixture.protection(), 71L);
    when(h.participation.findHistoricalForOriginalTuple(any()))
        .thenReturn(Optional.of(fixture.participation()));
    when(h.attempts.findHistoricalExact(any(), any()))
        .thenReturn(Optional.of(fixture.retainedAttempt()));
    when(h.protections.acquireOrReadExact(fixture.request(), null, fixture.participation()))
        .thenReturn(storedProtection);
    when(h.protections.findCurrentExact(
            eq(fixture.protection().accountProtectionId()),
            eq(fixture.protection().accountProtectionFence()),
            any(byte[].class)))
        .thenReturn(Optional.of(storedProtection));
    when(h.currentAttemptReader.read(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              var read =
                  (OriginalStartSessionCurrentAttemptEvidence.Request) invocation.getArgument(0);
              return new OriginalStartSessionCurrentAttemptEvidence.Result(
                  read, EXPIRY, fixture.request().accountRedemptionProjectionBytes());
            });
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Function<AccountStartSessionAuthorityCapture, StoredProtection> callback =
                  invocation.getArgument(3);
              return callback.apply(null);
            })
        .when(h.issuer)
        .withCurrentGameSessionAdmissionProtectionCurrentness(
            any(byte[].class),
            eq(fixture.request().gameSessionOwnerAttemptId()),
            eq(fixture.request().gameSessionOwnerFence()),
            any());

    var actual = withGameSessionPeerResult(() -> h.service.acquire(fixture.request()));

    assertThat(actual.canonicalBytes()).containsExactly(fixture.protection().canonicalBytes());
    verify(h.currentAttemptReader).read(any());
    verify(h.participation, never()).findCurrentExact(any());
    verify(h.attempts, times(2)).findHistoricalExact(any(), any());
    verify(h.attempts, never()).findCurrentExact(any(), any());
    verify(h.issuer)
        .withCurrentGameSessionAdmissionProtectionCurrentness(
            any(byte[].class),
            eq(fixture.request().gameSessionOwnerAttemptId()),
            eq(fixture.request().gameSessionOwnerFence()),
            any());
    verify(h.protections).acquireOrReadExact(fixture.request(), null, fixture.participation());
    verify(h.protections)
        .findCurrentExact(
            eq(fixture.protection().accountProtectionId()),
            eq(fixture.protection().accountProtectionFence()),
            any(byte[].class));
    assertThat(h.transactions.begins).isEqualTo(2);
    assertThat(h.transactions.commits).isEqualTo(2);
  }

  @Test
  void settlementReadsExactTerminalOutsideSqlAndCommitsImmutableReadback() {
    var fixture = fixture("admission-settle");
    var h = new SettlementHarness();
    var protectedRow = new StoredProtection(fixture.protection(), 79L);
    var canonicalSettlement =
        AccountStartSessionAdmissionProtectionSettlementEvidence.create(
            fixture.protection(), fixture.committedTerminal());
    var expected = storedSettlement(canonicalSettlement);
    when(h.repository.findHistoricalExact(
            fixture.protection().accountProtectionId(),
            fixture.protection().accountProtectionFence()))
        .thenReturn(Optional.of(protectedRow));
    when(h.repository.findSettlementExact(
            fixture.protection().accountProtectionId(),
            fixture.protection().accountProtectionFence()))
        .thenReturn(Optional.empty(), Optional.of(expected));
    when(h.client.read(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              var read = (AccountInitialAdmissionSettlementReadInput) invocation.getArgument(0);
              assertThat(read.protection().canonicalBytes())
                  .containsExactly(fixture.protection().canonicalBytes());
              return new AccountInitialAdmissionSettlementReadEvidence(
                  read.readRequestId(), read.protection(), fixture.committedTerminal());
            });
    when(h.repository.settleExact(any())).thenReturn(expected);

    var actual =
        withGameSessionPeerResult(
            () ->
                h.service.settle(
                    fixture.protection().accountProtectionId(),
                    fixture.protection().accountProtectionFence()));

    assertThat(actual.canonicalBytes()).containsExactly(canonicalSettlement.canonicalBytes());
    assertThat(h.transactions.begins).isEqualTo(3);
    assertThat(h.transactions.commits).isEqualTo(3);
    var captured =
        org.mockito.ArgumentCaptor.forClass(AccountInitialAdmissionSettlementReadEvidence.class);
    verify(h.repository).settleExact(captured.capture());
    assertThat(captured.getValue().protection().canonicalBytes())
        .containsExactly(fixture.protection().canonicalBytes());
    assertThat(
            GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                captured.getValue().terminal()))
        .containsExactly(
            GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(
                fixture.committedTerminal()));
    verify(h.client).read(any());
  }

  @Test
  void exactHistoricalSettlementReplayDoesNotRequeryOrRenewProtection() {
    var fixture = fixture("admission-settle-replay");
    var h = new SettlementHarness();
    var canonicalSettlement =
        AccountStartSessionAdmissionProtectionSettlementEvidence.create(
            fixture.protection(), fixture.committedTerminal());
    var receipt = storedSettlement(canonicalSettlement);
    when(h.repository.findHistoricalExact(
            fixture.protection().accountProtectionId(),
            fixture.protection().accountProtectionFence()))
        .thenReturn(Optional.of(new StoredProtection(fixture.protection(), 83L)));
    when(h.repository.findSettlementExact(
            fixture.protection().accountProtectionId(),
            fixture.protection().accountProtectionFence()))
        .thenReturn(Optional.of(receipt));

    var actual =
        withGameSessionPeerResult(
            () ->
                h.service.settle(
                    fixture.protection().accountProtectionId(),
                    fixture.protection().accountProtectionFence()));

    assertThat(actual.canonicalBytes()).containsExactly(receipt.canonicalBytes());
    verifyNoInteractions(h.client);
    verify(h.repository, never()).settleExact(any());
    assertThat(h.transactions.begins).isEqualTo(1);
    assertThat(h.transactions.commits).isEqualTo(1);
  }

  @Test
  void settlementAcceptsOnlyTheExactDefinitiveFencedAbortOutcome() {
    var fixture = fixture("admission-settle-abort");
    var h = new SettlementHarness();
    var aborted = fixture.abortedTerminal();
    var value =
        AccountStartSessionAdmissionProtectionSettlementEvidence.create(
            fixture.protection(), aborted);
    var expected = storedSettlement(value);
    when(h.repository.findHistoricalExact(
            fixture.protection().accountProtectionId(),
            fixture.protection().accountProtectionFence()))
        .thenReturn(Optional.of(new StoredProtection(fixture.protection(), 87L)));
    when(h.repository.findSettlementExact(
            fixture.protection().accountProtectionId(),
            fixture.protection().accountProtectionFence()))
        .thenReturn(Optional.empty(), Optional.of(expected));
    when(h.client.read(any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              var read = (AccountInitialAdmissionSettlementReadInput) invocation.getArgument(0);
              return new AccountInitialAdmissionSettlementReadEvidence(
                  read.readRequestId(), read.protection(), aborted);
            });
    when(h.repository.settleExact(any())).thenReturn(expected);

    var actual =
        withGameSessionPeerResult(
            () ->
                h.service.settle(
                    fixture.protection().accountProtectionId(),
                    fixture.protection().accountProtectionFence()));

    assertThat(actual.outcome())
        .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
    assertThat(actual.canonicalBytes()).containsExactly(value.canonicalBytes());
    assertThat(aborted.positiveDurableAbort()).isTrue();
  }

  @Test
  void settlementRejectsChangedOwnerReadBeforeMutationAndClassifiesDependencyFailure() {
    var fixture = fixture("admission-settle-mismatch");
    var h = new SettlementHarness();
    when(h.repository.findHistoricalExact(
            fixture.protection().accountProtectionId(),
            fixture.protection().accountProtectionFence()))
        .thenReturn(Optional.of(new StoredProtection(fixture.protection(), 89L)));
    when(h.repository.findSettlementExact(
            fixture.protection().accountProtectionId(),
            fixture.protection().accountProtectionFence()))
        .thenReturn(Optional.empty());
    when(h.client.read(any()))
        .thenAnswer(
            invocation -> {
              var read = (AccountInitialAdmissionSettlementReadInput) invocation.getArgument(0);
              return new AccountInitialAdmissionSettlementReadEvidence(
                  UUID.randomUUID(), read.protection(), fixture.committedTerminal());
            });

    withGameSessionPeer(
        () ->
            assertCode(
                Status.Code.FAILED_PRECONDITION,
                () ->
                    h.service.settle(
                        fixture.protection().accountProtectionId(),
                        fixture.protection().accountProtectionFence())));
    verify(h.repository, never()).settleExact(any());

    for (RuntimeException malformed :
        List.of(
            new IllegalArgumentException("malformed direct owner read"),
            new IllegalStateException("malformed decoded owner read"))) {
      var invalid = new SettlementHarness();
      when(invalid.repository.findHistoricalExact(
              fixture.protection().accountProtectionId(),
              fixture.protection().accountProtectionFence()))
          .thenReturn(Optional.of(new StoredProtection(fixture.protection(), 89L)));
      when(invalid.repository.findSettlementExact(
              fixture.protection().accountProtectionId(),
              fixture.protection().accountProtectionFence()))
          .thenReturn(Optional.empty());
      doThrow(malformed).when(invalid.client).read(any());
      var failure =
          statusFailure(
              () ->
                  withGameSessionPeer(
                      () ->
                          invalid.service.settle(
                              fixture.protection().accountProtectionId(),
                              fixture.protection().accountProtectionFence())));
      assertThat(Status.fromThrowable(failure).getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
      assertThat(failure.getCause()).isSameAs(malformed);
      verify(invalid.repository, never()).settleExact(any());
    }

    for (Status.Code code : Status.Code.values()) {
      var dependency = new SettlementHarness();
      when(dependency.repository.findHistoricalExact(
              fixture.protection().accountProtectionId(),
              fixture.protection().accountProtectionFence()))
          .thenReturn(Optional.of(new StoredProtection(fixture.protection(), 89L)));
      when(dependency.repository.findSettlementExact(
              fixture.protection().accountProtectionId(),
              fixture.protection().accountProtectionFence()))
          .thenReturn(Optional.empty());
      doThrow(Status.fromCode(code).asRuntimeException()).when(dependency.client).read(any());
      var failure =
          statusFailure(
              () ->
                  withGameSessionPeer(
                      () ->
                          dependency.service.settle(
                              fixture.protection().accountProtectionId(),
                              fixture.protection().accountProtectionFence())));
      assertThat(Status.fromThrowable(failure).getCode())
          .isEqualTo(infrastructureCodes().contains(code) ? code : Status.Code.FAILED_PRECONDITION);
      verify(dependency.repository, never()).settleExact(any());
    }
  }

  private static Fixture fixture(String requestId) {
    var pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            requestId,
            ACCOUNT,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, UUID.randomUUID()),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "TEST_ONLY original StartSession admission"));
    var bundle =
        StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
            pre,
            new StartSessionAuthorityEvidenceBundleFixture.Values(
                UUID.randomUUID(),
                "sha256:" + "a".repeat(64),
                "1",
                "CURRENT",
                Instant.parse("2030-01-01T00:00:00Z"),
                Instant.parse("2030-01-01T00:04:00Z"),
                1L,
                1L,
                1L,
                1L,
                1L,
                1L,
                UUID.randomUUID(),
                "1",
                "1"));
    var tuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            pre,
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
            "arfp/v1/test-key/" + "b".repeat(64),
            UUID.randomUUID(),
            1L,
            bundle,
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "1", "1", "1"));
    UUID mutationId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    UUID participationId = UUID.randomUUID();
    var holdRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            TENANT,
            "earth",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SHARED",
            UUID.randomUUID(),
            UUID.randomUUID(),
            3L,
            tuple.controlPlaneRequestId(),
            "c".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null);
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            holdRequest, UUID.randomUUID(), UUID.randomUUID());
    var request =
        AccountStartSessionAdmissionProtectionRequest.create(
            tuple.canonicalBytes(),
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
            mutationId,
            attemptId,
            7L,
            EXPIRY,
            participationId,
            11L,
            hold);
    SourceEvidence source =
        new SourceEvidence(
            SourceKind.ACCOUNT, TENANT.toString(), "1", "1", null, null, bytes("account-source"));
    var participation =
        new AccountStartSessionWorldParticipationRepository.StoredParticipation(
            participationId,
            11L,
            tuple.controlPlaneRequestId(),
            tuple.canonicalBytes(),
            NAMESPACE,
            TENANT,
            hold.request().canonicalGameInstanceId(),
            attemptId,
            7L,
            "{}",
            "sha256:" + "d".repeat(64),
            97L,
            OffsetDateTime.parse("2030-01-01T00:00:00Z"),
            List.of(source));
    var attemptRequest =
        new OriginalStartSessionCurrentAttemptEvidence.Request(
            UUID.randomUUID(), NAMESPACE, tuple.canonicalBytes(), attemptId, mutationId, 7L);
    var result =
        new OriginalStartSessionCurrentAttemptEvidence.Result(
            attemptRequest, EXPIRY, request.accountRedemptionProjectionBytes());
    byte[] response =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(result).toByteArray();
    var retainedAttempt =
        new AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence(
            participationId, mutationId, EXPIRY, response, digest(response), result);
    byte[] captureReference = captureReference(tuple);
    var evidence =
        AccountStartSessionAdmissionProtectionEvidence.create(
            request,
            UUID.randomUUID(),
            23L,
            captureReference,
            digest(captureReference).substring("sha256:".length()),
            List.of(source));
    return new Fixture(request, participation, retainedAttempt, evidence);
  }

  private static byte[] captureReference(StartSessionPostAuthorizationExecutionTuple tuple) {
    Map<String, Object> reference =
        Map.of(
            "schema",
                AccountStartSessionAdmissionProtectionEvidence.SOURCE_CAPTURE_REFERENCE_SCHEMA,
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "capturedAt", "2030-01-01T00:00:00.000Z",
            "bundleReference",
                Map.of(
                    "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                    "sourceVersion", "1",
                    "sourceFence", "1",
                    "linearization", "1"),
            "snapshotSha256", "e".repeat(64));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(reference));
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static StoredSettlement storedSettlement(
      AccountStartSessionAdmissionProtectionSettlementEvidence value) {
    return new StoredSettlement(
        value.protection(),
        value.terminal(),
        value.outcome(),
        value.canonicalBytes(),
        value.digest(),
        Instant.parse("2030-01-01T00:02:00Z"));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String digest(byte[] value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
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

  private static StatusRuntimeException statusFailure(Runnable work) {
    var failure = org.assertj.core.api.Assertions.catchThrowable(work::run);
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    return (StatusRuntimeException) failure;
  }

  private static void assertCode(Status.Code expected, Runnable work) {
    assertThatThrownBy(work::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static void withGameSessionPeer(Runnable work) {
    withPeer("game-session-service", work);
  }

  private static void withPeer(String service, Runnable work) {
    withIdentity(NAMESPACE, "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + service, service, work);
  }

  private static void withPeerInNamespace(String namespace, Runnable work) {
    withIdentity(
        namespace,
        "spiffe://firemud/ns/" + namespace + "/sa/game-session-service",
        "game-session-service",
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

  private static <T> T withGameSessionPeerResult(java.util.function.Supplier<T> work) {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service",
            NAMESPACE,
            "game-session-service");
    var next = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    var prior = next.attach();
    try {
      return work.get();
    } finally {
      next.detach(prior);
    }
  }

  private record Fixture(
      AccountStartSessionAdmissionProtectionRequest request,
      AccountStartSessionWorldParticipationRepository.StoredParticipation participation,
      AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence retainedAttempt,
      AccountStartSessionAdmissionProtectionEvidence protection) {
    GameSessionCanonicalInitialAdmissionOwnerProof committedTerminal() {
      return new GameSessionCanonicalInitialAdmissionOwnerProof(
          protection.request().worldAdmissionHoldIdentity(),
          GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
          1L,
          31L,
          "sha256:" + "f".repeat(64),
          false,
          Instant.parse("2030-01-01T00:01:00Z"));
    }

    GameSessionCanonicalInitialAdmissionOwnerProof abortedTerminal() {
      return new GameSessionCanonicalInitialAdmissionOwnerProof(
          protection.request().worldAdmissionHoldIdentity(),
          GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED,
          null,
          null,
          "sha256:" + "9".repeat(64),
          true,
          Instant.parse("2030-01-01T00:01:00Z"));
    }
  }

  private static final class AcquisitionHarness {
    final AccountStartSessionOperatorAuthorizationService issuer =
        mock(AccountStartSessionOperatorAuthorizationService.class);
    final AccountStartSessionWorldParticipationRepository participation =
        mock(AccountStartSessionWorldParticipationRepository.class);
    final AccountStartSessionWorldOriginalAttemptEvidenceRepository attempts =
        mock(AccountStartSessionWorldOriginalAttemptEvidenceRepository.class);
    final AccountStartSessionAdmissionProtectionRepository protections =
        mock(AccountStartSessionAdmissionProtectionRepository.class);
    final OriginalStartSessionCurrentAttemptClient currentAttemptReader =
        mock(OriginalStartSessionCurrentAttemptClient.class);
    final OwnerTransactions transactions = new OwnerTransactions();
    final AccountStartSessionAdmissionProtectionAcquisitionService service =
        new AccountStartSessionAdmissionProtectionAcquisitionService(
            issuer,
            participation,
            attempts,
            protections,
            currentAttemptReader,
            transactions,
            NAMESPACE);
  }

  private static final class SettlementHarness {
    final AccountStartSessionAdmissionProtectionRepository repository =
        mock(AccountStartSessionAdmissionProtectionRepository.class);
    final AccountInitialAdmissionSettlementReadClient client =
        mock(AccountInitialAdmissionSettlementReadClient.class);
    final OwnerTransactions transactions = new OwnerTransactions();
    final AccountStartSessionAdmissionProtectionSettlementService service =
        new AccountStartSessionAdmissionProtectionSettlementService(
            repository, client, transactions, NAMESPACE);
  }

  private static final class OwnerTransactions extends AbstractPlatformTransactionManager {
    private static final long serialVersionUID = 1L;
    int begins;
    int commits;
    int rollbacks;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      assertThat(definition.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(definition.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
      assertThat(definition.isReadOnly()).isFalse();
      begins++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
    }
  }
}
