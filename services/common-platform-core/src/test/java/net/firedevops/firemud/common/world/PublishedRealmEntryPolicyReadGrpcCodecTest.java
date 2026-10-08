package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyReadGrpcCodec;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.v1.ListPublishedRealmEntryPoliciesResponse;
import net.firedevops.firemud.gamedesign.v1.ResolvePublishedRealmEntryPolicyResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Closed wire and complete-set proof over isolated publication fixture vectors. */
class PublishedRealmEntryPolicyReadGrpcCodecTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void listAndResolveRoundTripTheCompleteOrderedSealedSet() throws Exception {
    Fixture fixture = fixture(true);
    var listRequest = listRequest(fixture, "99999999-9999-4999-8999-999999999999");
    var resolveRequest = resolveRequest(fixture, "99999999-9999-4999-8999-999999999999", "main");

    var listWire = PublishedRealmEntryPolicyReadGrpcCodec.toRequest(listRequest);
    var resolveWire = PublishedRealmEntryPolicyReadGrpcCodec.toRequest(resolveRequest);
    assertThat(PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(listWire)).isEqualTo(listRequest);
    assertThat(PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(resolveWire))
        .isEqualTo(resolveRequest);

    var listResponse =
        PublishedRealmEntryPolicyReadGrpcCodec.toResponse(listRequest, fixture.set());
    var resolveResponse =
        PublishedRealmEntryPolicyReadGrpcCodec.toResponse(resolveRequest, fixture.set());
    var listed = PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(listRequest, listResponse);
    var resolved =
        PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(resolveRequest, resolveResponse);

    assertThat(listed).isEqualTo(fixture.set());
    assertThat(resolved).isEqualTo(fixture.set());
    assertThat(listed.policyCount()).isEqualTo(2);
    assertThat(listed.policies()).containsExactlyElementsOf(fixture.set().policies());
    assertThat(listed.policySetDigest()).isEqualTo(fixture.set().policySetDigest());
    assertThat(listed.operationBytes()).isEqualTo(fixture.set().operationBytes());
    assertThat(listed.captureBytes()).isEqualTo(fixture.set().captureBytes());
    assertThat(listed.terminalEvidenceBytes()).isEqualTo(fixture.set().terminalEvidenceBytes());
  }

  @Test
  void requestsRejectUnknownFieldsUnsupportedSchemaAndNoncanonicalIdentityOrSelectors()
      throws Exception {
    Fixture fixture = fixture(false);
    var validList =
        PublishedRealmEntryPolicyReadGrpcCodec.toRequest(
            listRequest(fixture, "99999999-9999-4999-8999-999999999999"));
    var validResolve =
        PublishedRealmEntryPolicyReadGrpcCodec.toRequest(
            resolveRequest(fixture, "99999999-9999-4999-8999-999999999999", "main"));
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                    validList.toBuilder().setUnknownFields(unknown).build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                    validResolve.toBuilder().setSchemaVersion(2).build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                    validList.toBuilder()
                        .setReadRequestId("00000000-0000-0000-0000-000000000000")
                        .build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                    validList.toBuilder()
                        .setCanonicalTenantId("BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB")
                        .build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                    validList.toBuilder().setTargetNamespace("Test").build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                    validResolve.toBuilder().setWorldSlug("Main").build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromRequest(
                    validResolve.toBuilder().setRealmSlug("bad--slug").build()));
  }

  @Test
  void responsesRejectChangedEchoTargetAndMissingSelector() throws Exception {
    Fixture fixture = fixture(false);
    var request = listRequest(fixture, "99999999-9999-4999-8999-999999999999");
    var response = PublishedRealmEntryPolicyReadGrpcCodec.toResponse(request, fixture.set());

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(response.getRequest().toBuilder().setTargetNamespace("other"))
                        .build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(
                            response.getRequest().toBuilder()
                                .setReadRequestId("88888888-8888-4888-8888-888888888888"))
                        .build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(
                            response.getRequest().toBuilder()
                                .setCanonicalTenantId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
                        .build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setRequest(
                            response.getRequest().toBuilder()
                                .setCanonicalVersionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
                        .build()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    request,
                    response.toBuilder()
                        .setUnknownFields(
                            UnknownFieldSet.newBuilder()
                                .addField(
                                    99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                                .build())
                        .build()));

    var wrongTarget =
        new PublishedRealmEntryPolicyReadGrpcCodec.ListRequest(
            request.targetNamespace(),
            request.readRequestId(),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            request.canonicalVersionId());
    var substitutedEvidence =
        ListPublishedRealmEntryPoliciesResponse.newBuilder()
            .setRequest(PublishedRealmEntryPolicyReadGrpcCodec.toRequest(wrongTarget))
            .setPolicySetEvidence(ByteString.copyFrom(fixture.set().canonicalBytes()))
            .build();
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    wrongTarget, substitutedEvidence));

    var resolve = resolveRequest(fixture, "99999999-9999-4999-8999-999999999999", "missing");
    var missingSelectorResponse =
        ResolvePublishedRealmEntryPolicyResponse.newBuilder()
            .setRequest(PublishedRealmEntryPolicyReadGrpcCodec.toRequest(resolve))
            .setPolicySetEvidence(ByteString.copyFrom(fixture.set().canonicalBytes()))
            .build();
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    resolve, missingSelectorResponse));

    var exactResolve = resolveRequest(fixture, "99999999-9999-4999-8999-999999999999", "main");
    var exactResolveResponse =
        PublishedRealmEntryPolicyReadGrpcCodec.toResponse(exactResolve, fixture.set());
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(
                    exactResolve,
                    exactResolveResponse.toBuilder()
                        .setRequest(
                            exactResolveResponse.getRequest().toBuilder().setRealmSlug("side"))
                        .build()));
  }

  @Test
  void completeCarrierRejectsCountOrderAndDigestSubstitutionAtTheWireBoundary() throws Exception {
    Fixture fixture = fixture(true);
    var request = listRequest(fixture, "99999999-9999-4999-8999-999999999999");
    byte[] canonical = fixture.set().canonicalBytes();
    ObjectNode root = (ObjectNode) JSON.readTree(canonical);

    ObjectNode changedCount = root.deepCopy();
    changedCount.put("policyCount", fixture.set().policyCount() + 1);
    assertRejectedSet(request, changedCount);

    ObjectNode changedOrder = root.deepCopy();
    ArrayNode reversed = JSON.createArrayNode();
    ArrayNode policies = (ArrayNode) changedOrder.get("policies");
    for (int index = policies.size() - 1; index >= 0; index--) {
      reversed.add(policies.get(index));
    }
    changedOrder.set("policies", reversed);
    assertRejectedSet(request, changedOrder);

    ObjectNode changedDigest = root.deepCopy();
    changedDigest.put("policySetDigest", "sha256:" + "f".repeat(64));
    assertRejectedSet(request, changedDigest);
  }

  private static void assertRejectedSet(
      PublishedRealmEntryPolicyReadGrpcCodec.ListRequest request, ObjectNode carrier)
      throws IOException {
    byte[] changed = Rfc8785CanonicalJson.canonicalizeUtf8(carrier.toString());
    var response =
        ListPublishedRealmEntryPoliciesResponse.newBuilder()
            .setRequest(PublishedRealmEntryPolicyReadGrpcCodec.toRequest(request))
            .setPolicySetEvidence(ByteString.copyFrom(changed))
            .build();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublishedRealmEntryPolicyReadGrpcCodec.fromResponse(request, response));
  }

  private static PublishedRealmEntryPolicyReadGrpcCodec.ListRequest listRequest(
      Fixture fixture, String readId) {
    return new PublishedRealmEntryPolicyReadGrpcCodec.ListRequest(
        "test",
        UUID.fromString(readId),
        fixture.target().canonicalTenantId(),
        fixture.target().canonicalVersionId());
  }

  private static PublishedRealmEntryPolicyReadGrpcCodec.ResolveRequest resolveRequest(
      Fixture fixture, String readId, String realmSlug) {
    return new PublishedRealmEntryPolicyReadGrpcCodec.ResolveRequest(
        "test",
        UUID.fromString(readId),
        fixture.target().canonicalTenantId(),
        fixture.target().canonicalVersionId(),
        "earth",
        realmSlug);
  }

  private static Fixture fixture(boolean includeSide) throws Exception {
    var operation = operation();
    var target = operation.account().input().selection().target();
    var sourceBinding = operation.account().input().selection().selectedCommit();
    var terminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(), Outcome.PUBLISHED, release(operation), 6L);
    List<Source> sources = new ArrayList<>();
    sources.add(
        source(
            "main",
            true,
            "33333333-3333-4333-8333-333333333333",
            "44444444-4444-4444-8444-444444444444",
            "55555555-5555-4555-8555-555555555555"));
    if (includeSide) {
      sources.add(
          source(
              "side",
              false,
              "88888888-8888-4888-8888-888888888888",
              "66666666-6666-4666-8666-666666666666",
              "77777777-7777-4777-8777-777777777777"));
    }
    byte[] capture = capture(operation, sourceBinding, sources);
    List<PublishedRealmEntryPolicyEvidence> policies =
        sources.stream()
            .map(
                source -> {
                  UUID policyId = UUID.fromString(source.policyId());
                  return new PublishedRealmEntryPolicyEvidence(
                      policyId,
                      UUID.fromString(source.commitId()),
                      UUID.fromString(source.revisionId()),
                      "source-" + source.revisionId(),
                      source.policy(),
                      PublishedRealmEntryPolicySetEvidence.policyDigest(
                          policyId,
                          target,
                          terminal.releaseContent().versionNumber(),
                          terminal.publishedReleaseBundleRef(),
                          terminal.publishedReleaseBundleDigest(),
                          terminal.releaseContent().publishWorkflowId(),
                          terminal.releaseContent().manifestHash(),
                          UUID.fromString(source.commitId()),
                          UUID.fromString(source.revisionId()),
                          "source-" + source.revisionId(),
                          source.policy()));
                })
            .toList();
    var release = terminal.releaseContent();
    String setDigest =
        PublishedRealmEntryPolicySetEvidence.calculatePolicySetDigest(
            target,
            release.versionNumber(),
            sourceBinding.commitId(),
            "43",
            release.publishedReleaseBundleRef(),
            terminal.publishedReleaseBundleDigest(),
            release.publishWorkflowId(),
            release.manifestHash(),
            terminal.publicationVersionStateEpoch(),
            operation.canonicalBytes(),
            capture,
            terminal.canonicalBytes(),
            policies);
    return new Fixture(
        target,
        new PublishedRealmEntryPolicySetEvidence(
            target,
            release.versionNumber(),
            sourceBinding.commitId(),
            "43",
            release.publishedReleaseBundleRef(),
            terminal.publishedReleaseBundleDigest(),
            release.publishWorkflowId(),
            release.manifestHash(),
            terminal.publicationVersionStateEpoch(),
            operation.canonicalBytes(),
            capture,
            terminal.canonicalBytes(),
            policies.size(),
            setDigest,
            policies));
  }

  private static Source source(
      String realm, boolean production, String policyId, String commitId, String revisionId) {
    RealmEntryPolicy policy =
        RealmEntryPolicy.parse(
            "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
                + "\"realmSlug\":\""
                + realm
                + "\",\"realmDisplayName\":\""
                + realm
                + "\",\"visible\":true,\"publicProduction\":"
                + production
                + ",\"stateScope\":\"SHARED\",\"entryPolicy\":\"PRESEEDED_ONLY\"}",
            JSON);
    return new Source(policyId, commitId, revisionId, policy);
  }

  private static byte[] capture(
      net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding operation,
      DraftCommitBinding binding,
      List<Source> sources)
      throws IOException {
    List<Map<String, Object>> policies =
        sources.stream()
            .map(
                source -> {
                  Map<String, Object> value = new LinkedHashMap<>();
                  value.put("commitId", source.commitId());
                  value.put("revisionId", source.revisionId());
                  value.put("logicalRevisionId", "source-" + source.revisionId());
                  value.put("policy", JSON.readTree(source.policy().canonicalJson()));
                  return value;
                })
            .toList();
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("schema", PublishedRealmEntryPolicySetEvidence.SNAPSHOT_SCHEMA);
    snapshot.put("bindingJson", binding.canonicalJson());
    snapshot.put("bindingDigest", binding.digest());
    snapshot.put("sourceEpoch", "43");
    snapshot.put("policies", policies);
    byte[] snapshotBytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(snapshot));
    ByteArrayOutputStream capture = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(
        capture, PublishedRealmEntryPolicySetEvidence.CAPTURE_SCHEMA);
    DraftAuthorizationFenceBinding.frame(capture, operation.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(capture, snapshotBytes);
    return capture.toByteArray();
  }

  private static net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding
      operation() throws Exception {
    var seed = WorldPublishedStartLocationGrpcCodecTest.evidence();
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
                "5",
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
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
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
                5L,
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
    return new GameDesignPublicationOperationBinding(account, world);
  }

  private static ReleaseContent release(
      net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding operation) {
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
                            owner),
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
        "generation-1",
        operation.world());
  }

  private record Source(
      String policyId, String commitId, String revisionId, RealmEntryPolicy policy) {}

  private record Fixture(
      DraftCommitBinding.TargetProof target, PublishedRealmEntryPolicySetEvidence set) {}
}
