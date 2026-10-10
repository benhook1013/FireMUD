package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.service.impl.HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalStartSessionOwnerEvidenceRequest;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalStartSessionOwnerEvidenceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HistoricalOriginalStartSessionOwnerEvidenceReadGrpcServiceTest {
  private static final String NAMESPACE = "test";
  private static final String WORLD_SERVICE = "world-management-service";
  private static final String ACCOUNT_SERVICE = "account-service";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID ATTEMPT = uuid("02222222-2222-4222-8222-222222222222");
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = uuid("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final UUID GAME_INSTANCE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID STATE_NAMESPACE = uuid("44444444-4444-4444-8444-444444444444");
  private static final long OWNER_FENCE = 8L;
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearEndUserContext() {
    SessionContext.clear();
  }

  @Test
  void exactSameNamespaceWorldAndAccountHandlersReturnExpiredCanonicalRetainedEvidence()
      throws Exception {
    Fixture fixture = fixture();
    CanonicalGameInstanceLaunchAssociationRepository repository = mockRepository();
    when(repository.readHistoricalOriginalStartSessionOwnerEvidence(fixture.request()))
        .thenReturn(Optional.of(fixture.result()));
    var service =
        new HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService(repository, NAMESPACE);

    RecordingObserver<ReadHistoricalOriginalStartSessionOwnerEvidenceResponse> worldResponse =
        invokeAs(service, fixture.wireRequest(), WORLD_SERVICE);
    RecordingObserver<ReadHistoricalOriginalStartSessionOwnerEvidenceResponse> accountResponse =
        invokeAs(service, fixture.wireRequest(), ACCOUNT_SERVICE);

    var expected =
        HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.toResponse(fixture.result());
    assertThat(worldResponse.response()).isEqualTo(expected);
    assertThat(accountResponse.response()).isEqualTo(expected);
    assertThat(worldResponse.error()).isNull();
    assertThat(accountResponse.error()).isNull();
    assertThat(worldResponse.completed()).isTrue();
    assertThat(accountResponse.completed()).isTrue();
    var decoded =
        HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.fromResponse(
            fixture.request(), worldResponse.response());
    assertThat(decoded.originalTuple().canonicalBytes())
        .containsExactly(fixture.result().originalTuple().canonicalBytes());
    assertThat(decoded.ownerAttemptId()).isEqualTo(ATTEMPT);
    assertThat(decoded.ownerFence()).isEqualTo(OWNER_FENCE);
    assertThat(decoded.accountRedemptionProjection())
        .containsExactly(fixture.result().accountRedemptionProjection());
    assertThat(decoded.firstSelection().request().selection())
        .isInstanceOf(StartSessionTemplateAssociationReadEvidence.InitialConfigured.class);
    verify(repository, times(2)).readHistoricalOriginalStartSessionOwnerEvidence(fixture.request());
    verifyNoMoreInteractions(repository);
  }

  @Test
  void missingAndMalformedRetainedResultsFailWithoutSendingAResponse() throws Exception {
    Fixture fixture = fixture();
    CanonicalGameInstanceLaunchAssociationRepository repository = mockRepository();
    when(repository.readHistoricalOriginalStartSessionOwnerEvidence(fixture.request()))
        .thenReturn(Optional.empty());
    var service =
        new HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService(repository, NAMESPACE);

    var missing = invokeAs(service, fixture.wireRequest(), WORLD_SERVICE);
    assertRejected(missing, Status.Code.FAILED_PRECONDITION);

    HistoricalOriginalStartSessionOwnerEvidence.Result malformed =
        mock(HistoricalOriginalStartSessionOwnerEvidence.Result.class);
    when(malformed.request()).thenReturn(fixture.request());
    when(repository.readHistoricalOriginalStartSessionOwnerEvidence(fixture.request()))
        .thenReturn(Optional.of(malformed));
    var corrupt = invokeAs(service, fixture.wireRequest(), WORLD_SERVICE);
    assertRejected(corrupt, Status.Code.INTERNAL);
    verify(repository, times(2)).readHistoricalOriginalStartSessionOwnerEvidence(fixture.request());
    verifyNoMoreInteractions(repository);
  }

  @Test
  void changedRepositoryResultEchoFailsBeforeResponseEncoding() throws Exception {
    Fixture fixture = fixture();
    var changedSelector =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            UUID.randomUUID(),
            fixture.request().associationSelector().targetNamespace(),
            fixture.request().associationSelector().canonicalTenantId(),
            fixture.request().associationSelector().worldSlug(),
            fixture.request().associationSelector().gameInstanceUuid(),
            fixture.request().associationSelector().controlPlaneRequestId(),
            fixture.request().associationSelector().launchDescriptorId(),
            fixture.request().associationSelector().expectedDescriptorRequestDigest(),
            fixture.request().associationSelector().expectedDescriptorResultDigest(),
            fixture.request().associationSelector().expectedReleaseAttestationEvidenceDigest());
    var changedRequest =
        new HistoricalOriginalStartSessionOwnerEvidence.Request(
            changedSelector, fixture.request().expectedOwnerAttemptId(), OWNER_FENCE);
    HistoricalOriginalStartSessionOwnerEvidence.Result mismatched =
        mock(HistoricalOriginalStartSessionOwnerEvidence.Result.class);
    when(mismatched.request()).thenReturn(changedRequest);
    CanonicalGameInstanceLaunchAssociationRepository repository = mockRepository();
    when(repository.readHistoricalOriginalStartSessionOwnerEvidence(fixture.request()))
        .thenReturn(Optional.of(mismatched));
    var service =
        new HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService(repository, NAMESPACE);

    var observer = invokeAs(service, fixture.wireRequest(), WORLD_SERVICE);

    assertRejected(observer, Status.Code.FAILED_PRECONDITION);
    verify(repository).readHistoricalOriginalStartSessionOwnerEvidence(fixture.request());
    verifyNoMoreInteractions(repository);
  }

  @Test
  void invalidAndUnknownWireRequestsAreRejectedBeforeRepositoryAccess() throws Exception {
    Fixture fixture = fixture();
    CanonicalGameInstanceLaunchAssociationRepository repository = mockRepository();
    var service =
        new HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService(repository, NAMESPACE);
    var invalid = fixture.wireRequest().toBuilder().setSchemaVersion(2).build();
    var unknown =
        fixture.wireRequest().toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();

    assertRejected(invokeAs(service, invalid, WORLD_SERVICE), Status.Code.INVALID_ARGUMENT);
    assertRejected(invokeAs(service, unknown, WORLD_SERVICE), Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  @Test
  void unauthorizedAndEndUserContextsAreRejectedBeforeRepositoryAccess() throws Exception {
    Fixture fixture = fixture();
    CanonicalGameInstanceLaunchAssociationRepository repository = mockRepository();
    var service =
        new HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService(repository, NAMESPACE);
    RecordingObserver<ReadHistoricalOriginalStartSessionOwnerEvidenceResponse> noPeer =
        invoke(service, fixture.wireRequest());
    assertRejected(noPeer, Status.Code.PERMISSION_DENIED);

    assertRejected(
        invokeAs(service, fixture.wireRequest(), NAMESPACE, "entity-management-service"),
        Status.Code.PERMISSION_DENIED);
    assertRejected(
        invokeAs(service, fixture.wireRequest(), "other", WORLD_SERVICE),
        Status.Code.PERMISSION_DENIED);

    AtomicReference<RecordingObserver<ReadHistoricalOriginalStartSessionOwnerEvidenceResponse>>
        userResponse = new AtomicReference<>();
    runAsPeer(
        ACCOUNT_SERVICE,
        () -> {
          SessionContext.setContext("42", List.of("tenantAdmin"), Map.of());
          userResponse.set(invoke(service, fixture.wireRequest()));
        });
    assertRejected(userResponse.get(), Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  private static Fixture fixture() throws Exception {
    WorldCanonicalInstanceLifecycleEvidence lifecycle =
        AuthoringFixtures.lifecycleEvidence("ACTIVE", 1L);
    WorldPublishedStartLocationEvidence worldSelection =
        lifecycle.launchBinding().releaseAttestation().worldStartLocationEvidence();
    UUID tenant = worldSelection.request().canonicalTenantId();
    AuthoredWorldSourceEvidence source = sourceEvidence(tenant, "synthetic-world");
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        descriptor(source, tenant, "synthetic-world");
    AuthoredWorldReleaseAttestationEvidence release = release(descriptor, worldSelection);
    CompleteLaunchBindingEvidence binding = new CompleteLaunchBindingEvidence(descriptor, release);
    StartSessionPostAuthorizationExecutionTuple tuple = postTuple(descriptor);
    UUID intakeRequestId = uuid("55555555-5555-4555-8555-555555555555");
    UUID worldOperationId = uuid("66666666-6666-4666-8666-666666666666");
    var intakeRequest =
        new WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
            1,
            NAMESPACE,
            intakeRequestId,
            tenant,
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest());
    var receipt =
        new WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt(
            1,
            NAMESPACE,
            intakeRequestId,
            worldOperationId,
            tenant,
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            WorldAuthoredSourceIntakeGrpcCodec.requestDigest(intakeRequest),
            digest('e'),
            source);
    var firstSelectionAssociation =
        new StartSessionTemplateAssociationReadEvidence.Association(
            tenant,
            descriptor.gameTemplateId(),
            release.canonicalVersionId(),
            UUID.fromString(worldSelection.request().appliedCommitId()),
            release.publishWorkflowId(),
            digest('c'),
            digest('d'),
            NAMESPACE,
            intakeRequestId,
            worldOperationId,
            source.operationId(),
            source.worldSlug(),
            source.evidenceDigest(),
            new WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest(
                1,
                NAMESPACE,
                uuid("77777777-7777-4777-8777-777777777777"),
                intakeRequestId,
                tenant),
            receipt);
    var firstRequest =
        new StartSessionTemplateAssociationReadEvidence.Request(
            1,
            NAMESPACE,
            uuid("88888888-8888-4888-8888-888888888888"),
            tuple.canonicalBytes(),
            ATTEMPT,
            OWNER_FENCE,
            new StartSessionTemplateAssociationReadEvidence.InitialConfigured());
    StartSessionTemplateAssociationReadEvidence.Result firstSelection =
        new StartSessionTemplateAssociationReadEvidence.Result(
            firstRequest,
            firstSelectionAssociation,
            releaseBundle(descriptor, release),
            worldSelection,
            1L);
    var exactReplayRequest =
        new StartSessionTemplateAssociationReadEvidence.Request(
            firstRequest.schemaVersion(),
            firstRequest.targetNamespace(),
            firstRequest.readRequestId(),
            firstRequest.canonicalPostAuthorizationTuple(),
            firstRequest.ownerAttemptId(),
            firstRequest.ownerFence(),
            new StartSessionTemplateAssociationReadEvidence.ExactReplay(
                firstSelectionAssociation.canonicalVersionId(),
                firstSelectionAssociation.selectedCommitId(),
                firstSelectionAssociation.publishWorkflowId(),
                firstSelectionAssociation.associationDigest()));
    var exactReplaySelection =
        new StartSessionTemplateAssociationReadEvidence.Result(
            exactReplayRequest,
            firstSelectionAssociation,
            releaseBundle(descriptor, release),
            worldSelection,
            1L);
    var descriptorPin =
        new StartSessionLaunchDescriptorGrpcCodec.Resolved(
            exactReplaySelection,
            new StartSessionLaunchDescriptorGrpcCodec.DescriptorOutcome(descriptor));
    var selector =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            uuid("99999999-9999-4999-8999-999999999999"),
            NAMESPACE,
            tenant,
            descriptor.worldSlug(),
            GAME_INSTANCE,
            descriptor.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    var request =
        new HistoricalOriginalStartSessionOwnerEvidence.Request(selector, ATTEMPT, OWNER_FENCE);
    var launchAssociation =
        new HistoricalOriginalStartSessionOwnerEvidence.LaunchAssociation(
            NAMESPACE,
            tenant,
            descriptor.worldSlug(),
            GAME_INSTANCE,
            descriptor.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            STATE_NAMESPACE,
            RealmEntryPolicy.StateScope.SHARED,
            true,
            2L,
            binding);
    byte[] associationRequestWire =
        StartSessionTemplateAssociationReadGrpcCodec.toRequest(firstRequest).toByteArray();
    byte[] associationResponseWire =
        StartSessionTemplateAssociationReadGrpcCodec.toResponse(firstSelection).toByteArray();
    byte[] descriptorRequestWire =
        StartSessionLaunchDescriptorGrpcCodec.toRequest(exactReplayRequest).toByteArray();
    byte[] descriptorResponseWire =
        StartSessionLaunchDescriptorGrpcCodec.toResponse(
                exactReplayRequest, exactReplaySelection, descriptorPin.outcome())
            .toByteArray();
    var result =
        new HistoricalOriginalStartSessionOwnerEvidence.Result(
            request,
            tuple,
            ATTEMPT,
            OWNER_FENCE,
            new GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection(
                    tuple.authorizationReferenceFingerprint(),
                    tuple.authorityEvidenceBundleBytes(),
                    ISSUANCE_ID,
                    23L)
                .canonicalBytes(),
            firstSelection,
            descriptorPin,
            launchAssociation,
            HistoricalOriginalStartSessionOwnerEvidence.sha256(associationRequestWire),
            HistoricalOriginalStartSessionOwnerEvidence.sha256(associationResponseWire),
            HistoricalOriginalStartSessionOwnerEvidence.sha256(descriptorRequestWire),
            HistoricalOriginalStartSessionOwnerEvidence.sha256(descriptorResponseWire));
    return new Fixture(
        request, result, HistoricalOriginalStartSessionOwnerEvidenceGrpcCodec.toRequest(request));
  }

  private static AuthoredWorldSourceEvidence sourceEvidence(UUID tenant, String worldSlug) {
    UUID registrationId = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    UUID operationId = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    String tenantSlug = "test-tenant";
    String tenantKey = "test-tenant-key";
    String displayName = "Historical Test World";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationId, tenant, tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationId,
            operationId,
            requestDigest,
            tenant,
            tenantSlug,
            worldSlug,
            displayName,
            42L,
            tenantKey,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationId,
        operationId,
        requestDigest,
        tenant,
        tenantSlug,
        worldSlug,
        displayName,
        42L,
        tenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static AuthoredWorldLaunchDescriptorEvidence descriptor(
      AuthoredWorldSourceEvidence source, UUID tenant, String worldSlug) {
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            "historical-start-session-request",
            tenant,
            worldSlug,
            source.operationId(),
            source.evidenceDigest(),
            19L,
            false,
            null,
            false,
            null,
            false,
            null,
            true,
            "{}");
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        "historical-launch-descriptor",
        42L,
        false,
        null,
        "{}",
        "generation-revision",
        9L,
        7L,
        "historical-release-bundle",
        false,
        null);
  }

  private static AuthoredWorldReleaseAttestationEvidence release(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      WorldPublishedStartLocationEvidence worldSelection) {
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        worldSelection.request().appliedCommitId(),
                        worldSelection.request().contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? digest('f') : null))
            .toList();
    return AuthoredWorldReleaseAttestationEvidence.create(
        descriptor.targetNamespace(),
        descriptor.resultDigest(),
        descriptor.canonicalTenantId(),
        worldSelection.request().canonicalVersionId(),
        descriptor.worldSlug(),
        descriptor.authoredWorldSourceOperationId(),
        descriptor.authoredWorldSourceEvidenceDigest(),
        descriptor.launchDescriptorId(),
        descriptor.publishedReleaseBundleRef(),
        descriptor.versionStateEpoch(),
        worldSelection.request().publishWorkflowId(),
        worldSelection.request().appliedCommitId(),
        participants,
        digest('a'),
        1,
        List.of(),
        List.of(),
        List.of(),
        descriptor.generationConfigRevision(),
        worldSelection);
  }

  private static PublishedReleaseBundle releaseBundle(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldReleaseAttestationEvidence release) {
    var builder =
        PublishedReleaseBundle.newBuilder()
            .setId(descriptor.releaseBundleId())
            .setVersionId(descriptor.versionId())
            .setVersionNumber(1)
            .setAttestationSchemaVersion("v" + release.schemaVersion())
            .setPublishWorkflowId(release.publishWorkflowId())
            .setManifestHash(release.manifestHash())
            .setManifestSchemaVersion(release.manifestSchemaVersion())
            .addAllRequiredManifestAssetKeys(release.requiredManifestAssetKeys())
            .setGenerationConfigRevision(release.generationConfigRevision())
            .setPublishedReleaseBundleRef(release.publishedReleaseBundleRef())
            .setCanonicalTenantId(release.canonicalTenantId().toString())
            .setCanonicalVersionId(release.canonicalVersionId().toString())
            .addAllCommandDefinitions(release.commandDefinitions());
    for (var participant : release.participantDigests()) {
      ParticipantDigest.Builder value =
          ParticipantDigest.newBuilder()
              .setParticipantKey(participant.participantKey())
              .setScopeValue(participant.scopeValue())
              .setAppliedCommitId(participant.appliedCommitId())
              .setContentDigest(participant.contentDigest())
              .setDigestSchemaVersion(participant.digestSchemaVersion());
      if (participant.abilitySchemaDigestPresent()) {
        value.setAbilitySchemaDigest(participant.abilitySchemaDigest());
      }
      builder.addParticipantDigests(value);
    }
    for (var artifact : release.artifactDigests()) {
      builder.addArtifactDigests(
          PublishedArtifactDigest.newBuilder()
              .setUsageKey(artifact.usageKey())
              .setArtifactKind(artifact.artifactKind())
              .setImmutableObjectKey(artifact.immutableObjectKey())
              .setContentDigest(artifact.contentDigest())
              .setContentType(artifact.contentType())
              .setArtifactSchemaVersion(artifact.artifactSchemaVersion()));
    }
    return builder.build();
  }

  private static StartSessionPostAuthorizationExecutionTuple postTuple(
      AuthoredWorldLaunchDescriptorEvidence descriptor) throws IOException {
    var action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
            new StartSessionOperatorAction.Target(descriptor.gameTemplateId(), TARGET_OWNER),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "Read exact retained StartSession owner evidence");
    var pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            descriptor.controlPlaneRequestId(), ACTOR, action);
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
        FINGERPRINT,
        RESERVATION_OWNER,
        19L,
        authorityBundle(pre),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple)
      throws IOException {
    String tenant = TENANT.toString();
    Instant evaluatedAt = Instant.parse("2020-01-01T00:00:00Z");
    Instant expiresAt = Instant.parse("2020-01-01T00:01:00Z");
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenant, "targetNamespace", NAMESPACE),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenant),
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
                evaluatedAt.toString(),
                "expiresAt",
                expiresAt.toString()),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId",
                ISSUANCE_ID.toString(),
                "controlPlaneRequestId",
                tuple.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
                "mutationDigest",
                tuple.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration", 1L,
                "accountAuthorityGeneration", 2L,
                "tenantAuthorityGeneration", Map.of(tenant, 3L),
                "membershipAuthorityGeneration", Map.of(tenant, 4L),
                "privateRealmGrantVersions", List.of()),
            "membershipVersion",
            Map.of(tenant, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
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
                "3"));
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static RecordingObserver<ReadHistoricalOriginalStartSessionOwnerEvidenceResponse>
      invokeAs(
          HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService service,
          ReadHistoricalOriginalStartSessionOwnerEvidenceRequest request,
          String serviceName) {
    return invokeAs(service, request, NAMESPACE, serviceName);
  }

  private static RecordingObserver<ReadHistoricalOriginalStartSessionOwnerEvidenceResponse>
      invokeAs(
          HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService service,
          ReadHistoricalOriginalStartSessionOwnerEvidenceRequest request,
          String namespace,
          String serviceName) {
    AtomicReference<RecordingObserver<ReadHistoricalOriginalStartSessionOwnerEvidenceResponse>>
        observer = new AtomicReference<>();
    runAsPeer(namespace, serviceName, () -> observer.set(invoke(service, request)));
    return observer.get();
  }

  private static RecordingObserver<ReadHistoricalOriginalStartSessionOwnerEvidenceResponse> invoke(
      HistoricalOriginalStartSessionOwnerEvidenceReadGrpcService service,
      ReadHistoricalOriginalStartSessionOwnerEvidenceRequest request) {
    var observer = new RecordingObserver<ReadHistoricalOriginalStartSessionOwnerEvidenceResponse>();
    service.readHistoricalOriginalStartSessionOwnerEvidence(request, observer);
    return observer;
  }

  private static void runAsPeer(String serviceName, Runnable action) {
    runAsPeer(NAMESPACE, serviceName, action);
  }

  private static void runAsPeer(String namespace, String serviceName, Runnable action) {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + namespace + "/sa/" + serviceName, namespace, serviceName);
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
      SessionContext.clear();
    }
  }

  private static void assertRejected(RecordingObserver<?> observer, Status.Code expectedCode) {
    assertThat(observer.error()).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(observer.error()).getCode()).isEqualTo(expectedCode);
    assertThat(observer.response()).isNull();
    assertThat(observer.completed()).isFalse();
  }

  private static CanonicalGameInstanceLaunchAssociationRepository mockRepository() {
    return mock(CanonicalGameInstanceLaunchAssociationRepository.class);
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      HistoricalOriginalStartSessionOwnerEvidence.Request request,
      HistoricalOriginalStartSessionOwnerEvidence.Result result,
      ReadHistoricalOriginalStartSessionOwnerEvidenceRequest wireRequest) {}

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private final AtomicReference<T> response = new AtomicReference<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private final AtomicBoolean completed = new AtomicBoolean();

    @Override
    public void onNext(T value) {
      response.set(value);
    }

    @Override
    public void onError(Throwable failure) {
      error.set(failure);
    }

    @Override
    public void onCompleted() {
      completed.set(true);
    }

    T response() {
      return response.get();
    }

    Throwable error() {
      return error.get();
    }

    boolean completed() {
      return completed.get();
    }
  }
}
