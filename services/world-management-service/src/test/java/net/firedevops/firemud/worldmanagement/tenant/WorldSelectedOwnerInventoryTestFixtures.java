package net.firedevops.firemud.worldmanagement.tenant;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AssetSnapshot;
import net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot;
import net.firedevops.firemud.common.gamedesign.CommandSnapshot;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.VisibilityFence;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;

/** Shared synthetic source and freeze evidence for selected-owner World inventory tests. */
final class WorldSelectedOwnerInventoryTestFixtures {
  private static final String NAMESPACE = "firemud";

  private WorldSelectedOwnerInventoryTestFixtures() {}

  static Fixture fixture(Owner owner) {
    UUID tenant = id("11111111-1111-4111-8111-111111111111");
    UUID version = id("22222222-2222-4222-8222-222222222222");
    UUID actor = id("99999999-9999-4999-8999-999999999999");
    var selected =
        DraftCommitBinding.create(
            new TargetProof(tenant, version, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW"),
            id("33333333-3333-4333-8333-333333333333"),
            id("44444444-4444-4444-8444-444444444444"),
            "base-commit-0",
            List.of(
                new RevisionPayload(
                    "0",
                    id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"),
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    CommandSource.deletePayload("synthetic-fixture-command"))),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    "TEMPLATE_CONFIG",
                    version.toString(),
                    "TEMPLATE_CONFIG",
                    "ALL",
                    "0")));
    var scope =
        new SelectedOwnerIntakeSourceReadScope(
            owner,
            NAMESPACE,
            id("55555555-5555-4555-8555-555555555555"),
            id("66666666-6666-4666-8666-666666666666"),
            id("77777777-7777-4777-8777-777777777777"),
            actor,
            selected);
    var content = sourceContent(scope);
    var binding =
        new SelectedOwnerIntakeAuthorizationBinding(
            content,
            List.of(
                source(SourceKind.TENANT, tenant.toString(), "tenant"),
                source(SourceKind.ACCOUNT, actor.toString(), "account"),
                source(SourceKind.MEMBERSHIP, actor + "/" + tenant, "membership")));
    var selection =
        AuthoredDraftPublishSelectionBinding.capture(
            new PublishIntent(
                tenant,
                version,
                "publication-request",
                "9",
                "source-intake test",
                selected.requestId(),
                selected.commitId(),
                selected.digest()),
            selected.target(),
            selected,
            new VisibilityFence(
                selected.target(),
                selected.requestId(),
                selected.commitId(),
                selected.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-01T00:00:00Z")));
    var publicationBinding =
        new AccountPublicationAuthorizationBinding(
            id("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            id("ffffffff-ffff-4fff-8fff-ffffffffffff"),
            new AccountPublicationAuthorizationBinding.PreallocationInput(actor, selection),
            List.of(
                new SourceEvidence(
                    SourceKind.ACCOUNT, actor.toString(), "1", "1", null, null, new byte[] {1})));
    var freezeRequest =
        WorldSelectedDraftPublicationFreezeEvidence.Request.create(
            NAMESPACE,
            tenant,
            version,
            selection.intent().publishRequestId(),
            9,
            selection.digest().substring("sha256:".length()),
            publicationBinding);
    var acknowledgement =
        new Acknowledgement(
            freezeRequest,
            id("12121212-1212-4212-8212-121212121212"),
            9,
            id("13131313-1313-4313-8313-131313131313"),
            OwnerFreezePhase.FROZEN,
            selected.commitId().toString(),
            "a".repeat(64),
            3);
    var freezeEvidence =
        WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
            freezeRequest,
            WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement));
    return new Fixture(
        binding,
        freezeEvidence,
        SelectedOwnerWorldInventoryReadEvidence.create(NAMESPACE, binding, freezeEvidence));
  }

  private static SelectedOwnerIntakeSourceContent sourceContent(
      SelectedOwnerIntakeSourceReadScope scope) {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, SelectedOwnerIntakeSourceContent.DOMAIN);
    DraftAuthorizationFenceBinding.frame(out, scope.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, scope.digest());
    for (String family :
        List.of(
            "COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG")) {
      byte[] snapshot = sourceSnapshot(family, scope.selected());
      DraftAuthorizationFenceBinding.frame(out, family);
      DraftAuthorizationFenceBinding.frame(out, snapshot);
      DraftAuthorizationFenceBinding.frame(out, DraftAuthorizationFenceBinding.digest(snapshot));
    }
    byte[] bytes = out.toByteArray();
    return SelectedOwnerIntakeSourceContent.fromStored(
        bytes, scope, DraftAuthorizationFenceBinding.digest(bytes));
  }

  private static byte[] sourceSnapshot(String family, DraftCommitBinding selected) {
    UUID genesis = id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    return switch (family) {
      case "COMMAND" ->
          new CommandSnapshot(
                  selected,
                  "0",
                  null,
                  DraftAuthorizationFenceBinding.digest(
                      "synthetic-world-fixture".getBytes(StandardCharsets.UTF_8)),
                  List.of())
              .canonicalBytes();
      case "REALM_POLICY" -> new RealmPolicySnapshot(selected, "0", List.of()).canonicalBytes();
      case "ASSET" -> new AssetSnapshot(selected, "0", null, genesis, List.of()).canonicalBytes();
      case "GAMEPLAY_RULE" ->
          new GameplayRuleSelectedSource(
                  GameplayRuleManifest.canonical(
                      Map.of(
                          "schema",
                          "game-design-gameplay-rule-source-snapshot/v1",
                          "bindingJson",
                          selected.canonicalJson(),
                          "bindingDigest",
                          selected.digest(),
                          "sourceEpoch",
                          "0",
                          "inheritedCommitId",
                          "",
                          "genesisReceiptId",
                          genesis.toString(),
                          "manifestJson",
                          GameplayRuleManifest.explicitEmpty().canonicalJson(),
                          "entries",
                          List.of())))
              .canonicalBytes();
      case "BRANDING" ->
          new BrandingSourceSnapshot(selected, "0", null, genesis, List.of()).canonicalBytes();
      case "TEMPLATE_CONFIG" ->
          new TemplateConfigSourceSnapshot(selected, "0", null, genesis, List.of())
              .canonicalBytes();
      default -> throw new IllegalArgumentException("Unsupported selected source fixture family");
    };
  }

  private static SourceEvidence source(SourceKind kind, String scope, String marker) {
    return new SourceEvidence(
        kind, scope, null, "1", null, null, marker.getBytes(StandardCharsets.UTF_8));
  }

  private static UUID id(String value) {
    return UUID.fromString(value);
  }

  record Fixture(
      SelectedOwnerIntakeAuthorizationBinding binding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence,
      SelectedOwnerWorldInventoryReadEvidence.Request request) {}
}
