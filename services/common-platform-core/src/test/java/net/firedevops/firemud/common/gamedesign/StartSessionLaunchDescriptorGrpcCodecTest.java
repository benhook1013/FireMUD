package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.IntakeRequest;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.StartSessionLaunchDescriptorApplicationFailure;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StartSessionLaunchDescriptorGrpcCodecTest {
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID READ_ID = uuid("01111111-1111-4111-8111-111111111111");
  private static final UUID ATTEMPT = uuid("02222222-2222-4222-8222-222222222222");
  private static final UUID REGISTRATION_ID = uuid("03333333-3333-4333-8333-333333333333");
  private static final UUID INTAKE_ID = uuid("04444444-4444-4444-8444-444444444444");
  private static final UUID WORLD_OPERATION_ID = uuid("05555555-5555-4555-8555-555555555555");
  private static final UUID SOURCE_OPERATION_ID = uuid("06666666-6666-4666-8666-666666666666");
  private static final UUID WORLD_READ_ID = uuid("07777777-7777-4777-8777-777777777777");
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = uuid("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final String NAMESPACE = "test";
  private static final String WORLD_SLUG = "synthetic-world";
  private static final String CONTROL_PLANE_REQUEST_ID = "original-control-plane-request";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void exactReplayCarriesItsOriginalAssociationAndDescriptorWithoutUsingReadIdAsOperationId()
      throws Exception {
    Fixture fixture = fixture();
    var wireRequest = StartSessionLaunchDescriptorGrpcCodec.toRequest(fixture.request());
    var response =
        StartSessionLaunchDescriptorGrpcCodec.toDescriptorResponse(
            fixture.request(), fixture.associationRead(), fixture.descriptor());

    var decoded = StartSessionLaunchDescriptorGrpcCodec.fromResponse(fixture.request(), response);

    assertThat(StartSessionLaunchDescriptorGrpcCodec.fromRequest(wireRequest))
        .isEqualTo(fixture.request());
    assertThat(decoded.associationRead().request()).isEqualTo(fixture.request());
    assertThat(decoded.associationRead().request().readRequestId())
        .isNotEqualTo(fixture.descriptor().controlPlaneRequestId());
    assertThat(
            ((StartSessionLaunchDescriptorGrpcCodec.DescriptorOutcome) decoded.outcome())
                .descriptor())
        .isEqualTo(fixture.descriptor());
    assertThat(response.getAssociationRead().getRequest().getExactReplay().getAssociationDigest())
        .isEqualTo(fixture.associationRead().association().associationDigest());
  }

  @Test
  void rejectsInitialSelectionPartialMixedUnknownAndWrongVersionResponses() throws Exception {
    Fixture fixture = fixture();
    var exact = StartSessionLaunchDescriptorGrpcCodec.toRequest(fixture.request());
    var initial =
        exact.toBuilder()
            .clearSelection()
            .setInitialConfigured(
                net.firedevops.firemud.gamedesign.v1.StartSessionConfiguredTemplateSelection
                    .getDefaultInstance())
            .build();
    assertThatThrownBy(() -> StartSessionLaunchDescriptorGrpcCodec.fromRequest(initial))
        .isInstanceOf(IllegalArgumentException.class);

    var complete =
        StartSessionLaunchDescriptorGrpcCodec.toDescriptorResponse(
            fixture.request(), fixture.associationRead(), fixture.descriptor());
    assertThatThrownBy(
            () ->
                StartSessionLaunchDescriptorGrpcCodec.fromResponse(
                    fixture.request(), complete.toBuilder().clearLaunchDescriptor().build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                StartSessionLaunchDescriptorGrpcCodec.fromResponse(
                    fixture.request(),
                    complete.toBuilder()
                        .setApplicationFailure(
                            StartSessionLaunchDescriptorApplicationFailure.newBuilder()
                                .setCode("INVALID_TEMPLATE_CONFIGURATION")
                                .setMessage(
                                    "INVALID_TEMPLATE_CONFIGURATION: captured source unavailable"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                StartSessionLaunchDescriptorGrpcCodec.fromResponse(
                    fixture.request(), complete.toBuilder().setSchemaVersion(2).build()))
        .isInstanceOf(IllegalArgumentException.class);

    var unknown =
        complete.toBuilder()
            .setAssociationRead(
                complete.getAssociationRead().toBuilder()
                    .setAssociation(
                        complete.getAssociationRead().getAssociation().toBuilder()
                            .setUnknownFields(
                                UnknownFieldSet.newBuilder()
                                    .addField(
                                        88,
                                        UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                                    .build())
                            .build())
                    .build())
            .build();
    assertThatThrownBy(
            () -> StartSessionLaunchDescriptorGrpcCodec.fromResponse(fixture.request(), unknown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");

    var unknownDescriptorBinding =
        complete.toBuilder()
            .setLaunchDescriptor(
                complete.getLaunchDescriptor().toBuilder()
                    .setAuthoredWorldBinding(
                        complete.getLaunchDescriptor().getAuthoredWorldBinding().toBuilder()
                            .setUnknownFields(
                                UnknownFieldSet.newBuilder()
                                    .addField(
                                        89,
                                        UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                                    .build())
                            .build())
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                StartSessionLaunchDescriptorGrpcCodec.fromResponse(
                    fixture.request(), unknownDescriptorBinding))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
  }

  @Test
  void roundTripsAClosedCorrelatedApplicationFailureWireOutcome() throws Exception {
    // Codec evidence only; constructing this value does not prove a producer persisted it.
    Fixture fixture = fixture();
    String code = "INVALID_TEMPLATE_CONFIGURATION";
    String message = code + ": captured template has unsupported owner references";
    var response =
        StartSessionLaunchDescriptorGrpcCodec.toFailureResponse(
            fixture.request(), fixture.associationRead(), code, message);

    var decoded = StartSessionLaunchDescriptorGrpcCodec.fromResponse(fixture.request(), response);

    assertThat(decoded.associationRead().request()).isEqualTo(fixture.request());
    assertThat(decoded.outcome())
        .isEqualTo(new StartSessionLaunchDescriptorGrpcCodec.ApplicationFailure(code, message));
    assertThatThrownBy(
            () ->
                StartSessionLaunchDescriptorGrpcCodec.toFailureResponse(
                    fixture.request(), fixture.associationRead(), "UNAVAILABLE", "unavailable"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDescriptorFieldsThatDoNotMatchTheOriginalAssociationAndRelease() throws Exception {
    Fixture fixture = fixture();
    var original = fixture.descriptor();
    var alteredRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            original.targetNamespace(),
            original.controlPlaneRequestId(),
            original.canonicalTenantId(),
            "substituted-world",
            original.authoredWorldSourceOperationId(),
            original.authoredWorldSourceEvidenceDigest(),
            original.gameTemplateId(),
            false,
            null,
            false,
            null,
            false,
            null,
            true,
            "{}");
    var substituted =
        AuthoredWorldLaunchDescriptorEvidence.create(
            alteredRequest,
            original.launchDescriptorId(),
            original.versionId(),
            false,
            null,
            "{}",
            original.generationConfigRevision(),
            original.versionStateEpoch(),
            original.releaseBundleId(),
            original.publishedReleaseBundleRef(),
            false,
            null);
    assertThatThrownBy(
            () ->
                StartSessionLaunchDescriptorGrpcCodec.toDescriptorResponse(
                    fixture.request(), fixture.associationRead(), substituted))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("original StartSession tuple and association");

    var substitutedVersion =
        AuthoredWorldLaunchDescriptorEvidence.create(
            original.request(),
            original.launchDescriptorId(),
            original.versionId() + 1L,
            false,
            null,
            "{}",
            original.generationConfigRevision(),
            original.versionStateEpoch(),
            original.releaseBundleId(),
            original.publishedReleaseBundleRef(),
            false,
            null);
    assertThatThrownBy(
            () ->
                StartSessionLaunchDescriptorGrpcCodec.toDescriptorResponse(
                    fixture.request(), fixture.associationRead(), substitutedVersion))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version or release");

    var changedRuntimeFlags =
        AuthoredWorldLaunchDescriptorEvidence.create(
            original.request(),
            original.launchDescriptorId(),
            original.versionId(),
            false,
            null,
            "{\"runtime\":true}",
            original.generationConfigRevision(),
            original.versionStateEpoch(),
            original.releaseBundleId(),
            original.publishedReleaseBundleRef(),
            false,
            null);
    assertThatThrownBy(
            () ->
                StartSessionLaunchDescriptorGrpcCodec.toDescriptorResponse(
                    fixture.request(), fixture.associationRead(), changedRuntimeFlags))
        .isInstanceOf(IllegalArgumentException.class);

    var response =
        StartSessionLaunchDescriptorGrpcCodec.toDescriptorResponse(
            fixture.request(), fixture.associationRead(), original);
    var changedFlatVersion =
        response.toBuilder()
            .setLaunchDescriptor(
                response.getLaunchDescriptor().toBuilder()
                    .setVersionId(response.getLaunchDescriptor().getVersionId() + 1L)
                    .build())
            .build();
    assertThatThrownBy(
            () ->
                StartSessionLaunchDescriptorGrpcCodec.fromResponse(
                    fixture.request(), changedFlatVersion))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Fixture fixture() throws Exception {
    var worldEvidence = AuthoredWorldReleaseAttestationSelectorTest.selectorEvidence();
    UUID canonicalVersion = worldEvidence.request().canonicalVersionId();
    UUID selectedCommit = uuid(worldEvidence.request().appliedCommitId());
    String sourceRequestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, REGISTRATION_ID, TENANT, "tenant-key", WORLD_SLUG, "Synthetic World");
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            REGISTRATION_ID,
            SOURCE_OPERATION_ID,
            sourceRequestDigest,
            TENANT,
            "tenant-key",
            WORLD_SLUG,
            "Synthetic World",
            42L,
            "tenant-key",
            "NEW_GAME_ROW");
    var source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            REGISTRATION_ID,
            SOURCE_OPERATION_ID,
            sourceRequestDigest,
            TENANT,
            "tenant-key",
            WORLD_SLUG,
            "Synthetic World",
            42L,
            "tenant-key",
            "NEW_GAME_ROW",
            sourceEvidenceDigest);
    var intake =
        new IntakeRequest(
            1, NAMESPACE, INTAKE_ID, TENANT, WORLD_SLUG, SOURCE_OPERATION_ID, sourceEvidenceDigest);
    var receipt =
        new PublicReceipt(
            1,
            NAMESPACE,
            INTAKE_ID,
            WORLD_OPERATION_ID,
            TENANT,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            sourceEvidenceDigest,
            WorldAuthoredSourceIntakeGrpcCodec.requestDigest(intake),
            digest('e'),
            source);
    var association =
        new StartSessionTemplateAssociationReadEvidence.Association(
            TENANT,
            91L,
            canonicalVersion,
            selectedCommit,
            worldEvidence.request().publishWorkflowId(),
            digest('c'),
            digest('a'),
            NAMESPACE,
            INTAKE_ID,
            WORLD_OPERATION_ID,
            SOURCE_OPERATION_ID,
            WORLD_SLUG,
            sourceEvidenceDigest,
            new ByIdReadRequest(1, NAMESPACE, WORLD_READ_ID, INTAKE_ID, TENANT),
            receipt);
    Request request =
        new Request(
            1,
            NAMESPACE,
            READ_ID,
            postTuple().canonicalBytes(),
            ATTEMPT,
            8L,
            new ExactReplay(
                canonicalVersion,
                selectedCommit,
                worldEvidence.request().publishWorkflowId(),
                association.associationDigest()));
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            CONTROL_PLANE_REQUEST_ID,
            TENANT,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            sourceEvidenceDigest,
            91L,
            false,
            null,
            false,
            null,
            false,
            null,
            true,
            "{}");
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "descriptor-1",
            42L,
            false,
            null,
            "{}",
            "generation",
            9L,
            7L,
            "release-1",
            false,
            null);
    var releaseEvidence =
        AuthoredWorldReleaseAttestationSelectorTest.release(descriptor, worldEvidence);
    PublishedReleaseBundle.Builder release =
        PublishedReleaseBundle.newBuilder()
            .setId(7L)
            .setVersionId(42L)
            .setVersionNumber(1)
            .setAttestationSchemaVersion("v2")
            .setPublishWorkflowId(worldEvidence.request().publishWorkflowId())
            .setManifestHash(releaseEvidence.manifestHash())
            .setManifestSchemaVersion(releaseEvidence.manifestSchemaVersion())
            .addAllRequiredManifestAssetKeys(releaseEvidence.requiredManifestAssetKeys())
            .setGenerationConfigRevision(releaseEvidence.generationConfigRevision())
            .setPublishedReleaseBundleRef(releaseEvidence.publishedReleaseBundleRef())
            .setCanonicalTenantId(TENANT.toString())
            .setCanonicalVersionId(canonicalVersion.toString())
            .addAllCommandDefinitions(releaseEvidence.commandDefinitions());
    for (var participant : releaseEvidence.participantDigests()) {
      ParticipantDigest.Builder participantMessage =
          ParticipantDigest.newBuilder()
              .setParticipantKey(participant.participantKey())
              .setScopeValue(participant.scopeValue())
              .setAppliedCommitId(participant.appliedCommitId())
              .setContentDigest(participant.contentDigest())
              .setDigestSchemaVersion(participant.digestSchemaVersion());
      if (participant.abilitySchemaDigestPresent()) {
        participantMessage.setAbilitySchemaDigest(participant.abilitySchemaDigest());
      }
      release.addParticipantDigests(participantMessage);
    }
    Result associationRead = new Result(request, association, release.build(), worldEvidence, 23L);
    return new Fixture(request, associationRead, descriptor);
  }

  private static StartSessionPostAuthorizationExecutionTuple postTuple() {
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            CONTROL_PLANE_REQUEST_ID,
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "exact StartSession descriptor selection"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        RESERVATION_OWNER,
        19L,
        authorityBundle(pre),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", TENANT.toString(), "targetNamespace", NAMESPACE),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", TENANT.toString()),
            "accountProjectionEvidence",
            Map.of(
                "sourceType", "ACCOUNT",
                "sourceEvidenceId", digest('a'),
                "sourceEvidenceVersion", "17",
                "projectionStatus", "CURRENT",
                "evaluatedAt", "2026-10-09T00:00:00Z",
                "expiresAt", "2026-10-09T00:05:00Z"),
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
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration", 1L,
                "accountAuthorityGeneration", 2L,
                "tenantAuthorityGeneration", Map.of(TENANT.toString(), 3L),
                "membershipAuthorityGeneration", Map.of(TENANT.toString(), 4L),
                "privateRealmGrantVersions", List.of()),
            "membershipVersion",
            Map.of(TENANT.toString(), 5L),
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
    try {
      return net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
          JSON.writeValueAsString(value));
    } catch (IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      Request request, Result associationRead, AuthoredWorldLaunchDescriptorEvidence descriptor) {}
}
