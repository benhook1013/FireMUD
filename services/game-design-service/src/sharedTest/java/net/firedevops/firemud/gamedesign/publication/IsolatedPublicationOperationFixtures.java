package net.firedevops.firemud.gamedesign.publication;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import tools.jackson.databind.ObjectMapper;

/**
 * ISOLATED stipulated upstream source/World bytes; only callers' GD owner writes are real proof.
 */
public final class IsolatedPublicationOperationFixtures {
  private IsolatedPublicationOperationFixtures() {}

  /**
   * A new fixture operation and freeze, never a reinterpretation of retained publication history.
   */
  public static GameDesignPublicationOperation fresh(DraftCommitBinding.TargetProof target)
      throws Exception {
    var seed = PublishedWorldSelectorFixtures.evidence(target);
    var originalDraftAccount =
        DraftAuthorizationFenceBinding.fromStored(seed.originalAccountBindingBytes());
    var draft =
        DraftCommitBinding.fromStored(
            new String(originalDraftAccount.gameDesignBinding(), StandardCharsets.UTF_8),
            originalDraftAccount.inputDigest());
    var appliedEpochs =
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
                        new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
            .toList();
    String vector =
        new String(
            Rfc8785CanonicalJson.canonicalizeUtf8(
                new ObjectMapper()
                    .writeValueAsString(
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
                                "ISOLATED-world-applied",
                                "resultBytesBase64",
                                Base64.getEncoder().encodeToString(seed.appliedResultBytes()),
                                "appliedEpochs",
                                appliedEpochs)))),
            StandardCharsets.UTF_8);
    var intent =
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            "ISOLATED-publication-request",
            "5",
            "ISOLATED owner transaction proof",
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
                OffsetDateTime.parse("2026-10-07T00:00:00Z")));
    return forSelection(selected, seed);
  }

  public static GameDesignPublicationOperation forSelection(
      AuthoredDraftPublishSelectionBinding selected, WorldPublishedStartLocationEvidence seed) {
    var originalDraftAccount =
        DraftAuthorizationFenceBinding.fromStored(seed.originalAccountBindingBytes());
    var target = selected.target();
    var intent = selected.intent();
    var draft = selected.selectedCommit();
    var account =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new AccountPublicationAuthorizationBinding.PreallocationInput(
                originalDraftAccount.actorAccountId(), selected),
            java.util.Arrays.stream(DraftAuthorizationFenceBinding.SourceKind.values())
                .map(
                    kind ->
                        new DraftAuthorizationFenceBinding.SourceEvidence(
                            kind,
                            switch (kind) {
                              case ACCOUNT, GLOBAL_ROLES ->
                                  originalDraftAccount.actorAccountId().toString();
                              case TENANT -> target.canonicalTenantId().toString();
                              case MEMBERSHIP ->
                                  originalDraftAccount.actorAccountId()
                                      + "/"
                                      + target.canonicalTenantId();
                              default -> "ISOLATED-" + kind;
                            },
                            "1",
                            "1",
                            null,
                            null,
                            ("ISOLATED-stipulated-" + kind).getBytes(StandardCharsets.UTF_8)))
                .toList());
    var world =
        new WorldPublishedStartLocationEvidence(
            new WorldPublishedStartLocationEvidence.Request(
                "test",
                target.canonicalTenantId(),
                target.canonicalVersionId(),
                seed.request().intakeRequestId(),
                UUID.randomUUID(),
                intent.publishRequestId(),
                selected.digest().substring(7),
                Long.parseLong(intent.expectedVersionStateEpoch()),
                FiremudWorkflowIds.workflowId(
                    "publish",
                    target.canonicalTenantId().toString(),
                    "publish-request",
                    intent.publishRequestId()),
                draft.commitId().toString(),
                seed.request().contentDigest(),
                3,
                seed.request().worldAffectedTuples()),
            seed.selectorReceiptBytes(),
            seed.originalAccountBindingBytes(),
            seed.appliedResultBytes());
    return new GameDesignPublicationOperation(
        account,
        world,
        net.firedevops.firemud.test.IsolatedWorldPublicationInventoryFixtures.stipulated(
            account, world));
  }
}
