package integration.net.firedevops.firemud.gamesession.repository;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.test.IsolatedWorldPublicationInventoryFixtures;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controlled upstream evidence for local GS owner tests, using current closed Common contracts.
 * Account, World and GD producer/transport authority is stipulated; GS owners and SQL remain real.
 */
public final class CanonicalHumanPublicationOwnerTestFixtures {
  private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder().build();

  private CanonicalHumanPublicationOwnerTestFixtures() {}

  private static UUID id(String value) {
    return UUID.nameUUIDFromBytes(("TEST_ONLY:" + value).getBytes(StandardCharsets.UTF_8));
  }

  public static FreshTenantCreationEvidence creationEvidence(AuthoredWorldSourceEvidence source) {
    UUID request =
        UUID.nameUUIDFromBytes(
            ("test-creation-request:" + source.targetNamespace() + ":" + source.canonicalTenantId())
                .getBytes(StandardCharsets.UTF_8));
    UUID operation =
        UUID.nameUUIDFromBytes(
            ("test-creation-operation:"
                    + source.targetNamespace()
                    + ":"
                    + source.canonicalTenantId())
                .getBytes(StandardCharsets.UTF_8));
    String digest =
        GameTenantCreationDigest.requestDigest(
            source.targetNamespace(),
            request,
            source.sourceGameTenantKey(),
            source.worldDisplayName(),
            null);
    return new FreshTenantCreationEvidence(
        1,
        source.targetNamespace(),
        request,
        operation,
        digest,
        source.canonicalTenantId(),
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        GameTenantCreationDigest.evidenceDigest(
            source.targetNamespace(),
            request,
            operation,
            digest,
            source.canonicalTenantId(),
            source.sourceGameRowId(),
            source.sourceGameTenantKey(),
            source.provenanceKind()));
  }

  public static RealmPolicyPublishedEvidence.PublishedSet publishedSet(
      CreateCanonicalRealmCatalogRequest request,
      AuthoredWorldSourceEvidence source,
      UUID canonicalVersionId,
      long versionRowId,
      String publishRequestId,
      String bundleRef,
      String generationConfigRevision,
      long publicationVersionStateEpoch) {
    try {
      TargetProof target =
          new TargetProof(
              source.canonicalTenantId(),
              canonicalVersionId,
              versionRowId,
              source.sourceGameTenantKey(),
              source.sourceGameRowId(),
              source.sourceGameTenantKey(),
              source.provenanceKind());
      var seed = new WorldSeed(target, source.targetNamespace()).build();
      var original = DraftAuthorizationFenceBinding.fromStored(seed.originalAccountBindingBytes());
      var draft =
          DraftCommitBinding.fromStored(
              new String(original.gameDesignBinding(), StandardCharsets.UTF_8),
              original.inputDigest());
      var appliedEpochs =
          draft.affectedUnits().stream()
              .map(
                  unit ->
                      Map.of(
                          "aggregateType",
                          unit.aggregateType(),
                          "aggregateId",
                          unit.aggregateId(),
                          "scopeType",
                          unit.scopeType(),
                          "scopeId",
                          unit.scopeId(),
                          "expectedEpoch",
                          unit.expectedEpoch(),
                          "resultingEpoch",
                          new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
              .toList();
      String vector =
          new String(
              net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
                  JSON.writeValueAsString(
                      List.of(
                          Map.of(
                              "owner",
                              "WORLD_MANAGEMENT",
                              "status",
                              "APPLIED",
                              "commitId",
                              draft.commitId().toString(),
                              "bindingDigest",
                              draft.digest(),
                              "resultIdentity",
                              "TEST_ONLY-world-applied",
                              "resultBytesBase64",
                              Base64.getEncoder().encodeToString(seed.appliedResultBytes()),
                              "appliedEpochs",
                              appliedEpochs)))),
              StandardCharsets.UTF_8);
      var intent =
          new AuthoredDraftPublishSelectionBinding.PublishIntent(
              source.canonicalTenantId(),
              canonicalVersionId,
              publishRequestId,
              Long.toString(publicationVersionStateEpoch - 1),
              "TEST_ONLY complete Human owner input",
              draft.requestId(),
              draft.commitId(),
              draft.digest());
      var selected =
          AuthoredDraftPublishSelectionBinding.capture(
              intent,
              target,
              draft,
              new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                  target,
                  draft.requestId(),
                  draft.commitId(),
                  draft.digest(),
                  vector,
                  OffsetDateTime.parse("2026-10-14T00:00:00Z")));
      var account =
          new AccountPublicationAuthorizationBinding(
              id("publication-operation:" + publishRequestId + ":" + canonicalVersionId),
              id("publication-fence:" + publishRequestId + ":" + canonicalVersionId),
              new AccountPublicationAuthorizationBinding.PreallocationInput(
                  original.actorAccountId(), selected),
              java.util.Arrays.stream(SourceKind.values())
                  .map(
                      kind ->
                          new SourceEvidence(
                              kind,
                              switch (kind) {
                                case ACCOUNT, GLOBAL_ROLES -> original.actorAccountId().toString();
                                case TENANT -> source.canonicalTenantId().toString();
                                case MEMBERSHIP ->
                                    original.actorAccountId() + "/" + source.canonicalTenantId();
                                default -> "TEST_ONLY-" + kind;
                              },
                              "1",
                              "1",
                              null,
                              null,
                              ("TEST_ONLY-" + kind).getBytes(StandardCharsets.UTF_8)))
                  .toList());
      String workflow =
          PublicationDigestRequestBinding.full(
                  source.canonicalTenantId().toString(),
                  Long.toString(versionRowId),
                  publishRequestId)
              .derivedWorkflowIdentity();
      var world =
          new WorldPublishedStartLocationEvidence(
              new WorldPublishedStartLocationEvidence.Request(
                  source.targetNamespace(),
                  source.canonicalTenantId(),
                  canonicalVersionId,
                  seed.request().intakeRequestId(),
                  id("world-publication-fence:" + publishRequestId + ":" + canonicalVersionId),
                  publishRequestId,
                  selected.digest().substring(7),
                  publicationVersionStateEpoch - 1,
                  workflow,
                  draft.commitId().toString(),
                  seed.request().contentDigest(),
                  3,
                  seed.request().worldAffectedTuples()),
              seed.selectorReceiptBytes(),
              seed.originalAccountBindingBytes(),
              seed.appliedResultBytes());
      var operation =
          new GameDesignPublicationOperationBinding(
              account, world, IsolatedWorldPublicationInventoryFixtures.stipulated(account, world));
      String policyJson =
          RealmPolicySource.canonical(
              Map.of(
                  "schemaVersion",
                  1,
                  "worldSlug",
                  source.worldSlug(),
                  "worldDisplayName",
                  source.worldDisplayName(),
                  "realmSlug",
                  request.realmSlug(),
                  "realmDisplayName",
                  request.realmDisplayName(),
                  "visible",
                  request.visible(),
                  "publicProduction",
                  request.publicProduction(),
                  "stateScope",
                  request.stateScope(),
                  "entryPolicy",
                  request.characterCreationPolicy()));
      var policySource =
          new RealmPolicySource.Policy(
              draft.commitId(),
              id("policy-revision:" + request.requestId()),
              "TEST_ONLY-policy",
              RealmEntryPolicy.parse(policyJson, JSON));
      var capture = new RealmPolicySnapshot(draft, "1", List.of(policySource));
      var participants =
          AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
              .map(
                  owner ->
                      new GameDesignPublicationTerminalEvidence.Participant(
                          owner,
                          Long.toString(versionRowId),
                          null,
                          draft.commitId().toString(),
                          "WORLD_MANAGEMENT".equals(owner)
                              ? world.request().contentDigest()
                              : "c".repeat(64),
                          AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                              owner,
                              AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                          "GAME_LOGIC".equals(owner) ? "sha256:" + "d".repeat(64) : null,
                          null,
                          null))
              .toList();
      var release =
          new GameDesignPublicationTerminalEvidence.ReleaseContent(
              source.canonicalTenantId(),
              canonicalVersionId,
              bundleRef,
              1,
              "v2",
              workflow,
              "sha256:" + "b".repeat(64),
              1,
              List.of(),
              List.of(),
              participants,
              List.of(),
              generationConfigRevision,
              world);
      var terminal =
          new GameDesignPublicationTerminalEvidence(
              operation.canonicalBytes(),
              GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED,
              release,
              publicationVersionStateEpoch);
      UUID policyId = id("policy-identity:" + request.requestId());
      var policy =
          new RealmPolicyPublishedEvidence.Policy(
              policyId,
              policySource,
              RealmPolicyPublishedEvidence.policyDigest(
                  policyId,
                  target,
                  1,
                  bundleRef,
                  release.contentDigest(),
                  workflow,
                  release.manifestHash(),
                  policySource));
      var frame = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(frame, "game-design-realm-policy-source-capture/v1");
      DraftAuthorizationFenceBinding.frame(frame, operation.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(frame, capture.canonicalBytes());
      var policies = List.of(policy);
      String digest =
          RealmPolicyPublishedEvidence.computePolicySetDigest(
              target,
              1,
              draft.commitId(),
              "1",
              bundleRef,
              release.contentDigest(),
              workflow,
              release.manifestHash(),
              publicationVersionStateEpoch,
              operation.canonicalBytes(),
              frame.toByteArray(),
              terminal.canonicalBytes(),
              policies);
      return new RealmPolicyPublishedEvidence.PublishedSet(
          target,
          1,
          draft.commitId(),
          "1",
          bundleRef,
          release.contentDigest(),
          workflow,
          release.manifestHash(),
          publicationVersionStateEpoch,
          operation.canonicalBytes(),
          frame.toByteArray(),
          terminal.canonicalBytes(),
          1,
          digest,
          policies);
    } catch (Exception invalid) {
      throw new IllegalStateException(
          "Controlled complete publication fixture is invalid", invalid);
    }
  }

  /**
   * Controlled original release pair, preserving the schema1 boundary of existing local owner
   * tests.
   */
  public static net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence
      completeBinding(
          net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence descriptor,
          RealmPolicyPublishedEvidence.PublishedSet publishedSet) {
    var terminal =
        GameDesignPublicationTerminalEvidence.fromStored(publishedSet.terminalEvidenceBytes());
    var release = terminal.releaseContent();
    var participants =
        release.participantDigests().stream()
            .map(
                participant ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        participant.participantKey(),
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        publishedSet.sourceCommitId().toString(),
                        participant.contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            participant.participantKey()),
                        participant.abilitySchemaDigest() != null,
                        participant.abilitySchemaDigest()))
            .toList();
    var evidence =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            publishedSet.target().canonicalVersionId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            publishedSet.publishWorkflowId(),
            publishedSet.sourceCommitId().toString(),
            participants,
            release.manifestHash(),
            release.manifestSchemaVersion(),
            release.requiredManifestAssetKeys(),
            release.artifactDigests(),
            release.commandDefinitions(),
            descriptor.generationConfigRevision());
    return new net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence(
        descriptor, evidence);
  }

  /** Controlled Account-issued tuple input; this helper establishes no live Account authority. */
  public static net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple
      tuple(
          String namespace,
          UUID tenant,
          UUID ownerAccountUuid,
          long gameTemplateId,
          String requestId) {
    var action =
        new net.firedevops.firemud.common.operator.StartSessionOperatorAction(
            net.firedevops.firemud.common.operator.StartSessionOperatorAction
                .ACTION_FAMILY_SCHEMA_ID,
            net.firedevops.firemud.common.operator.StartSessionOperatorAction
                .ACTION_FAMILY_SCHEMA_VERSION,
            new net.firedevops.firemud.common.operator.StartSessionOperatorAction.Scope(
                tenant, namespace),
            new net.firedevops.firemud.common.operator.StartSessionOperatorAction.Target(
                gameTemplateId, ownerAccountUuid),
            net.firedevops.firemud.common.operator.StartSessionOperatorAction.ExpectedVersion
                .ABSENT,
            new net.firedevops.firemud.common.operator.StartSessionOperatorAction.Mutation(
                net.firedevops.firemud.common.operator.StartSessionOperatorAction.ClientIp
                    .absent()),
            "TEST_ONLY original Human allocation");
    var pre =
        net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
            .createHuman(requestId, id("actor:" + tenant), action);
    var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    var values =
        new net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture
            .Values(
            id("issuance:" + requestId),
            "sha256:" + "a".repeat(64),
            "1",
            "CURRENT",
            now,
            now.plusSeconds(3600),
            1,
            1,
            1,
            1,
            1,
            1,
            id("ui-token:" + requestId),
            "1",
            "1");
    return net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple
        .createHuman(
            pre,
            "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
            "arfp/v1/test-only/" + "a".repeat(64),
            id("reservation:" + requestId),
            1,
            net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture
                .canonicalBytes(pre, values),
            new net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle
                .BundleReference(
                net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle
                    .BUNDLE_VERSION,
                "1",
                "1",
                "1"));
  }

  /**
   * Calls the actual GS attempt, selection-pin and descriptor-pin owners with controlled upstream
   * inputs.
   */
  public static net.firedevops.firemud.gamesession.repository
          .GameSessionStartSessionOperatorAttemptRepository.AttemptClaim
      pinLaunch(
          org.jooq.DSLContext dsl,
          org.springframework.transaction.support.TransactionTemplate transactions,
          net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple tuple,
          AuthoredWorldSourceEvidence source,
          RealmPolicyPublishedEvidence.PublishedSet publishedSet,
          net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence binding) {
    return pinLaunch(
        dsl, transactions, tuple, source, publishedSet, binding, java.time.Duration.ofMinutes(5));
  }

  public static net.firedevops.firemud.gamesession.repository
          .GameSessionStartSessionOperatorAttemptRepository.AttemptClaim
      pinLaunch(
          org.jooq.DSLContext dsl,
          org.springframework.transaction.support.TransactionTemplate transactions,
          net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple tuple,
          AuthoredWorldSourceEvidence source,
          RealmPolicyPublishedEvidence.PublishedSet publishedSet,
          net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence binding,
          java.time.Duration lease) {
    var attempts =
        new net.firedevops.firemud.gamesession.repository
            .GameSessionStartSessionOperatorAttemptRepository(dsl, lease);
    var selections =
        new net.firedevops.firemud.gamesession.repository
            .GameSessionStartSessionTemplateAssociationRepository(dsl, attempts);
    var descriptors =
        new net.firedevops.firemud.gamesession.repository
            .GameSessionStartSessionLaunchDescriptorRepository(dsl, selections);
    var reservation =
        java.util.Objects.requireNonNull(transactions.execute(status -> attempts.reserve(tuple)));
    var claim = reservation.claim().orElseThrow();
    var bundle =
        net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle.decode(
            tuple.authorityEvidenceBundleBytes());
    var projection =
        new net.firedevops.firemud.gamesession.repository
            .GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection(
            tuple.authorizationReferenceFingerprint(),
            tuple.authorityEvidenceBundleBytes(),
            bundle.issuanceOperationId(),
            Long.parseLong(tuple.issuanceFence()),
            tuple.canonicalBytes());
    transactions.executeWithoutResult(
        status -> attempts.attachAccountRedemptionProjection(claim, projection));
    var terminal =
        GameDesignPublicationTerminalEvidence.fromStored(publishedSet.terminalEvidenceBytes());
    var release = terminal.releaseContent();
    var world = release.worldStartLocationEvidence();
    var worldRequest = world.request();
    var intakeRequest =
        new net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest(
            1,
            source.targetNamespace(),
            worldRequest.intakeRequestId(),
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest());
    var receipt =
        new net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt(
            1,
            source.targetNamespace(),
            worldRequest.intakeRequestId(),
            id("world-source-operation:" + source.operationId()),
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.requestDigest(
                intakeRequest),
            "sha256:" + "9".repeat(64),
            source);
    var selected =
        new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
            .Association(
            source.canonicalTenantId(),
            binding.descriptor().gameTemplateId(),
            publishedSet.target().canonicalVersionId(),
            publishedSet.sourceCommitId(),
            publishedSet.publishWorkflowId(),
            GameDesignPublicationOperationBinding.fromStored(publishedSet.operationBytes())
                .account()
                .input()
                .selection()
                .digest(),
            "sha256:" + "b".repeat(64),
            source.targetNamespace(),
            worldRequest.intakeRequestId(),
            receipt.operationId(),
            source.operationId(),
            source.worldSlug(),
            source.evidenceDigest(),
            new net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec
                .ByIdReadRequest(
                1,
                source.targetNamespace(),
                id("world-source-read:" + tuple.controlPlaneRequestId()),
                worldRequest.intakeRequestId(),
                source.canonicalTenantId()),
            receipt);
    var proto =
        net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle.newBuilder()
            .setId(binding.descriptor().releaseBundleId())
            .setVersionId(binding.descriptor().versionId())
            .setVersionNumber(release.versionNumber())
            .setAttestationSchemaVersion(release.attestationSchemaVersion())
            .setPublishWorkflowId(release.publishWorkflowId())
            .setManifestHash(release.manifestHash())
            .setManifestSchemaVersion(release.manifestSchemaVersion())
            .setGenerationConfigRevision(release.generationConfigRevision())
            .setPublishedReleaseBundleRef(release.publishedReleaseBundleRef())
            .setCanonicalTenantId(source.canonicalTenantId().toString())
            .setCanonicalVersionId(publishedSet.target().canonicalVersionId().toString());
    release
        .participantDigests()
        .forEach(
            participant -> {
              var item =
                  net.firedevops.firemud.gamedesign.v1.ParticipantDigest.newBuilder()
                      .setParticipantKey(participant.participantKey())
                      .setScopeValue(participant.scopeValue())
                      .setAppliedCommitId(participant.appliedCommitId())
                      .setContentDigest(participant.contentDigest())
                      .setDigestSchemaVersion(participant.digestSchemaVersion());
              if (participant.abilitySchemaDigest() != null)
                item.setAbilitySchemaDigest(participant.abilitySchemaDigest());
              proto.addParticipantDigests(item);
            });
    var initialRequest =
        new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
            .Request(
            1,
            source.targetNamespace(),
            id("first-selection:" + tuple.controlPlaneRequestId()),
            tuple.canonicalBytes(),
            claim.ownerAttemptId(),
            claim.ownerFence(),
            new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
                .InitialConfigured());
    var initial =
        new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
            .Result(
            initialRequest,
            selected,
            proto.build(),
            world,
            publishedSet.publicationVersionStateEpoch());
    transactions.execute(status -> selections.pinInitialOrValidateExactReplay(claim, initial));
    var replayRequest =
        new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
            .Request(
            1,
            source.targetNamespace(),
            initialRequest.readRequestId(),
            tuple.canonicalBytes(),
            claim.ownerAttemptId(),
            claim.ownerFence(),
            new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
                .ExactReplay(
                selected.canonicalVersionId(),
                selected.selectedCommitId(),
                selected.publishWorkflowId(),
                selected.associationDigest()));
    var replay =
        new net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
            .Result(replayRequest, selected, proto.build(), world, initial.phaseEpoch());
    var resolved =
        new net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec.Resolved(
            replay,
            new net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec
                .DescriptorOutcome(binding.descriptor()));
    transactions.execute(status -> descriptors.pin(claim, resolved));
    return claim;
  }

  private static final class WorldSeed {
    private final TargetProof target;
    private final String namespace;
    private final String baseReference;

    private WorldSeed(TargetProof target, String namespace) {
      this.target = target;
      this.namespace = namespace;
      this.baseReference =
          "genesis:" + id("reviewed-genesis:" + namespace + ":" + target.canonicalVersionId());
      this.TENANT_ID = target.canonicalTenantId();
      this.VERSION_ID = target.canonicalVersionId();
    }

    private WorldPublishedStartLocationEvidence build() throws Exception {
      return build(freshGraphBinding());
    }

    private WorldPublishedStartLocationEvidence build(DraftCommitBinding draft) throws Exception {
      var terminal =
          WorldDraftTerminalReadEvidence.Request.create(namespace, accountBinding(draft));
      var result = committedReadback(terminal, true);
      var applied = JSON.readTree(result.result());
      var tuples =
          draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
              .sorted(
                  Comparator.comparing(AffectedUnit::owner)
                      .thenComparing(AffectedUnit::aggregateType)
                      .thenComparing(AffectedUnit::aggregateId)
                      .thenComparing(AffectedUnit::scopeType)
                      .thenComparing(AffectedUnit::scopeId)
                      .thenComparing(AffectedUnit::expectedEpoch))
              .map(
                  u ->
                      new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                          u.owner().name(),
                          u.aggregateType(),
                          u.aggregateId(),
                          u.scopeType(),
                          u.scopeId(),
                          u.expectedEpoch()))
              .toList();
      var request =
          new WorldPublishedStartLocationEvidence.Request(
              namespace,
              draft.target().canonicalTenantId(),
              draft.target().canonicalVersionId(),
              uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
              uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
              "publication-request",
              "a".repeat(64),
              5L,
              "publish-workflow",
              draft.commitId().toString(),
              "b".repeat(64),
              3,
              tuples);
      return new WorldPublishedStartLocationEvidence(
          request,
          Base64.getDecoder().decode(applied.get("startLocationReceiptBase64").textValue()),
          result.fullBinding(),
          result.result());
    }

    private final UUID OPERATION_ID = uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private final UUID REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
    private final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
    private final UUID FENCE_ID = uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private final UUID ACTOR_ID = uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private final UUID TENANT_ID;
    private final UUID VERSION_ID;
    private final UUID REVISION_ID = uuid("66666666-6666-4666-8666-666666666666");
    private final UUID REGION_TEMPLATE_ID = uuid("88888888-8888-4888-8888-888888888888");
    private final UUID ZONE_TEMPLATE_ID = uuid("99999999-9999-4999-8999-999999999999");
    private final UUID ROOM_TEMPLATE_ID = uuid("77777777-7777-4777-8777-777777777777");
    private final UUID REGION_REVISION_ID = uuid("12345678-1234-4234-8234-123456789001");
    private final UUID ZONE_REVISION_ID = uuid("12345678-1234-4234-8234-123456789002");
    private final UUID ROOM_REVISION_ID = uuid("12345678-1234-4234-8234-123456789003");
    private final tools.jackson.databind.ObjectMapper JSON =
        new tools.jackson.databind.ObjectMapper();

    private DraftAuthorizationFenceBinding.OwnerReadback committedReadback(
        WorldDraftTerminalReadEvidence.Request request, boolean freshGraph) throws Exception {
      var account = request.accountBinding();
      var draft =
          DraftCommitBinding.fromStored(
              new String(account.gameDesignBinding(), StandardCharsets.UTF_8),
              account.inputDigest());
      var operation = new ByteArrayOutputStream();
      var frames = new java.io.DataOutputStream(operation);
      java.util.function.Consumer<byte[]> frame =
          bytes -> {
            try {
              frames.writeInt(bytes.length);
              frames.write(bytes);
            } catch (java.io.IOException impossible) {
              throw new AssertionError(impossible);
            }
          };
      java.util.function.Consumer<String> text =
          value -> frame.accept(value.getBytes(StandardCharsets.UTF_8));
      text.accept("world-draft-terminal-operation/v1");
      for (UUID id :
          List.of(
              account.operationId(),
              account.requestId(),
              account.commitId(),
              account.fenceId(),
              account.tenantId(),
              account.versionId())) text.accept(id.toString());
      frame.accept(draft.canonicalBytes());
      for (String value :
          List.of(
              request.targetNamespace(),
              account.tenantId().toString(),
              account.versionId().toString(),
              "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
              Long.toString(draft.target().gameDesignVersionRowId()),
              "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
              "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
              "a".repeat(64),
              "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
              "b".repeat(64),
              "c".repeat(64))) text.accept(value);
      text.accept(sha256(account.canonicalBytes()));
      frame.accept(account.canonicalBytes());
      byte[] graph = freshGraph ? graphBytes(request, draft) : historicalGraphBytes(account);
      String graphDigest = sha256(graph);
      var result = new java.util.LinkedHashMap<String, Object>();
      result.put(
          "schema", freshGraph ? "world-draft-graph-applied/v2" : "world-draft-graph-applied/v1");
      result.put("status", "APPLIED");
      result.put(
          "operationBytesBase64", Base64.getEncoder().encodeToString(operation.toByteArray()));
      result.put("graphBytesBase64", Base64.getEncoder().encodeToString(graph));
      result.put("graphDigest", graphDigest);
      if (freshGraph) {
        var receipt = receipt(request, draft, graphDigest);
        byte[] receiptBytes = canonical(receipt);
        result.put("startLocationReceiptBase64", Base64.getEncoder().encodeToString(receiptBytes));
        result.put("startLocationReceiptDigest", receipt.get("receiptDigest").textValue());
      }
      result.put(
          "appliedEpochs",
          draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
              .map(
                  unit ->
                      Map.of(
                          "aggregateType",
                          unit.aggregateType(),
                          "aggregateId",
                          unit.aggregateId(),
                          "scopeType",
                          unit.scopeType(),
                          "scopeId",
                          unit.scopeId(),
                          "expectedEpoch",
                          unit.expectedEpoch(),
                          "resultingEpoch",
                          new java.math.BigInteger(unit.expectedEpoch())
                              .add(java.math.BigInteger.ONE)
                              .toString()))
              .toList());
      return new DraftAuthorizationFenceBinding.OwnerReadback(
          Owner.WORLD,
          Outcome.COMMITTED,
          account.operationId(),
          account.commitId(),
          account.fenceId(),
          account.inputDigest(),
          account.canonicalBytes(),
          canonical(JSON.valueToTree(result)));
    }

    private DraftCommitBinding freshGraphBinding() throws Exception {
      return DraftCommitBinding.create(
          new TargetProof(
              TENANT_ID,
              VERSION_ID,
              target.gameDesignVersionRowId(),
              target.gameDesignVersionTenantKey(),
              target.sourceGameRowId(),
              target.sourceGameTenantKey(),
              target.sourceProvenanceKind()),
          REQUEST_ID,
          COMMIT_ID,
          baseReference,
          freshWorldRevisions(COMMIT_ID, 0),
          freshWorldAffectedUnits());
    }

    private List<RevisionPayload> freshWorldRevisions(UUID commitId, int revisionOffset)
        throws Exception {
      String declaration =
          JSON.writeValueAsString(
              Map.of(
                  "tenantId", TENANT_ID.toString(),
                  "versionId", VERSION_ID.toString(),
                  "startLocation",
                      Map.of(
                          "tenantId", TENANT_ID.toString(),
                          "versionId", VERSION_ID.toString(),
                          "roomTemplateId", ROOM_TEMPLATE_ID.toString()),
                  "familyCounts",
                      List.of(
                          Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_REGION", "count", 1),
                          Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ZONE", "count", 1),
                          Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM", "count", 1),
                          Map.of("family", "WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT", "count", 0),
                          Map.of(
                              "family", "WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE", "count", 0),
                          Map.of(
                              "family",
                              "WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING",
                              "count",
                              0))));
      return List.of(
          new RevisionPayload(
              Integer.toString(revisionOffset),
              REGION_REVISION_ID,
              DraftCommitBinding.Owner.WORLD_MANAGEMENT,
              worldRevisionPayload(
                  commitId,
                  REGION_REVISION_ID,
                  "WORLD_DESIGN_AGGREGATE_TYPE_REGION",
                  REGION_TEMPLATE_ID,
                  declaration)),
          new RevisionPayload(
              Integer.toString(revisionOffset + 1),
              ZONE_REVISION_ID,
              DraftCommitBinding.Owner.WORLD_MANAGEMENT,
              worldRevisionPayload(
                  commitId,
                  ZONE_REVISION_ID,
                  "WORLD_DESIGN_AGGREGATE_TYPE_ZONE",
                  ZONE_TEMPLATE_ID,
                  null)),
          new RevisionPayload(
              Integer.toString(revisionOffset + 2),
              ROOM_REVISION_ID,
              DraftCommitBinding.Owner.WORLD_MANAGEMENT,
              worldRevisionPayload(
                  commitId,
                  ROOM_REVISION_ID,
                  "WORLD_DESIGN_AGGREGATE_TYPE_ROOM",
                  ROOM_TEMPLATE_ID,
                  null)));
    }

    private List<AffectedUnit> freshWorldAffectedUnits() {
      return List.of(
              affected("REGION", REGION_TEMPLATE_ID, "REGION_SUBTREE", REGION_TEMPLATE_ID),
              affected("ZONE", ZONE_TEMPLATE_ID, "REGION_SUBTREE", REGION_TEMPLATE_ID),
              affected("ROOM", ROOM_TEMPLATE_ID, "ZONE_SUBTREE", ZONE_TEMPLATE_ID))
          .stream()
          .flatMap(List::stream)
          .toList();
    }

    private String worldRevisionPayload(
        UUID commitId, UUID revisionId, String family, UUID templateId, String declaration)
        throws Exception {
      var payload = new java.util.LinkedHashMap<String, Object>();
      payload.put("logicalRevisionId", revisionId.toString());
      payload.put("commitId", commitId.toString());
      payload.put("aggregateType", family);
      payload.put("aggregateId", templateId.toString());
      if (declaration != null) payload.put("freshGraphDeclaration", JSON.readTree(declaration));
      return JSON.writeValueAsString(payload);
    }

    private List<AffectedUnit> affected(
        String family, UUID templateId, String scopeType, UUID scopeId) {
      return List.of(
          new AffectedUnit(
              DraftCommitBinding.Owner.WORLD_MANAGEMENT,
              family,
              templateId.toString(),
              "AGGREGATE",
              templateId.toString(),
              "0"),
          new AffectedUnit(
              DraftCommitBinding.Owner.WORLD_MANAGEMENT,
              family,
              templateId.toString(),
              scopeType,
              scopeId.toString(),
              "0"));
    }

    private byte[] accountBinding(DraftCommitBinding draft) {
      byte[] draftBytes = draft.canonicalBytes();
      return new DraftAuthorizationFenceBinding(
              OPERATION_ID,
              draft.requestId(),
              draft.commitId(),
              FENCE_ID,
              ACTOR_ID,
              draft.target().canonicalTenantId(),
              draft.target().canonicalVersionId(),
              draft.baseCommitId(),
              "0",
              draftBytes,
              draftBytes,
              draft.digest(),
              List.of(
                  new SourceEvidence(
                      SourceKind.GLOBAL_ROLES,
                      uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd").toString(),
                      null,
                      "1",
                      null,
                      null,
                      new byte[] {4, 5})))
          .withRequiredOwners()
          .canonicalBytes();
    }

    private byte[] graphBytes(
        WorldDraftTerminalReadEvidence.Request request, DraftCommitBinding draft) throws Exception {
      var root = new java.util.LinkedHashMap<String, Object>();
      root.put("schemaVersion", "2");
      root.put("canonicalTenantId", draft.target().canonicalTenantId().toString());
      root.put("canonicalVersionId", draft.target().canonicalVersionId().toString());
      var rows = new java.util.ArrayList<Map<String, Object>>();
      int mappingId = 1;
      long privateRowKey = 101;
      for (RevisionPayload revision : draft.revisions()) {
        if (revision.owner() != DraftCommitBinding.Owner.WORLD_MANAGEMENT) continue;
        var mutation = JSON.readTree(revision.payload());
        String family =
            mutation.get("aggregateType").textValue().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
        var mapping = new java.util.LinkedHashMap<String, Object>();
        mapping.put("id", mappingId++);
        mapping.put("target_namespace", request.targetNamespace());
        mapping.put("canonical_tenant_id", draft.target().canonicalTenantId().toString());
        mapping.put("canonical_version_id", draft.target().canonicalVersionId().toString());
        mapping.put("family", family);
        mapping.put("template_id", mutation.get("aggregateId").textValue());
        mapping.put("private_row_key", privateRowKey++);
        mapping.put("tenant_id", 11);
        mapping.put("version_id", target.gameDesignVersionRowId());
        mapping.put("version_identity_operation_id", OPERATION_ID.toString());
        mapping.put("request_id", draft.requestId().toString());
        mapping.put("commit_id", draft.commitId().toString());
        mapping.put("revision_id", revision.revisionId().toString());
        mapping.put("revision_order", revision.revisionOrder());
        rows.add(Map.of("mapping", mapping, "content", Map.of()));
      }
      root.put("rows", rows);
      return JSON.writeValueAsBytes(root);
    }

    private byte[] historicalGraphBytes(DraftAuthorizationFenceBinding account) {
      throw new IllegalArgumentException("This fixture supplies only selector-bearing APPLIED v2");
    }

    private tools.jackson.databind.node.ObjectNode receipt(
        WorldDraftTerminalReadEvidence.Request request,
        DraftCommitBinding draft,
        String graphDigest)
        throws Exception {
      var account = request.accountBinding();
      var receipt =
          WorldDraftStartLocationEvidence.create(
              request.targetNamespace(),
              account.operationId(),
              account.requestId(),
              account.commitId(),
              account.fenceId(),
              sha256(account.canonicalBytes()),
              draft.digest(),
              new RoomTemplateRef(TENANT_ID, VERSION_ID, ROOM_TEMPLATE_ID),
              graphDigest);
      return (tools.jackson.databind.node.ObjectNode) JSON.readTree(receipt.canonicalBytes());
    }

    private byte[] canonical(tools.jackson.databind.JsonNode value) throws Exception {
      return net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
          JSON.writeValueAsString(value));
    }

    private String sha256(byte[] bytes) throws Exception {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private UUID uuid(String value) {
      return UUID.fromString(value);
    }
  }
}
