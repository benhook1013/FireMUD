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
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
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
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository.OriginalStartSessionAssociation;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository.Candidate;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredActorCoreRetentionRepository.StoredSource;
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
 * Service-ordering proof with controlled launch/Account values and mocked canonical Human owner
 * repositories and clients. It does not prove actual owner production, authenticated Game Design
 * transport or mTLS behavior.
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
            .readOriginalStartSessionAssociation(
                inputs.historical().request().associationSelector()))
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
            .readOriginalStartSessionAssociation(
                inputs.historical().request().associationSelector()))
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

  @Test
  void substitutedCanonicalOriginalTupleAttemptFenceOrAssociationDeniesBeforeFreshReads()
      throws Exception {
    for (int substitution = 0; substitution < 4; substitution++) {
      TestInputs inputs = inputs();
      when(inputs
              .retention()
              .readByOriginalOperation(NAMESPACE, inputs.tuple().controlPlaneRequestId()))
          .thenReturn(Optional.empty());
      var original = inputs.launch();
      var tuple = inputs.tuple();
      if (substitution == 0) {
        // Structurally valid controlled tuple with a substituted original reservation identity.
        tuple =
            net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple
                .createHuman(
                    tuple.preAuthorizationTuple(),
                    tuple.authenticatedWorkloadIdentity(),
                    tuple.authorizationReferenceFingerprint(),
                    UUID.randomUUID(),
                    tuple.reservationClaimFence(),
                    tuple.authorityEvidenceBundleBytes(),
                    tuple.bundleReference());
      }
      var association = original.association();
      if (substitution == 3) {
        association =
            new CanonicalGameInstanceLaunchAssociation(
                association.targetNamespace(),
                association.gameSessionTenantId(),
                association.tenantAssociationOperationId(),
                association.canonicalTenantId(),
                UUID.randomUUID(),
                association.worldSlug(),
                association.playableStateNamespaceId(),
                association.playableStateScope(),
                association.publicProduction(),
                association.controlPlaneRequestId(),
                association.launchDescriptorId(),
                association.capturedStartingRowVersion(),
                association.launchBindingEvidence(),
                association.currentGameInstanceStatus(),
                association.currentRowVersion());
      }
      var changed =
          new OriginalStartSessionAssociation(
              association,
              tuple,
              substitution == 1 ? UUID.randomUUID() : original.ownerAttemptId(),
              substitution == 2 ? original.ownerFence() + 1L : original.ownerFence(),
              original.canonicalRealmId(),
              original.catalogRevision());
      when(inputs
              .launches()
              .readOriginalStartSessionAssociation(
                  inputs.historical().request().associationSelector()))
          .thenReturn(Optional.of(changed));
      assertThatThrownBy(
              () -> inputs.service().retain(inputs.historical(), inputs.currentAttemptRequest()))
          .isInstanceOf(
              net.firedevops.firemud.gamesession.repository
                  .GameSessionStartSessionOperatorAttemptRepository
                  .StartSessionOperatorAttemptConflictException.class);
      verify(inputs.launches())
          .readOriginalStartSessionAssociation(inputs.historical().request().associationSelector());
      verifyNoInteractions(inputs.attempts(), inputs.gameDesign(), inputs.transactionManager());
      verify(inputs.retention(), never()).retainInOwnerTransaction(any(Candidate.class));
    }
  }

  private static TestInputs inputs() throws Exception {
    UUID tenantId = UUID.randomUUID();
    String tenantSlug = "retention-fixture-tenant";
    String worldSlug = "retention-fixture-world";
    String displayName = "Retention Fixture";
    UUID sourceOperationId = UUID.randomUUID();
    UUID registrationRequestId = UUID.randomUUID();
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequestId, tenantId, tenantSlug, worldSlug, displayName);
    AuthoredWorldSourceEvidence sourceEvidence =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            sourceRequestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            displayName,
            91L,
            "retention-fixture-key",
            "NEW_GAME_ROW",
            AuthoredWorldSourceDigest.evidenceDigest(
                NAMESPACE,
                registrationRequestId,
                sourceOperationId,
                sourceRequestDigest,
                tenantId,
                tenantSlug,
                worldSlug,
                displayName,
                91L,
                "retention-fixture-key",
                "NEW_GAME_ROW"));

    var tuple =
        integration.net.firedevops.firemud.gamesession.repository
            .CanonicalHumanPublicationOwnerTestFixtures.tuple(
            NAMESPACE,
            tenantId,
            UUID.randomUUID(),
            91L,
            "retained-authored-core-" + UUID.randomUUID());
    var createCatalog =
        new CreateCanonicalRealmCatalogRequest(
            UUID.randomUUID(),
            NAMESPACE,
            tenantId,
            sourceOperationId,
            worldSlug,
            displayName,
            true,
            true,
            "SHARED",
            "PRESEEDED_ONLY",
            null,
            null);
    RealmPolicyPublishedEvidence.PublishedSet publishedSet =
        integration.net.firedevops.firemud.gamesession.repository
            .CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
            createCatalog,
            sourceEvidence,
            UUID.randomUUID(),
            19L,
            "retention-fixture-publication-" + UUID.randomUUID(),
            "immutable-release-ref",
            "generation-1",
            5L);
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            tuple.controlPlaneRequestId(),
            tenantId,
            worldSlug,
            sourceOperationId,
            sourceEvidence.evidenceDigest(),
            91L,
            false,
            null,
            false,
            null,
            false,
            null,
            true,
            "{}");
    var unpinnedDescriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "unowned-start-session-descriptor",
            19L,
            false,
            null,
            "{}",
            "generation-1",
            publishedSet.publicationVersionStateEpoch(),
            91L,
            "immutable-release-ref",
            false,
            null);
    CompleteLaunchBindingEvidence binding =
        integration.net.firedevops.firemud.gamesession.repository
            .CanonicalHumanPublicationOwnerTestFixtures.completeBinding(
            unpinnedDescriptor, publishedSet);
    var descriptor = binding.descriptor();
    var release = binding.releaseAttestation();
    UUID ownerAttemptId = UUID.randomUUID();
    UUID ownerMutationId = UUID.randomUUID();
    long ownerFence = 7L;
    UUID gameInstanceUuid = UUID.randomUUID();
    UUID playableNamespaceId = UUID.randomUUID();
    String associationDigest = "sha256:" + "b".repeat(64);

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

    // Controlled canonical Human owner readback; repository and transport ordering remain mocked.
    var association =
        new CanonicalGameInstanceLaunchAssociation(
            NAMESPACE,
            1L,
            UUID.randomUUID(),
            tenantId,
            gameInstanceUuid,
            worldSlug,
            playableNamespaceId,
            RealmEntryPolicy.StateScope.SHARED,
            true,
            tuple.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            0L,
            binding,
            CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.STARTING,
            0L);
    var launch =
        new OriginalStartSessionAssociation(
            association, tuple, ownerAttemptId, ownerFence, UUID.randomUUID(), 1L);

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

    var launches = mock(CanonicalGameInstanceLaunchAssociationRepository.class);
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
        inputs.historical().firstSelection().association().associationDigest(),
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
      OriginalStartSessionAssociation launch,
      AttemptSnapshot attempt,
      CanonicalGameInstanceLaunchAssociationRepository launches,
      GameSessionStartSessionOperatorAttemptRepository attempts,
      AuthoredInitialActorCoreReadClient gameDesign,
      GameSessionAuthoredActorCoreRetentionRepository retention,
      PlatformTransactionManager transactionManager,
      GameSessionAuthoredActorCoreRetentionService service) {}
}
