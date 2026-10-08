package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Participant;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.ReleaseContent;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.ReadRequest;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.ReadResult;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence.Status;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fixed isolated fixtures prove carrier integrity, not owner producer, database, or mTLS proof. */
class PublishedRealmEntryPolicySetEvidenceTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final ObjectMapper JSON = new ObjectMapper();

  static PublishedRealmEntryPolicySetEvidence preseededSetFixture() throws Exception {
    return fixture(false).set();
  }

  @Test
  void terminalReadRequestUsesCanonicalByteValueEquality() throws Exception {
    var fixture = fixture(false);
    var expected =
        new ReadRequest(
            ReadRequest.SCHEMA_VERSION,
            fixture.operation().world().request().targetNamespace(),
            uuid("10101010-1010-4010-8010-101010101010"),
            fixture.operation().canonicalBytes());
    var decoded = ReadRequest.fromStored(expected.canonicalBytes());

    assertThat(decoded).isEqualTo(expected);
    assertThat(decoded.hashCode()).isEqualTo(expected.hashCode());
    assertThat(new java.util.HashSet<>(List.of(expected, decoded))).hasSize(1);
    var changedIdentity =
        new ReadRequest(
            expected.schemaVersion(),
            expected.targetNamespace(),
            uuid("20202020-2020-4020-8020-202020202020"),
            expected.operationBytes());
    assertThat(changedIdentity).isNotEqualTo(expected);
  }

  @Test
  void terminalEvidenceAndPublishedOrNoPublicationReadResultsUseCanonicalByteValueEquality()
      throws Exception {
    var fixture = fixture(false);
    var terminal = fixture.terminal();
    var decodedTerminal =
        GameDesignPublicationTerminalEvidence.fromStored(terminal.canonicalBytes());
    assertThat(decodedTerminal).isEqualTo(terminal);
    assertThat(decodedTerminal.hashCode()).isEqualTo(terminal.hashCode());

    var request =
        new ReadRequest(
            ReadRequest.SCHEMA_VERSION,
            fixture.operation().world().request().targetNamespace(),
            uuid("10101010-1010-4010-8010-101010101010"),
            fixture.operation().canonicalBytes());
    var published = new ReadResult(request, Status.PUBLISHED, Optional.of(terminal));
    var decodedPublished = ReadResult.fromStored(published.canonicalBytes());
    assertThat(decodedPublished).isEqualTo(published);
    assertThat(decodedPublished.hashCode()).isEqualTo(published.hashCode());

    var noPublicationTerminal =
        new GameDesignPublicationTerminalEvidence(
            fixture.operation().canonicalBytes(), Outcome.NO_PUBLICATION, null, null);
    var noPublication =
        new ReadResult(request, Status.NO_PUBLICATION, Optional.of(noPublicationTerminal));
    var decodedNoPublication = ReadResult.fromStored(noPublication.canonicalBytes());
    assertThat(decodedNoPublication).isEqualTo(noPublication);
    assertThat(decodedNoPublication.hashCode()).isEqualTo(noPublication.hashCode());

    var changedTerminal =
        terminal(
            fixture.operation(),
            fixture.release("changed-generation"),
            terminal.publicationVersionStateEpoch());
    assertThat(changedTerminal).isNotEqualTo(terminal);
    assertThat(new ReadResult(request, Status.PUBLISHED, Optional.of(changedTerminal)))
        .isNotEqualTo(published);
  }

  @Test
  void storedDraftSelectionRetainsCanonicalValidationAndTheNestedWireFailureCause()
      throws Exception {
    var selection = fixture(false).operation().account().input().selection();

    assertThatThrownBy(
            () ->
                AuthoredDraftPublishSelectionBinding.fromStored(
                    selection.canonicalJson() + " ", selection.digest()))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(
            failure -> {
              assertThat(failure.getCause())
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("not exact canonical JSON");
            });

    ObjectNode corrupted = (ObjectNode) JSON.readTree(selection.canonicalJson());
    corrupted.put("selectedCommitBindingJson", "{}");
    String corruptedJson = JSON.writeValueAsString(corrupted);
    assertThatThrownBy(
            () ->
                AuthoredDraftPublishSelectionBinding.fromStored(corruptedJson, selection.digest()))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(
            failure -> {
              assertThat(failure.getCause())
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("corrupt");
            });
  }

  @Test
  void completeSetRetainsTheActualOperationCaptureTerminalAndOrderedSourceRows() throws Exception {
    Fixture fixture = fixture(false);
    var restored = PublishedRealmEntryPolicySetEvidence.fromStored(fixture.set().canonicalBytes());

    assertThat(fixture.set().hasValidDigest()).isTrue();
    assertThat(restored).isEqualTo(fixture.set());
    assertThat(restored.canonicalBytes()).isEqualTo(fixture.set().canonicalBytes());
    assertThat(restored.target()).isEqualTo(fixture.target());
    assertThat(restored.sourceCommitId()).isEqualTo(fixture.sourceBinding().commitId());
    assertThat(restored.sourceEpoch()).isEqualTo("43");
    assertThat(restored.policyCount()).isEqualTo(1);
    assertThat(restored.policies()).containsExactlyElementsOf(fixture.policies());
    assertThat(restored.operationBytes()).isEqualTo(fixture.operation().canonicalBytes());
    assertThat(restored.captureBytes()).isEqualTo(fixture.captureBytes());
    assertThat(restored.terminalEvidenceBytes()).isEqualTo(fixture.terminal().canonicalBytes());
  }

  @Test
  void completeSetRejectsCountMissingOrderDuplicateAndDigestChanges() throws Exception {
    Fixture fixture = fixture(true);
    var set = fixture.set();

    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> copy(fixture, set.policyCount() + 1, set.policies(), set.policySetDigest()));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> copy(fixture, 0, List.of(), set.policySetDigest()));
    var reversed = new ArrayList<>(set.policies());
    java.util.Collections.reverse(reversed);
    assertThat(
            PublishedRealmEntryPolicySetEvidence.calculatePolicySetDigest(
                set.target(),
                set.versionNumber(),
                set.sourceCommitId(),
                set.sourceEpoch(),
                set.publishedReleaseBundleRef(),
                set.publishedReleaseBundleDigest(),
                set.publishWorkflowId(),
                set.manifestHash(),
                set.publicationVersionStateEpoch(),
                set.operationBytes(),
                set.captureBytes(),
                set.terminalEvidenceBytes(),
                reversed))
        .isEqualTo(set.policySetDigest());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> copy(fixture, reversed.size(), reversed, set.policySetDigest()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> copy(fixture, set.policyCount(), set.policies(), "sha256:" + "f".repeat(64)));
    var invalidChildDigest =
        new PublishedRealmEntryPolicyEvidence(
            fixture.policies().getFirst().policyId(),
            fixture.policies().getFirst().sourceCommitId(),
            fixture.policies().getFirst().sourceRevisionId(),
            fixture.policies().getFirst().logicalRevisionId(),
            fixture.policies().getFirst().policy(),
            "sha256:" + "f".repeat(64));
    var invalidChildren = List.of(invalidChildDigest, set.policies().getLast());
    var digestOverInvalidChild =
        PublishedRealmEntryPolicySetEvidence.calculatePolicySetDigest(
            set.target(),
            set.versionNumber(),
            set.sourceCommitId(),
            set.sourceEpoch(),
            set.publishedReleaseBundleRef(),
            set.publishedReleaseBundleDigest(),
            set.publishWorkflowId(),
            set.manifestHash(),
            set.publicationVersionStateEpoch(),
            set.operationBytes(),
            set.captureBytes(),
            set.terminalEvidenceBytes(),
            invalidChildren);
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> copy(fixture, set.policyCount(), invalidChildren, digestOverInvalidChild));

    var duplicateSource =
        List.of(
            fixture.policies().getFirst(),
            new PublishedRealmEntryPolicyEvidence(
                uuid("99999999-9999-4999-8999-999999999999"),
                uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                "duplicate-realm-in-another-world",
                policy("mars", "Mars", "main", "Main", false, false),
                DIGEST));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                PublishedRealmEntryPolicySetEvidence.calculatePolicySetDigest(
                    fixture.target(),
                    set.versionNumber(),
                    set.sourceCommitId(),
                    set.sourceEpoch(),
                    set.publishedReleaseBundleRef(),
                    set.publishedReleaseBundleDigest(),
                    set.publishWorkflowId(),
                    set.manifestHash(),
                    set.publicationVersionStateEpoch(),
                    set.operationBytes(),
                    set.captureBytes(),
                    set.terminalEvidenceBytes(),
                    duplicateSource));
  }

  @Test
  void completeSetRejectsChildSourceScopeCaptureTerminalAndBundleSubstitution() throws Exception {
    Fixture fixture = fixture(false);
    var set = fixture.set();
    var original = fixture.policies().getFirst();
    var changedSource =
        new PublishedRealmEntryPolicyEvidence(
            original.policyId(),
            original.sourceCommitId(),
            uuid("99999999-9999-4999-8999-999999999999"),
            original.logicalRevisionId(),
            original.policy(),
            original.policyDigest());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> copy(fixture, 1, List.of(changedSource), set.policySetDigest()));

    var changedTarget =
        new DraftCommitBinding.TargetProof(
            fixture.target().canonicalTenantId(),
            fixture.target().canonicalVersionId(),
            fixture.target().gameDesignVersionRowId() + 1,
            fixture.target().gameDesignVersionTenantKey(),
            fixture.target().sourceGameRowId(),
            fixture.target().sourceGameTenantKey(),
            fixture.target().sourceProvenanceKind());
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new PublishedRealmEntryPolicySetEvidence(
                    changedTarget,
                    set.versionNumber(),
                    set.sourceCommitId(),
                    set.sourceEpoch(),
                    set.publishedReleaseBundleRef(),
                    set.publishedReleaseBundleDigest(),
                    set.publishWorkflowId(),
                    set.manifestHash(),
                    set.publicationVersionStateEpoch(),
                    set.operationBytes(),
                    set.captureBytes(),
                    set.terminalEvidenceBytes(),
                    set.policyCount(),
                    set.policySetDigest(),
                    set.policies()));

    var changedOperation = operation(6L);
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new PublishedRealmEntryPolicySetEvidence(
                    set.target(),
                    set.versionNumber(),
                    set.sourceCommitId(),
                    set.sourceEpoch(),
                    set.publishedReleaseBundleRef(),
                    set.publishedReleaseBundleDigest(),
                    set.publishWorkflowId(),
                    set.manifestHash(),
                    set.publicationVersionStateEpoch(),
                    changedOperation.canonicalBytes(),
                    set.captureBytes(),
                    set.terminalEvidenceBytes(),
                    set.policyCount(),
                    set.policySetDigest(),
                    set.policies()));

    byte[] substitutedCapture =
        capture(fixture.operation(), fixture.sourceBinding(), "44", fixture.sources());
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new PublishedRealmEntryPolicySetEvidence(
                    set.target(),
                    set.versionNumber(),
                    set.sourceCommitId(),
                    set.sourceEpoch(),
                    set.publishedReleaseBundleRef(),
                    set.publishedReleaseBundleDigest(),
                    set.publishWorkflowId(),
                    set.manifestHash(),
                    set.publicationVersionStateEpoch(),
                    set.operationBytes(),
                    substitutedCapture,
                    set.terminalEvidenceBytes(),
                    set.policyCount(),
                    set.policySetDigest(),
                    set.policies()));

    var substitutedTerminal = terminal(fixture.operation(), fixture.release("generation-2"), 6L);
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new PublishedRealmEntryPolicySetEvidence(
                    set.target(),
                    set.versionNumber(),
                    set.sourceCommitId(),
                    set.sourceEpoch(),
                    set.publishedReleaseBundleRef(),
                    set.publishedReleaseBundleDigest(),
                    set.publishWorkflowId(),
                    set.manifestHash(),
                    set.publicationVersionStateEpoch(),
                    set.operationBytes(),
                    set.captureBytes(),
                    substitutedTerminal.canonicalBytes(),
                    set.policyCount(),
                    set.policySetDigest(),
                    set.policies()));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new PublishedRealmEntryPolicySetEvidence(
                    set.target(),
                    set.versionNumber(),
                    set.sourceCommitId(),
                    set.sourceEpoch(),
                    "substituted-bundle-reference",
                    set.publishedReleaseBundleDigest(),
                    set.publishWorkflowId(),
                    set.manifestHash(),
                    set.publicationVersionStateEpoch(),
                    set.operationBytes(),
                    set.captureBytes(),
                    set.terminalEvidenceBytes(),
                    set.policyCount(),
                    set.policySetDigest(),
                    set.policies()));
  }

  @Test
  void storedSetRejectsUnknownAndNoncanonicalCarrierBytes() throws Exception {
    Fixture fixture = fixture(false);
    byte[] spaced = (" " + fixture.set().canonicalJson()).getBytes(StandardCharsets.UTF_8);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublishedRealmEntryPolicySetEvidence.fromStored(spaced));

    ObjectNode withUnknown = (ObjectNode) JSON.readTree(fixture.set().canonicalJson());
    withUnknown.put("unrecognized", "field");
    byte[] unknown = Rfc8785CanonicalJson.canonicalizeUtf8(withUnknown.toString());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PublishedRealmEntryPolicySetEvidence.fromStored(unknown));
  }

  private static PublishedRealmEntryPolicySetEvidence copy(
      Fixture fixture,
      int policyCount,
      List<PublishedRealmEntryPolicyEvidence> policies,
      String digest) {
    var set = fixture.set();
    return new PublishedRealmEntryPolicySetEvidence(
        set.target(),
        set.versionNumber(),
        set.sourceCommitId(),
        set.sourceEpoch(),
        set.publishedReleaseBundleRef(),
        set.publishedReleaseBundleDigest(),
        set.publishWorkflowId(),
        set.manifestHash(),
        set.publicationVersionStateEpoch(),
        set.operationBytes(),
        set.captureBytes(),
        set.terminalEvidenceBytes(),
        policyCount,
        digest,
        policies);
  }

  static Fixture fixture(boolean includeSide) throws Exception {
    GameDesignPublicationOperationBinding operation = operation(5L);
    var target = operation.account().input().selection().target();
    var sourceBinding = operation.account().input().selection().selectedCommit();
    var terminal = terminal(operation, release(operation, "generation-1"), 6L);
    List<Source> sources = new ArrayList<>();
    sources.add(
        source(
            "earth",
            "Earth",
            "main",
            "Main",
            true,
            true,
            "33333333-3333-4333-8333-333333333333",
            "44444444-4444-4444-8444-444444444444",
            "55555555-5555-4555-8555-555555555555",
            "main-policy-v1"));
    if (includeSide) {
      sources.add(
          source(
              "earth",
              "Earth",
              "side",
              "Side",
              true,
              false,
              "88888888-8888-4888-8888-888888888888",
              "66666666-6666-4666-8666-666666666666",
              "77777777-7777-4777-8777-777777777777",
              "side-policy-v1"));
    }
    byte[] capture = capture(operation, sourceBinding, "43", sources);
    List<PublishedRealmEntryPolicyEvidence> policies =
        sources.stream()
            .map(
                source -> {
                  UUID policyId = UUID.fromString(source.policyId());
                  String policyDigest =
                      PublishedRealmEntryPolicySetEvidence.policyDigest(
                          policyId,
                          target,
                          terminal.releaseContent().versionNumber(),
                          terminal.publishedReleaseBundleRef(),
                          terminal.publishedReleaseBundleDigest(),
                          terminal.releaseContent().publishWorkflowId(),
                          terminal.releaseContent().manifestHash(),
                          UUID.fromString(source.sourceCommitId()),
                          UUID.fromString(source.sourceRevisionId()),
                          source.logicalRevisionId(),
                          source.policy());
                  return new PublishedRealmEntryPolicyEvidence(
                      policyId,
                      UUID.fromString(source.sourceCommitId()),
                      UUID.fromString(source.sourceRevisionId()),
                      source.logicalRevisionId(),
                      source.policy(),
                      policyDigest);
                })
            .toList();
    var setDigest =
        PublishedRealmEntryPolicySetEvidence.calculatePolicySetDigest(
            target,
            terminal.releaseContent().versionNumber(),
            sourceBinding.commitId(),
            "43",
            terminal.publishedReleaseBundleRef(),
            terminal.publishedReleaseBundleDigest(),
            terminal.releaseContent().publishWorkflowId(),
            terminal.releaseContent().manifestHash(),
            terminal.publicationVersionStateEpoch(),
            operation.canonicalBytes(),
            capture,
            terminal.canonicalBytes(),
            policies);
    var set =
        new PublishedRealmEntryPolicySetEvidence(
            target,
            terminal.releaseContent().versionNumber(),
            sourceBinding.commitId(),
            "43",
            terminal.publishedReleaseBundleRef(),
            terminal.publishedReleaseBundleDigest(),
            terminal.releaseContent().publishWorkflowId(),
            terminal.releaseContent().manifestHash(),
            terminal.publicationVersionStateEpoch(),
            operation.canonicalBytes(),
            capture,
            terminal.canonicalBytes(),
            policies.size(),
            setDigest,
            policies);
    return new Fixture(
        target, operation, sourceBinding, terminal, capture, List.copyOf(sources), policies, set);
  }

  private static Source source(
      String world,
      String worldName,
      String realm,
      String realmName,
      boolean visible,
      boolean production,
      String policyId,
      String sourceCommitId,
      String sourceRevisionId,
      String logicalRevisionId) {
    return new Source(
        policyId,
        sourceCommitId,
        sourceRevisionId,
        logicalRevisionId,
        policy(world, worldName, realm, realmName, visible, production));
  }

  private static RealmEntryPolicy policy(
      String world,
      String worldName,
      String realm,
      String realmName,
      boolean visible,
      boolean production) {
    return RealmEntryPolicy.parse(
        "{\"schemaVersion\":1,\"worldSlug\":\""
            + world
            + "\",\"worldDisplayName\":\""
            + worldName
            + "\",\"realmSlug\":\""
            + realm
            + "\",\"realmDisplayName\":\""
            + realmName
            + "\",\"visible\":"
            + visible
            + ",\"publicProduction\":"
            + production
            + ",\"stateScope\":\"SHARED\",\"entryPolicy\":\"PRESEEDED_ONLY\"}",
        JSON);
  }

  private static byte[] capture(
      GameDesignPublicationOperationBinding operation,
      DraftCommitBinding binding,
      String sourceEpoch,
      List<Source> sources)
      throws IOException {
    List<Map<String, Object>> policies =
        sources.stream()
            .map(
                source -> {
                  Map<String, Object> value = new LinkedHashMap<>();
                  value.put("commitId", source.sourceCommitId());
                  value.put("revisionId", source.sourceRevisionId());
                  value.put("logicalRevisionId", source.logicalRevisionId());
                  value.put("policy", JSON.readTree(source.policy().canonicalJson()));
                  return value;
                })
            .toList();
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("schema", PublishedRealmEntryPolicySetEvidence.SNAPSHOT_SCHEMA);
    snapshot.put("bindingJson", binding.canonicalJson());
    snapshot.put("bindingDigest", binding.digest());
    snapshot.put("sourceEpoch", sourceEpoch);
    snapshot.put("policies", policies);
    byte[] snapshotBytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(snapshot));
    ByteArrayOutputStream capture = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(
        capture, PublishedRealmEntryPolicySetEvidence.CAPTURE_SCHEMA);
    DraftAuthorizationFenceBinding.frame(capture, operation.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(capture, snapshotBytes);
    return capture.toByteArray();
  }

  private static GameDesignPublicationTerminalEvidence terminal(
      GameDesignPublicationOperationBinding operation, ReleaseContent release, long epoch) {
    return new GameDesignPublicationTerminalEvidence(
        operation.canonicalBytes(), Outcome.PUBLISHED, release, epoch);
  }

  private static ReleaseContent release(
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
        generation,
        operation.world());
  }

  private static GameDesignPublicationOperationBinding operation(long epoch) throws Exception {
    var seed = WorldPublishedStartLocationGrpcCodecTest.evidence();
    var original = DraftAuthorizationFenceBinding.fromStored(seed.originalAccountBindingBytes());
    var draft =
        DraftCommitBinding.fromStored(
            new String(original.gameDesignBinding(), StandardCharsets.UTF_8),
            original.inputDigest());
    var target = draft.target();
    String request = "publication-request";
    var selected =
        AuthoredDraftPublishSelectionBinding.capture(
            new AuthoredDraftPublishSelectionBinding.PublishIntent(
                target.canonicalTenantId(),
                target.canonicalVersionId(),
                request,
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
    var requestEvidence = seed.request();
    var world =
        new WorldPublishedStartLocationEvidence(
            new WorldPublishedStartLocationEvidence.Request(
                requestEvidence.targetNamespace(),
                requestEvidence.canonicalTenantId(),
                requestEvidence.canonicalVersionId(),
                requestEvidence.intakeRequestId(),
                requestEvidence.publicationFence(),
                request,
                selected.digest().substring(7),
                epoch,
                PublicationDigestRequestBinding.full(
                        target.canonicalTenantId().toString(),
                        Long.toString(target.gameDesignVersionRowId()),
                        request)
                    .derivedWorkflowIdentity(),
                requestEvidence.appliedCommitId(),
                requestEvidence.contentDigest(),
                requestEvidence.digestSchemaVersion(),
                requestEvidence.worldAffectedTuples()),
            seed.selectorReceiptBytes(),
            seed.originalAccountBindingBytes(),
            seed.appliedResultBytes());
    return new GameDesignPublicationOperationBinding(account, world);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Source(
      String policyId,
      String sourceCommitId,
      String sourceRevisionId,
      String logicalRevisionId,
      RealmEntryPolicy policy) {}

  record Fixture(
      DraftCommitBinding.TargetProof target,
      GameDesignPublicationOperationBinding operation,
      DraftCommitBinding sourceBinding,
      GameDesignPublicationTerminalEvidence terminal,
      byte[] captureBytes,
      List<Source> sources,
      List<PublishedRealmEntryPolicyEvidence> policies,
      PublishedRealmEntryPolicySetEvidence set) {
    private ReleaseContent release(String generation) {
      return PublishedRealmEntryPolicySetEvidenceTest.release(operation, generation);
    }
  }
}
