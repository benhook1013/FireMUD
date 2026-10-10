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
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
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
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic composition proof; Game Session and Account storage remain explicit mocks. */
class AccountStartSessionAdmissionProtectionAcquisitionServiceTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID ACCOUNT_ID = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TENANT_ID = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID PARTICIPATION_ID = uuid("a8c1e8c8-f237-41b7-918d-ec2281bcac10");
  private static final UUID GAME_SESSION_MUTATION_ID = uuid("b9d2f9d9-0438-42c8-829e-fd3392cd9d21");
  private static final UUID GAME_SESSION_ATTEMPT_ID = uuid("4a3fc69f-a18a-4b94-b1d1-2e8b4c3f0e4b");
  private static final UUID GAME_INSTANCE_ID = uuid("c0e30aea-1549-43d9-93af-0e44a3deae32");
  private static final UUID PROTECTION_ID = uuid("9e180ae4-ea1b-42cf-8e61-a49a112a95d5");
  private static final UUID DIFFERENT_PROTECTION_ID = uuid("b833e0a3-e6e9-43ed-846a-a0a2c39052a3");
  private static final long PARTICIPATION_FENCE = 31L;
  private static final long GAME_SESSION_FENCE = 37L;
  private static final long PROTECTION_FENCE = 43L;
  private static final String PREPARATION_INPUT = "{\"selected\":\"immutable source\"}";
  private static final Instant ORIGINAL_LEASE_EXPIRY = Instant.parse("2026-10-12T00:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesExactGameSessionPeerAndRejectsEndUserOrAmbientSqlBeforeInputProcessing() {
    Harness harness = new Harness();

    assertCode(Status.Code.UNAUTHENTICATED, () -> harness.service.acquire(null));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> peer(NAMESPACE, "world-management-service", () -> harness.service.acquire(null)));
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> peer("other-runtime", "game-session-service", () -> harness.service.acquire(null)));
    SessionContext.setContext("123", List.of(), Map.of());
    assertCode(
        Status.Code.PERMISSION_DENIED,
        () -> peer(NAMESPACE, "game-session-service", () -> harness.service.acquire(null)));
    SessionContext.clear();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> peer(NAMESPACE, "game-session-service", () -> harness.service.acquire(null)));

    verifyNoInteractions(harness.issuer);
    verifyNoInteractions(harness.participationRepository);
    verifyNoInteractions(harness.attemptEvidenceRepository);
    verifyNoInteractions(harness.protectionRepository);
    verifyNoInteractions(harness.gameSessionClient);
    assertThat(harness.readbackTransactions.begins).isZero();
  }

  @Test
  void changedCompleteAccountProjectionDeniesBeforeCurrentnessOrProtectionWrite() {
    Harness harness = new Harness();
    harness.configureClientResult("substituted projection".getBytes(StandardCharsets.UTF_8));

    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> authorized(() -> harness.service.acquire(harness.request)));

    assertThat(harness.steps).containsExactly("remote-read");
    verifyNoInteractions(harness.issuer);
    verifyNoInteractions(harness.protectionRepository);
    assertThat(harness.readbackTransactions.begins).isZero();
  }

  @Test
  void comparesFreshObservationWithHistoricalWorldEvidenceInsideCurrentnessCallback() {
    Harness harness = new Harness();
    harness.configureSuccess();
    AccountStartSessionAdmissionProtectionEvidence acquired =
        authorized(() -> harness.service.acquire(harness.request));

    assertThat(acquired.accountProtectionId()).isEqualTo(PROTECTION_ID);
    assertThat(acquired.accountProtectionFence()).isEqualTo(PROTECTION_FENCE);
    assertThat(acquired.request().accountWorldParticipationId()).isEqualTo(PARTICIPATION_ID);
    assertThat(acquired.request().gameSessionOwnerMutationId()).isEqualTo(GAME_SESSION_MUTATION_ID);
    assertThat(harness.steps)
        .containsExactly(
            "remote-read",
            "issuer-currentness",
            "world-history",
            "attempt-history",
            "protection-write-or-exact-read",
            "protection-readback");
    assertThat(harness.readbackTransactions.begins).isEqualTo(1);
    assertThat(harness.readbackTransactions.commits).isEqualTo(1);
    verify(harness.issuer)
        .withCurrentGameSessionAdmissionProtectionCurrentness(
            eq(harness.tuple.canonicalBytes()),
            eq(GAME_SESSION_ATTEMPT_ID),
            eq(GAME_SESSION_FENCE),
            any());
    verify(harness.participationRepository)
        .findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE);
    verify(harness.attemptEvidenceRepository)
        .findHistoricalExact(any(StoredParticipation.class), any(Request.class));
    verify(harness.participationRepository, times(0)).findCurrentExact(any());
  }

  @Test
  void changedOriginalLeaseExpiryDeniesBeforeCreatingProtection() {
    Harness harness = new Harness();
    harness.configureSuccess();
    harness.configureClientResult(projection(harness.tuple), ORIGINAL_LEASE_EXPIRY.plusMillis(1));

    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> authorized(() -> harness.service.acquire(harness.request)));

    assertThat(harness.steps)
        .containsExactly("remote-read", "issuer-currentness", "world-history", "attempt-history");
    verifyNoInteractions(harness.protectionRepository);
    assertThat(harness.readbackTransactions.begins).isZero();
  }

  @Test
  void missingIndependentReadbackDeniesWithoutRetryingTheCreatePath() {
    Harness harness = new Harness();
    harness.configureSuccess();
    harness.protectionMissingOnReadback = true;

    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> authorized(() -> harness.service.acquire(harness.request)));

    assertThat(harness.steps)
        .containsExactly(
            "remote-read",
            "issuer-currentness",
            "world-history",
            "attempt-history",
            "protection-write-or-exact-read",
            "protection-readback");
    verify(harness.protectionRepository, times(1)).createOrReadExact(any(), any());
    verify(harness.protectionRepository, times(1)).findCurrentExact(any());
    assertThat(harness.readbackTransactions.begins).isEqualTo(1);
  }

  @Test
  void lostReadbackAckRetryRechecksCurrentnessAndReturnsTheOriginalIdentityAndExpiry() {
    Harness harness = new Harness();
    harness.configureSuccess();
    harness.missingReadbacksRemaining = 1;

    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> authorized(() -> harness.service.acquire(harness.request)));
    AccountStartSessionAdmissionProtectionEvidence committedButUnacknowledged =
        harness.retainedProtection.get();
    assertThat(committedButUnacknowledged).isNotNull();
    assertThat(harness.readbackTransactions.begins).isEqualTo(1);
    assertThat(harness.readbackTransactions.commits).isZero();
    assertThat(harness.readbackTransactions.rollbacks).isEqualTo(1);

    AccountStartSessionAdmissionProtectionEvidence retry =
        authorized(() -> harness.service.acquire(harness.request));

    assertThat(retry.canonicalBytes()).containsExactly(committedButUnacknowledged.canonicalBytes());
    assertThat(retry.accountProtectionId()).isEqualTo(PROTECTION_ID);
    assertThat(retry.request().originalLeaseExpiresAt()).isEqualTo(ORIGINAL_LEASE_EXPIRY);
    ArgumentCaptor<Request> currentReads = ArgumentCaptor.forClass(Request.class);
    verify(harness.gameSessionClient, times(2)).read(currentReads.capture());
    assertThat(currentReads.getAllValues().get(0).readRequestId())
        .isNotEqualTo(currentReads.getAllValues().get(1).readRequestId());
    ArgumentCaptor<AccountStartSessionAdmissionProtectionRequest> protectionRequests =
        ArgumentCaptor.forClass(AccountStartSessionAdmissionProtectionRequest.class);
    verify(harness.protectionRepository, times(2))
        .createOrReadExact(
            protectionRequests.capture(), any(AccountStartSessionAuthorityCapture.class));
    assertThat(protectionRequests.getAllValues().get(0).canonicalBytes())
        .containsExactly(protectionRequests.getAllValues().get(1).canonicalBytes());
    assertThat(protectionRequests.getAllValues())
        .allSatisfy(
            value -> assertThat(value.originalLeaseExpiresAt()).isEqualTo(ORIGINAL_LEASE_EXPIRY));
    assertThat(harness.steps)
        .containsExactly(
            "remote-read",
            "issuer-currentness",
            "world-history",
            "attempt-history",
            "protection-write-or-exact-read",
            "protection-readback",
            "remote-read",
            "issuer-currentness",
            "world-history",
            "attempt-history",
            "protection-write-or-exact-read",
            "protection-readback");
    assertThat(harness.readbackTransactions.begins).isEqualTo(2);
    assertThat(harness.readbackTransactions.commits).isEqualTo(1);
    assertThat(harness.readbackTransactions.rollbacks).isEqualTo(1);
  }

  @Test
  void changedCompleteReadbackIsRejectedWithoutRepeatingTheCreatePath() {
    Harness harness = new Harness();
    harness.configureSuccess();
    harness.changedReadbackIdentity = true;

    assertCode(
        Status.Code.FAILED_PRECONDITION,
        () -> authorized(() -> harness.service.acquire(harness.request)));

    assertThat(harness.steps)
        .containsExactly(
            "remote-read",
            "issuer-currentness",
            "world-history",
            "attempt-history",
            "protection-write-or-exact-read",
            "protection-readback");
    verify(harness.protectionRepository, times(1)).createOrReadExact(any(), any());
    verify(harness.protectionRepository, times(1)).findCurrentExact(any());
    assertThat(harness.readbackTransactions.begins).isEqualTo(1);
    assertThat(harness.readbackTransactions.commits).isEqualTo(1);
    assertThat(harness.readbackTransactions.rollbacks).isZero();
  }

  @Test
  void exactRetryGetsFreshCurrentnessBeforeLookupAndKeepsTheOriginalProtectionIdentity() {
    Harness harness = new Harness();
    harness.configureSuccess();

    AccountStartSessionAdmissionProtectionEvidence first =
        authorized(() -> harness.service.acquire(harness.request));
    AccountStartSessionAdmissionProtectionEvidence retry =
        authorized(() -> harness.service.acquire(harness.request));

    ArgumentCaptor<Request> reads = ArgumentCaptor.forClass(Request.class);
    verify(harness.gameSessionClient, times(2)).read(reads.capture());
    assertThat(reads.getAllValues()).hasSize(2);
    assertThat(reads.getAllValues().get(0).readRequestId())
        .isNotEqualTo(reads.getAllValues().get(1).readRequestId());
    assertThat(first.accountProtectionId()).isEqualTo(retry.accountProtectionId());
    assertThat(first.accountProtectionFence()).isEqualTo(retry.accountProtectionFence());
    assertThat(first.canonicalBytes()).containsExactly(retry.canonicalBytes());
    assertThat(harness.steps)
        .containsExactly(
            "remote-read",
            "issuer-currentness",
            "world-history",
            "attempt-history",
            "protection-write-or-exact-read",
            "protection-readback",
            "remote-read",
            "issuer-currentness",
            "world-history",
            "attempt-history",
            "protection-write-or-exact-read",
            "protection-readback");
    verify(harness.protectionRepository, times(2)).createOrReadExact(any(), any());
    verify(harness.protectionRepository, times(2)).findCurrentExact(any());
    assertThat(harness.readbackTransactions.begins).isEqualTo(2);
    assertThat(harness.readbackTransactions.commits).isEqualTo(2);
  }

  private static byte[] projection(StartSessionPostAuthorizationExecutionTuple tuple) {
    return net.firedevops.firemud.common.account.startsession
        .StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple);
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple() {
    UUID targetOwnerId = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
    UUID reservationOwnerId = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
    String requestId = "admission-protection-original-request";
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
                "synthetic Account admission-protection proof"));
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

  private static WorldCanonicalInitialAdmissionHold.HoldIdentity holdIdentity() {
    WorldCanonicalInitialAdmissionHold.Request request =
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            TENANT_ID,
            "admission-world",
            uuid("5d9e714d-f9be-4e7c-8e32-2f740e4caf95"),
            uuid("64366775-dfea-4710-a168-1fd618bb0be8"),
            "SHARED",
            GAME_INSTANCE_ID,
            uuid("ea2bb613-dbc7-4a7f-9e27-202b4189a104"),
            1L,
            "distinct-world-admission-request-id",
            "b".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1L,
            null);
    return new WorldCanonicalInitialAdmissionHold.HoldIdentity(
        request,
        uuid("6af87b1d-d2c4-4a8e-ae39-bf815d4e257a"),
        uuid("72d2d148-0831-4ca3-85b0-6db4ed8a5673"));
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
          Long.parseLong(tuple.bundleReference().sourceVersion()),
          Long.parseLong(tuple.bundleReference().sourceFence()),
          tuple.bundleReference().linearization(),
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
      StartSessionPostAuthorizationExecutionTuple tuple) {
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
        List.of(sourceEvidence("current source")));
  }

  private static StoredEvidence previousObservation(
      StoredParticipation participation,
      StartSessionPostAuthorizationExecutionTuple tuple,
      Instant expiry) {
    Request originalRead =
        new Request(
            uuid("dfdaaf5d-4af0-4a25-9015-b55a328e4d70"),
            NAMESPACE,
            tuple.canonicalBytes(),
            GAME_SESSION_ATTEMPT_ID,
            GAME_SESSION_MUTATION_ID,
            GAME_SESSION_FENCE);
    Result result = new Result(originalRead, expiry, projection(tuple));
    byte[] response =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(result).toByteArray();
    return new StoredEvidence(
        participation.participationId(),
        GAME_SESSION_MUTATION_ID,
        expiry,
        response,
        "sha256:" + sha256(response),
        result);
  }

  private static String digest(String value) {
    return "sha256:" + sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static <T> T authorized(java.util.function.Supplier<T> action) {
    return peer(NAMESPACE, "game-session-service", action);
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
    final AccountStartSessionAdmissionProtectionRepository protectionRepository =
        mock(AccountStartSessionAdmissionProtectionRepository.class);
    final OriginalStartSessionCurrentAttemptClient gameSessionClient =
        mock(OriginalStartSessionCurrentAttemptClient.class);
    final OwnerTransactions readbackTransactions = new OwnerTransactions();
    final StartSessionPostAuthorizationExecutionTuple tuple = originalTuple();
    final WorldCanonicalInitialAdmissionHold.HoldIdentity hold = holdIdentity();
    final StoredParticipation worldParticipation = participation(tuple);
    final StoredEvidence previousEvidence =
        previousObservation(worldParticipation, tuple, ORIGINAL_LEASE_EXPIRY);
    final AtomicReference<AccountStartSessionAdmissionProtectionEvidence> retainedProtection =
        new AtomicReference<>();
    final List<String> steps = new ArrayList<>();
    final AccountStartSessionAdmissionProtectionAcquisitionService.AcquisitionRequest request =
        new AccountStartSessionAdmissionProtectionAcquisitionService.AcquisitionRequest(
            tuple.canonicalBytes(),
            GAME_SESSION_MUTATION_ID,
            GAME_SESSION_ATTEMPT_ID,
            GAME_SESSION_FENCE,
            PARTICIPATION_ID,
            PARTICIPATION_FENCE,
            hold);
    final AccountStartSessionAdmissionProtectionAcquisitionService service =
        new AccountStartSessionAdmissionProtectionAcquisitionService(
            issuer,
            participationRepository,
            attemptEvidenceRepository,
            protectionRepository,
            gameSessionClient,
            readbackTransactions,
            NAMESPACE);
    boolean protectionMissingOnReadback;
    int missingReadbacksRemaining;
    boolean changedReadbackIdentity;

    void configureSuccess() {
      configureClientResult(projection(tuple), ORIGINAL_LEASE_EXPIRY);
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
          .withCurrentGameSessionAdmissionProtectionCurrentness(
              any(byte[].class), eq(GAME_SESSION_ATTEMPT_ID), eq(GAME_SESSION_FENCE), any());
      when(participationRepository.findHistoricalExact(PARTICIPATION_ID, PARTICIPATION_FENCE))
          .thenAnswer(
              ignored -> {
                assertWritableReadCommittedTransaction();
                steps.add("world-history");
                return Optional.of(worldParticipation);
              });
      when(attemptEvidenceRepository.findHistoricalExact(
              any(StoredParticipation.class), any(Request.class)))
          .thenAnswer(
              ignored -> {
                assertWritableReadCommittedTransaction();
                steps.add("attempt-history");
                return Optional.of(previousEvidence);
              });
      when(protectionRepository.createOrReadExact(any(), any()))
          .thenAnswer(
              invocation -> {
                assertWritableReadCommittedTransaction();
                steps.add("protection-write-or-exact-read");
                if (retainedProtection.get() == null) {
                  var exactRequest =
                      invocation
                          .<net.firedevops.firemud.common.account.startsession
                                  .AccountStartSessionAdmissionProtectionRequest>
                              getArgument(0);
                  AccountStartSessionAuthorityCapture exactCapture = invocation.getArgument(1);
                  retainedProtection.set(
                      AccountStartSessionAdmissionProtectionEvidence.create(
                          exactRequest,
                          PROTECTION_ID,
                          PROTECTION_FENCE,
                          exactCapture.canonicalBytes(),
                          exactCapture.canonicalSha256(),
                          List.of(sourceEvidence("current source"))));
                }
                return retainedProtection.get();
              });
      when(protectionRepository.findCurrentExact(any()))
          .thenAnswer(
              ignored -> {
                assertWritableReadCommittedTransaction();
                steps.add("protection-readback");
                if (protectionMissingOnReadback || missingReadbacksRemaining > 0) {
                  if (missingReadbacksRemaining > 0) missingReadbacksRemaining--;
                  return Optional.empty();
                }
                AccountStartSessionAdmissionProtectionEvidence stored = retainedProtection.get();
                if (stored == null) return Optional.empty();
                return Optional.of(
                    changedReadbackIdentity
                        ? AccountStartSessionAdmissionProtectionEvidence.create(
                            stored.request(),
                            DIFFERENT_PROTECTION_ID,
                            PROTECTION_FENCE + 1,
                            stored.originalSourceCaptureReferenceBytes(),
                            stored.originalSourceCaptureReferenceSha256(),
                            stored.sourceEvidenceVector())
                        : stored);
              });
    }

    void configureClientResult(byte[] projectionBytes) {
      configureClientResult(projectionBytes, ORIGINAL_LEASE_EXPIRY);
    }

    void configureClientResult(byte[] projectionBytes, Instant expiry) {
      when(gameSessionClient.read(any(Request.class)))
          .thenAnswer(
              invocation -> {
                assertNoSql();
                steps.add("remote-read");
                Request read = invocation.getArgument(0);
                return new Result(read, expiry, projectionBytes);
              });
    }
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
