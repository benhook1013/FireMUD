package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository.OriginalStartSessionAssociation;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameInstanceLaunchAssociationReadGrpcService;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationResponse;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class CanonicalGameInstanceLaunchAssociationReadGrpcServiceTest {
  private static final String NAMESPACE = "launch-owner";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID INSTANCE = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID PLAYABLE_NAMESPACE = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID ASSOCIATION_OPERATION = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID OWNER_ATTEMPT = uuid("77777777-7777-4777-8777-777777777777");
  private static final UUID REALM = uuid("88888888-8888-4888-8888-888888888888");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = uuid("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final String CONTROL_PLANE_REQUEST_ID = "launch-request-1";
  private static final String LAUNCH_DESCRIPTOR_ID = "descriptor-902";
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);

  private final CanonicalGameInstanceLaunchAssociationRepository repository =
      mock(CanonicalGameInstanceLaunchAssociationRepository.class);
  private final CanonicalGameInstanceLaunchAssociationReadGrpcService service =
      new CanonicalGameInstanceLaunchAssociationReadGrpcService(repository, NAMESPACE);

  @AfterEach
  void clearAmbientState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void missingPeerAndWrongWorkloadAreRejectedBeforeParsingOrStorage() {
    var missingPeer = new RecordingObserver();
    Context.ROOT.run(
        () ->
            service.getCanonicalGameInstanceLaunchAssociation(
                GetCanonicalGameInstanceLaunchAssociationRequest.getDefaultInstance(),
                missingPeer));
    assertDenied(missingPeer, Status.Code.UNAUTHENTICATED);

    assertDenied(
        invoke(
            "world-runtime",
            "account-service",
            GetCanonicalGameInstanceLaunchAssociationRequest.getDefaultInstance()),
        Status.Code.PERMISSION_DENIED);
    assertDenied(
        invoke(
            "other-runtime",
            "world-management-service",
            GetCanonicalGameInstanceLaunchAssociationRequest.getDefaultInstance()),
        Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void authenticatedHumanContextCannotBorrowTheWorldWorkloadIdentity() {
    Fixture fixture = fixture();
    SessionContext.setContext("42", List.of("tenantAdmin"), Map.of());

    assertDenied(invokeWorld(fixture.wireRequest()), Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void malformedUnknownAndWrongNamespaceSelectorsAreRejectedBeforeStorage() {
    Fixture fixture = fixture();
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    assertDenied(
        invokeWorld(GetCanonicalGameInstanceLaunchAssociationRequest.getDefaultInstance()),
        Status.Code.INVALID_ARGUMENT);
    assertDenied(
        invokeWorld(fixture.wireRequest().toBuilder().setUnknownFields(unknown).build()),
        Status.Code.INVALID_ARGUMENT);
    assertDenied(
        invokeWorld(fixture.wireRequest().toBuilder().setTargetNamespace("other-runtime").build()),
        Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void ambientSqlTransactionIsRejectedBeforeOwnerRead() {
    Fixture fixture = fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertDenied(invokeWorld(fixture.wireRequest()), Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(repository);
  }

  @Test
  void currentOriginalOwnerReturnsTheExactSelectorAndCanonicalStartingSnapshot() {
    Fixture fixture = fixture();
    when(repository.readOriginalStartSessionAssociation(fixture.request()))
        .thenReturn(Optional.of(fixture.original()));

    RecordingObserver observer = invokeWorld(fixture.wireRequest());
    var association = fixture.original().association();
    var expectedResult =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
            fixture.request(),
            PLAYABLE_NAMESPACE,
            RealmEntryPolicy.StateScope.SHARED,
            true,
            CurrentGameInstanceStatus.STARTING,
            association.currentRowVersion(),
            OWNER_ATTEMPT,
            8L,
            fixture.original().canonicalRealmId(),
            fixture.original().catalogRevision(),
            association.launchBindingEvidence());

    assertThat(observer.error()).isNull();
    assertThat(observer.completed()).isTrue();
    assertThat(observer.response())
        .isEqualTo(CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(expectedResult));
    assertThat(
            CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                fixture.request(), observer.response()))
        .isEqualTo(expectedResult);
    verify(repository).readOriginalStartSessionAssociation(fixture.request());
    verifyNoMoreInteractions(repository);
  }

  @Test
  void exactAccountOperationReturnsTheActualCurrentOwnerSnapshot() {
    var fixture = fixture();
    var request = accountRequest(fixture, UUID.randomUUID(), OWNER_ATTEMPT, 8L);
    when(repository.readOriginalStartSessionAssociation(request)).thenReturn(Optional.of(fixture.original()));

    var observer = invoke(NAMESPACE, "account-service", CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(request));

    assertThat(observer.error()).isNull();
    assertThat(observer.completed()).isTrue();
    var result = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(request, observer.response());
    assertThat(result.launchBindingEvidence()).isEqualTo(fixture.original().association().launchBindingEvidence());
    assertThat(result.currentGameInstanceStatus()).isEqualTo(CurrentGameInstanceStatus.STARTING);
    assertThat(result.currentRowVersion()).isEqualTo(fixture.original().association().currentRowVersion());
    assertThat(result.request().originalOperation()).isEqualTo(request.originalOperation());
    verify(repository).readOriginalStartSessionAssociation(request);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void accountCallerRequiresCompleteOperationAndCannotBorrowWorldOrHumanContext() {
    var fixture = fixture();
    assertDenied(invoke(NAMESPACE, "account-service", fixture.wireRequest()), Status.Code.PERMISSION_DENIED);
    var request = accountRequest(fixture, UUID.randomUUID(), OWNER_ATTEMPT, 8L);
    var wire = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(request);
    assertDenied(invoke(NAMESPACE, "world-management-service", wire), Status.Code.PERMISSION_DENIED);
    assertDenied(invoke("other-runtime", "account-service", wire), Status.Code.PERMISSION_DENIED);
    assertDenied(invoke(NAMESPACE, "account-service", wire.toBuilder().clearExpectedOwnerMutationId().build()), Status.Code.INVALID_ARGUMENT);
    SessionContext.setContext("123", List.of(), Map.of());
    assertDenied(invoke(NAMESPACE, "account-service", wire), Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void accountOperationRejectsAmbientTransactionAndMissingCurrentOwner() {
    var fixture = fixture();
    var request = accountRequest(fixture, UUID.randomUUID(), OWNER_ATTEMPT, 8L);
    var wire = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(request);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertDenied(invoke(NAMESPACE, "account-service", wire), Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(repository);
    TransactionSynchronizationManager.clear();
    when(repository.readOriginalStartSessionAssociation(request)).thenReturn(Optional.empty());
    assertDenied(invoke(NAMESPACE, "account-service", wire), Status.Code.FAILED_PRECONDITION);
  }

  @Test
  void accountOperationCannotAcceptAnotherAttemptOrNonStartingOwnerResult() {
    var fixture = fixture();
    var wrong = accountRequest(fixture, UUID.randomUUID(), UUID.randomUUID(), 8L);
    when(repository.readOriginalStartSessionAssociation(wrong)).thenReturn(Optional.of(fixture.original()));
    assertDenied(invoke(NAMESPACE, "account-service", CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(wrong)), Status.Code.FAILED_PRECONDITION);
    var stopped = fixture(810L, 810L, CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.STOPPED, INSTANCE);
    var request = accountRequest(stopped, UUID.randomUUID(), OWNER_ATTEMPT, 8L);
    when(repository.readOriginalStartSessionAssociation(request)).thenReturn(Optional.of(stopped.original()));
    assertDenied(invoke(NAMESPACE, "account-service", CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(request)), Status.Code.FAILED_PRECONDITION);
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Request accountRequest(Fixture fixture, UUID mutation, UUID attempt, long fence) {
    var request = fixture.request();
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(request.readRequestId(), request.targetNamespace(), request.canonicalTenantId(), request.worldSlug(), request.gameInstanceUuid(), request.controlPlaneRequestId(), request.launchDescriptorId(), request.expectedDescriptorRequestDigest(), request.expectedDescriptorResultDigest(), request.expectedReleaseAttestationEvidenceDigest(),
        new CanonicalGameInstanceLaunchAssociationReadEvidence.OriginalOperation(fixture.original().postAuthorizationExecutionTuple().canonicalBytes(), mutation, attempt, fence));
  }

  @Test
  void missingOrNullOriginalRowsFailClosedWithoutReturningEvidence() {
    Fixture fixture = fixture();
    when(repository.readOriginalStartSessionAssociation(fixture.request()))
        .thenReturn(Optional.empty())
        .thenReturn(null);

    assertDenied(invokeWorld(fixture.wireRequest()), Status.Code.FAILED_PRECONDITION);
    assertDenied(invokeWorld(fixture.wireRequest()), Status.Code.FAILED_PRECONDITION);
    verify(repository, org.mockito.Mockito.times(2))
        .readOriginalStartSessionAssociation(fixture.request());
    verifyNoMoreInteractions(repository);
  }

  @Test
  void wrongAttemptTupleCurrentStatusOrAssociationIdentityFailsClosed() {
    Fixture wrongTuple =
        fixture(
            810L,
            811L,
            CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.STARTING,
            INSTANCE);
    Fixture wrongCurrentStatus =
        fixture(
            810L,
            810L,
            CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING,
            INSTANCE);
    Fixture wrongAssociationIdentity =
        fixture(
            810L,
            810L,
            CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.STARTING,
            uuid("99999999-9999-4999-8999-999999999999"));
    when(repository.readOriginalStartSessionAssociation(wrongTuple.request()))
        .thenReturn(
            Optional.of(wrongTuple.original()),
            Optional.of(wrongCurrentStatus.original()),
            Optional.of(wrongAssociationIdentity.original()));
    assertDenied(invokeWorld(wrongTuple.wireRequest()), Status.Code.FAILED_PRECONDITION);
    assertDenied(invokeWorld(wrongCurrentStatus.wireRequest()), Status.Code.FAILED_PRECONDITION);
    assertDenied(
        invokeWorld(wrongAssociationIdentity.wireRequest()), Status.Code.FAILED_PRECONDITION);

    verify(repository, org.mockito.Mockito.times(3))
        .readOriginalStartSessionAssociation(wrongTuple.request());
    verifyNoMoreInteractions(repository);
  }

  @Test
  void unavailableOwnerStorageDoesNotBecomeInferredAssociationEvidence() {
    Fixture fixture = fixture();
    when(repository.readOriginalStartSessionAssociation(fixture.request()))
        .thenThrow(new DataAccessException("test-only unavailable storage"));

    assertDenied(invokeWorld(fixture.wireRequest()), Status.Code.UNAVAILABLE);
    verify(repository).readOriginalStartSessionAssociation(fixture.request());
    verifyNoMoreInteractions(repository);
  }

  private RecordingObserver invokeWorld(GetCanonicalGameInstanceLaunchAssociationRequest request) {
    return invoke(NAMESPACE, "world-management-service", request);
  }

  private RecordingObserver invoke(
      String namespace,
      String serviceName,
      GetCanonicalGameInstanceLaunchAssociationRequest request) {
    var peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + serviceName)
            .orElseThrow();
    var observer = new RecordingObserver();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> service.getCanonicalGameInstanceLaunchAssociation(request, observer));
    return observer;
  }

  private static Fixture fixture() {
    return fixture(
        810L,
        810L,
        CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.STARTING,
        INSTANCE);
  }

  private static Fixture fixture(
      long descriptorTemplateId,
      long tupleTemplateId,
      CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus currentStatus,
      UUID gameInstanceUuid) {
    CompleteLaunchBindingEvidence binding = binding(descriptorTemplateId);
    CanonicalGameInstanceLaunchAssociation association =
        new CanonicalGameInstanceLaunchAssociation(
            NAMESPACE,
            701L,
            ASSOCIATION_OPERATION,
            TENANT,
            gameInstanceUuid,
            "earth",
            PLAYABLE_NAMESPACE,
            RealmEntryPolicy.StateScope.SHARED,
            true,
            CONTROL_PLANE_REQUEST_ID,
            LAUNCH_DESCRIPTOR_ID,
            3L,
            binding,
            currentStatus,
            4L);
    StartSessionPostAuthorizationExecutionTuple tuple = tuple(tupleTemplateId);
    OriginalStartSessionAssociation original =
        new OriginalStartSessionAssociation(association, tuple, OWNER_ATTEMPT, 8L, REALM, 12L);
    var descriptor = binding.descriptor();
    var attestation = binding.releaseAttestation();
    CanonicalGameInstanceLaunchAssociationReadEvidence.Request request =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            uuid("01111111-1111-4111-8111-111111111111"),
            NAMESPACE,
            TENANT,
            "earth",
            INSTANCE,
            CONTROL_PLANE_REQUEST_ID,
            LAUNCH_DESCRIPTOR_ID,
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            attestation.evidenceDigest());
    return new Fixture(request, original);
  }

  private static CompleteLaunchBindingEvidence binding(long templateId) {
    UUID sourceOperationId = uuid("55555555-5555-4555-8555-555555555555");
    String sourceEvidenceDigest = digest('a');
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            CONTROL_PLANE_REQUEST_ID,
            TENANT,
            "earth",
            sourceOperationId,
            sourceEvidenceDigest,
            templateId,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            LAUNCH_DESCRIPTOR_ID,
            902L,
            false,
            null,
            "{}",
            "generation-4",
            4L,
            903L,
            "prb:tenant:902:903",
            false,
            null);
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        "902",
                        false,
                        null,
                        "publish-commit-902",
                        "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? digest('d') : null))
            .toList();
    var attestation =
        AuthoredWorldReleaseAttestationEvidence.create(
            NAMESPACE,
            descriptor.resultDigest(),
            TENANT,
            uuid("66666666-6666-4666-8666-666666666666"),
            "earth",
            sourceOperationId,
            sourceEvidenceDigest,
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "publish:tenant:version:request",
            "publish-commit-902",
            participants,
            digest('b'),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision());
    return new CompleteLaunchBindingEvidence(descriptor, attestation);
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(long templateId) {
    var preAuthorizationTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            CONTROL_PLANE_REQUEST_ID,
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(templateId, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "Read original StartSession launch association"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preAuthorizationTuple,
        "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
        FINGERPRINT,
        RESERVATION_OWNER,
        19L,
        authorityBundle(preAuthorizationTuple),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(
      StartSessionPreAuthorizationReservationTuple preAuthorizationTuple) {
    String tenantId = TENANT.toString();
    Map<String, Object> evidence =
        Map.of(
            "evidenceType",
            StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId",
            ACTOR.toString(),
            "controlUiTokenJti",
            TOKEN_JTI.toString(),
            "role",
            "tenantAdmin",
            "accountGeneration",
            "2",
            "tenantGeneration",
            "3");
    Map<String, Object> bundle =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenantId, "targetNamespace", NAMESPACE),
                "actionFamily", preAuthorizationTuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenantId),
            "accountProjectionEvidence",
            Map.of(
                "sourceType",
                "ACCOUNT",
                "sourceEvidenceId",
                digest('a'),
                "sourceEvidenceVersion",
                "17",
                "projectionStatus",
                "CURRENT",
                "evaluatedAt",
                "2026-10-09T00:00:00Z",
                "expiresAt",
                "2026-10-09T00:05:00Z"),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId",
                ISSUANCE_ID.toString(),
                "controlPlaneRequestId",
                preAuthorizationTuple.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    preAuthorizationTuple.controlPlaneRequestId()),
                "mutationDigest",
                preAuthorizationTuple.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration",
                1L,
                "accountAuthorityGeneration",
                2L,
                "tenantAuthorityGeneration",
                Map.of(tenantId, 3L),
                "membershipAuthorityGeneration",
                Map.of(tenantId, 4L),
                "privateRealmGrantVersions",
                List.of()),
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            evidence);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(
          JsonMapper.builder().build().writeValueAsString(bundle));
    } catch (IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static void assertDenied(RecordingObserver observer, Status.Code status) {
    assertThat(observer.response()).isNull();
    assertThat(observer.completed()).isFalse();
    assertThat(observer.error()).isEqualTo(status);
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request request,
      OriginalStartSessionAssociation original) {
    GetCanonicalGameInstanceLaunchAssociationRequest wireRequest() {
      return CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(request);
    }
  }

  private static final class RecordingObserver
      implements StreamObserver<GetCanonicalGameInstanceLaunchAssociationResponse> {
    private final AtomicReference<GetCanonicalGameInstanceLaunchAssociationResponse> response =
        new AtomicReference<>();
    private final AtomicReference<Status.Code> error = new AtomicReference<>();
    private volatile boolean completed;

    @Override
    public void onNext(GetCanonicalGameInstanceLaunchAssociationResponse value) {
      response.set(value);
    }

    @Override
    public void onError(Throwable failure) {
      error.set(Status.fromThrowable(failure).getCode());
    }

    @Override
    public void onCompleted() {
      completed = true;
    }

    GetCanonicalGameInstanceLaunchAssociationResponse response() {
      return response.get();
    }

    Status.Code error() {
      return error.get();
    }

    boolean completed() {
      return completed;
    }
  }
}
