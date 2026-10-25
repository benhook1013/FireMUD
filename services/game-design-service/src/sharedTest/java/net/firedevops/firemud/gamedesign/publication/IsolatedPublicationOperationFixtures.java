package net.firedevops.firemud.gamedesign.publication;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import net.firedevops.firemud.test.IsolatedWorldPublicationInventoryFixtures;
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
    return fresh(target, seed);
  }

  public static GameDesignPublicationOperation fresh(
      DraftCommitBinding.TargetProof target, String baseReference) throws Exception {
    return fresh(target, PublishedWorldSelectorFixtures.evidence(target, baseReference));
  }

  /** Fresh stipulated selected-full fixture; schema 4 is constructed before operation retention. */
  public static GameDesignPublicationOperation freshSelectedFull(
      DraftCommitBinding.TargetProof target, String baseReference) throws Exception {
    return fresh(
        target,
        PublishedWorldSelectorFixtures.selectedFullSeed(target, baseReference),
        AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION);
  }

  private static GameDesignPublicationOperation fresh(
      DraftCommitBinding.TargetProof target, WorldPublishedStartLocationEvidence seed)
      throws Exception {
    return fresh(target, seed, 3);
  }

  private static GameDesignPublicationOperation fresh(
      DraftCommitBinding.TargetProof target,
      WorldPublishedStartLocationEvidence seed,
      int worldDigestSchemaVersion)
      throws Exception {
    var originalDraftAccount =
        DraftAuthorizationFenceBinding.fromStored(seed.originalAccountBindingBytes());
    var draft =
        DraftCommitBinding.fromStored(
            new String(originalDraftAccount.gameDesignBinding(), StandardCharsets.UTF_8),
            originalDraftAccount.inputDigest());
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
    return selection(selected, seed, false, worldDigestSchemaVersion);
  }

  public static GameDesignPublicationOperation forSelection(
      AuthoredDraftPublishSelectionBinding selected, WorldPublishedStartLocationEvidence seed) {
    return selection(selected, seed, false, 3);
  }

  /** Builds a fresh selected-full operation without reinterpreting retained schema-3 history. */
  public static GameDesignPublicationOperation forSelectedFullSelection(
      AuthoredDraftPublishSelectionBinding selected, WorldPublishedStartLocationEvidence seed) {
    return selection(
        selected,
        seed,
        false,
        AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION);
  }

  /**
   * Explicit pre-v2 fixture preimage for retained-schema tests only. Decoding these original three
   * frames preserves history; it cannot provide complete inventory or fresh v2 authority.
   */
  public static GameDesignPublicationOperation historicalV1ForSelection(
      AuthoredDraftPublishSelectionBinding selected, WorldPublishedStartLocationEvidence seed) {
    return selection(selected, seed, true, 3);
  }

  private static GameDesignPublicationOperation selection(
      AuthoredDraftPublishSelectionBinding selected,
      WorldPublishedStartLocationEvidence seed,
      boolean historicalV1,
      int worldDigestSchemaVersion) {
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
                worldDigestSchemaVersion,
                seed.request().worldAffectedTuples()),
            seed.selectorReceiptBytes(),
            seed.originalAccountBindingBytes(),
            seed.appliedResultBytes());
    if (historicalV1) {
      var original = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(original, "game-design-publication-operation/v1");
      DraftAuthorizationFenceBinding.frame(original, account.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(original, world.canonicalBytes());
      return GameDesignPublicationOperation.fromStored(original.toByteArray());
    }
    return new GameDesignPublicationOperation(
        account, world, IsolatedWorldPublicationInventoryFixtures.stipulated(account, world));
  }
}
