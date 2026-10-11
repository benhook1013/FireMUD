package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.Candidate;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationAcquisitionService.AcquiredParticipation;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationAcquisitionService.AcquisitionRequest;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic composition proof only; current Game Session and Account storage are mocked. */
class AccountStartSessionWorldParticipationAcquisitionServiceTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID ACCOUNT_ID = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TENANT_ID = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID PARTICIPATION_ID = uuid("a8c1e8c8-f237-41b7-918d-ec2281bcac10");
  private static final UUID GAME_SESSION_MUTATION_ID = uuid("b9d2f9d9-0438-42c8-829e-fd3392cd9d21");
  private static final UUID GAME_SESSION_ATTEMPT_ID = uuid("4a3fc69f-a18a-4b94-b1d1-2e8b4c3f0e4b");
  private static final UUID GAME_INSTANCE_ID = uuid("c0e30aea-1549-43d9-93af-0e44a3deae32");
  private static final long PARTICIPATION_FENCE = 31L;
  private static final long GAME_SESSION_FENCE = 37L;
  private static final String PREPARATION_INPUT =
      "{ \"selected\" : \"immutable source\", \"revision\": 3 }\n";
  private static final Instant ORIGINAL_LEASE_EXPIRY = Instant.parse("2026-10-12T00:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesExactWorldPeerWithoutEndUserBeforeDecodingOrCallingDependencies() {
    Harness harness = new Harness();

    assertCode(Status.Code.UNAUTHENTICATED, () -> harness.service.acquire(null));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () ->
            peer(
                NAMESPACE,
                "game-session-service",
                () -> harness.service.acquire(request(new byte[0]))));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () ->
            peer(
                "other-runtime",
                "world-management-service",
                () -> harness.service.acquire(request(new byte[0]))));
    SessionContext.setContext("123", List.of(), Map.of());
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> authorized(() -> harness.service.acquire(request(new byte[0]))));

    verifyNoInteractions(harness.issuer);
    verifyNoInteractions(harness.participationRepository);
    verifyNoInteractions(harness.attemptEvidenceRepository);
    verifyNoInteractions(harness.gameSessionClient);
    assertThat(harness.readbackTransactions.begins).isZero();
  }

  @Test
  void rejectsAmbientSqlOrSynchronizationBeforeDecodingOrRemoteRead() {
    Harness harness = new Harness();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> authorized(() -> harness.service.acquire(request(new byte[0]))));
    TransactionSynchronizationManager.clear();
    TransactionSynchronizationManager.initSynchronization();
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> authorized(() -> harness.service.acquire(request(new byte[0]))));

    verifyNoInteractions(harness.issuer);
    verifyNoInteractions(harness.participationRepository);
    verifyNoInteractions(harness.attemptEvidenceRepository);
    verifyNoInteractions(harness.gameSessionClient);
    assertThat(harness.readbackTransactions.begins).isZero();
  }

  @Test
  void rejectsUnavailableOrSubstitutedGameSessionRequestEchoBeforeIssuerCallbackOrWrites() {
    List<Function<Request, Request>> substitutedEchoes =
        List.of(
            ignored -> null,
            read ->
                new Request(
                    read.readRequestId(),
                    read.targetNamespace(),
                    read.canonicalPostAuthorizationTuple(),
                    uuid("11111111-1111-4111-8111-111111111111"),
                    read.expectedOwnerMutationId(),
                    read.expectedOwnerFence()),
            read ->
                new Request(
                    read.readRequestId(),
                    read.targetNamespace(),
                    read.canonicalPostAuthorizationTuple(),
                    read.expectedOwnerAttemptId(),
                    uuid("22222222-2222-4222-8222-222222222222"),
                    read.expectedOwnerFence()),
            read ->
                new Request(
                    read.readRequestId(),
                    read.targetNamespace(),
                    read.canonicalPostAuthorizationTuple(),
                    read.expectedOwnerAttemptId(),
                    read.expectedOwnerMutationId(),
                    read.expectedOwnerFence() + 1));

    for (Function<Request, Request> substituteEcho : substitutedEchoes) {
      Harness harness = new Harness();
      when(harness.gameSessionClient.read(any(Request.class)))
          .thenAnswer(
              invocation -> {
                assertNoSql();
                harness.steps.add("remote-read");
                Request expected = invocation.getArgument(0);
                Request echoed = substituteEcho.apply(expected);
                return echoed == null
                    ? null
                    : new Result(
                        echoed, ORIGINAL_LEASE_EXPIRY, projection(harness.tuple, Map.of()));
              });

      assertCode(
          Status.Code.FAILED_PRECONDITION,
          () -> authorized(() -> harness.service.acquire(request(null))));

      assertThat(harness.steps).containsExactly("remote-read");
      verify(harness.gameSessionClient).read(any(Request.class));
      verify(harness.issuer, times(0))
          .withCurrentWorldReceivingParticipationCurrentness(
              any(byte[].class), eq(GAME_SESSION_ATTEMPT_ID), eq(GAME_SESSION_FENCE), any());
      verifyNoInteractions(harness.participationRepository);
      verifyNoInteractions(harness.attemptEvidenceRepository);
      assertThat(harness.readbackTransactions.begins).isZero();
    }
  }

  @Test
  void readsGameSessionOutsideSqlThenWritesAtomicallyAndPerformsIndependentLookupOnlyReadback() {
    Harness harness = new Harness();
    harness.configureSuccess();

    AcquiredParticipation acquired = authorized(() -> harness.service.acquire(request(null)));

    assertThat(acquired.participation().participationId()).isEqualTo(PARTICIPATION_ID);
    assertThat(acquired.participation().participationFence()).isEqualTo(PARTICIPATION_FENCE);
    assertThat(acquired.participation().originalPostAuthorizationTuple())
        .containsExactly(harness.tuple.canonicalBytes());
    assertThat(acquired.participation().sources()).hasSize(1);
    assertThat(acquired.originalAttemptEvidence().gameSessionOwnerMutationId())
        .isEqualTo(GAME_SESSION_MUTATION_ID);
    assertThat(acquired.originalAttemptEvidence().originalResponseBytes()).isNotEmpty();
    assertThat(harness.steps)
        .containsExactly(
            "remote-read",
            "issuer-currentness",
            "participation-write",
            "proof-write",
            "participation-readback",
            "proof-readback");
    assertThat(harness.readbackTransactions.begins).isEqualTo(1);
    assertThat(harness.readbackTransactions.commits).isEqualTo(1);
    verify(harness.gameSessionClient).read(any(Request.class));
    verify(harness.issuer)
        .withCurrentWorldReceivingParticipationCurrentness(
            eq(harness.tuple.canonicalBytes()),
            eq(GAME_SESSION_ATTEMPT_ID),
            eq(GAME_SESSION_FENCE),
            any());
    verify(harness.participationRepository).createOrReadExact(any(Candidate.class));
    verify(harness.attemptEvidenceRepository)
        .retainCurrentExact(any(StoredParticipation.class), any(Result.class));
    verify(harness.participationRepository).findCurrentExact(any(Candidate.class));
    verify(harness.attemptEvidenceRepository)
        .findCurrentExact(any(StoredParticipation.class), any(Request.class));
  }

  @Test
  void retriesTheSameOriginalTupleWithFreshGameSessionReadsAndCurrentAccountChecks() {
    Harness harness = new Harness();
    harness.configureSuccess();
    var originalRequest = request(null);

    AcquiredParticipation first = authorized(() -> harness.service.acquire(originalRequest));
    AcquiredParticipation second = authorized(() -> harness.service.acquire(originalRequest));

    ArgumentCaptor<Request> requests = ArgumentCaptor.forClass(Request.class);
    verify(harness.gameSessionClient, times(2)).read(requests.capture());
    assertThat(requests.getAllValues()).hasSize(2);
    assertThat(requests.getAllValues().get(0).readRequestId())
        .isNotEqualTo(requests.getAllValues().get(1).readRequestId());
    for (Request read : requests.getAllValues()) {
      assertThat(read.targetNamespace()).isEqualTo(NAMESPACE);
      assertThat(read.canonicalPostAuthorizationTuple())
          .containsExactly(harness.tuple.canonicalBytes());
      assertThat(read.expectedOwnerMutationId()).isEqualTo(GAME_SESSION_MUTATION_ID);
      assertThat(read.expectedOwnerAttemptId()).isEqualTo(GAME_SESSION_ATTEMPT_ID);
      assertThat(read.expectedOwnerFence()).isEqualTo(GAME_SESSION_FENCE);
    }
    assertThat(first.participation().participationId())
        .isEqualTo(second.participation().participationId());
    assertThat(first.originalAttemptEvidence().originalResponseBytes())
        .containsExactly(second.originalAttemptEvidence().originalResponseBytes());
    assertThat(harness.readbackTransactions.begins).isEqualTo(2);
    assertThat(harness.readbackTransactions.commits).isEqualTo(2);
    assertThat(harness.steps)
        .containsExactly(
            "remote-read",
            "issuer-currentness",
            "participation-write",
            "proof-write",
            "participation-readback",
            "proof-readback",
            "remote-read",
            "issuer-currentness",
            "participation-write",
            "proof-write",
            "participation-readback",
            "proof-readback");
  }

  @Test
  void rejectsChangedAccountRedemptionProjectionBeforeCurrentnessOrWrites() {
    Harness harness = new Harness();
    harness.configureClientResult(projection(harness.tuple, Map.of("unbound", "changed")));

    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> authorized(() -> harness.service.acquire(request(null))));

    assertThat(harness.steps).containsExactly("remote-read");
    verify(harness.issuer, times(0))
        .withCurrentWorldReceivingParticipationCurrentness(
            any(byte[].class), eq(GAME_SESSION_ATTEMPT_ID), eq(GAME_SESSION_FENCE), any());
    verifyNoInteractions(harness.participationRepository);
    verifyNoInteractions(harness.attemptEvidenceRepository);
    assertThat(harness.readbackTransactions.begins).isZero();
  }

  @Test
  void rejectsChangedCommittedSourceVectorDuringIndependentReadbackWithoutRetryWrite() {
    Harness harness = new Harness();
    harness.configureSuccess();
    harness.participationReadback.set(
        participation(harness.tuple, List.of(sourceEvidence("different source"))));

    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> authorized(() -> harness.service.acquire(request(null))));

    assertThat(harness.readbackTransactions.begins).isEqualTo(1);
    // The lookup-only transaction completes before the exact source-vector comparison denies.
    assertThat(harness.readbackTransactions.commits).isEqualTo(1);
    verify(harness.participationRepository, times(1)).createOrReadExact(any(Candidate.class));
    verify(harness.participationRepository, times(1)).findCurrentExact(any(Candidate.class));
  }

  @Test
  void missingPostCommitReadbackDeniesAndDoesNotRepeatTheParticipationWrite() {
    Harness harness = new Harness();
    harness.configureSuccess();
    harness.participationMissingOnReadback = true;

    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> authorized(() -> harness.service.acquire(request(null))));

    assertThat(harness.readbackTransactions.begins).isEqualTo(1);
    assertThat(harness.readbackTransactions.rollbacks).isEqualTo(1);
    verify(harness.participationRepository, times(1)).createOrReadExact(any(Candidate.class));
    verify(harness.participationRepository, times(1)).findCurrentExact(any(Candidate.class));
    verify(harness.attemptEvidenceRepository, times(1))
        .retainCurrentExact(any(StoredParticipation.class), any(Result.class));
    verify(harness.attemptEvidenceRepository, times(0))
        .findCurrentExact(any(StoredParticipation.class), any(Request.class));
  }

  private static AcquisitionRequest request(byte[] tupleBytes) {
    StartSessionPostAuthorizationExecutionTuple tuple = originalTuple();
    return new AcquisitionRequest(
        tupleBytes == null ? tuple.canonicalBytes() : tupleBytes,
        GAME_SESSION_MUTATION_ID,
        GAME_SESSION_ATTEMPT_ID,
        GAME_SESSION_FENCE,
        GAME_INSTANCE_ID,
        PREPARATION_INPUT,
        digest(PREPARATION_INPUT));
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple() {
    UUID targetOwnerId = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
    UUID reservationOwnerId = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
    String requestId = "world-participation-original-request";
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            requestId,
            ACCOUNT_ID,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT_ID, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, targetOwnerId),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "synthetic World participation composition proof"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        reservationOwnerId,
        19L,
        authorityBundle(preTuple),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    String actorId = tuple.actor().accountId().toString();
    String tenantId = tuple.action().scope().tenantId().toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-10T00:00:00Z");
    Map<String, Object> operation =
        Map.of(
            "issuanceOperationId", "f5d044bd-7e5f-4e2d-9859-9025cbdcc60f",
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(tenantId, 3L),
            "membershipAuthorityGeneration", Map.of(tenantId, 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> human =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", actorId,
            "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
            "role", "tenantAdmin",
            "accountGeneration", "2",
            "tenantGeneration", "3");
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope",
                Map.of("tenantId", tenantId, "targetNamespace", NAMESPACE),
                "actionFamily",
                tuple.actionFamily(),
                "applicableAccountId",
                actorId,
                "applicableTenantId",
                tenantId),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operation,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            human);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] projection(
      StartSessionPostAuthorizationExecutionTuple tuple, Map<String, Object> additionalFields) {
    var bundle = StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
    Map<String, Object> projection =
        Map.of(
            "projectionSchemaId", "accountStartSessionRedemptionProjection",
            "projectionSchemaVersion", "1",
            "authorizationReferenceFingerprint", tuple.authorizationReferenceFingerprint(),
            "authorityEvidenceBundle", bundle.jsonValue(),
            "issuanceOperationId", bundle.issuanceOperationId().toString(),
            "issuanceFence", Long.parseLong(tuple.issuanceFence()));
    if (additionalFields.isEmpty()) {
      try {
        return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(projection));
      } catch (java.io.IOException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
    var changed = new java.util.LinkedHashMap<>(projection);
    changed.putAll(additionalFields);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(changed));
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static AccountStartSessionAuthorityCapture capture(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    SourceEvidence source = sourceEvidence("current source");
    var bundle = StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
    byte[] signerReceipt = "synthetic-signer-receipt".getBytes(StandardCharsets.UTF_8);
    Map<String, Object> snapshot =
        Map.ofEntries(
            Map.entry("schema", "account-start-session-authority-snapshot/v1"),
            Map.entry("controlPlaneRequestId", tuple.controlPlaneRequestId()),
            Map.entry(
                "preAuthorizationTuple",
                Base64.getEncoder()
                    .encodeToString(
                        tuple
                            .preAuthorizationTuple()
                            .canonicalJson()
                            .getBytes(StandardCharsets.UTF_8))),
            Map.entry("mutationDigest", tuple.mutationDigest()),
            Map.entry("accountId", tuple.preAuthorizationTuple().actor().accountId().toString()),
            Map.entry(
                "tenantId", tuple.preAuthorizationTuple().action().scope().tenantId().toString()),
            Map.entry("targetOwner", tuple.preAuthorizationTuple().targetOwner()),
            Map.entry("loggingWorkloadUri", tuple.authenticatedWorkloadIdentity()),
            Map.entry("reservationOwnerId", tuple.reservationOwnerId().toString()),
            Map.entry("reservationClaimFence", Long.toString(tuple.reservationClaimFence())),
            Map.entry("controlUiOperationId", bundle.issuanceOperationId().toString()),
            Map.entry("controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc"),
            Map.entry("controlUiTokenHash", "sha256:" + "d".repeat(64)),
            Map.entry("controlUiSignerReceipt", Base64.getEncoder().encodeToString(signerReceipt)),
            Map.entry("controlUiSignerReceiptSha256", sha256(signerReceipt)),
            Map.entry(
                "sourceVectorEvidence",
                Base64.getEncoder().encodeToString(source.canonicalBytes())),
            Map.entry(
                "sourceVector",
                List.of(Base64.getEncoder().encodeToString(source.canonicalBytes()))),
            Map.entry("outboxCheckpoints", Map.of()),
            Map.entry("authorityTuple", bundle.authorityTuple()),
            Map.entry("membershipVersion", bundle.membershipVersion()),
            Map.entry("accountIdentitySource", "ACCOUNT_UUID"),
            Map.entry("issuanceFence", tuple.issuanceFence()),
            Map.entry("issuanceFenceSourceVersion", "17"));
    try {
      return AccountStartSessionAuthorityCapture.create(
          tuple.controlPlaneRequestId(),
          17L,
          31L,
          "123456",
          "2026-10-09T00:00:00.000Z",
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(snapshot)));
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static SourceEvidence sourceEvidence(String detail) {
    return new SourceEvidence(
        SourceKind.ACCOUNT,
        ACCOUNT_ID.toString(),
        "2",
        "17",
        null,
        null,
        detail.getBytes(StandardCharsets.UTF_8));
  }

  private static StoredParticipation participation(
      StartSessionPostAuthorizationExecutionTuple tuple, List<SourceEvidence> sources) {
    return new StoredParticipation(
        PARTICIPATION_ID,
        PARTICIPATION_FENCE,
        tuple.controlPlaneRequestId(),
        tuple.canonicalBytes(),
        NAMESPACE,
        TENANT_ID,
        GAME_INSTANCE_ID,
        GAME_SESSION_ATTEMPT_ID,
        GAME_SESSION_FENCE,
        PREPARATION_INPUT,
        digest(PREPARATION_INPUT),
        987L,
        OffsetDateTime.parse("2026-10-09T00:00:00Z"),
        sources);
  }

  private static SourceEvidence participationSource(SourceEvidence source) {
    return SourceEvidence.fromStored(source.canonicalBytes());
  }

  private static String digest(String value) {
    return "sha256:"
        + HexFormat.of().formatHex(sha256Bytes(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] sha256Bytes(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String sha256(byte[] value) {
    return HexFormat.of().formatHex(sha256Bytes(value));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static <T> T authorized(java.util.function.Supplier<T> action) {
    return peer(NAMESPACE, "world-management-service", action);
  }

  private static <T> T peer(
      String namespace, String workload, java.util.function.Supplier<T> action) {
    var context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                    .orElseThrow());
    var prior = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(prior);
    }
  }

  private static void assertNoSql() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  private static void assertWritableReadCommittedTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  private static void assertCode(Status.Code code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .satisfies(failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(code));
  }

  private static final class Harness {
    final AccountStartSessionOperatorAuthorizationService issuer =
        mock(AccountStartSessionOperatorAuthorizationService.class);
    final AccountStartSessionWorldParticipationRepository participationRepository =
        mock(AccountStartSessionWorldParticipationRepository.class);
    final AccountStartSessionWorldOriginalAttemptEvidenceRepository attemptEvidenceRepository =
        mock(AccountStartSessionWorldOriginalAttemptEvidenceRepository.class);
    final OriginalStartSessionCurrentAttemptClient gameSessionClient =
        mock(OriginalStartSessionCurrentAttemptClient.class);
    final OwnerTransactions readbackTransactions = new OwnerTransactions();
    final StartSessionPostAuthorizationExecutionTuple tuple = originalTuple();
    final StoredParticipation committedParticipation =
        participation(tuple, List.of(participationSource(sourceEvidence("current source"))));
    final AtomicReference<StoredParticipation> participationReadback =
        new AtomicReference<>(committedParticipation);
    final AtomicReference<StoredEvidence> retainedProof = new AtomicReference<>();
    final List<String> steps = new ArrayList<>();
    final AccountStartSessionWorldParticipationAcquisitionService service =
        new AccountStartSessionWorldParticipationAcquisitionService(
            issuer,
            participationRepository,
            attemptEvidenceRepository,
            gameSessionClient,
            readbackTransactions,
            NAMESPACE);
    boolean participationMissingOnReadback;

    void configureSuccess() {
      configureClientResult(projection(tuple, Map.of()));
      doAnswer(
              invocation -> {
                assertNoSql();
                steps.add("issuer-currentness");
                @SuppressWarnings("unchecked")
                Function<AccountStartSessionAuthorityCapture, Object> callback =
                    invocation.getArgument(3);
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                    TransactionDefinition.ISOLATION_READ_COMMITTED);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
                TransactionSynchronizationManager.initSynchronization();
                try {
                  return callback.apply(capture(tuple));
                } finally {
                  TransactionSynchronizationManager.clear();
                }
              })
          .when(issuer)
          .withCurrentWorldReceivingParticipationCurrentness(
              any(byte[].class), eq(GAME_SESSION_ATTEMPT_ID), eq(GAME_SESSION_FENCE), any());
      when(participationRepository.createOrReadExact(any(Candidate.class)))
          .thenAnswer(
              invocation -> {
                assertWritableReadCommittedTransaction();
                steps.add("participation-write");
                return committedParticipation;
              });
      when(attemptEvidenceRepository.retainCurrentExact(
              any(StoredParticipation.class), any(Result.class)))
          .thenAnswer(
              invocation -> {
                assertWritableReadCommittedTransaction();
                steps.add("proof-write");
                StoredParticipation exactParticipation = invocation.getArgument(0);
                Result observation = invocation.getArgument(1);
                StoredEvidence existing = retainedProof.get();
                if (existing != null) return existing;
                StoredEvidence created = storedEvidence(exactParticipation, observation);
                retainedProof.set(created);
                return created;
              });
      when(participationRepository.findCurrentExact(any(Candidate.class)))
          .thenAnswer(
              invocation -> {
                assertWritableReadCommittedTransaction();
                steps.add("participation-readback");
                return participationMissingOnReadback
                    ? Optional.empty()
                    : Optional.of(participationReadback.get());
              });
      when(attemptEvidenceRepository.findCurrentExact(
              any(StoredParticipation.class), any(Request.class)))
          .thenAnswer(
              invocation -> {
                assertWritableReadCommittedTransaction();
                steps.add("proof-readback");
                return Optional.ofNullable(retainedProof.get());
              });
    }

    void configureClientResult(byte[] projectionBytes) {
      when(gameSessionClient.read(any(Request.class)))
          .thenAnswer(
              invocation -> {
                assertNoSql();
                steps.add("remote-read");
                Request read = invocation.getArgument(0);
                return new Result(read, ORIGINAL_LEASE_EXPIRY, projectionBytes);
              });
    }
  }

  private static StoredEvidence storedEvidence(
      StoredParticipation participation, Result observation) {
    byte[] responseBytes =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(observation).toByteArray();
    return new StoredEvidence(
        participation.participationId(),
        GAME_SESSION_MUTATION_ID,
        observation.originalLeaseExpiresAt(),
        responseBytes,
        "sha256:" + sha256(responseBytes),
        observation);
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
      assertNoSql();
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
