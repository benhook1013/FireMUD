package net.firedevops.firemud.common.testing;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed PLAYER_CREATED publication carriers for source receiver tests. Account authorization,
 * World application/inventory and publication custody are controlled fixtures, not executed owners.
 */
public final class PlayerCreatedPublishedPolicyFixtures {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private PlayerCreatedPublishedPolicyFixtures() {}

  /** Adds the existing controlled World graph to the receiver's selected authored source. */
  public static DraftCommitBinding withWorldSource(DraftCommitBinding source) throws Exception {
    var original = AuthoringFixtures.freshGraphRequest().accountBinding();
    var graph =
        DraftCommitBinding.fromStored(
            new String(original.gameDesignBinding(), StandardCharsets.UTF_8),
            original.inputDigest());
    var revisions = new ArrayList<>(source.revisions());
    for (var revision : graph.revisions()) {
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(revisions.size()),
              revision.revisionId(),
              revision.owner(),
              revision.payload()));
    }
    var affected = new ArrayList<>(source.affectedUnits());
    affected.addAll(graph.affectedUnits());
    return DraftCommitBinding.create(
        source.target(),
        graph.requestId(),
        graph.commitId(),
        graph.baseCommitId(),
        revisions,
        affected);
  }

  public record Prepared(
      DraftCommitBinding selected,
      AccountPublicationAuthorizationBinding account,
      WorldPublishedStartLocationEvidence world) {}

  /** Builds a matching selected publication operation prefix with real closed World codecs. */
  public static Prepared prepare(DraftCommitBinding selected) throws Exception {
    var original =
        DraftAuthorizationFenceBinding.fromStored(AuthoringFixtures.accountBinding(selected));
    String request = "publication-request";
    long epoch = 5L;
    var selection =
        AuthoredDraftPublishSelectionBinding.capture(
            new AuthoredDraftPublishSelectionBinding.PublishIntent(
                selected.target().canonicalTenantId(),
                selected.target().canonicalVersionId(),
                request,
                Long.toString(epoch),
                "controlled PLAYER_CREATED source",
                selected.requestId(),
                selected.commitId(),
                selected.digest()),
            selected.target(),
            selected,
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                selected.target(),
                selected.requestId(),
                selected.commitId(),
                selected.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-07T00:00:00Z")));
    var account =
        new AccountPublicationAuthorizationBinding(
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            new AccountPublicationAuthorizationBinding.PreallocationInput(
                original.actorAccountId(), selection),
            List.of(
                new DraftAuthorizationFenceBinding.SourceEvidence(
                    DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
                    original.actorAccountId().toString(),
                    "1",
                    "1",
                    null,
                    null,
                    new byte[] {1})));
    var terminalRequest =
        new WorldDraftTerminalReadEvidence.Request(
            1,
            "test",
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            original.canonicalBytes());
    var applied = AuthoringFixtures.committedFreshGraphReadback(terminalRequest);
    var result = JSON.readTree(applied.result());
    var tuples =
        selected.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    var world =
        new WorldPublishedStartLocationEvidence(
            new WorldPublishedStartLocationEvidence.Request(
                "test",
                selected.target().canonicalTenantId(),
                selected.target().canonicalVersionId(),
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                request,
                selection.digest().substring(7),
                epoch,
                PublicationDigestRequestBinding.full(
                        selected.target().canonicalTenantId().toString(),
                        Long.toString(selected.target().gameDesignVersionRowId()),
                        request)
                    .derivedWorkflowIdentity(),
                selected.commitId().toString(),
                "b".repeat(64),
                3,
                tuples),
            Base64.getDecoder().decode(result.get("startLocationReceiptBase64").textValue()),
            original.canonicalBytes(),
            applied.result());
    return new Prepared(selected, account, world);
  }

  /**
   * Caller supplies the existing explicitly stipulated inventory fixture; no owner is simulated.
   */
  public static PublishedRealmEntryPolicySetEvidence seal(
      Prepared prepared,
      WorldSelectedPublicationArtifactInventoryEvidence inventory,
      GameDesignPublicationTerminalEvidence.ReleaseContent release,
      AuthoredInitialActorCoreProjection projection)
      throws Exception {
    var operation =
        new GameDesignPublicationOperationBinding(prepared.account(), prepared.world(), inventory);
    var terminal =
        new GameDesignPublicationTerminalEvidence(
            operation.canonicalBytes(),
            GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED,
            release,
            prepared.world().request().versionStateEpoch() + 1L);
    var source = projection.policy();
    var policy = RealmEntryPolicy.parseCanonical(source.canonicalJson(), JSON);
    UUID policyId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    var selectedPolicy =
        new PublishedRealmEntryPolicyEvidence(
            policyId,
            source.commitId(),
            source.revisionId(),
            source.logicalRevisionId(),
            policy,
            PublishedRealmEntryPolicyEvidence.calculateDigest(
                policyId,
                prepared.selected().target(),
                release.versionNumber(),
                release.publishedReleaseBundleRef(),
                release.contentDigest(),
                release.publishWorkflowId(),
                release.manifestHash(),
                source.commitId(),
                source.revisionId(),
                source.logicalRevisionId(),
                policy));
    var snapshot =
        Map.of(
            "schema",
            PublishedRealmEntryPolicySetEvidence.SNAPSHOT_SCHEMA,
            "bindingJson",
            prepared.selected().canonicalJson(),
            "bindingDigest",
            prepared.selected().digest(),
            "sourceEpoch",
            "1",
            "policies",
            List.of(
                Map.of(
                    "commitId",
                    source.commitId().toString(),
                    "revisionId",
                    source.revisionId().toString(),
                    "logicalRevisionId",
                    source.logicalRevisionId(),
                    "policy",
                    JSON.readTree(policy.canonicalJson()))));
    var capture = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(
        capture, PublishedRealmEntryPolicySetEvidence.CAPTURE_SCHEMA);
    DraftAuthorizationFenceBinding.frame(capture, operation.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(
        capture, Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(snapshot)));
    var policies = List.of(selectedPolicy);
    var digest =
        PublishedRealmEntryPolicySetEvidence.calculatePolicySetDigest(
            prepared.selected().target(),
            release.versionNumber(),
            prepared.selected().commitId(),
            "1",
            release.publishedReleaseBundleRef(),
            release.contentDigest(),
            release.publishWorkflowId(),
            release.manifestHash(),
            terminal.publicationVersionStateEpoch(),
            operation.canonicalBytes(),
            capture.toByteArray(),
            terminal.canonicalBytes(),
            policies);
    return new PublishedRealmEntryPolicySetEvidence(
        prepared.selected().target(),
        release.versionNumber(),
        prepared.selected().commitId(),
        "1",
        release.publishedReleaseBundleRef(),
        release.contentDigest(),
        release.publishWorkflowId(),
        release.manifestHash(),
        terminal.publicationVersionStateEpoch(),
        operation.canonicalBytes(),
        capture.toByteArray(),
        terminal.canonicalBytes(),
        1,
        digest,
        policies);
  }
}
