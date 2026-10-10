package net.firedevops.firemud.common.account.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.automation.AutomationAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.entity.EntityAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.AppliedEpoch;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.TemplateConfigOwnerSourceInventoryDeclaration;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceValues;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceValues.Config;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceValues.Entry;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceValues.OperationKind;
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
import org.junit.jupiter.api.Test;

class SelectedOwnerEmptySourceInputsTest {
  private static final UUID TENANT = id("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION = id("22222222-2222-4222-8222-222222222222");
  private static final UUID ACTOR = id("99999999-9999-4999-8999-999999999999");
  private static final String TEMPLATE_CONFIG =
      "{\"schemaVersion\":1,\"baseVersionId\":\""
          + VERSION
          + "\",\"world\":{\"regions\":[],\"rooms\":[]},"
          + "\"entity\":{\"items\":[],\"npcs\":[]},\"gameLogic\":{\"inputs\":[]},"
          + "\"automation\":{\"scripts\":[],\"scriptPatch\":{\"presence\":\"ABSENT\"}},"
          + "\"supportedSettings\":[]}";

  @Test
  void retainsTheExactEmptyInputsAndOriginalDeclarationForBothOwners() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      Fixture fixture = fixture(owner, Options.defaults());

      var result =
          new SelectedOwnerEmptySourceInputs(
              fixture.authorization(), fixture.worldInventoryReadEvidence());

      assertThat(result.authorizationBinding()).isSameAs(fixture.authorization());
      assertThat(result.worldInventoryReadEvidence())
          .isSameAs(fixture.worldInventoryReadEvidence());
      assertThat(result.sourceContent()).isSameAs(fixture.authorization().content());
      var actualDeclaration = result.ownerSourceInventoryDeclaration();
      var expectedDeclaration = fixture.expectedDeclaration();
      assertThat(actualDeclaration.object()).isEqualTo(expectedDeclaration.object());
      assertThat(actualDeclaration.owner()).isEqualTo(owner);
      assertThat(actualDeclaration.inventoryJson()).isEqualTo(expectedDeclaration.inventoryJson());
      assertThat(actualDeclaration.sourceBinding().canonicalBytes())
          .containsExactly(expectedDeclaration.sourceBinding().canonicalBytes());
      assertThat(actualDeclaration.sourceBinding().digest())
          .isEqualTo(expectedDeclaration.sourceBinding().digest());
      assertThat(actualDeclaration.sourceBinding().commitId())
          .isNotEqualTo(fixture.authorization().selected().commitId());
      assertThat(actualDeclaration.revisionOrder()).isEqualTo(expectedDeclaration.revisionOrder());
      assertThat(actualDeclaration.revisionId()).isEqualTo(expectedDeclaration.revisionId());
      assertThat(actualDeclaration.revisionOrder())
          .isEqualTo(owner == Owner.ENTITY_MANAGEMENT ? "0" : "1");
      assertThat(
              fixture
                  .worldInventoryReadEvidence()
                  .inventory()
                  .publicEvidence()
                  .sourceModel()
                  .familyCounts())
          .extracting(FamilyCount::family)
          .contains("ROOM", "ROOM_EXIT");
    }
  }

  @Test
  void rejectsChangedOriginalBindingContentNamespaceAndSelectedCommit() {
    Fixture original = fixture(Owner.ENTITY_MANAGEMENT, Options.defaults());

    Fixture changedBinding =
        fixture(
            Owner.ENTITY_MANAGEMENT,
            Options.defaults().withOperation(id("55555555-5555-4555-8555-555555555551")));
    assertRejected(original.authorization(), changedBinding.worldInventoryReadEvidence());

    Fixture changedContent =
        fixture(Owner.ENTITY_MANAGEMENT, Options.defaults().withSourceEpoch("2"));
    assertRejected(original.authorization(), changedContent.worldInventoryReadEvidence());

    Fixture changedReadNamespace =
        fixture(Owner.ENTITY_MANAGEMENT, Options.defaults().withNamespace("other"));
    assertRejected(original.authorization(), changedReadNamespace.worldInventoryReadEvidence());

    Fixture changedCommit =
        fixture(Owner.ENTITY_MANAGEMENT, Options.defaults().withSelectedVariant(1));
    assertRejected(original.authorization(), changedCommit.worldInventoryReadEvidence());
  }

  @Test
  void rejectsMissingAndOtherOwnerOnlyDeclarationsFromTemplateConfigSnapshot() {
    Fixture missing =
        fixture(Owner.ENTITY_MANAGEMENT, Options.defaults().withDeclarationOwners(List.of()));
    assertThat(missing.authorization().content().templateConfigSource().canonicalJson())
        .contains(TemplateConfigSourceSnapshot.SCHEMA);
    assertRejected(missing.authorization(), missing.worldInventoryReadEvidence());

    Fixture otherOwnerOnly =
        fixture(
            Owner.ENTITY_MANAGEMENT,
            Options.defaults().withDeclarationOwners(List.of(Owner.AUTOMATION_SCRIPTING)));
    assertThat(otherOwnerOnly.authorization().content().templateConfigSource().canonicalJson())
        .contains(TemplateConfigSourceSnapshot.SCHEMA_V2);
    assertRejected(otherOwnerOnly.authorization(), otherOwnerOnly.worldInventoryReadEvidence());
  }

  @Test
  void rejectsAllOwnerSpecificTemplateReferencesInEveryEntry() {
    for (String collection : List.of("items", "npcs")) {
      String key = "items".equals(collection) ? "items" : "npcs";
      Config config =
          new Config(
              TEMPLATE_CONFIG.replace(
                  "\"" + key + "\":[]",
                  "\""
                      + key
                      + "\":[{\"entityTemplateId\":\""
                      + "33333333-3333-4333-8333-333333333333"
                      + "\"}]"));
      Fixture fixture =
          fixture(Owner.ENTITY_MANAGEMENT, Options.defaults().withEntries(List.of(config)));
      assertRejected(fixture.authorization(), fixture.worldInventoryReadEvidence());
    }

    Config scripts =
        new Config(
            TEMPLATE_CONFIG.replace(
                "\"scripts\":[]",
                "\"scripts\":[{\"scriptId\":\"authored-script\",\"eventBindings\":[]}]"));
    Fixture scriptReference =
        fixture(Owner.AUTOMATION_SCRIPTING, Options.defaults().withEntries(List.of(scripts)));
    assertRejected(scriptReference.authorization(), scriptReference.worldInventoryReadEvidence());

    Config patch =
        new Config(
            TEMPLATE_CONFIG.replace(
                "\"presence\":\"ABSENT\"",
                "\"presence\":\"PRESENT\",\"scriptPatchVersion\":\"patch-1\""));
    Fixture patchReference =
        fixture(Owner.AUTOMATION_SCRIPTING, Options.defaults().withEntries(List.of(patch)));
    assertRejected(patchReference.authorization(), patchReference.worldInventoryReadEvidence());
  }

  @Test
  void rejectsLegacyOrMissingWorldClosureAndEntitySpawnBindings() {
    Fixture legacyWorld =
        fixture(
            Owner.ENTITY_MANAGEMENT, Options.defaults().withWorldProfile(WorldProfile.LEGACY_V1));
    assertRejected(legacyWorld.authorization(), legacyWorld.worldInventoryReadEvidence());

    assertThatThrownBy(
            () ->
                fixture(
                    Owner.ENTITY_MANAGEMENT,
                    Options.defaults().withWorldProfile(WorldProfile.MISSING_CLOSURE_V2)))
        .isInstanceOf(IllegalArgumentException.class);

    Fixture entitySpawn = fixture(Owner.ENTITY_MANAGEMENT, Options.defaults().withSpawnBindings(1));
    assertRejected(entitySpawn.authorization(), entitySpawn.worldInventoryReadEvidence());
  }

  private static void assertRejected(
      SelectedOwnerIntakeAuthorizationBinding authorization,
      SelectedOwnerWorldInventoryReadEvidence worldEvidence) {
    assertThatThrownBy(() -> new SelectedOwnerEmptySourceInputs(authorization, worldEvidence))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Fixture fixture(Owner owner, Options options) {
    DraftCommitBinding selected = selected(options.selectedVariant());
    SelectedOwnerIntakeSourceReadScope scope =
        new SelectedOwnerIntakeSourceReadScope(
            owner,
            options.namespace(),
            options.operation(),
            id("66666666-6666-4666-8666-666666666666"),
            id("77777777-7777-4777-8777-777777777777"),
            ACTOR,
            selected);
    var templateSnapshot = templateSnapshot(selected, options);
    var sourceContent = sourceContent(scope, templateSnapshot);
    var authorization =
        new SelectedOwnerIntakeAuthorizationBinding(sourceContent, accountSources());
    var freezeEvidence = freezeEvidence(options.namespace(), selected, options.worldProfile());
    var worldRequest =
        SelectedOwnerWorldInventoryReadEvidence.create(
            options.namespace(), authorization, freezeEvidence);
    var inventory =
        WorldSelectedPublicationArtifactInventoryEvidence.fromPublicEvidence(
            freezeEvidence,
            publicInventory(freezeEvidence, options.worldProfile(), options.spawnBindings()));
    return new Fixture(
        authorization,
        new SelectedOwnerWorldInventoryReadEvidence(worldRequest, inventory),
        templateSnapshot.ownerSourceInventoryDeclarations().stream()
            .filter(declaration -> declaration.owner() == owner)
            .findFirst()
            .orElse(null));
  }

  private static TemplateConfigSourceSnapshot templateSnapshot(
      DraftCommitBinding selected, Options options) {
    var declarationPayloads = new ArrayList<String>();
    for (Owner owner : options.declarationOwners().stream().sorted().toList()) {
      if (owner == Owner.AUTOMATION_SCRIPTING) {
        var inventory = AutomationAuthoredSourceInventoryDeclaration.parse(automationInventory());
        declarationPayloads.add(TemplateConfigSourceValues.ownerInventoryPayload(owner, inventory));
      } else if (owner == Owner.ENTITY_MANAGEMENT) {
        var inventory = EntityAuthoredSourceInventoryDeclaration.parse(entityInventory());
        declarationPayloads.add(TemplateConfigSourceValues.ownerInventoryPayload(owner, inventory));
      }
    }
    var payloads = new ArrayList<>(declarationPayloads);
    for (int index = 0; index < options.entries().size(); index++) {
      payloads.add(
          TemplateConfigSourceValues.createPayload("entry-" + index, options.entries().get(index)));
    }
    if (payloads.isEmpty()) payloads.add("{\"revisionKind\":\"COMMAND_DEFINITION\"}");

    DraftCommitBinding authored = authoredBinding(payloads);
    var mutations = TemplateConfigSourceValues.mutations(authored);
    var declarations =
        mutations.stream()
            .filter(
                mutation -> mutation.operation() == OperationKind.DECLARE_OWNER_SOURCE_INVENTORY)
            .map(TemplateConfigSourceValues.Mutation::ownerInventoryDeclaration)
            .sorted(java.util.Comparator.comparing(declaration -> declaration.owner().name()))
            .toList();
    var entries = new ArrayList<Entry>();
    for (int index = 0; index < options.entries().size(); index++) {
      var revision = authored.revisions().get(declarationPayloads.size() + index);
      entries.add(
          new Entry(
              Integer.toString(index + 1),
              options.entries().get(index),
              authored,
              revision.revisionOrder(),
              revision.revisionId(),
              "entry-" + index));
    }
    return new TemplateConfigSourceSnapshot(
        selected,
        options.sourceEpoch(),
        declarations.isEmpty() ? null : authored.commitId(),
        id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        entries,
        declarations);
  }

  private static DraftCommitBinding authoredBinding(List<String> payloads) {
    List<RevisionPayload> revisions = new ArrayList<>();
    for (int index = 0; index < payloads.size(); index++) {
      revisions.add(
          new RevisionPayload(
              Integer.toString(index),
              UUID.randomUUID(),
              Owner.GAME_DESIGN_CONTROL_PLANE,
              payloads.get(index)));
    }
    return DraftCommitBinding.create(
        target(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "base-commit-0",
        revisions,
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
      SelectedOwnerIntakeSourceReadScope scope, TemplateConfigSourceSnapshot templateSnapshot) {
    var out = new java.io.ByteArrayOutputStream();
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
    if ("GAMEPLAY_RULE".equals(family)) {
      return new GameplayRuleSelectedSource(
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
                      "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                      "manifestJson",
                      GameplayRuleManifest.explicitEmpty().canonicalJson(),
                      "entries",
                      List.of())))
          .canonicalBytes();
    }
    return GameplayRuleManifest.canonical(
            Map.of(
                "schema",
                "synthetic-test-only/v1",
                "bindingJson",
                selected.canonicalJson(),
                "bindingDigest",
                selected.digest(),
                "family",
                family))
        .getBytes(StandardCharsets.UTF_8);
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

  private static DraftCommitBinding selected(int variant) {
    return DraftCommitBinding.create(
        target(),
        variant == 0
            ? id("33333333-3333-4333-8333-333333333333")
            : id("35353535-3535-4535-8535-353535353535"),
        variant == 0
            ? id("44444444-4444-4444-8444-444444444444")
            : id("45454545-4545-4545-8454-454545454545"),
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
      String namespace, DraftCommitBinding selected, WorldProfile profile) {
    var selection =
        AuthoredDraftPublishSelectionBinding.capture(
            new PublishIntent(
                TENANT,
                VERSION,
                "publication-request",
                "9",
                "selected-owner intake fixture",
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
    var freezeRequest =
        WorldSelectedDraftPublicationFreezeEvidence.Request.create(
            namespace,
            TENANT,
            VERSION,
            selection.intent().publishRequestId(),
            9,
            selection.digest().substring("sha256:".length()),
            account);
    var acknowledgement =
        new Acknowledgement(
            freezeRequest,
            id("12121212-1212-4212-8212-121212121212"),
            9,
            id("13131313-1313-4313-8313-131313131313"),
            OwnerFreezePhase.FROZEN,
            selected.commitId().toString(),
            "a".repeat(64),
            profile == WorldProfile.LEGACY_V1 ? 3 : 4);
    return WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
        freezeRequest, WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement));
  }

  private static PublicEvidence publicInventory(
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence,
      WorldProfile profile,
      int spawnBindings) {
    var request = freezeEvidence.request();
    var acknowledgement = freezeEvidence.acknowledgement();
    var account = request.accountBinding();
    var selection = account.input().selection();
    var selected = selection.selectedCommit();
    boolean closureProfile = profile != WorldProfile.LEGACY_V1;
    InboundSourceClosureDeclaration closure =
        profile == WorldProfile.INBOUND_CLOSURE_V2 ? emptyInboundClosure() : null;
    var spawnInputs =
        spawnBindings == 0
            ? List.<WorldSelectedPublicationArtifactInventoryEvidence.SpawnBindingInput>of()
            : List.of(
                new WorldSelectedPublicationArtifactInventoryEvidence.SpawnBindingInput(
                    id("18181818-1818-4818-8818-181818181818"),
                    id("19191919-1919-4919-8919-191919191919"),
                    "ITEM",
                    "ENTITY_TEMPLATE_REFERENCE_TYPE_CANONICAL_UUID",
                    TENANT,
                    VERSION,
                    id("20202020-2020-4020-8020-202020202020"),
                    1,
                    0));
    var sourceModel =
        new SourceModel(
            closureProfile
                ? WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SOURCE_MODEL
                : WorldSelectedPublicationArtifactInventoryEvidence.SOURCE_MODEL,
            closureProfile
                ? WorldSelectedPublicationArtifactInventoryEvidence
                    .INBOUND_CLOSURE_GRAPH_SCHEMA_VERSION
                : 2,
            "sha256:" + "4".repeat(64),
            "sha256:" + "5".repeat(64),
            List.of(
                new FamilyCount("REGION", 1),
                new FamilyCount("ZONE", 1),
                new FamilyCount("ROOM", 1),
                new FamilyCount("ROOM_EXIT", 1),
                new FamilyCount("GENERATION_RULE", 0),
                new FamilyCount("WORLD_ENTITY_SPAWN_BINDING", spawnBindings)),
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
            spawnInputs,
            0,
            spawnBindings,
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
            closure);
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
        closureProfile
            ? WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA
            : WorldSelectedPublicationArtifactInventoryEvidence.SCHEMA,
        closureProfile
            ? WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA_VERSION
            : WorldSelectedPublicationArtifactInventoryEvidence.SCHEMA_VERSION,
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
        new Checkpoint(
            acknowledgement.appliedCommitId(),
            acknowledgement.contentDigest(),
            closureProfile ? 4 : 3),
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

  private static void frame(java.io.ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void frame(java.io.ByteArrayOutputStream out, byte[] value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static String automationInventory() {
    return "{\"schema\":\"automation-authored-source-inventory/v1\",\"families\":{"
        + "\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[],\"SCRIPT_PATCH_SOURCES\":[]}}";
  }

  private static String entityInventory() {
    return "{\"schema\":\"entity-authored-source-inventory/v1\","
        + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
        + "\"ACTOR_BODY_LAYOUT_ASSIGNMENTS\":[],\"ARCHETYPE_ASSIGNMENTS\":[],"
        + "\"ARCHETYPE_CONSTRAINTS\":[],\"ARCHETYPE_ROOTS\":[],"
        + "\"BALANCE_CURVE_ATTACHMENTS\":[],\"BALANCE_CURVE_ROOTS\":[],"
        + "\"BODY_LAYOUT_MEMBERSHIPS\":[],\"BODY_LAYOUT_ROOTS\":[],"
        + "\"CRAFTING_INGREDIENT_BINDINGS\":[],\"CRAFTING_RECIPE_RESULT_BINDINGS\":[],"
        + "\"CRAFTING_RECIPE_ROOTS\":[],\"EQUIPMENT_ATTACHMENT_RULES\":[],"
        + "\"EQUIPMENT_CAPABILITIES\":[],\"EQUIPMENT_COMPATIBILITY_RULES\":[],"
        + "\"EQUIPMENT_OCCUPANCY_RULES\":[],\"EQUIPMENT_SLOT_GROUPS\":[],"
        + "\"EQUIPMENT_SLOT_ROOTS\":[],\"INBOUND_LOOT_BINDINGS\":[],"
        + "\"ITEM_TEMPLATE_ROOTS\":[],\"LOOT_ITEM_MAPPINGS\":[],"
        + "\"LOOT_TABLE_ROOTS\":[],\"NPC_TEMPLATE_ROOTS\":[],"
        + "\"OTHER_ACTOR_TEMPLATE_ROOTS\":[]}}";
  }

  private static UUID id(String value) {
    return UUID.fromString(value);
  }

  private enum WorldProfile {
    INBOUND_CLOSURE_V2,
    LEGACY_V1,
    MISSING_CLOSURE_V2
  }

  private record Options(
      String namespace,
      int selectedVariant,
      UUID operation,
      String sourceEpoch,
      List<Owner> declarationOwners,
      List<Config> entries,
      WorldProfile worldProfile,
      int spawnBindings) {
    private static Options defaults() {
      return new Options(
          "test",
          0,
          id("55555555-5555-4555-8555-555555555555"),
          "1",
          List.of(Owner.AUTOMATION_SCRIPTING, Owner.ENTITY_MANAGEMENT),
          List.of(),
          WorldProfile.INBOUND_CLOSURE_V2,
          0);
    }

    private Options withNamespace(String value) {
      return new Options(
          value,
          selectedVariant,
          operation,
          sourceEpoch,
          declarationOwners,
          entries,
          worldProfile,
          spawnBindings);
    }

    private Options withSelectedVariant(int value) {
      return new Options(
          namespace,
          value,
          operation,
          sourceEpoch,
          declarationOwners,
          entries,
          worldProfile,
          spawnBindings);
    }

    private Options withOperation(UUID value) {
      return new Options(
          namespace,
          selectedVariant,
          value,
          sourceEpoch,
          declarationOwners,
          entries,
          worldProfile,
          spawnBindings);
    }

    private Options withSourceEpoch(String value) {
      return new Options(
          namespace,
          selectedVariant,
          operation,
          value,
          declarationOwners,
          entries,
          worldProfile,
          spawnBindings);
    }

    private Options withDeclarationOwners(List<Owner> values) {
      return new Options(
          namespace,
          selectedVariant,
          operation,
          sourceEpoch,
          values,
          entries,
          worldProfile,
          spawnBindings);
    }

    private Options withEntries(List<Config> values) {
      return new Options(
          namespace,
          selectedVariant,
          operation,
          sourceEpoch,
          declarationOwners,
          values,
          worldProfile,
          spawnBindings);
    }

    private Options withWorldProfile(WorldProfile value) {
      return new Options(
          namespace,
          selectedVariant,
          operation,
          sourceEpoch,
          declarationOwners,
          entries,
          value,
          spawnBindings);
    }

    private Options withSpawnBindings(int value) {
      return new Options(
          namespace,
          selectedVariant,
          operation,
          sourceEpoch,
          declarationOwners,
          entries,
          worldProfile,
          value);
    }
  }

  private record Fixture(
      SelectedOwnerIntakeAuthorizationBinding authorization,
      SelectedOwnerWorldInventoryReadEvidence worldInventoryReadEvidence,
      TemplateConfigOwnerSourceInventoryDeclaration expectedDeclaration) {}
}
