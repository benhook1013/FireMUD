package net.firedevops.firemud.loggingadmin.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Acquisition;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.AuthorizedSnapshot;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimState;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.OwnerExecutionHandoff;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Phase;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.RecoveryAcquisition;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Snapshot;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.State;
import net.firedevops.firemud.loggingadmin.repository.StartSessionPreAuthorizationReservationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.json.JsonMapper;

class StartSessionPreAuthorizationReservationServiceTest {
  private static final long NOW_EPOCH_MILLIS = 1_800_000_000_000L;
  private static final UUID TENANT_ID = UUID.fromString("3d3c5ca5-6d43-4db6-821d-8a0de883a467");
  private static final UUID ACTOR_ID = UUID.fromString("863843ee-f00a-4905-a9ad-11f706f1b693");
  private static final UUID ISSUANCE_ID = UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private final StartSessionPreAuthorizationReservationRepository repository =
      mock(StartSessionPreAuthorizationReservationRepository.class);
  private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW_EPOCH_MILLIS), ZoneOffset.UTC);
  private final StartSessionPreAuthorizationReservationService service =
      new StartSessionPreAuthorizationReservationService(repository, clock);

  @AfterEach
  void clearAuthenticatedContext() {
    SessionContext.clear();
  }

  @Test
  void firstAcquisitionGetsOneBoundedClaimAndExactDuplicateGetsNoClaim() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("service-unit-01");
    Snapshot initial = snapshot(tuple, State.RESERVED, 1L, 1L, ClaimState.ACTIVE);
    when(repository.acquire(any(), any(), eq(NOW_EPOCH_MILLIS), eq(NOW_EPOCH_MILLIS + 30_000L)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(initial, true))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(initial, false));

    Acquisition first = service.acquire(tuple);
    Acquisition duplicate = service.acquire(tuple);

    assertThat(first.newlyAcquired()).isTrue();
    assertThat(first.claim()).isNotNull();
    assertThat(first.claim().claimFence()).isEqualTo(1L);
    assertThat(duplicate.newlyAcquired()).isFalse();
    assertThat(duplicate.claim()).isNull();
    verify(repository, org.mockito.Mockito.times(2))
        .acquire(any(), any(), eq(NOW_EPOCH_MILLIS), eq(NOW_EPOCH_MILLIS + 30_000L));
  }

  @Test
  void parsedTupleCannotBeUsedAsAnInitialReservationAuthorityAssertion() {
    StartSessionPreAuthorizationReservationTuple parsed =
        StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
            tuple("service-unit-02").canonicalJson());

    assertThat(parsed.isAuthorityDerived()).isFalse();
    assertThatThrownBy(() -> service.acquire(parsed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("actor derived");
  }

  @Test
  void expiredPendingClaimCanOnlyBeReacquiredAsReadOnlyRecoveryClaim() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("service-unit-03");
    Snapshot recovered = snapshot(tuple, State.AUTHORIZATION_PENDING, 1L, 3L, ClaimState.ACTIVE);
    when(repository.acquireRecoveryClaim(
            eq(tuple), any(), eq(NOW_EPOCH_MILLIS), eq(NOW_EPOCH_MILLIS + 30_000L)))
        .thenReturn(
            Optional.of(
                new StartSessionPreAuthorizationReservationRepository.RecoveryClaimResult(
                    recovered, UUID.fromString("6e0ef777-8661-4139-80eb-904b75de5860"))));

    Optional<RecoveryAcquisition> acquisition = service.acquireAuthorizationRecoveryClaim(tuple);

    assertThat(acquisition).isPresent();
    assertThat(acquisition.orElseThrow().claim().isRecoveryLookupOnly()).isTrue();
    assertThat(acquisition.orElseThrow().snapshot().reservationClaimFence()).isEqualTo(1L);
    assertThat(acquisition.orElseThrow().snapshot().claimFence()).isEqualTo(3L);
    assertThat(acquisition.orElseThrow().snapshot().state()).isEqualTo(State.AUTHORIZATION_PENDING);
    assertThat(acquisition.orElseThrow().claim().claimEvidence().reservationClaimFence())
        .isEqualTo(1L);
    assertThat(acquisition.orElseThrow().claim().claimEvidence().currentClaimFence()).isEqualTo(3L);
    assertThatThrownBy(() -> service.markAuthorizationPending(acquisition.orElseThrow().claim()))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);
  }

  @Test
  void currentClaimReadEchoesExactIssueAndRecoveryOwnershipWithoutMutation() {
    String requestId = "start-session/operator/βeta";
    UUID reservationOwnerId = UUID.fromString("3bacbd32-12e5-46d2-9504-51aab74052bd");
    UUID recoveryOwnerId = UUID.fromString("a7bf2688-9f75-4ad8-ab45-7f65c7adbd97");
    StartSessionPreAuthorizationReservationTuple tuple = tuple(requestId);
    Snapshot issueSnapshot =
        snapshot(tuple, State.AUTHORIZATION_PENDING, 1L, 1L, ClaimState.ACTIVE);
    Snapshot recoverySnapshot =
        snapshot(tuple, State.AUTHORIZATION_PENDING, 1L, 3L, ClaimState.ACTIVE);
    when(repository.readCurrentClaim(
            tuple, reservationOwnerId, 1L, reservationOwnerId, 1L, NOW_EPOCH_MILLIS))
        .thenReturn(
            Optional.of(
                new StartSessionPreAuthorizationReservationRepository.CurrentClaimResult(
                    issueSnapshot, reservationOwnerId, reservationOwnerId)));
    when(repository.readCurrentClaim(
            tuple, reservationOwnerId, 1L, recoveryOwnerId, 3L, NOW_EPOCH_MILLIS))
        .thenReturn(
            Optional.of(
                new StartSessionPreAuthorizationReservationRepository.CurrentClaimResult(
                    recoverySnapshot, reservationOwnerId, recoveryOwnerId)));

    var issue =
        service
            .readCurrentClaimEvidence(
                requestId,
                tuple,
                reservationOwnerId,
                1L,
                reservationOwnerId,
                1L,
                StartSessionPreAuthorizationReservationService.ReadPurpose.ISSUE)
            .orElseThrow();
    var recovery =
        service
            .readCurrentClaimEvidence(
                requestId,
                tuple,
                reservationOwnerId,
                1L,
                recoveryOwnerId,
                3L,
                StartSessionPreAuthorizationReservationService.ReadPurpose.RECOVER)
            .orElseThrow();

    assertThat(issue.snapshot().tuple().canonicalJson()).isEqualTo(tuple.canonicalJson());
    assertThat(issue.snapshot().mutationDigest()).isEqualTo(tuple.mutationDigest());
    assertThat(issue.reservationOwnerId()).isEqualTo(reservationOwnerId);
    assertThat(issue.currentClaimOwnerId()).isEqualTo(reservationOwnerId);
    assertThat(issue.snapshot().claimFence()).isEqualTo(1L);
    assertThat(issue.purpose())
        .isEqualTo(StartSessionPreAuthorizationReservationService.ReadPurpose.ISSUE);
    assertThat(issue.observedAtEpochMillis()).isEqualTo(NOW_EPOCH_MILLIS);
    assertThat(recovery.reservationOwnerId()).isEqualTo(reservationOwnerId);
    assertThat(recovery.currentClaimOwnerId()).isEqualTo(recoveryOwnerId);
    assertThat(recovery.snapshot().reservationClaimFence()).isEqualTo(1L);
    assertThat(recovery.snapshot().claimFence()).isEqualTo(3L);
    assertThat(recovery.purpose())
        .isEqualTo(StartSessionPreAuthorizationReservationService.ReadPurpose.RECOVER);
    verify(repository)
        .readCurrentClaim(tuple, reservationOwnerId, 1L, reservationOwnerId, 1L, NOW_EPOCH_MILLIS);
    verify(repository)
        .readCurrentClaim(tuple, reservationOwnerId, 1L, recoveryOwnerId, 3L, NOW_EPOCH_MILLIS);
  }

  @Test
  void currentClaimReadRejectsWrongPurposeFreshnessAndExpiredSnapshots() {
    String requestId = "start-session/operator/expiry";
    UUID reservationOwnerId = UUID.fromString("871ec253-4fe6-4e9f-9584-c74f9795f78a");
    UUID recoveryOwnerId = UUID.fromString("ec1e7980-e05a-4200-bde8-66cd314b0930");
    StartSessionPreAuthorizationReservationTuple tuple = tuple(requestId);

    assertThatThrownBy(
            () ->
                service.readCurrentClaimEvidence(
                    requestId,
                    tuple,
                    reservationOwnerId,
                    1L,
                    recoveryOwnerId,
                    3L,
                    StartSessionPreAuthorizationReservationService.ReadPurpose.ISSUE))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);
    assertThatThrownBy(
            () ->
                service.readCurrentClaimEvidence(
                    requestId,
                    tuple,
                    reservationOwnerId,
                    1L,
                    reservationOwnerId,
                    1L,
                    StartSessionPreAuthorizationReservationService.ReadPurpose.RECOVER))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);
    verifyNoInteractions(repository);

    Snapshot expired =
        new Snapshot(
            tuple,
            tuple.mutationDigest(),
            Phase.ACCOUNT_AUTHORIZATION,
            State.AUTHORIZATION_PENDING,
            1L,
            1L,
            NOW_EPOCH_MILLIS,
            ClaimState.ACTIVE);
    when(repository.readCurrentClaim(
            tuple, reservationOwnerId, 1L, reservationOwnerId, 1L, NOW_EPOCH_MILLIS))
        .thenReturn(
            Optional.of(
                new StartSessionPreAuthorizationReservationRepository.CurrentClaimResult(
                    expired, reservationOwnerId, reservationOwnerId)));

    assertThatThrownBy(
            () ->
                service.readCurrentClaimEvidence(
                    requestId,
                    tuple,
                    reservationOwnerId,
                    1L,
                    reservationOwnerId,
                    1L,
                    StartSessionPreAuthorizationReservationService.ReadPurpose.ISSUE))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);
  }

  @Test
  void durableAuthorizationAndOwnerTransitionExposeOnlyTheFirstHandoffForDispatch() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("service-post-authorization");
    Snapshot reserved = snapshot(tuple, State.RESERVED, 1L, 1L, ClaimState.ACTIVE);
    when(repository.acquire(any(), any(), eq(NOW_EPOCH_MILLIS), eq(NOW_EPOCH_MILLIS + 30_000L)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(reserved, true));
    Acquisition acquired = service.acquire(tuple);
    var claim = acquired.claim();
    UUID owner = claim.claimEvidence().reservationOwnerId();
    StartSessionPostAuthorizationExecutionTuple postTuple =
        postTuple(tuple, owner, Instant.ofEpochMilli(NOW_EPOCH_MILLIS + 300_000L));

    AuthorizedSnapshot authorizedSnapshot =
        new AuthorizedSnapshot(
            snapshot(tuple, State.AUTHORIZED, 1L, 1L, ClaimState.ACTIVE),
            postTuple,
            owner,
            owner,
            null);
    when(repository.completeAuthorization(
            eq(tuple),
            eq(tuple.mutationDigest()),
            eq(owner),
            eq(1L),
            eq(owner),
            eq(1L),
            eq(postTuple.canonicalJson()),
            eq(NOW_EPOCH_MILLIS)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AuthorizationTransitionResult(
                authorizedSnapshot, true));

    var completed = service.completeAuthorization(claim, postTuple);

    assertThat(completed.transitioned()).isTrue();
    assertThat(completed.snapshot().reservationSnapshot().state()).isEqualTo(State.AUTHORIZED);
    assertThat(completed.snapshot().postAuthorizationTuple().canonicalBytes())
        .containsExactly(postTuple.canonicalBytes());

    OwnerExecutionHandoff handoff =
        new OwnerExecutionHandoff(UUID.fromString("dcb77e1f-e82a-4e07-a74a-1a4c92d1640c"));
    AuthorizedSnapshot pendingSnapshot =
        new AuthorizedSnapshot(
            new Snapshot(
                tuple,
                tuple.mutationDigest(),
                Phase.OWNER_EXECUTION,
                State.OWNER_EXECUTION_PENDING,
                1L,
                1L,
                NOW_EPOCH_MILLIS + 30_000L,
                ClaimState.ACTIVE),
            postTuple,
            owner,
            owner,
            handoff);
    when(repository.beginOwnerExecution(
            eq(tuple),
            eq(tuple.mutationDigest()),
            eq(owner),
            eq(1L),
            eq(owner),
            eq(1L),
            eq(postTuple.canonicalJson()),
            eq(handoff.handoffId()),
            eq(NOW_EPOCH_MILLIS)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.OwnerExecutionTransitionResult(
                pendingSnapshot, true))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.OwnerExecutionTransitionResult(
                pendingSnapshot, false));

    var first = service.beginOwnerExecution(claim, postTuple, handoff);
    var duplicate = service.beginOwnerExecution(claim, postTuple, handoff);

    assertThat(first.mayDispatch()).isTrue();
    assertThat(first.snapshot().handoff()).isEqualTo(handoff);
    assertThat(duplicate.mayDispatch()).isFalse();
    verify(repository)
        .completeAuthorization(
            eq(tuple),
            eq(tuple.mutationDigest()),
            eq(owner),
            eq(1L),
            eq(owner),
            eq(1L),
            eq(postTuple.canonicalJson()),
            eq(NOW_EPOCH_MILLIS));
    verify(repository, org.mockito.Mockito.times(2))
        .beginOwnerExecution(
            eq(tuple),
            eq(tuple.mutationDigest()),
            eq(owner),
            eq(1L),
            eq(owner),
            eq(1L),
            eq(postTuple.canonicalJson()),
            eq(handoff.handoffId()),
            eq(NOW_EPOCH_MILLIS));
  }

  @Test
  void phaseQualifiedStateModelHasNoSyntheticTerminalOutcome() {
    assertThat(State.values())
        .containsExactly(
            State.RESERVED,
            State.AUTHORIZATION_PENDING,
            State.AUTHORIZED,
            State.OWNER_EXECUTION_PENDING);
    assertThat(Phase.values()).containsExactly(Phase.ACCOUNT_AUTHORIZATION, Phase.OWNER_EXECUTION);
  }

  @Test
  void annotationConfigContextSelectsRepositoryConstructorWithoutDatabase() {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.registerBean(StartSessionPreAuthorizationReservationRepository.class, () -> repository);
    context.register(StartSessionPreAuthorizationReservationService.class);

    try {
      context.refresh();

      assertThat(context.getBean(StartSessionPreAuthorizationReservationService.class)).isNotNull();
    } finally {
      context.close();
    }
  }

  private static Snapshot snapshot(
      StartSessionPreAuthorizationReservationTuple tuple,
      State state,
      long reservationFence,
      long currentFence,
      ClaimState claimState) {
    return new Snapshot(
        tuple,
        tuple.mutationDigest(),
        Phase.ACCOUNT_AUTHORIZATION,
        state,
        reservationFence,
        currentFence,
        NOW_EPOCH_MILLIS + 30_000L,
        claimState);
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(String requestId) {
    SessionContext.setContext(
        ACTOR_ID.toString(),
        List.of(),
        Map.of(TENANT_ID.toString(), List.of("tenantAdmin")),
        false,
        null,
        null);
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, "world-runtime"),
            new StartSessionOperatorAction.Target(
                17L, UUID.fromString("2a9a4d47-a544-4984-bbc4-8e85f84116e4")),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "unit-test StartSession prerequisite");
    return StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(requestId, action);
  }

  private static StartSessionPostAuthorizationExecutionTuple postTuple(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID reservationOwner,
      Instant expiresAt) {
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
            .fromCanonicalJson(tuple.canonicalJson()),
        "spiffe://firemud/ns/world-runtime/sa/logging-admin-service",
        FINGERPRINT,
        reservationOwner,
        1L,
        authorityEvidenceBundle(tuple, expiresAt),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityEvidenceBundle(
      StartSessionPreAuthorizationReservationTuple tuple, Instant expiresAt) {
    String tenantId = TENANT_ID.toString();
    Map<String, Object> value =
        Map.of(
            "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
                Map.of(
                    "scope", Map.of("tenantId", tenantId, "targetNamespace", "world-runtime"),
                    "actionFamily", tuple.actionFamily(),
                    "applicableAccountId", ACTOR_ID.toString(),
                    "applicableTenantId", tenantId),
            "accountProjectionEvidence",
                Map.of(
                    "sourceType",
                    "ACCOUNT",
                    "sourceEvidenceId",
                    "sha256:" + "a".repeat(64),
                    "sourceEvidenceVersion",
                    "17",
                    "projectionStatus",
                    "CURRENT",
                    "evaluatedAt",
                    Instant.ofEpochMilli(NOW_EPOCH_MILLIS - 1_000L).toString(),
                    "expiresAt",
                    expiresAt.toString()),
            "issuanceOperationIdentity",
                Map.of(
                    "issuanceOperationId", ISSUANCE_ID.toString(),
                    "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                    "actionFamilyRequestIdentity",
                        Map.of(
                            "requestIdentityKind",
                            "controlPlaneRequestId",
                            "requestId",
                            tuple.controlPlaneRequestId()),
                    "mutationDigest", tuple.mutationDigest()),
            "issuanceKind", "human_operator",
            "authorityTuple",
                Map.of(
                    "issuerAuthGeneration", 1L,
                    "accountAuthorityGeneration", 2L,
                    "tenantAuthorityGeneration", Map.of(tenantId, 3L),
                    "membershipAuthorityGeneration", Map.of(tenantId, 4L),
                    "privateRealmGrantVersions", List.of()),
            "membershipVersion", Map.of(tenantId, 5L),
            "issuanceFence", "23",
            "issuanceEvidence",
                Map.of(
                    "evidenceType",
                    StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                    "actorAccountId",
                    ACTOR_ID.toString(),
                    "controlUiTokenJti",
                    TOKEN_JTI.toString(),
                    "role",
                    "tenantAdmin",
                    "accountGeneration",
                    "2",
                    "tenantGeneration",
                    "3"));
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException exception) {
      throw new IllegalStateException("could not encode test authority bundle", exception);
    }
  }
}
