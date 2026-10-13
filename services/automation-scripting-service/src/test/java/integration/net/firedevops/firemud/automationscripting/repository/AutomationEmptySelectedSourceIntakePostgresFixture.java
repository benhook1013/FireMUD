package net.firedevops.firemud.automationscripting.repository;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerEmptySourceInputs;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.automation.AutomationAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.gamedesign.AssetSnapshot;
import net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot;
import net.firedevops.firemud.common.gamedesign.CommandSnapshot;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.AppliedEpoch;
import net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.TemplateConfigOwnerSourceInventoryDeclaration;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceValues;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.VisibilityFence;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.ArtifactDecision;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.Checkpoint;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.Freeze;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceClosureDeclaration;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicAccountOrder;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicOwnerScope;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SelectedApplication;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SourceModel;

/**
 * Test-only synthetic inputs built through the same typed Common decoders as the shared fixture.
 * They are not authenticated Account or World producer evidence or physical owner proof.
 */
final class AutomationEmptySelectedSourceIntakePostgresFixture {
  static final String NAMESPACE = "test";
  static final UUID TENANT = id("11111111-1111-4111-8111-111111111111");
  static final UUID VERSION = id("22222222-2222-4222-8222-222222222222");
  static final UUID ACTOR = id("99999999-9999-4999-8999-999999999999");

  private static final UUID OPERATION = id("55555555-5555-4555-8555-555555555555");
  private static final UUID FENCE = id("66666666-6666-4666-8666-666666666666");
  private static final UUID INTAKE_REQUEST = id("77777777-7777-4777-8777-777777777777");
  private static final UUID GENESIS_RECEIPT = id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  private static final String TEMPLATE_CONFIG =
      "{\"schemaVersion\":1,\"baseVersionId\":\""
          + VERSION
          + "\",\"world\":{\"regions\":[],\"rooms\":[]},"
          + "\"entity\":{\"items\":[],\"npcs\":[]},\"gameLogic\":{\"inputs\":[]},"
          + "\"automation\":{\"scripts\":[],\"scriptPatch\":{\"presence\":\"ABSENT\"}},"
          + "\"supportedSettings\":[]}";

  private AutomationEmptySelectedSourceIntakePostgresFixture() {}

  static Fixture create(String sourceEpoch) {
    DraftCommitBinding selected = selected();
    var scope =
        new net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope(
            Owner.AUTOMATION_SCRIPTING,
            NAMESPACE,
            OPERATION,
            FENCE,
            INTAKE_REQUEST,
            ACTOR,
            selected);
    TemplateConfigSourceSnapshot templateSnapshot = templateSnapshot(selected, sourceEpoch);
    SelectedOwnerIntakeAuthorizationBinding authorization =
        new SelectedOwnerIntakeAuthorizationBinding(
            sourceContent(scope, templateSnapshot), accountSources());
    WorldSelectedDraftPublicationFreezeEvidence freeze = freezeEvidence(selected);
    var inventory =
        WorldSelectedPublicationArtifactInventoryEvidence.fromPublicEvidence(
            freeze, publicInventory(freeze));
    var worldRequest =
        SelectedOwnerWorldInventoryReadEvidence.create(NAMESPACE, authorization, freeze);
    var worldEvidence = new SelectedOwnerWorldInventoryReadEvidence(worldRequest, inventory);
    return new Fixture(
        authorization,
        freeze,
        worldEvidence,
        new SelectedOwnerEmptySourceInputs(authorization, worldEvidence));
  }

  static Fixture withFreshWorldReadCorrelation(Fixture original) {
    var request =
        SelectedOwnerWorldInventoryReadEvidence.create(
            NAMESPACE, original.authorization(), original.freezeEvidence());
    var evidence =
        new SelectedOwnerWorldInventoryReadEvidence(request, original.worldEvidence().inventory());
    return new Fixture(
        original.authorization(),
        original.freezeEvidence(),
        evidence,
        new SelectedOwnerEmptySourceInputs(original.authorization(), evidence));
  }

  private static TemplateConfigSourceSnapshot templateSnapshot(
      DraftCommitBinding selected, String sourceEpoch) {
    var declaration =
        AutomationAuthoredSourceInventoryDeclaration.parse(
            "{\"schema\":\"automation-authored-source-inventory/v1\",\"families\":{"
                + "\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[],"
                + "\"SCRIPT_PATCH_SOURCES\":[]}}");
    String declarationPayload =
        TemplateConfigSourceValues.ownerInventoryPayload(Owner.AUTOMATION_SCRIPTING, declaration);
    DraftCommitBinding authored = authoredBinding(declarationPayload);
    TemplateConfigOwnerSourceInventoryDeclaration ownerDeclaration =
        TemplateConfigSourceValues.mutations(authored).stream()
            .filter(
                mutation ->
                    mutation.operation()
                        == TemplateConfigSourceValues.OperationKind.DECLARE_OWNER_SOURCE_INVENTORY)
            .map(TemplateConfigSourceValues.Mutation::ownerInventoryDeclaration)
            .findFirst()
            .orElseThrow();
    return new TemplateConfigSourceSnapshot(
        selected,
        sourceEpoch,
        authored.commitId(),
        GENESIS_RECEIPT,
        List.of(),
        List.of(ownerDeclaration));
  }

  private static DraftCommitBinding authoredBinding(String payload) {
    return DraftCommitBinding.create(
        target(),
        id("51515151-5151-4151-8151-515151515151"),
        id("52525252-5252-4252-8252-525252525252"),
        "base-commit-0",
        List.of(
            new RevisionPayload(
                "0",
                id("53535353-5353-4353-8353-535353535353"),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                payload)),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                TemplateConfigSourceValues.SCOPE,
                VERSION.toString(),
                TemplateConfigSourceValues.SCOPE,
                TemplateConfigSourceValues.SCOPE_ID,
                "0")));
  }

  private static SelectedOwnerIntakeSourceContent sourceContent(
      net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope scope,
      TemplateConfigSourceSnapshot templateSnapshot) {
    var out = new ByteArrayOutputStream();
    frame(out, SelectedOwnerIntakeSourceContent.DOMAIN);
    frame(out, scope.canonicalBytes());
    frame(out, scope.digest());
    for (String family :
        List.of(
            "COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG")) {
      byte[] snapshot =
          "TEMPLATE_CONFIG".equals(family)
              ? templateSnapshot.canonicalBytes()
              : sourceSnapshot(family, scope.selected());
      frame(out, family);
      frame(out, snapshot);
      frame(out, DraftAuthorizationFenceBinding.digest(snapshot));
    }
    byte[] bytes = out.toByteArray();
    return SelectedOwnerIntakeSourceContent.fromStored(
        bytes, scope, DraftAuthorizationFenceBinding.digest(bytes));
  }

  private static byte[] sourceSnapshot(String family, DraftCommitBinding selected) {
    // These values exercise exact stored-byte decoding only; they are synthetic, not owner proof.
    return switch (family) {
      case "COMMAND" ->
          new CommandSnapshot(selected, "0", null, "sha256:" + "0".repeat(64), List.of())
              .canonicalBytes();
      case "REALM_POLICY" -> new RealmPolicySnapshot(selected, "0", List.of()).canonicalBytes();
      case "ASSET" ->
          new AssetSnapshot(selected, "0", null, GENESIS_RECEIPT, List.of()).canonicalBytes();
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
                          GENESIS_RECEIPT.toString(),
                          "manifestJson",
                          GameplayRuleManifest.explicitEmpty().canonicalJson(),
                          "entries",
                          List.of())))
              .canonicalBytes();
      case "BRANDING" ->
          new BrandingSourceSnapshot(selected, "0", null, GENESIS_RECEIPT, List.of())
              .canonicalBytes();
      default -> throw new IllegalArgumentException("Unsupported source family fixture: " + family);
    };
  }

  private static List<SourceEvidence> accountSources() {
    return List.of(
        source(SourceKind.TENANT, TENANT.toString(), "tenant"),
        source(SourceKind.ACCOUNT, ACTOR.toString(), "account"),
        source(SourceKind.MEMBERSHIP, ACTOR + "/" + TENANT, "membership"));
  }

  private static SourceEvidence source(SourceKind kind, String scope, String marker) {
    return new SourceEvidence(
        kind, scope, null, "1", null, null, marker.getBytes(StandardCharsets.UTF_8));
  }

  private static DraftCommitBinding selected() {
    return DraftCommitBinding.create(
        target(),
        id("33333333-3333-4333-8333-333333333333"),
        id("44444444-4444-4444-8444-444444444444"),
        "base-commit-0",
        List.of(
            new RevisionPayload(
                "0", id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"), Owner.WORLD_MANAGEMENT, "{}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.WORLD_MANAGEMENT, "REGION", "region-1", "AGGREGATE", "region-1", "0")));
  }

  private static TargetProof target() {
    return new TargetProof(TENANT, VERSION, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW");
  }

  private static WorldSelectedDraftPublicationFreezeEvidence freezeEvidence(
      DraftCommitBinding selected) {
    var selection =
        AuthoredDraftPublishSelectionBinding.capture(
            new PublishIntent(
                TENANT,
                VERSION,
                "publication-request",
                "9",
                "synthetic selected-owner intake fixture",
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
    var account =
        new AccountPublicationAuthorizationBinding(
            id("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            id("ffffffff-ffff-4fff-8fff-ffffffffffff"),
            new AccountPublicationAuthorizationBinding.PreallocationInput(ACTOR, selection),
            List.of(source(SourceKind.ACCOUNT, ACTOR.toString(), "account-publication")));
    var request =
        WorldSelectedDraftPublicationFreezeEvidence.Request.create(
            NAMESPACE,
            TENANT,
            VERSION,
            selection.intent().publishRequestId(),
            9,
            selection.digest().substring("sha256:".length()),
            account);
    var acknowledgement =
        new Acknowledgement(
            request,
            id("12121212-1212-4212-8212-121212121212"),
            9,
            id("13131313-1313-4313-8313-131313131313"),
            OwnerFreezePhase.FROZEN,
            selected.commitId().toString(),
            "a".repeat(64),
            4);
    return WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
        request, WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement));
  }

  private static PublicEvidence publicInventory(
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    var request = freezeEvidence.request();
    var acknowledgement = freezeEvidence.acknowledgement();
    var account = request.accountBinding();
    var selection = account.input().selection();
    var selected = selection.selectedCommit();
    var sourceModel =
        new SourceModel(
            WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SOURCE_MODEL,
            WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_GRAPH_SCHEMA_VERSION,
            "sha256:" + "4".repeat(64),
            "sha256:" + "5".repeat(64),
            List.of(
                new FamilyCount("REGION", 1),
                new FamilyCount("ZONE", 1),
                new FamilyCount("ROOM", 1),
                new FamilyCount("ROOM_EXIT", 1),
                new FamilyCount("GENERATION_RULE", 0),
                new FamilyCount("WORLD_ENTITY_SPAWN_BINDING", 0)),
            List.of(new RegionGeneratorInput(id("15151515-1515-4515-8515-151515151515"), "", "")),
            List.of("id", "name", "scopeType", "scopeId", "value"),
            List.of(
                "id",
                "shardId",
                "name",
                "weather",
                "generationSeed",
                "generatorType",
                "generatorParams",
                "spacingMultiplier"),
            List.of("id", "regionId", "name"),
            List.of(
                "id",
                "zoneId",
                "name",
                "description",
                "nameLocalizedVariantsJson",
                "descriptionLocalizedVariantsJson"),
            List.of("id", "fromRoomId", "toRoomId", "direction", "cost"),
            List.of(
                "id",
                "roomId",
                "entityTemplateType",
                "entityReference.kind",
                "entityReference.tenantId",
                "entityReference.versionId",
                "entityReference.templateId",
                "spawnCount",
                "respawnDelaySeconds"),
            List.of(),
            0,
            0,
            selected.affectedUnits(Owner.WORLD_MANAGEMENT).stream()
                .map(
                    unit ->
                        new AppliedEpoch(
                            unit.aggregateType(),
                            unit.aggregateId(),
                            unit.scopeType(),
                            unit.scopeId(),
                            unit.expectedEpoch(),
                            new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
                .toList(),
            emptyInboundClosure());
    var ownerScope =
        new PublicOwnerScope(
            request.targetNamespace(),
            TENANT,
            VERSION,
            id("14141414-1414-4414-8414-141414141414"),
            acknowledgement.intakeRequestId(),
            id("13131313-1313-4313-8313-131313131313"),
            "sha256:" + "1".repeat(64),
            id("17171717-1717-4717-8717-171717171717"),
            "sha256:" + "2".repeat(64),
            "sha256:" + "3".repeat(64));
    return new PublicEvidence(
        WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA,
        WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA_VERSION,
        "COMPLETE",
        ownerScope,
        new SelectedApplication(
            id("16161616-1616-4616-8616-161616161616"),
            selected.requestId(),
            selected.commitId().toString(),
            selected.digest(),
            "sha256:" + "6".repeat(64)),
        new PublicAccountOrder(
            account.operationId(),
            account.fenceId(),
            account.input().actorAccountId(),
            selection.intent().publishRequestId(),
            selection.digest(),
            selected.commitId().toString(),
            DraftAuthorizationFenceBinding.digest(account.canonicalBytes())),
        new Freeze(
            acknowledgement.publicationFence(),
            request.publicationRequestId(),
            request.requestDigest(),
            Long.toString(acknowledgement.versionStateEpoch()),
            PublicationDigestRequestBinding.full(
                    request.canonicalTenantId().toString(),
                    Long.toString(selection.target().gameDesignVersionRowId()),
                    request.publicationRequestId())
                .derivedWorkflowIdentity()),
        new Checkpoint(acknowledgement.appliedCommitId(), acknowledgement.contentDigest(), 4),
        sourceModel,
        List.of(
            new ArtifactDecision(
                "NAVMESH", "NOT_REQUIRED", "ROOM_LOGICAL_TOPOLOGY_HAS_NO_SPATIAL_NAVMESH_INPUT"),
            new ArtifactDecision(
                "PATH_GRAPH",
                "NOT_REQUIRED",
                "AUTHORED_ROOM_EXIT_EDGES_ARE_THE_SUPPORTED_TRAVERSAL_GRAPH")));
  }

  private static InboundSourceClosureDeclaration emptyInboundClosure() {
    return new InboundSourceClosureDeclaration(
        1,
        List.of(
            new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT", 0),
            new InboundSourceFamilyCount(
                "WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT", 0),
            new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION", 0),
            new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING", 0),
            new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK", 0),
            new InboundSourceFamilyCount(
                "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE", 0),
            new InboundSourceFamilyCount(
                "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING", 0)));
  }

  private static void frame(ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void frame(ByteArrayOutputStream out, byte[] value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static UUID id(String value) {
    return UUID.fromString(value);
  }

  record Fixture(
      SelectedOwnerIntakeAuthorizationBinding authorization,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence,
      SelectedOwnerWorldInventoryReadEvidence worldEvidence,
      SelectedOwnerEmptySourceInputs inputs) {}
}
