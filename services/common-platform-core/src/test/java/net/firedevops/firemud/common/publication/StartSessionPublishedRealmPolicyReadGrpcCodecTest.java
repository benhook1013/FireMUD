package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodecTest;
import net.firedevops.firemud.common.gamedesign.StartSessionPublishedRealmPolicyReadGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionPublishedRealmPolicyResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Common structural codec units. Vectors combine the existing exact-replay descriptor fixture with
 * actual canonical publication operation and terminal bytes; they are not a database, running
 * server, or production-producer proof.
 */
class StartSessionPublishedRealmPolicyReadGrpcCodecTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID FIRST_POLICY_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SECOND_POLICY_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID FIRST_REVISION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID SECOND_REVISION_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");

  @Test
  void roundTripsTheCompleteExactReplayRequestAndOwnerResponseWithoutNestedMockDecoders()
      throws Exception {
    Fixture fixture = fixture();

    var decoded =
        StartSessionPublishedRealmPolicyReadGrpcCodec.fromResponse(
            fixture.wireRequest(), fixture.response());

    assertThat(
            StartSessionPublishedRealmPolicyReadGrpcCodec.fromRequest(
                StartSessionPublishedRealmPolicyReadGrpcCodec.toRequest(fixture.request())))
        .isEqualTo(fixture.wireRequest());
    assertThat(decoded.request().targetNamespace()).isEqualTo(fixture.request().targetNamespace());
    assertThat(decoded.request().readRequestId()).isEqualTo(fixture.request().readRequestId());
    assertThat(decoded.request().authorizationRequest())
        .isEqualTo(fixture.request().authorizationRequest());
    assertThat(decoded.request().descriptor()).isEqualTo(fixture.request().descriptor());
    assertThat(decoded.request().releaseAttestation().evidenceDigest())
        .isEqualTo(fixture.request().releaseAttestation().evidenceDigest());
    assertThat(decoded.request().target()).isEqualTo(fixture.request().target());
    assertThat(decoded.authorizationResult().request())
        .isEqualTo(fixture.authorizationResult().request());
    assertThat(decoded.authorizationResult().association().associationDigest())
        .isEqualTo(fixture.authorizationResult().association().associationDigest());
    assertThat(decoded.authorizationResult().association().selectedCommitId())
        .isEqualTo(fixture.authorizationResult().association().selectedCommitId());
    assertThat(decoded.authorizationResult().association().publishWorkflowId())
        .isEqualTo(fixture.authorizationResult().association().publishWorkflowId());
    assertThat(decoded.authorizationResult().releaseBundle())
        .isEqualTo(fixture.authorizationResult().releaseBundle());
    assertThat(decoded.authorizationResult().worldPublishedStartLocationEvidence().canonicalBytes())
        .containsExactly(
            fixture.authorizationResult().worldPublishedStartLocationEvidence().canonicalBytes());
    assertThat(decoded.authorizationResult().phaseEpoch())
        .isEqualTo(fixture.authorizationResult().phaseEpoch());
    assertThat(decoded.sourceEvidence()).isEqualTo(fixture.sourceEvidence());
    assertThat(decoded.publishedSet().target()).isEqualTo(fixture.publishedSet().target());
    assertThat(decoded.publishedSet().sourceCommitId())
        .isEqualTo(fixture.publishedSet().sourceCommitId());
    assertThat(decoded.publishedSet().sourceEpoch())
        .isEqualTo(fixture.publishedSet().sourceEpoch());
    assertThat(decoded.publishedSet().publishedReleaseBundleRef())
        .isEqualTo(fixture.publishedSet().publishedReleaseBundleRef());
    assertThat(decoded.publishedSet().publishedReleaseBundleDigest())
        .isEqualTo(fixture.publishedSet().publishedReleaseBundleDigest());
    assertThat(decoded.publishedSet().publishWorkflowId())
        .isEqualTo(fixture.publishedSet().publishWorkflowId());
    assertThat(decoded.publishedSet().manifestHash())
        .isEqualTo(fixture.publishedSet().manifestHash());
    assertThat(decoded.publishedSet().publicationVersionStateEpoch())
        .isEqualTo(fixture.publishedSet().publicationVersionStateEpoch());
    assertThat(decoded.publishedSet().operationBytes())
        .containsExactly(fixture.publishedSet().operationBytes());
    assertThat(decoded.publishedSet().captureBytes())
        .containsExactly(fixture.publishedSet().captureBytes());
    assertThat(decoded.publishedSet().terminalEvidenceBytes())
        .containsExactly(fixture.publishedSet().terminalEvidenceBytes());
    assertThat(decoded.publishedSet().policies())
        .containsExactlyElementsOf(fixture.publishedSet().policies());
    assertThat(decoded.publishedSet().policySetDigest())
        .isEqualTo(fixture.publishedSet().policySetDigest());
    assertThat(fixture.response().getRequest()).isEqualTo(fixture.wireRequest().message());
    assertThat(fixture.response().getAuthorizationResponse().getRequest())
        .isEqualTo(fixture.wireRequest().message().getAuthorizationRequest());
    assertThat(fixture.authorizationResult().request().selection())
        .isInstanceOf(StartSessionTemplateAssociationReadEvidence.ExactReplay.class);
  }

  @Test
  void rejectsChangedEchoAuthorizationCorrelationTargetSourceReleaseAndPolicyEvidence()
      throws Exception {
    Fixture fixture = fixture();
    ReadStartSessionPublishedRealmPolicyResponse response = fixture.response();
    var wireRequest = fixture.wireRequest();

    var changedEcho =
        response.toBuilder()
            .setRequest(
                response.getRequest().toBuilder()
                    .setReadRequestId("88888888-8888-4888-8888-888888888888"))
            .build();
    assertRejected(wireRequest, changedEcho);

    var authorization = response.getAuthorizationResponse();
    var changedAuthorizationCorrelation =
        response.toBuilder()
            .setAuthorizationResponse(
                authorization.toBuilder()
                    .setRequest(
                        authorization.getRequest().toBuilder()
                            .setOwnerFence(authorization.getRequest().getOwnerFence() + 1)))
            .build();
    assertRejected(wireRequest, changedAuthorizationCorrelation);

    String targetJson =
        new String(
            wireRequest.message().getCanonicalTargetProofJson().toByteArray(),
            StandardCharsets.UTF_8);
    String changedTargetJson =
        targetJson.replace(
            "\"sourceGameRowId\":\"" + fixture.publishedSet().target().sourceGameRowId() + "\"",
            "\"sourceGameRowId\":\""
                + (fixture.publishedSet().target().sourceGameRowId() + 1)
                + "\"");
    assertThat(changedTargetJson).isNotEqualTo(targetJson);
    var changedTargetWire =
        StartSessionPublishedRealmPolicyReadGrpcCodec.fromRequest(
            wireRequest.message().toBuilder()
                .setCanonicalTargetProofJson(ByteString.copyFromUtf8(changedTargetJson))
                .build());
    assertRejected(
        changedTargetWire, response.toBuilder().setRequest(changedTargetWire.message()).build());

    ResolveAuthoredWorldSourceResponse source = response.getSourceEvidence();
    var changedSource =
        response.toBuilder()
            .setSourceEvidence(
                source.toBuilder().setSourceGameRowId(source.getSourceGameRowId() + 1))
            .build();
    assertRejected(wireRequest, changedSource);

    var changedReleaseRequest =
        wireRequest.message().toBuilder()
            .setReleaseAttestation(
                wireRequest.message().getReleaseAttestation().toBuilder()
                    .setVersionStateEpoch(
                        wireRequest.message().getReleaseAttestation().getVersionStateEpoch() + 1))
            .build();
    var changedReleaseWire =
        StartSessionPublishedRealmPolicyReadGrpcCodec.fromRequest(changedReleaseRequest);
    assertRejected(
        changedReleaseWire, response.toBuilder().setRequest(changedReleaseWire.message()).build());

    byte[] changedCarrier = response.getPublishedPolicySet().toByteArray();
    byte[] policyMarker = "\\\"realmSlug\\\":\\\"realm-a\\\"".getBytes(StandardCharsets.UTF_8);
    int policyOffset = indexOf(changedCarrier, policyMarker);
    assertThat(policyOffset).isGreaterThanOrEqualTo(0);
    changedCarrier[policyOffset + policyMarker.length - 3] = (byte) 'z';
    assertRejected(
        wireRequest,
        response.toBuilder().setPublishedPolicySet(ByteString.copyFrom(changedCarrier)).build());
  }

  @Test
  void actualPublicationOperationAndTerminalRemainInsideTheCanonicalCarrier() throws Exception {
    RealmPolicyPublishedEvidence.PublishedSet original = publishedSet();

    byte[] carrier = RealmPolicyPublishedEvidenceCodec.encode(original);
    RealmPolicyPublishedEvidence.PublishedSet decoded =
        RealmPolicyPublishedEvidenceCodec.decode(carrier);

    assertThat(decoded.target()).isEqualTo(original.target());
    assertThat(decoded.sourceCommitId()).isEqualTo(original.sourceCommitId());
    assertThat(decoded.publishedReleaseBundleRef()).isEqualTo(original.publishedReleaseBundleRef());
    assertThat(decoded.publicationVersionStateEpoch())
        .isEqualTo(original.publicationVersionStateEpoch());
    assertThat(decoded.operationBytes()).containsExactly(original.operationBytes());
    assertThat(decoded.captureBytes()).containsExactly(original.captureBytes());
    assertThat(decoded.terminalEvidenceBytes()).containsExactly(original.terminalEvidenceBytes());
    assertThat(decoded.policies()).containsExactlyElementsOf(original.policies());
    assertThat(RealmPolicyPublishedEvidence.targetProofJson(decoded.target()))
        .isEqualTo(RealmPolicyPublishedEvidence.targetProofJson(original.target()));
  }

  private static RealmPolicyPublishedEvidence.PublishedSet publishedSet() throws Exception {
    var operation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    var terminal =
        GameDesignPublicationTerminalReadGrpcCodecTest.terminal(operation, Outcome.PUBLISHED);
    return publishedSet(operation, terminal);
  }

  private static RealmPolicyPublishedEvidence.PublishedSet publishedSet(
      GameDesignPublicationOperationBinding operation,
      net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence terminal) {
    DraftCommitBinding selectedCommit = operation.account().input().selection().selectedCommit();
    DraftCommitBinding.TargetProof target = selectedCommit.target();
    var sources =
        List.of(
            new RealmPolicySource.Policy(
                selectedCommit.commitId(),
                FIRST_REVISION_ID,
                "logical-realm-a",
                policy("realm-a", "World", true)),
            new RealmPolicySource.Policy(
                selectedCommit.commitId(),
                SECOND_REVISION_ID,
                "logical-realm-b",
                policy("realm-b", "World", false)));
    var snapshot =
        new net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot(
            selectedCommit, "7", sources);
    byte[] captureBytes = capture(operation.canonicalBytes(), snapshot.canonicalBytes());
    var release = terminal.releaseContent();
    var policies =
        List.of(
            new RealmPolicyPublishedEvidence.Policy(
                FIRST_POLICY_ID,
                sources.getFirst(),
                RealmPolicyPublishedEvidence.policyDigest(
                    FIRST_POLICY_ID,
                    target,
                    release.versionNumber(),
                    release.publishedReleaseBundleRef(),
                    release.contentDigest(),
                    release.publishWorkflowId(),
                    release.manifestHash(),
                    sources.getFirst())),
            new RealmPolicyPublishedEvidence.Policy(
                SECOND_POLICY_ID,
                sources.get(1),
                RealmPolicyPublishedEvidence.policyDigest(
                    SECOND_POLICY_ID,
                    target,
                    release.versionNumber(),
                    release.publishedReleaseBundleRef(),
                    release.contentDigest(),
                    release.publishWorkflowId(),
                    release.manifestHash(),
                    sources.get(1))));
    byte[] operationBytes = operation.canonicalBytes();
    byte[] terminalBytes = terminal.canonicalBytes();
    String digest =
        RealmPolicyPublishedEvidence.computePolicySetDigest(
            target,
            release.versionNumber(),
            selectedCommit.commitId(),
            snapshot.sourceEpoch(),
            release.publishedReleaseBundleRef(),
            release.contentDigest(),
            release.publishWorkflowId(),
            release.manifestHash(),
            terminal.publicationVersionStateEpoch(),
            operationBytes,
            captureBytes,
            terminalBytes,
            policies);
    return new RealmPolicyPublishedEvidence.PublishedSet(
        target,
        release.versionNumber(),
        selectedCommit.commitId(),
        snapshot.sourceEpoch(),
        release.publishedReleaseBundleRef(),
        release.contentDigest(),
        release.publishWorkflowId(),
        release.manifestHash(),
        terminal.publicationVersionStateEpoch(),
        operationBytes,
        captureBytes,
        terminalBytes,
        policies.size(),
        digest,
        policies);
  }

  private static Fixture fixture() throws Exception {
    var launch = StartSessionLaunchDescriptorGrpcCodecTest.fixture();
    var operation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    var terminal =
        GameDesignPublicationTerminalReadGrpcCodecTest.terminal(operation, Outcome.PUBLISHED);
    var target = operation.account().input().selection().target();
    var content = terminal.releaseContent();
    var oldRequest = launch.request();
    var oldResult = launch.associationRead();
    var oldAssociation = oldResult.association();
    UUID selectedCommit = UUID.fromString(operation.world().request().appliedCommitId());
    var association =
        new StartSessionTemplateAssociationReadEvidence.Association(
            oldAssociation.canonicalTenantId(),
            oldAssociation.templateId(),
            target.canonicalVersionId(),
            selectedCommit,
            content.publishWorkflowId(),
            oldAssociation.publicationSelectionDigest(),
            oldAssociation.associationDigest(),
            oldAssociation.targetNamespace(),
            oldAssociation.intakeRequestId(),
            oldAssociation.worldOperationId(),
            oldAssociation.sourceOperationId(),
            oldAssociation.worldSlug(),
            oldAssociation.sourceEvidenceDigest(),
            oldAssociation.worldReadRequest(),
            oldAssociation.worldReceipt());
    var authorizationRequest =
        new StartSessionTemplateAssociationReadEvidence.Request(
            oldRequest.schemaVersion(),
            oldRequest.targetNamespace(),
            oldRequest.readRequestId(),
            oldRequest.canonicalPostAuthorizationTuple(),
            oldRequest.ownerAttemptId(),
            oldRequest.ownerFence(),
            new StartSessionTemplateAssociationReadEvidence.ExactReplay(
                target.canonicalVersionId(),
                selectedCommit,
                content.publishWorkflowId(),
                association.associationDigest()));

    var originalDescriptor = launch.descriptor();
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            originalDescriptor.request(),
            originalDescriptor.launchDescriptorId(),
            target.gameDesignVersionRowId(),
            false,
            null,
            originalDescriptor.runtimeFlagsJson(),
            content.generationConfigRevision(),
            originalDescriptor.versionStateEpoch(),
            originalDescriptor.releaseBundleId(),
            content.publishedReleaseBundleRef(),
            false,
            null);
    PublishedReleaseBundle releaseBundle =
        releaseBundle(oldResult.releaseBundle(), descriptor, content);
    var authorizationResult =
        new StartSessionTemplateAssociationReadEvidence.Result(
            authorizationRequest,
            association,
            releaseBundle,
            operation.world(),
            oldResult.phaseEpoch());
    var release = releaseAttestation(descriptor, content, operation.world());
    var request =
        new StartSessionPublishedRealmPolicyReadGrpcCodec.ReadRequest(
            oldRequest.targetNamespace(),
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            authorizationRequest,
            descriptor,
            release,
            target);
    var wireRequest =
        StartSessionPublishedRealmPolicyReadGrpcCodec.fromRequest(
            StartSessionPublishedRealmPolicyReadGrpcCodec.toRequest(request));
    AuthoredWorldSourceEvidence source = oldAssociation.worldReceipt().source();
    RealmPolicyPublishedEvidence.PublishedSet publishedSet = publishedSet(operation, terminal);
    assertThat(publishedSet.target()).isEqualTo(target);
    var authorized =
        StartSessionPublishedRealmPolicyReadGrpcCodec.authorize(wireRequest, authorizationResult);
    var response =
        StartSessionPublishedRealmPolicyReadGrpcCodec.toResponse(
            wireRequest, authorized, authorizationResult, source, publishedSet);
    return new Fixture(wireRequest, request, authorizationResult, source, publishedSet, response);
  }

  private static PublishedReleaseBundle releaseBundle(
      PublishedReleaseBundle original,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      GameDesignPublicationTerminalEvidence.ReleaseContent content) {
    var builder =
        original.toBuilder()
            .setId(descriptor.releaseBundleId())
            .setVersionId(descriptor.versionId())
            .setVersionNumber(content.versionNumber())
            .setAttestationSchemaVersion(content.attestationSchemaVersion())
            .setPublishWorkflowId(content.publishWorkflowId())
            .setManifestHash(content.manifestHash())
            .setManifestSchemaVersion(content.manifestSchemaVersion())
            .setGenerationConfigRevision(content.generationConfigRevision())
            .setPublishedReleaseBundleRef(content.publishedReleaseBundleRef())
            .setCanonicalTenantId(content.canonicalTenantId().toString())
            .setCanonicalVersionId(content.canonicalVersionId().toString())
            .clearArtifactDigests()
            .clearRequiredManifestAssetKeys()
            .clearParticipantDigests()
            .clearCommandDefinitions();
    content.artifactDigests().stream()
        .map(
            artifact ->
                net.firedevops.firemud.gamedesign.v1.PublishedArtifactDigest.newBuilder()
                    .setUsageKey(artifact.usageKey())
                    .setArtifactKind(artifact.artifactKind())
                    .setImmutableObjectKey(artifact.immutableObjectKey())
                    .setContentDigest(artifact.contentDigest())
                    .setContentType(artifact.contentType())
                    .setArtifactSchemaVersion(artifact.artifactSchemaVersion())
                    .build())
        .forEach(builder::addArtifactDigests);
    builder.addAllRequiredManifestAssetKeys(content.requiredManifestAssetKeys());
    content.participantDigests().stream()
        .map(
            participant -> {
              var value =
                  net.firedevops.firemud.gamedesign.v1.ParticipantDigest.newBuilder()
                      .setParticipantKey(participant.participantKey())
                      .setScopeValue(participant.scopeValue())
                      .setAppliedCommitId(participant.appliedCommitId())
                      .setContentDigest(participant.contentDigest())
                      .setDigestSchemaVersion(participant.digestSchemaVersion());
              if (participant.abilitySchemaDigest() != null) {
                value.setAbilitySchemaDigest(participant.abilitySchemaDigest());
              }
              return value.build();
            })
        .forEach(builder::addParticipantDigests);
    return builder.addAllCommandDefinitions(content.commandDefinitions()).build();
  }

  private static AuthoredWorldReleaseAttestationEvidence releaseAttestation(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      GameDesignPublicationTerminalEvidence.ReleaseContent content,
      WorldPublishedStartLocationEvidence worldEvidence) {
    var participants =
        content.participantDigests().stream()
            .map(
                participant ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        participant.participantKey(),
                        participant.scopeValue(),
                        participant.baseVersionId() != null,
                        participant.baseVersionId(),
                        participant.appliedCommitId(),
                        participant.contentDigest(),
                        participant.digestSchemaVersion(),
                        participant.abilitySchemaDigest() != null,
                        participant.abilitySchemaDigest()))
            .toList();
    return AuthoredWorldReleaseAttestationEvidence.create(
        descriptor.targetNamespace(),
        descriptor.resultDigest(),
        content.canonicalTenantId(),
        content.canonicalVersionId(),
        descriptor.worldSlug(),
        descriptor.authoredWorldSourceOperationId(),
        descriptor.authoredWorldSourceEvidenceDigest(),
        descriptor.launchDescriptorId(),
        content.publishedReleaseBundleRef(),
        descriptor.versionStateEpoch(),
        content.publishWorkflowId(),
        content.participantDigests().getFirst().appliedCommitId(),
        participants,
        content.manifestHash(),
        content.manifestSchemaVersion(),
        content.requiredManifestAssetKeys(),
        content.artifactDigests(),
        content.commandDefinitions(),
        content.generationConfigRevision(),
        worldEvidence);
  }

  private static void assertRejected(
      StartSessionPublishedRealmPolicyReadGrpcCodec.WireRequest expected,
      ReadStartSessionPublishedRealmPolicyResponse response) {
    assertThatThrownBy(
            () -> StartSessionPublishedRealmPolicyReadGrpcCodec.fromResponse(expected, response))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static int indexOf(byte[] bytes, byte[] needle) {
    for (int offset = 0; offset <= bytes.length - needle.length; offset++) {
      boolean matches = true;
      for (int index = 0; index < needle.length; index++) {
        if (bytes[offset + index] != needle[index]) {
          matches = false;
          break;
        }
      }
      if (matches) return offset;
    }
    return -1;
  }

  private static RealmEntryPolicy policy(
      String realmSlug, String worldDisplayName, boolean production) {
    return RealmEntryPolicy.parse(
        "{\"entryPolicy\":\"PRESEEDED_ONLY\",\"publicProduction\":"
            + production
            + ",\"realmDisplayName\":\""
            + realmSlug
            + "\",\"realmSlug\":\""
            + realmSlug
            + "\",\"schemaVersion\":1,\"stateScope\":\"SHARED\",\"visible\":true,"
            + "\"worldDisplayName\":\""
            + worldDisplayName
            + "\",\"worldSlug\":\"world\"}",
        JSON);
  }

  private static byte[] capture(byte[] operationBytes, byte[] snapshotBytes) {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, "game-design-realm-policy-source-capture/v1");
    DraftAuthorizationFenceBinding.frame(out, operationBytes);
    DraftAuthorizationFenceBinding.frame(out, snapshotBytes);
    return out.toByteArray();
  }

  private record Fixture(
      StartSessionPublishedRealmPolicyReadGrpcCodec.WireRequest wireRequest,
      StartSessionPublishedRealmPolicyReadGrpcCodec.ReadRequest request,
      StartSessionTemplateAssociationReadEvidence.Result authorizationResult,
      AuthoredWorldSourceEvidence sourceEvidence,
      RealmPolicyPublishedEvidence.PublishedSet publishedSet,
      ReadStartSessionPublishedRealmPolicyResponse response) {}
}
