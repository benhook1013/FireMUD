package net.firedevops.firemud.common.account.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Participant;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.ReleaseContent;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionCandidate;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Shape and tuple codec proof only; this fixture grants no admission or Account authority. */
class CanonicalGameplayAdmissionDecisionReadGrpcCodecTest {
  private static final String HIGH = "9007199254740993";
  private static final UUID DECISION_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final UUID OTHER = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID VERSION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID HOLD_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_FENCE = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID REALM = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID PLAYABLE_NAMESPACE =
      UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID INSTANCE = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder().build();

  @Test
  void roundTripsCompleteProvisionalReadAndPreservesOriginalLeaseBytes() {
    var lease = lease();
    var request = request(lease);
    var response = response(request, lease, target(lease, "PRESEEDED_ONLY"));

    var evidence = CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromResponse(request, response);

    assertThat(response.getOriginalLeaseEvidenceUtf8(0).toByteArray())
        .containsExactly(request.expectedLeaseEvidenceUtf8());
    assertThat(evidence.originalLeaseEvidenceJson()).isEqualTo(lease.canonicalJson());
    assertThat(evidence.status())
        .isEqualTo(CanonicalGameplayAdmissionDecisionReadGrpcCodec.Status.PROVISIONAL);
    assertThat(evidence.bindingGeneration()).isEqualTo(new BigInteger(HIGH));
    assertThat(evidence.candidate().regionEpoch()).isEqualTo(new BigInteger(HIGH));
    assertThat(evidence.candidate().issuerIndexPartitionCount()).isEqualTo(new BigInteger(HIGH));
    assertThat(evidence.candidate().issuerIndexPartitionCapacity()).isEqualTo(new BigInteger(HIGH));
    assertThat(evidence.targetEvidence().catalogRevision()).isEqualTo(Long.parseLong(HIGH));
    assertThat(evidence.targetEvidence().pointerVersion()).isEqualTo(Long.parseLong(HIGH));
    assertThat(evidence.targetEvidence().activeWorldEpoch()).isEqualTo(Long.parseLong(HIGH));
  }

  @Test
  void rejectsChangedDecisionLeaseCandidateAndOwnerProofTuple() {
    var lease = lease();
    var request = request(lease);
    var response = response(request, lease, target(lease, "PRESEEDED_ONLY"));

    assertRejected(
        request, response.toBuilder().setBindingDecisionUuid(0, OTHER.toString()).build());

    var changedLeaseCarrier = AccountGameplayAdmissionLeaseEvidenceTest.fixture();
    mutableObject(changedLeaseCarrier, "bindingScope").put("pointerVersion", "9007199254740994");
    var changedLease = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedLeaseCarrier);
    assertRejected(
        request,
        response.toBuilder()
            .setOriginalLeaseEvidenceUtf8(
                0,
                ByteString.copyFrom(changedLease.canonicalJson().getBytes(StandardCharsets.UTF_8)))
            .build());

    assertRejected(
        request,
        response.toBuilder()
            .setCandidate(
                0, response.getCandidate(0).toBuilder().setAccountUuid(0, OTHER.toString()))
            .build());

    var legacyIssuerCandidate =
        response.getCandidate(0).toBuilder().setIssuerId(0, "77777777-7777-4777-8777-777777777777");
    assertThat(legacyIssuerCandidate.getIssuerAuthGeneration(0))
        .isEqualTo(response.getCandidate(0).getIssuerAuthGeneration(0));
    assertRejected(
        request, response.toBuilder().setCandidate(0, legacyIssuerCandidate.build()).build());

    assertRejected(
        request,
        response.toBuilder()
            .setTargetEvidence(
                0,
                response.getTargetEvidence(0).toBuilder()
                    .setHoldBindingDigest(0, "sha256:" + "f".repeat(64))
                    .build())
            .build());
  }

  @Test
  void keepsIssuerIdAtStringTagElevenAndEmitsOnlyTheAccountIssuer() {
    var wire = CanonicalGameplayAdmissionDecisionReadGrpcCodec.toCandidate(candidate(lease()));
    var issuerField =
        CanonicalGameplayAdmissionCandidate.getDescriptor().findFieldByName("issuer_id");

    assertThat(issuerField.getNumber()).isEqualTo(11);
    assertThat(issuerField.getType())
        .isEqualTo(com.google.protobuf.Descriptors.FieldDescriptor.Type.STRING);
    assertThat(wire.getIssuerIdList()).containsExactly(GameSessionAccountDelegationProfile.ISSUER);
  }

  @Test
  void rejectsMissingDuplicateAndUnknownWireFieldsAtEachClosedLevel() {
    var lease = lease();
    var wireRequest = CanonicalGameplayAdmissionDecisionReadGrpcCodec.toRequest(DECISION_ID, lease);
    var request = CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(wireRequest);
    var response = response(request, lease, target(lease, "PRESEEDED_ONLY"));
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(
                    wireRequest.toBuilder().clearExpectedLeaseEvidenceUtf8().build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(
                    wireRequest.toBuilder().addSchemaVersion(1).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(
                    wireRequest.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);

    assertRejected(request, response.toBuilder().clearBindingGeneration().build());
    assertRejected(request, response.toBuilder().addBindingGeneration(HIGH).build());
    assertRejected(request, response.toBuilder().setUnknownFields(unknown).build());
    assertRejected(
        request,
        response.toBuilder()
            .setCandidate(0, response.getCandidate(0).toBuilder().clearAccountUuid().build())
            .build());
    assertRejected(
        request,
        response.toBuilder()
            .setCandidate(
                0, response.getCandidate(0).toBuilder().addAccountUuid(OTHER.toString()).build())
            .build());
    assertRejected(
        request,
        response.toBuilder()
            .setCandidate(0, response.getCandidate(0).toBuilder().setUnknownFields(unknown).build())
            .build());
    assertRejected(
        request,
        response.toBuilder()
            .setTargetEvidence(
                0, response.getTargetEvidence(0).toBuilder().setUnknownFields(unknown).build())
            .build());
    assertRejected(
        request,
        response.toBuilder()
            .setTargetEvidence(
                0,
                response.getTargetEvidence(0).toBuilder()
                    .addExpectedPriorPointerVersion("1")
                    .addExpectedPriorPointerVersion("2")
                    .build())
            .build());
  }

  @Test
  void enforcesFiniteRequestAndCompleteResponseBounds() {
    var lease = lease();
    var wireRequest = CanonicalGameplayAdmissionDecisionReadGrpcCodec.toRequest(DECISION_ID, lease);
    var oversizedRequest =
        wireRequest.toBuilder()
            .clearExpectedLeaseEvidenceUtf8()
            .addExpectedLeaseEvidenceUtf8(
                ByteString.copyFrom(
                    new byte
                        [CanonicalGameplayAdmissionDecisionReadGrpcCodec.MAX_REQUEST_WIRE_BYTES]))
            .build();
    assertThatThrownBy(
            () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(oversizedRequest))
        .isInstanceOf(IllegalArgumentException.class);

    var request = CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(wireRequest);
    assertThatThrownBy(() -> response(request, lease, target(lease, "x".repeat(270_000))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void roundTripsClosedPublishedRealmSelectorAndCompleteCurrentTarget() throws Exception {
    var source = publishedRealmEvidence();
    var selector = source.request();
    var wireRequest =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmRequest(selector);
    var parsedRequest =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(wireRequest);
    assertThat(parsedRequest.selector()).isEqualTo(selector);

    var target = publishedTarget(source);
    var response =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmResponse(
            parsedRequest, source, target);
    var evidence =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
            parsedRequest, response);
    assertThat(evidence.selector()).isEqualTo(selector);
    assertThat(evidence.sourceEvidence()).isEqualTo(source);
    assertThat(evidence.currentTarget()).isEqualTo(target);

    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
                    wireRequest.toBuilder().addSchemaVersion(1).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
                    wireRequest.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
                    wireRequest.toBuilder()
                        .setSelector(
                            0, wireRequest.getSelector(0).toBuilder().setUnknownFields(unknown))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
                    wireRequest.toBuilder().addSelector(wireRequest.getSelector(0)).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
                    wireRequest.toBuilder()
                        .setSelector(0, wireRequest.getSelector(0).toBuilder().clearWorldSlug())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
                    wireRequest.toBuilder().clearSelector().build()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                    parsedRequest, response.toBuilder().addSchemaVersion(1).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                    parsedRequest, response.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                    parsedRequest,
                    response.toBuilder()
                        .setPublishedSource(
                            0, response.getPublishedSource(0).toBuilder().setUnknownFields(unknown))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                    parsedRequest, response.toBuilder().clearPublishedSource().build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                    parsedRequest,
                    response.toBuilder()
                        .setPublishedSource(
                            0, response.getPublishedSource(0).toBuilder().clearRequest())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                    parsedRequest,
                    response.toBuilder().addCurrentTarget(response.getCurrentTarget(0)).build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                    parsedRequest,
                    response.toBuilder()
                        .setCurrentTarget(
                            0, response.getCurrentTarget(0).toBuilder().setUnknownFields(unknown))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                    parsedRequest,
                    response.toBuilder()
                        .setCurrentTarget(
                            0, response.getCurrentTarget(0).toBuilder().addPointerVersion("74"))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                    parsedRequest,
                    response.toBuilder()
                        .setPublishedSource(
                            0,
                            response.getPublishedSource(0).toBuilder()
                                .setRequest(
                                    response.getPublishedSource(0).getRequest().toBuilder()
                                        .setCanonicalTenantUuid(OTHER.toString())))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsPublishedTargetSourceAndSelectorSubstitutions() throws Exception {
    var source = publishedRealmEvidence();
    var request =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
            CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmRequest(
                source.request()));
    var target = publishedTarget(source);
    var response =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmResponse(
            request, source, target);
    var targetEvidence = response.getCurrentTarget(0);
    for (var changed :
        List.of(
            targetEvidence.toBuilder().setCanonicalTenantUuid(0, OTHER.toString()).build(),
            targetEvidence.toBuilder().setPointerVersion(0, "74").build(),
            targetEvidence.toBuilder().setAdmissionPointerSnapshotDigest(0, "f".repeat(64)).build(),
            targetEvidence.toBuilder().setOwnerProofDigest(0, "sha256:" + "f".repeat(64)).build(),
            targetEvidence.toBuilder().setCharacterCreationPolicy(0, "PLAYER_CREATED").build())) {
      assertThatThrownBy(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                      request, response.toBuilder().setCurrentTarget(0, changed).build()))
          .isInstanceOf(IllegalArgumentException.class);
    }

    var changedSelector =
        selectorWithExpectedPointer(
            request.selector(), request.selector().expectedPointerVersion() + 1);
    var changedRequest =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
            CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmRequest(
                changedSelector));
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                    changedRequest, response))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void enforcesPublishedRealmWireBoundsOnRequestAndResponse() throws Exception {
    var source = publishedRealmEvidence();
    var request =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmRequest(source.request());
    var oversizedRequest =
        request.toBuilder()
            .setSelector(0, request.getSelector(0).toBuilder().setWorldSlug("x".repeat(3_000)))
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
                    oversizedRequest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wire bound");

    var parsedRequest =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(request);
    var response =
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmResponse(
            parsedRequest, source, publishedTarget(source));
    var oversizedResponse =
        response.toBuilder()
            .setPublishedSource(
                0,
                response.getPublishedSource(0).toBuilder()
                    .setPublishedPolicySetEvidence(
                        ByteString.copyFrom(
                            new byte
                                [CanonicalGameplayAdmissionDecisionReadGrpcCodec
                                    .MAX_PUBLISHED_REALM_RESPONSE_WIRE_BYTES])))
            .build();
    assertThatThrownBy(
            () ->
                CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromPublishedRealmResponse(
                    parsedRequest, oversizedResponse))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wire bound");
  }

  /** Controlled owner/policy fixture for codec shape proof; it simulates no owner execution. */
  private static CanonicalGameplayRosterOwnerReadEvidence publishedRealmEvidence()
      throws Exception {
    var operation = publishedOperation(5L);
    var selected = operation.account().input().selection();
    var target = selected.target();
    var sourceBinding = selected.selectedCommit();
    var sourcePolicy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
                + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main\",\"visible\":true,"
                + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
                + "\"entryPolicy\":\"PRESEEDED_ONLY\"}",
            JSON);
    UUID policyId = uuid("33333333-3333-4333-8333-333333333333");
    UUID policyCommitId = uuid("44444444-4444-4444-8444-444444444444");
    UUID policyRevisionId = uuid("55555555-5555-4555-8555-555555555555");
    var release = publishedRelease(operation, "generation-1");
    var terminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(), Outcome.PUBLISHED, release, 6L);
    var policyEvidence =
        new PublishedRealmEntryPolicyEvidence(
            policyId,
            policyCommitId,
            policyRevisionId,
            "main-policy-v1",
            sourcePolicy,
            PublishedRealmEntryPolicyEvidence.calculateDigest(
                policyId,
                target,
                release.versionNumber(),
                release.publishedReleaseBundleRef(),
                release.contentDigest(),
                release.publishWorkflowId(),
                release.manifestHash(),
                policyCommitId,
                policyRevisionId,
                "main-policy-v1",
                sourcePolicy));
    List<Map<String, Object>> policyRows =
        List.of(
            Map.of(
                "commitId", policyCommitId.toString(),
                "revisionId", policyRevisionId.toString(),
                "logicalRevisionId", "main-policy-v1",
                "policy", JSON.readTree(sourcePolicy.canonicalJson())));
    var snapshot =
        Map.of(
            "schema",
            PublishedRealmEntryPolicySetEvidence.SNAPSHOT_SCHEMA,
            "bindingJson",
            sourceBinding.canonicalJson(),
            "bindingDigest",
            sourceBinding.digest(),
            "sourceEpoch",
            "43",
            "policies",
            policyRows);
    byte[] snapshotBytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(snapshot));
    var capture = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(
        capture, PublishedRealmEntryPolicySetEvidence.CAPTURE_SCHEMA);
    DraftAuthorizationFenceBinding.frame(capture, operation.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(capture, snapshotBytes);
    var policies = List.of(policyEvidence);
    String policySetDigest =
        PublishedRealmEntryPolicySetEvidence.calculatePolicySetDigest(
            target,
            release.versionNumber(),
            sourceBinding.commitId(),
            "43",
            release.publishedReleaseBundleRef(),
            release.contentDigest(),
            release.publishWorkflowId(),
            release.manifestHash(),
            terminal.publicationVersionStateEpoch(),
            operation.canonicalBytes(),
            capture.toByteArray(),
            terminal.canonicalBytes(),
            policies);
    var policySet =
        new PublishedRealmEntryPolicySetEvidence(
            target,
            release.versionNumber(),
            sourceBinding.commitId(),
            "43",
            release.publishedReleaseBundleRef(),
            release.contentDigest(),
            release.publishWorkflowId(),
            release.manifestHash(),
            terminal.publicationVersionStateEpoch(),
            operation.canonicalBytes(),
            capture.toByteArray(),
            terminal.canonicalBytes(),
            policies.size(),
            policySetDigest,
            policies);

    var holdRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            operation.world().request().targetNamespace(),
            target.canonicalTenantId(),
            "earth",
            REALM,
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            target.canonicalVersionId(),
            82L,
            "first-open-request-1",
            "a".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            71L,
            null);
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(
            holdRequest,
            uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            uuid("ffffffff-ffff-4fff-8fff-ffffffffffff"));
    var proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            hold,
            GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
            73L,
            91L,
            "sha256:" + "b".repeat(64),
            false,
            Instant.parse("2026-10-08T03:04:05Z"));
    var request =
        new CanonicalGameplayRosterOwnerReadEvidence.Request(
            uuid("11111111-1111-4111-8111-111111111111"),
            uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            operation.world().request().targetNamespace(),
            target.canonicalTenantId(),
            "earth",
            REALM,
            "main",
            PLAYABLE_NAMESPACE,
            "SHARED",
            INSTANCE,
            target.canonicalVersionId(),
            71L,
            73L,
            82L);
    return new CanonicalGameplayRosterOwnerReadEvidence(request, "1".repeat(64), proof, policySet);
  }

  private static Target publishedTarget(CanonicalGameplayRosterOwnerReadEvidence evidence) {
    var request = evidence.request();
    var proof = evidence.gameSessionOwnerProof();
    var hold = proof.holdIdentity();
    return new Target(
        request.targetNamespace(),
        "tenant",
        request.canonicalTenantUuid(),
        request.worldSlug(),
        "Earth",
        request.realmUuid(),
        request.realmSlug(),
        "Main",
        11L,
        71L,
        request.playableStateNamespaceUuid(),
        request.playableStateScope(),
        request.canonicalGameInstanceUuid(),
        request.canonicalVersionUuid(),
        99L,
        request.expectedCatalogRevision(),
        request.expectedPointerVersion(),
        evidence.admissionPointerSnapshotDigest(),
        request.expectedActiveWorldEpoch(),
        hold.request().initialAdmissionRequestId(),
        hold.request().initialAdmissionRequestDigest(),
        OriginKind.NO_PRIOR_POINTER,
        null,
        hold.holdId(),
        hold.holdFence(),
        hold.holdBindingDigest(),
        proof.auditEventId(),
        proof.proofDigest(),
        OwnerProofOutcome.COMMITTED,
        false,
        proof.terminalAt(),
        "PRESEEDED_ONLY");
  }

  private static CanonicalGameplayRosterOwnerReadEvidence.Request selectorWithExpectedPointer(
      CanonicalGameplayRosterOwnerReadEvidence.Request request, long expectedPointerVersion) {
    return new CanonicalGameplayRosterOwnerReadEvidence.Request(
        request.requestUuid(),
        request.canonicalAccountUuid(),
        request.targetNamespace(),
        request.canonicalTenantUuid(),
        request.worldSlug(),
        request.realmUuid(),
        request.realmSlug(),
        request.playableStateNamespaceUuid(),
        request.playableStateScope(),
        request.canonicalGameInstanceUuid(),
        request.canonicalVersionUuid(),
        request.expectedCatalogRevision(),
        expectedPointerVersion,
        request.expectedActiveWorldEpoch());
  }

  private static GameDesignPublicationOperationBinding publishedOperation(long epoch)
      throws Exception {
    var seed = AuthoringFixtures.startLocationEvidence();
    var original = DraftAuthorizationFenceBinding.fromStored(seed.originalAccountBindingBytes());
    var draft =
        DraftCommitBinding.fromStored(
            new String(original.gameDesignBinding(), StandardCharsets.UTF_8),
            original.inputDigest());
    var target = draft.target();
    String requestId = "publication-request";
    var selected =
        AuthoredDraftPublishSelectionBinding.capture(
            new AuthoredDraftPublishSelectionBinding.PublishIntent(
                target.canonicalTenantId(),
                target.canonicalVersionId(),
                requestId,
                Long.toString(epoch),
                "fixed-notes",
                draft.requestId(),
                draft.commitId(),
                draft.digest()),
            target,
            draft,
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                target,
                draft.requestId(),
                draft.commitId(),
                draft.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-07T00:00:00Z")));
    var account =
        new AccountPublicationAuthorizationBinding(
            uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            new AccountPublicationAuthorizationBinding.PreallocationInput(
                original.actorAccountId(), selected),
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    original.actorAccountId().toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[] {1})));
    var request = seed.request();
    var world =
        new WorldPublishedStartLocationEvidence(
            new WorldPublishedStartLocationEvidence.Request(
                request.targetNamespace(),
                request.canonicalTenantId(),
                request.canonicalVersionId(),
                request.intakeRequestId(),
                request.publicationFence(),
                requestId,
                selected.digest().substring(7),
                epoch,
                PublicationDigestRequestBinding.full(
                        target.canonicalTenantId().toString(),
                        Long.toString(target.gameDesignVersionRowId()),
                        requestId)
                    .derivedWorkflowIdentity(),
                request.appliedCommitId(),
                request.contentDigest(),
                request.digestSchemaVersion(),
                request.worldAffectedTuples()),
            seed.selectorReceiptBytes(),
            seed.originalAccountBindingBytes(),
            seed.appliedResultBytes());
    return new GameDesignPublicationOperationBinding(
        account,
        world,
        net.firedevops.firemud.test.IsolatedWorldPublicationInventoryFixtures.stipulated(
            account, world));
  }

  private static ReleaseContent publishedRelease(
      GameDesignPublicationOperationBinding operation, String generation) {
    var request = operation.world().request();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new Participant(
                        owner,
                        Long.toString(
                            operation
                                .account()
                                .input()
                                .selection()
                                .target()
                                .gameDesignVersionRowId()),
                        null,
                        request.appliedCommitId(),
                        owner.equals("WORLD_MANAGEMENT") ? request.contentDigest() : "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        owner.equals("GAME_LOGIC") ? DIGEST : null,
                        null,
                        null))
            .toList();
    return new ReleaseContent(
        request.canonicalTenantId(),
        request.canonicalVersionId(),
        "bundle:exact",
        12,
        "v2",
        request.publishWorkflowId(),
        DIGEST,
        1,
        List.of(
            new AuthoredWorldReleaseAttestationEvidence.Artifact(
                "asset-é", "FILE", "artifacts/sha256/" + "a".repeat(64), DIGEST, "text/plain", 1)),
        List.of("asset-é"),
        participants,
        List.of("LOOK"),
        generation,
        operation.world());
  }

  private static void assertRejected(
      CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence request,
      GetCanonicalGameplayAdmissionDecisionResponse response) {
    assertThatThrownBy(
            () -> CanonicalGameplayAdmissionDecisionReadGrpcCodec.fromResponse(request, response))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static GetCanonicalGameplayAdmissionDecisionResponse response(
      CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence request,
      AccountGameplayAdmissionLeaseEvidence lease,
      Target target) {
    return CanonicalGameplayAdmissionDecisionReadGrpcCodec.toResponse(
        request, DECISION_ID, lease, new BigInteger(HIGH), null, null, candidate(lease), target);
  }

  private static CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence request(
      AccountGameplayAdmissionLeaseEvidence lease) {
    return CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toRequest(DECISION_ID, lease));
  }

  private static AccountGameplayAdmissionLeaseEvidence lease() {
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(
        AccountGameplayAdmissionLeaseEvidenceTest.fixture());
  }

  private static Candidate candidate(AccountGameplayAdmissionLeaseEvidence lease) {
    Map<?, ?> scope = object(lease.carrier(), "bindingScope");
    Map<?, ?> authority = object(lease.carrier(), "authorityTuple");
    return new Candidate(
        uuid(scope, "accountId"),
        uuid(scope, "tenantId"),
        uuid(scope, "playableStateNamespaceId"),
        uuid(scope, "characterId"),
        (String) scope.get("playableStateScope"),
        uuid(scope, "gameInstanceId"),
        71,
        uuid(scope, "sessionId"),
        uuid(scope, "regionId"),
        new BigInteger((String) scope.get("regionEpoch")),
        GameSessionAccountDelegationProfile.ISSUER,
        new BigInteger((String) authority.get("issuerAuthGeneration")),
        new BigInteger(HIGH),
        new BigInteger(HIGH),
        new BigInteger(HIGH));
  }

  private static Target target(AccountGameplayAdmissionLeaseEvidence lease, String policy) {
    Map<?, ?> carrier = lease.carrier();
    Map<?, ?> scope = object(carrier, "bindingScope");
    UUID tenantId = uuid(scope, "tenantId");
    UUID realmId = uuid(scope, "realmId");
    UUID playableStateNamespaceId = uuid(scope, "playableStateNamespaceId");
    UUID gameInstanceId = uuid(scope, "gameInstanceId");
    long catalogRevision = Long.parseLong((String) scope.get("catalogRevision"));
    long pointerVersion = Long.parseLong((String) scope.get("pointerVersion"));
    long activeWorldEpoch = Long.parseLong(HIGH);
    String requestId = (String) carrier.get("requestId");
    String requestDigest = "c".repeat(64);
    WorldCanonicalInitialAdmissionHold.Request ownerRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            (String) carrier.get("targetNamespace"),
            tenantId,
            (String) scope.get("worldSlug"),
            realmId,
            playableStateNamespaceId,
            (String) scope.get("playableStateScope"),
            gameInstanceId,
            VERSION_ID,
            activeWorldEpoch,
            requestId,
            requestDigest,
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            catalogRevision,
            null);
    return new Target(
        (String) carrier.get("targetNamespace"),
        "tenant",
        tenantId,
        (String) scope.get("worldSlug"),
        "World",
        realmId,
        (String) scope.get("realmSlug"),
        "Realm",
        11,
        71,
        playableStateNamespaceId,
        (String) scope.get("playableStateScope"),
        gameInstanceId,
        VERSION_ID,
        99,
        catalogRevision,
        pointerVersion,
        "d".repeat(64),
        activeWorldEpoch,
        requestId,
        requestDigest,
        OriginKind.NO_PRIOR_POINTER,
        null,
        HOLD_ID,
        HOLD_FENCE,
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(ownerRequest, HOLD_ID, HOLD_FENCE)
            .holdBindingDigest(),
        101,
        "sha256:" + "a".repeat(64),
        OwnerProofOutcome.COMMITTED,
        false,
        Instant.parse("2026-10-25T00:00:00Z"),
        policy);
  }

  private static Map<?, ?> object(Map<?, ?> source, String field) {
    return (Map<?, ?>) source.get(field);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mutableObject(Map<String, Object> source, String field) {
    return (Map<String, Object>) source.get(field);
  }

  private static UUID uuid(Map<?, ?> source, String field) {
    return UUID.fromString((String) source.get(field));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
