package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadClient;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository.Candidate;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository.StoredSource;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchProofFixtures.SyntheticLaunchProof;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository.CanonicalInstanceLaunch;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.CanonicalRealmCatalog;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.StaleStartSessionOperatorAttemptClaimException;
import net.firedevops.firemud.gamesession.service.impl.GameSessionAuthoredActorCoreRetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Service-ordering proof over the existing synthetic canonical launch fixture and mocked owner
 * clients. It does not prove authenticated Game Design transport or mTLS behavior.
 */
class GameSessionAuthoredActorCoreRetentionServiceTest {
  private static final String NAMESPACE = "test";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void exactRetainedRetryReturnsFirstEmptyBytesWithoutRemoteReadOrLeaseRenewal() throws Exception {
    TestInputs inputs = inputs();
    byte[] firstBytes = emptyCanonicalSourceBytes(sourceRequestBytes(inputs));
    Candidate first = candidate(inputs, firstBytes);
    StoredSource stored = new StoredSource(first, Instant.parse("2026-01-02T03:04:05Z"));
    when(inputs
            .retention()
            .readByOriginalOperation(NAMESPACE, inputs.tuple().controlPlaneRequestId()))
        .thenReturn(Optional.of(stored));

    StoredSource result =
        inputs.service().retain(inputs.historical(), inputs.currentAttemptRequest());

    assertThat(result).isSameAs(stored);
    assertThat(result.candidate().canonicalSourceResultBytes()).containsExactly(firstBytes);
    assertThat(new String(firstBytes, java.nio.charset.StandardCharsets.UTF_8))
        .contains("\"projections\":[]");
    verify(inputs.retention())
        .readByOriginalOperation(NAMESPACE, inputs.tuple().controlPlaneRequestId());
    verifyNoInteractions(
        inputs.launches(), inputs.attempts(), inputs.gameDesign(), inputs.transactionManager());
  }

  @Test
  void changedCurrentAttemptAfterSourceReadDeniesBeforeRetentionInsert() throws Exception {
    TestInputs inputs = inputs();
    when(inputs
            .retention()
            .readByOriginalOperation(NAMESPACE, inputs.tuple().controlPlaneRequestId()))
        .thenReturn(Optional.empty());
    when(inputs
            .launches()
            .readByOriginalTuple(
                inputs.historical().request().associationSelector(),
                inputs.tuple().canonicalBytes()))
        .thenReturn(Optional.of(inputs.launch()));
    AttemptSnapshot original = snapshot(inputs, inputs.ownerMutationId());
    AttemptSnapshot changed = snapshot(inputs, UUID.randomUUID());
    when(inputs.attempts().readCurrentOriginalAttempt(inputs.currentAttemptRequest()))
        .thenReturn(original, changed);
    stubAuthenticatedSourceResult(inputs, inputs.binding().descriptor());

    assertThatThrownBy(
            () -> inputs.service().retain(inputs.historical(), inputs.currentAttemptRequest()))
        .isInstanceOf(StaleStartSessionOperatorAttemptClaimException.class)
        .hasMessageContaining("Exact live pending original StartSession attempt");

    verify(inputs.gameDesign()).read(any(AuthoredInitialActorCoreReadEvidence.ReadRequest.class));
    verify(inputs.attempts(), times(2)).readCurrentOriginalAttempt(inputs.currentAttemptRequest());
    verify(inputs.retention(), never()).retainInOwnerTransaction(any(Candidate.class));
  }

  @Test
  void sourceDescriptorThatDiffersFromActualLaunchDeniesBeforeRetentionInsert() throws Exception {
    TestInputs inputs = inputs();
    when(inputs
            .retention()
            .readByOriginalOperation(NAMESPACE, inputs.tuple().controlPlaneRequestId()))
        .thenReturn(Optional.empty());
    when(inputs
            .launches()
            .readByOriginalTuple(
                inputs.historical().request().associationSelector(),
                inputs.tuple().canonicalBytes()))
        .thenReturn(Optional.of(inputs.launch()));
    AttemptSnapshot original = snapshot(inputs, inputs.ownerMutationId());
    when(inputs.attempts().readCurrentOriginalAttempt(inputs.currentAttemptRequest()))
        .thenReturn(original);
    AuthoredWorldLaunchDescriptorEvidence mismatchedDescriptor =
        mock(AuthoredWorldLaunchDescriptorEvidence.class);
    stubAuthenticatedSourceResult(inputs, mismatchedDescriptor);

    assertThatThrownBy(
            () -> inputs.service().retain(inputs.historical(), inputs.currentAttemptRequest()))
        .isInstanceOf(
            net.firedevops.firemud.gamesession.repository
                .GameSessionStartSessionOperatorAttemptRepository
                .StartSessionOperatorAttemptConflictException.class)
        .hasMessageContaining("exact original association or launch binding");

    verify(inputs.gameDesign()).read(any(AuthoredInitialActorCoreReadEvidence.ReadRequest.class));
    verify(inputs.attempts()).readCurrentOriginalAttempt(inputs.currentAttemptRequest());
    verify(inputs.retention(), never()).retainInOwnerTransaction(any(Candidate.class));
  }

  private static TestInputs inputs() throws Exception {
    UUID tenantId = UUID.randomUUID();
    String worldSlug = "retention-fixture-world";
    UUID sourceOperationId = UUID.randomUUID();
    UUID registrationRequestId = UUID.randomUUID();
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE,
            registrationRequestId,
            tenantId,
            "retention-fixture-tenant",
            worldSlug,
            "Retention Fixture");
    AuthoredWorldSourceEvidence sourceEvidence =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            sourceRequestDigest,
            tenantId,
            "retention-fixture-tenant",
            worldSlug,
            "Retention Fixture",
            91L,
            "retention-fixture-key",
            "NEW_GAME_ROW",
            AuthoredWorldSourceDigest.evidenceDigest(
                NAMESPACE,
                registrationRequestId,
                sourceOperationId,
                sourceRequestDigest,
                tenantId,
                "retention-fixture-tenant",
                worldSlug,
                "Retention Fixture",
                91L,
                "retention-fixture-key",
                "NEW_GAME_ROW"));
    SyntheticLaunchProof fixtureProof =
        GameSessionCanonicalInstanceLaunchProofFixtures.create(
            NAMESPACE,
            "retained-authored-core-" + UUID.randomUUID(),
            tenantId,
            worldSlug,
            sourceEvidence,
            91L,
            13L,
            17L,
            "retention-descriptor-" + UUID.randomUUID(),
            "generation-fixture",
            2L,
            "fixture-release/" + UUID.randomUUID());
    SyntheticLaunchProof proof = withUuidCommit(fixtureProof, sourceEvidence);
    var tuple = proof.originalTuple();
    var binding = proof.completeLaunchBindingEvidence();
    var descriptor = binding.descriptor();
    var release = binding.releaseAttestation();
    UUID ownerAttemptId = UUID.randomUUID();
    UUID ownerMutationId = UUID.randomUUID();
    long ownerFence = 7L;
    UUID gameInstanceUuid = UUID.randomUUID();
    UUID playableNamespaceId = UUID.randomUUID();
    String associationDigest = "sha256:" + "1".repeat(64);

    var selector =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            UUID.randomUUID(),
            NAMESPACE,
            tenantId,
            worldSlug,
            gameInstanceUuid,
            tuple.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest());
    var exactRequest =
        new StartSessionTemplateAssociationReadEvidence.Request(
            StartSessionTemplateAssociationReadEvidence.SCHEMA_VERSION,
            NAMESPACE,
            UUID.randomUUID(),
            tuple.canonicalBytes(),
            ownerAttemptId,
            ownerFence,
            new StartSessionTemplateAssociationReadEvidence.ExactReplay(
                release.canonicalVersionId(),
                UUID.fromString(release.commitId()),
                release.publishWorkflowId(),
                associationDigest));
    var historicalRequest =
        new HistoricalOriginalStartSessionOwnerEvidence.Request(
            selector, ownerAttemptId, ownerFence);
    byte[] accountProjection = StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple);

    var selection = mock(StartSessionTemplateAssociationReadEvidence.Association.class);
    when(selection.canonicalTenantId()).thenReturn(tenantId);
    when(selection.canonicalVersionId()).thenReturn(release.canonicalVersionId());
    when(selection.selectedCommitId()).thenReturn(UUID.fromString(release.commitId()));
    when(selection.publishWorkflowId()).thenReturn(release.publishWorkflowId());
    when(selection.associationDigest()).thenReturn(associationDigest);
    when(selection.sourceOperationId()).thenReturn(sourceOperationId);
    when(selection.sourceEvidenceDigest()).thenReturn(sourceEvidence.evidenceDigest());
    when(selection.worldSlug()).thenReturn(worldSlug);
    var firstSelection = mock(StartSessionTemplateAssociationReadEvidence.Result.class);
    when(firstSelection.association()).thenReturn(selection);

    PublishedReleaseBundle bundle = releaseBundle(binding);
    var pinnedAssociationRead = mock(StartSessionTemplateAssociationReadEvidence.Result.class);
    when(pinnedAssociationRead.request()).thenReturn(exactRequest);
    when(pinnedAssociationRead.releaseBundle()).thenReturn(bundle);
    var descriptorPin =
        new StartSessionLaunchDescriptorGrpcCodec.Resolved(
            pinnedAssociationRead,
            new StartSessionLaunchDescriptorGrpcCodec.DescriptorOutcome(descriptor));
    var launchAssociation =
        new HistoricalOriginalStartSessionOwnerEvidence.LaunchAssociation(
            NAMESPACE,
            tenantId,
            worldSlug,
            gameInstanceUuid,
            tuple.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            playableNamespaceId,
            RealmEntryPolicy.StateScope.SHARED,
            true,
            0L,
            binding);
    var historical = mock(HistoricalOriginalStartSessionOwnerEvidence.Result.class);
    when(historical.request()).thenReturn(historicalRequest);
    when(historical.originalTuple()).thenReturn(tuple);
    when(historical.ownerAttemptId()).thenReturn(ownerAttemptId);
    when(historical.ownerFence()).thenReturn(ownerFence);
    when(historical.accountRedemptionProjection()).thenReturn(accountProjection);
    when(historical.firstSelection()).thenReturn(firstSelection);
    when(historical.descriptorPin()).thenReturn(descriptorPin);
    when(historical.launchAssociation()).thenReturn(launchAssociation);

    CanonicalRealmCatalog catalog = mock(CanonicalRealmCatalog.class);
    when(catalog.worldSlug()).thenReturn(worldSlug);
    CanonicalInstanceLaunch launch = mock(CanonicalInstanceLaunch.class);
    when(launch.targetNamespace()).thenReturn(NAMESPACE);
    when(launch.controlPlaneRequestId()).thenReturn(tuple.controlPlaneRequestId());
    when(launch.canonicalTenantId()).thenReturn(tenantId);
    when(launch.gameInstanceUuid()).thenReturn(gameInstanceUuid);
    when(launch.canonicalRealmCatalog()).thenReturn(catalog);
    when(launch.launchBindingEvidence()).thenReturn(binding);
    when(launch.playableStateNamespaceId()).thenReturn(playableNamespaceId);
    when(launch.playableStateScope()).thenReturn(RealmEntryPolicy.StateScope.SHARED);
    when(launch.publicProduction()).thenReturn(true);
    when(launch.capturedStartingRowVersion()).thenReturn(0L);

    UUID readId = UUID.randomUUID();
    var currentAttemptRequest =
        new OriginalStartSessionCurrentAttemptEvidence.Request(
            readId, NAMESPACE, tuple.canonicalBytes(), ownerAttemptId, ownerMutationId, ownerFence);
    AttemptSnapshot attempt = mock(AttemptSnapshot.class);
    Instant leaseExpiresAt = Instant.now().plusSeconds(300);
    when(attempt.targetNamespace()).thenReturn(NAMESPACE);
    when(attempt.controlPlaneRequestId()).thenReturn(tuple.controlPlaneRequestId());
    when(attempt.canonicalTenantId()).thenReturn(tenantId);
    when(attempt.ownerAttemptId()).thenReturn(ownerAttemptId);
    when(attempt.ownerMutationId()).thenReturn(ownerMutationId);
    when(attempt.ownerFence()).thenReturn(ownerFence);
    when(attempt.phaseState()).thenReturn(OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE);
    when(attempt.postAuthorizationExecutionTuple()).thenReturn(tuple.canonicalBytes());
    when(attempt.accountRedemptionProjection()).thenReturn(accountProjection);
    when(attempt.leaseExpiresAt()).thenReturn(leaseExpiresAt);

    var launches = mock(GameSessionCanonicalInstanceLaunchRepository.class);
    var attempts = mock(GameSessionStartSessionOperatorAttemptRepository.class);
    var gameDesign = mock(AuthoredInitialActorCoreReadClient.class);
    var retention = mock(GameSessionAuthoredActorCoreRetentionRepository.class);
    var transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus status = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
    doNothing().when(transactionManager).commit(status);
    doNothing().when(transactionManager).rollback(status);
    var service =
        new GameSessionAuthoredActorCoreRetentionService(
            NAMESPACE, launches, attempts, gameDesign, retention, transactionManager);
    return new TestInputs(
        tuple,
        binding,
        ownerAttemptId,
        ownerMutationId,
        ownerFence,
        gameInstanceUuid,
        exactRequest,
        currentAttemptRequest,
        historical,
        launch,
        attempt,
        launches,
        attempts,
        gameDesign,
        retention,
        transactionManager,
        service);
  }

  private static void stubAuthenticatedSourceResult(
      TestInputs inputs, AuthoredWorldLaunchDescriptorEvidence sourceDescriptor) throws Exception {
    PublishedReleaseBundle bundle = releaseBundle(inputs.binding());
    var retainedAssociation = inputs.historical().firstSelection().association();
    var associationRead = mock(StartSessionTemplateAssociationReadEvidence.Result.class);
    when(associationRead.request()).thenReturn(inputs.associationRequest());
    when(associationRead.association()).thenReturn(retainedAssociation);
    when(associationRead.releaseBundle()).thenReturn(bundle);
    when(associationRead.worldPublishedStartLocationEvidence()).thenReturn(null);
    var source = mock(AuthoredInitialActorCoreReadEvidence.ReadResult.class);
    byte[] bytes = emptyCanonicalSourceBytes(sourceRequestBytes(inputs));
    when(source.request()).thenReturn(inputs.associationRequest());
    when(source.associationRead()).thenReturn(associationRead);
    when(source.descriptor()).thenReturn(sourceDescriptor);
    when(source.canonicalBytes()).thenReturn(bytes);
    when(source.digest()).thenReturn(sha256(bytes));
    when(inputs.gameDesign().read(any(AuthoredInitialActorCoreReadEvidence.ReadRequest.class)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return source;
            });
  }

  private static AttemptSnapshot snapshot(TestInputs inputs, UUID mutationId) {
    AttemptSnapshot snapshot = mock(AttemptSnapshot.class);
    when(snapshot.targetNamespace()).thenReturn(NAMESPACE);
    when(snapshot.controlPlaneRequestId()).thenReturn(inputs.tuple().controlPlaneRequestId());
    when(snapshot.canonicalTenantId())
        .thenReturn(inputs.tuple().preAuthorizationTuple().action().scope().tenantId());
    when(snapshot.ownerAttemptId()).thenReturn(inputs.ownerAttemptId());
    when(snapshot.ownerMutationId()).thenReturn(mutationId);
    when(snapshot.ownerFence()).thenReturn(inputs.ownerFence());
    when(snapshot.phaseState())
        .thenReturn(OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE);
    when(snapshot.postAuthorizationExecutionTuple()).thenReturn(inputs.tuple().canonicalBytes());
    when(snapshot.accountRedemptionProjection())
        .thenReturn(StartSessionAccountRedemptionProjection.fromOriginalTuple(inputs.tuple()));
    when(snapshot.leaseExpiresAt()).thenReturn(Instant.now().plusSeconds(300));
    return snapshot;
  }

  private static Candidate candidate(TestInputs inputs, byte[] bytes) throws Exception {
    var release = inputs.binding().releaseAttestation();
    var descriptor = inputs.binding().descriptor();
    byte[] projection = StartSessionAccountRedemptionProjection.fromOriginalTuple(inputs.tuple());
    byte[] requestBytes =
        StartSessionTemplateAssociationReadGrpcCodec.toRequest(inputs.associationRequest())
            .toByteArray();
    return new Candidate(
        NAMESPACE,
        inputs.tuple().controlPlaneRequestId(),
        inputs.tuple().preAuthorizationTuple().action().scope().tenantId(),
        inputs.gameInstanceUuid(),
        inputs.ownerAttemptId(),
        inputs.ownerMutationId(),
        inputs.ownerFence(),
        Instant.parse("2026-01-01T00:00:00Z"),
        inputs.tuple().canonicalBytes(),
        projection,
        OriginalStartSessionCurrentAttemptEvidence.projectionDigest(projection),
        release.canonicalVersionId(),
        UUID.fromString(release.commitId()),
        release.publishWorkflowId(),
        "sha256:" + "1".repeat(64),
        release.authoredWorldSourceOperationId(),
        release.authoredWorldSourceEvidenceDigest(),
        release.worldSlug(),
        descriptor.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        release.evidenceDigest(),
        requestBytes,
        bytes,
        sha256(bytes));
  }

  private static SyntheticLaunchProof withUuidCommit(
      SyntheticLaunchProof stipulation, AuthoredWorldSourceEvidence source) {
    var descriptor = stipulation.completeLaunchBindingEvidence().descriptor();
    String commitId = UUID.randomUUID().toString();
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                key ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        key,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        commitId,
                        "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            key),
                        "GAME_LOGIC".equals(key),
                        "GAME_LOGIC".equals(key) ? "sha256:" + "d".repeat(64) : null))
            .toList();
    var release =
        AuthoredWorldReleaseAttestationEvidence.create(
            NAMESPACE,
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            UUID.randomUUID(),
            descriptor.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "synthetic-publish-workflow",
            commitId,
            participants,
            "sha256:" + "d".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision());
    return new SyntheticLaunchProof(
        stipulation.originalTuple(), new CompleteLaunchBindingEvidence(descriptor, release));
  }

  private static PublishedReleaseBundle releaseBundle(CompleteLaunchBindingEvidence binding) {
    var descriptor = binding.descriptor();
    var release = binding.releaseAttestation();
    var builder =
        PublishedReleaseBundle.newBuilder()
            .setId(descriptor.releaseBundleId())
            .setVersionId(descriptor.versionId())
            .setIsScriptOnly(false)
            .setPublishedReleaseBundleRef(descriptor.publishedReleaseBundleRef())
            .setCanonicalTenantId(release.canonicalTenantId().toString())
            .setCanonicalVersionId(release.canonicalVersionId().toString())
            .setPublishWorkflowId(release.publishWorkflowId())
            .setAttestationSchemaVersion("v" + release.schemaVersion())
            .setManifestHash(release.manifestHash())
            .setManifestSchemaVersion(release.manifestSchemaVersion())
            .setGenerationConfigRevision(release.generationConfigRevision())
            .addAllRequiredManifestAssetKeys(release.requiredManifestAssetKeys())
            .addAllCommandDefinitions(release.commandDefinitions());
    for (AuthoredWorldReleaseAttestationEvidence.Participant expected :
        release.participantDigests()) {
      var participant =
          ParticipantDigest.newBuilder()
              .setParticipantKey(expected.participantKey())
              .setScopeValue(expected.scopeValue())
              .setAppliedCommitId(expected.appliedCommitId())
              .setContentDigest(expected.contentDigest())
              .setDigestSchemaVersion(expected.digestSchemaVersion());
      if (expected.abilitySchemaDigestPresent()) {
        participant.setAbilitySchemaDigest(expected.abilitySchemaDigest());
      }
      builder.addParticipantDigests(participant);
    }
    return builder.build();
  }

  private static byte[] sourceRequestBytes(TestInputs inputs) {
    return StartSessionTemplateAssociationReadGrpcCodec.toRequest(inputs.associationRequest())
        .toByteArray();
  }

  private static byte[] emptyCanonicalSourceBytes(byte[] requestBytes) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(
        JSON.writeValueAsString(
            java.util.Map.of(
                "schema", "game-design-authored-initial-actor-core-read/v1",
                "request", Base64.getEncoder().encodeToString(requestBytes),
                "associationRead", "fixture-association",
                "launchDescriptor", "fixture-descriptor",
                "projections", List.of())));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return "sha256:"
        + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private record TestInputs(
      net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple tuple,
      CompleteLaunchBindingEvidence binding,
      UUID ownerAttemptId,
      UUID ownerMutationId,
      long ownerFence,
      UUID gameInstanceUuid,
      StartSessionTemplateAssociationReadEvidence.Request associationRequest,
      OriginalStartSessionCurrentAttemptEvidence.Request currentAttemptRequest,
      HistoricalOriginalStartSessionOwnerEvidence.Result historical,
      CanonicalInstanceLaunch launch,
      AttemptSnapshot attempt,
      GameSessionCanonicalInstanceLaunchRepository launches,
      GameSessionStartSessionOperatorAttemptRepository attempts,
      AuthoredInitialActorCoreReadClient gameDesign,
      GameSessionAuthoredActorCoreRetentionRepository retention,
      PlatformTransactionManager transactionManager,
      GameSessionAuthoredActorCoreRetentionService service) {}
}
