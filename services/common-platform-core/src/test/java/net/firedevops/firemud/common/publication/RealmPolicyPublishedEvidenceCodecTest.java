package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RealmPolicyPublishedEvidenceCodecTest {
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
  void roundTripsCompletePublishedSetAndPreservesEveryFieldAndDefensiveBytes() throws Exception {
    var original = publishedSet();
    var encoded = RealmPolicyPublishedEvidenceCodec.encode(original);
    var decoded = RealmPolicyPublishedEvidenceCodec.decode(encoded);

    assertThat(RealmPolicyPublishedEvidenceCodec.encode(decoded)).containsExactly(encoded);
    assertThat(decoded.target()).isEqualTo(original.target());
    assertThat(decoded.versionNumber()).isEqualTo(original.versionNumber());
    assertThat(decoded.sourceCommitId()).isEqualTo(original.sourceCommitId());
    assertThat(decoded.sourceEpoch()).isEqualTo(original.sourceEpoch());
    assertThat(decoded.publishedReleaseBundleRef()).isEqualTo(original.publishedReleaseBundleRef());
    assertThat(decoded.publishedReleaseBundleDigest())
        .isEqualTo(original.publishedReleaseBundleDigest());
    assertThat(decoded.publishWorkflowId()).isEqualTo(original.publishWorkflowId());
    assertThat(decoded.manifestHash()).isEqualTo(original.manifestHash());
    assertThat(decoded.publicationVersionStateEpoch())
        .isEqualTo(original.publicationVersionStateEpoch());
    assertThat(decoded.operationBytes()).containsExactly(original.operationBytes());
    assertThat(decoded.captureBytes()).containsExactly(original.captureBytes());
    assertThat(decoded.terminalEvidenceBytes()).containsExactly(original.terminalEvidenceBytes());
    assertThat(decoded.policyCount()).isEqualTo(original.policyCount());
    assertThat(decoded.policySetDigest()).isEqualTo(original.policySetDigest());
    assertThat(decoded.policies()).containsExactlyElementsOf(original.policies());

    byte[] returnedOperation = decoded.operationBytes();
    byte[] returnedCapture = decoded.captureBytes();
    byte[] returnedTerminal = decoded.terminalEvidenceBytes();
    Arrays.fill(returnedOperation, (byte) 0);
    Arrays.fill(returnedCapture, (byte) 0);
    Arrays.fill(returnedTerminal, (byte) 0);
    assertThat(decoded.operationBytes()).containsExactly(original.operationBytes());
    assertThat(decoded.captureBytes()).containsExactly(original.captureBytes());
    assertThat(decoded.terminalEvidenceBytes()).containsExactly(original.terminalEvidenceBytes());

    byte[] inputOperation = original.operationBytes();
    byte[] inputCapture = original.captureBytes();
    byte[] inputTerminal = original.terminalEvidenceBytes();
    var defensiveCopy =
        new RealmPolicyPublishedEvidence.PublishedSet(
            original.target(),
            original.versionNumber(),
            original.sourceCommitId(),
            original.sourceEpoch(),
            original.publishedReleaseBundleRef(),
            original.publishedReleaseBundleDigest(),
            original.publishWorkflowId(),
            original.manifestHash(),
            original.publicationVersionStateEpoch(),
            inputOperation,
            inputCapture,
            inputTerminal,
            original.policyCount(),
            original.policySetDigest(),
            original.policies());
    Arrays.fill(inputOperation, (byte) 0);
    Arrays.fill(inputCapture, (byte) 0);
    Arrays.fill(inputTerminal, (byte) 0);
    assertThat(defensiveCopy.operationBytes()).containsExactly(original.operationBytes());
    assertThat(defensiveCopy.captureBytes()).containsExactly(original.captureBytes());
    assertThat(defensiveCopy.terminalEvidenceBytes())
        .containsExactly(original.terminalEvidenceBytes());
  }

  @Test
  void rejectsPolicyPayloadSubstitutionWhenTheOriginalPolicyDigestIsUnchanged() throws Exception {
    var original = publishedSet();
    var first = original.policies().getFirst();
    var substitutedSource =
        new RealmPolicySource.Policy(
            first.source().commitId(),
            first.source().revisionId(),
            first.source().logicalRevisionId(),
            policy("realm-a", "Changed World", true));
    var substituted =
        new RealmPolicyPublishedEvidence.Policy(
            first.policyId(), substitutedSource, first.policyDigest());
    var changedPolicies = List.of(substituted, original.policies().get(1));

    assertThatThrownBy(
            () ->
                copy(
                    original, original.target(), original.terminalEvidenceBytes(), changedPolicies))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("policy digest");
  }

  @Test
  void rejectsPolicyMutationThatRecomputesDigestsButDiffersFromOriginalCapture() throws Exception {
    var original = publishedSet();
    var first = original.policies().getFirst();
    var changedSource =
        new RealmPolicySource.Policy(
            first.source().commitId(),
            first.source().revisionId(),
            first.source().logicalRevisionId(),
            policy("realm-a", "Changed World", true));
    var changedPolicy =
        new RealmPolicyPublishedEvidence.Policy(
            first.policyId(),
            changedSource,
            RealmPolicyPublishedEvidence.policyDigest(
                first.policyId(),
                original.target(),
                original.versionNumber(),
                original.publishedReleaseBundleRef(),
                original.publishedReleaseBundleDigest(),
                original.publishWorkflowId(),
                original.manifestHash(),
                changedSource));
    var changedPolicies = List.of(changedPolicy, original.policies().get(1));
    String changedSetDigest =
        RealmPolicyPublishedEvidence.computePolicySetDigest(
            original.target(),
            original.versionNumber(),
            original.sourceCommitId(),
            original.sourceEpoch(),
            original.publishedReleaseBundleRef(),
            original.publishedReleaseBundleDigest(),
            original.publishWorkflowId(),
            original.manifestHash(),
            original.publicationVersionStateEpoch(),
            original.operationBytes(),
            original.captureBytes(),
            original.terminalEvidenceBytes(),
            changedPolicies);

    assertThatThrownBy(
            () ->
                new RealmPolicyPublishedEvidence.PublishedSet(
                    original.target(),
                    original.versionNumber(),
                    original.sourceCommitId(),
                    original.sourceEpoch(),
                    original.publishedReleaseBundleRef(),
                    original.publishedReleaseBundleDigest(),
                    original.publishWorkflowId(),
                    original.manifestHash(),
                    original.publicationVersionStateEpoch(),
                    original.operationBytes(),
                    original.captureBytes(),
                    original.terminalEvidenceBytes(),
                    changedPolicies.size(),
                    changedSetDigest,
                    changedPolicies))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capture");
  }

  @Test
  void rejectsChangedScopeAndChangedTerminalEvidence() throws Exception {
    var original = publishedSet();
    var target = original.target();
    var changedTarget =
        new DraftCommitBinding.TargetProof(
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            target.canonicalVersionId(),
            target.gameDesignVersionRowId(),
            target.gameDesignVersionTenantKey(),
            target.sourceGameRowId(),
            target.sourceGameTenantKey(),
            target.sourceProvenanceKind());
    assertThatThrownBy(
            () ->
                copy(
                    original, changedTarget, original.terminalEvidenceBytes(), original.policies()))
        .isInstanceOf(IllegalArgumentException.class);

    var operation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    byte[] noPublicationTerminal =
        GameDesignPublicationTerminalReadGrpcCodecTest.terminal(operation, Outcome.NO_PUBLICATION)
            .canonicalBytes();
    assertThatThrownBy(
            () -> copy(original, original.target(), noPublicationTerminal, original.policies()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNoncanonicalSourceOrder() throws Exception {
    var original = publishedSet();
    assertThatThrownBy(
            () ->
                copy(
                    original,
                    original.target(),
                    original.terminalEvidenceBytes(),
                    List.of(original.policies().get(1), original.policies().getFirst())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source order");
  }

  @Test
  void rejectsMalformedUnknownMissingNoncanonicalTrailingAndOversizedInput() throws Exception {
    var original = publishedSet();
    byte[] encoded = RealmPolicyPublishedEvidenceCodec.encode(original);
    assertThatThrownBy(() -> RealmPolicyPublishedEvidenceCodec.decode(new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RealmPolicyPublishedEvidenceCodec.decode(
                    Arrays.copyOf(encoded, encoded.length + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RealmPolicyPublishedEvidenceCodec.decode(
                    new byte[RealmPolicyPublishedEvidenceCodec.MAX_ENVELOPE_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);

    assertRejectedPayload(original, root -> root.put("unsupported", "value"));
    assertRejectedPayload(original, root -> root.remove("manifestHash"));
    assertRejectedPayload(
        original,
        root ->
            ((ObjectNode) root.get("target"))
                .put("canonicalTenantId", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"));
    assertRejectedPayload(original, root -> root.put("versionNumber", "01"));

    byte[] payload = unframe(encoded);
    byte[] noncanonicalJson =
        (new String(payload, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> RealmPolicyPublishedEvidenceCodec.decode(frame(noncanonicalJson)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertRejectedPayload(
      RealmPolicyPublishedEvidence.PublishedSet evidence,
      java.util.function.Consumer<ObjectNode> mutation)
      throws Exception {
    var root =
        (ObjectNode) JSON.readTree(unframe(RealmPolicyPublishedEvidenceCodec.encode(evidence)));
    mutation.accept(root);
    byte[] payload = RealmPolicySource.canonical(root).getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> RealmPolicyPublishedEvidenceCodec.decode(frame(payload)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RealmPolicyPublishedEvidence.PublishedSet publishedSet() throws Exception {
    var operation = GameDesignPublicationTerminalReadGrpcCodecTest.operation();
    var terminal =
        GameDesignPublicationTerminalReadGrpcCodecTest.terminal(operation, Outcome.PUBLISHED);
    DraftCommitBinding selectedCommit = operation.account().input().selection().selectedCommit();
    var target = selectedCommit.target();
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
    String policySetDigest =
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
        policySetDigest,
        policies);
  }

  private static RealmPolicyPublishedEvidence.PublishedSet copy(
      RealmPolicyPublishedEvidence.PublishedSet evidence,
      DraftCommitBinding.TargetProof target,
      byte[] terminalEvidenceBytes,
      List<RealmPolicyPublishedEvidence.Policy> policies) {
    return new RealmPolicyPublishedEvidence.PublishedSet(
        target,
        evidence.versionNumber(),
        evidence.sourceCommitId(),
        evidence.sourceEpoch(),
        evidence.publishedReleaseBundleRef(),
        evidence.publishedReleaseBundleDigest(),
        evidence.publishWorkflowId(),
        evidence.manifestHash(),
        evidence.publicationVersionStateEpoch(),
        evidence.operationBytes(),
        evidence.captureBytes(),
        terminalEvidenceBytes,
        policies.size(),
        evidence.policySetDigest(),
        policies);
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

  private static byte[] unframe(byte[] encoded) {
    var reader = new DraftAuthorizationFenceBinding.FrameReader(encoded);
    reader.expect(RealmPolicyPublishedEvidenceCodec.SCHEMA);
    byte[] payload = reader.bytes();
    reader.requireEnd();
    return payload;
  }

  private static byte[] frame(byte[] payload) {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, RealmPolicyPublishedEvidenceCodec.SCHEMA);
    DraftAuthorizationFenceBinding.frame(out, payload);
    return out.toByteArray();
  }
}
