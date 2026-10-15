package unit.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.publication.CommandApplication;
import net.firedevops.firemud.gamedesign.publication.CommandSnapshot;
import net.firedevops.firemud.gamedesign.publication.CommandSource;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySource;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSource;
import org.junit.jupiter.api.Test;

class CommandSourceTest {
  private static final TargetProof TARGET =
      new TargetProof(
          UUID.randomUUID(), UUID.randomUUID(), 7L, "owner", 3L, "owner", "NEW_GAME_ROW");

  @Test
  void parsesOnlyClosedDigestBoundOperationsAndRetainsExactPayloadAndProvenance() {
    String definition =
        command("look", "l").replace("{\"schemaVersion\":1", "{ \"schemaVersion\":1");
    UUID commitId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    var binding =
        binding(
            commitId,
            List.of(revision("0", revisionId, CommandSource.upsertPayload(definition))),
            List.of(commandScope("0")));

    var mutation = CommandSource.mutations(binding).getFirst();
    assertThat(mutation.commandId()).isEqualTo("look");
    assertThat(mutation.definitionJson()).isEqualTo(definition);
    assertThat(mutation.commitId()).isEqualTo(commitId);
    assertThat(mutation.revisionId()).isEqualTo(revisionId);
    assertThat(mutation.revisionOrder()).isEqualTo("0");

    var malformed =
        List.of(
            Map.entry(
                CommandSource.upsertPayload(definition)
                    .replace("\"operation\":\"UPSERT\"", "\"operation\":\"UPSERT\",\"extra\":true"),
                "Closed command source operation fields are required"),
            Map.entry(
                "{\"schemaVersion\":1,\"revisionKind\":\"UNSUPPORTED\",\"operation\":\"DELETE\",\"commandId\":\"look\"}",
                "Unsupported Game Design command source revision kind"),
            Map.entry(
                CommandSource.upsertPayload(definition)
                    .replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                "Unsupported command source operation schema"));
    for (var malformedCase : malformed) {
      var bad =
          binding(
              commitId,
              List.of(revision("0", revisionId, malformedCase.getKey())),
              List.of(commandScope("0")));
      assertThatThrownBy(() -> CommandSource.mutations(bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(malformedCase.getValue());
    }
  }

  @Test
  void replaysStableKeyUpsertAndExplicitDeleteInRevisionOrderWithoutLosingAuthorship() {
    var inherited = List.of(definition("look", "l", UUID.randomUUID(), UUID.randomUUID(), "0"));
    UUID commit = UUID.randomUUID();
    UUID upsertRevision = UUID.randomUUID();
    String exactDefinition = command("LOOK", "see");
    var operations =
        List.of(
            new CommandSource.Mutation(
                "0", UUID.randomUUID(), commit, CommandSource.OperationKind.DELETE, "look", null),
            new CommandSource.Mutation(
                "1",
                upsertRevision,
                commit,
                CommandSource.OperationKind.UPSERT,
                "LOOK",
                exactDefinition));

    var result = CommandSource.replay(inherited, operations);
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().commandId()).isEqualTo("LOOK");
    assertThat(result.getFirst().definitionJson()).isEqualTo(exactDefinition);
    assertThat(result.getFirst().sourceCommitId()).isEqualTo(commit);
    assertThat(result.getFirst().sourceRevisionId()).isEqualTo(upsertRevision);
    assertThat(CommandSource.replay(inherited, List.of())).containsExactlyElementsOf(inherited);
    var delete =
        new CommandSource.Mutation(
            "2", UUID.randomUUID(), commit, CommandSource.OperationKind.DELETE, "LOOK", null);
    assertThat(CommandSource.replay(result, List.of(delete))).isEmpty();
    var absentDelete =
        new CommandSource.Mutation(
            "3", UUID.randomUUID(), commit, CommandSource.OperationKind.DELETE, "absent", null);
    assertThatThrownBy(() -> CommandSource.replay(inherited, List.of(absentDelete)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot delete an absent command definition key");
    assertThatThrownBy(() -> CommandSource.replay(List.of(), List.of(absentDelete)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot delete an absent command definition key");
    var secondDelete =
        new CommandSource.Mutation(
            "1", UUID.randomUUID(), commit, CommandSource.OperationKind.DELETE, "look", null);
    assertThatThrownBy(
            () -> CommandSource.replay(inherited, List.of(operations.getFirst(), secondDelete)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot delete an absent command definition key");
    assertThatThrownBy(
            () -> CommandSource.replay(inherited, List.of(operations.get(1), operations.get(0))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mixedControlPlaneCommitReturnsCommandEvidenceWithoutTakingOwnerOutcomeOwnership() {
    UUID commitId = UUID.randomUUID();
    var commandRevision =
        revision("0", UUID.randomUUID(), CommandSource.upsertPayload(command("look", "l")));
    var policyRevision =
        revision(
            "1",
            UUID.randomUUID(),
            "{\"revisionKind\":\"REALM_ENTRY_POLICY\",\"logicalRevisionId\":\"main\",\"policy\":{}}");
    var assetRevision =
        revision(
            "2",
            UUID.randomUUID(),
            AssetSource.upsertPayload("41", "map", AssetSource.Requiredness.REQUIRED));
    var policyScope =
        new DraftCommitBinding.AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            RealmPolicySource.SCOPE,
            TARGET.canonicalVersionId().toString(),
            RealmPolicySource.SCOPE,
            "effective",
            "0");
    var binding =
        binding(
            commitId,
            List.of(commandRevision, policyRevision, assetRevision),
            List.of(commandScope("0"), policyScope, assetScope("0")));

    var mutations = CommandSource.mutations(binding);
    assertThat(mutations).hasSize(1);
    assertThat(AssetSource.mutations(binding)).hasSize(1);
    assertThat(CommandSource.hasRealmPolicyRevision(binding)).isTrue();
    var snapshot =
        new CommandSnapshot(
            binding,
            "1",
            null,
            "sha256:" + "b".repeat(64),
            CommandSource.replay(List.of(), mutations));
    var evidence = new CommandApplication(binding, null, "0", mutations, snapshot);
    assertThat(evidence.commandAppliedEpoch())
        .isEqualTo(
            new DraftCommitCoordinatorRepository.AppliedEpoch(
                CommandSource.SCOPE,
                TARGET.canonicalVersionId().toString(),
                CommandSource.SCOPE,
                CommandSource.SCOPE_ID,
                "0",
                "1"));
    byte[] applicationBytes = evidence.canonicalBytes();
    byte[] schemaBytes = CommandSource.APPLICATION_SCHEMA.getBytes(StandardCharsets.UTF_8);
    assertThat(applicationBytes).isNotEmpty();
    assertThat(ByteBuffer.wrap(applicationBytes).getInt()).isEqualTo(schemaBytes.length);
    assertThat(ByteBuffer.wrap(applicationBytes).getInt(4 + schemaBytes.length)).isZero();
  }

  @Test
  void unknownOwnerRevisionKindRemainsClosedForCommandClassification() {
    var unknownRevision = revision("0", UUID.randomUUID(), "{\"revisionKind\":\"UNSUPPORTED\"}");
    var binding = binding(UUID.randomUUID(), List.of(unknownRevision), List.of(commandScope("0")));

    assertThatThrownBy(() -> CommandSource.mutations(binding))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported Game Design command source revision kind");
    assertThatThrownBy(() -> CommandSource.hasRealmPolicyRevision(binding))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported Game Design command source revision kind");
  }

  @Test
  void rejectsAmbiguousAliasesAndKeepsTheEntireEffectiveSet() {
    var first = definition("look", "inspect", UUID.randomUUID(), UUID.randomUUID(), "0");
    var collision = definition("inspect", "peek", UUID.randomUUID(), UUID.randomUUID(), "1");
    assertThatThrownBy(() -> CommandSource.replay(List.of(first, collision), List.of()))
        .isInstanceOf(IllegalArgumentException.class);

    List<CommandSource.Definition> inherited = new ArrayList<>();
    for (int index = 0; index < 140; index++) {
      inherited.add(
          definition(
              "command-" + index, "alias-" + index, UUID.randomUUID(), UUID.randomUUID(), "0"));
    }
    assertThat(CommandSource.replay(inherited, List.of())).hasSize(140);
  }

  @Test
  void snapshotRoundTripAndCaptureBindTheActualSelectedCommit() throws Exception {
    GameDesignPublicationOperation operation = IsolatedPublicationOperationFixtures.fresh(TARGET);
    var selected = operation.account().input().selection().selectedCommit();
    var snapshot = new CommandSnapshot(selected, "0", null, "sha256:" + "a".repeat(64), List.of());
    String canonicalJson = snapshot.canonicalJson();
    assertThat(CommandSnapshot.fromStored(canonicalJson)).isEqualTo(snapshot);
    assertThat(canonicalJson).contains("\"inheritedCommitId\":\"\"");
    List<String> invalidInheritedCommitIds =
        List.of(
            canonicalJson.replace(",\"inheritedCommitId\":\"\"", ""),
            canonicalJson.replace("\"inheritedCommitId\":\"\"", "\"inheritedCommitId\":null"),
            canonicalJson.replace("\"inheritedCommitId\":\"\"", "\"inheritedCommitId\":7"),
            canonicalJson.replace(
                "\"inheritedCommitId\":\"\"", "\"inheritedCommitId\":\"not-a-uuid\""));
    for (String invalidJson : invalidInheritedCommitIds) {
      assertThatThrownBy(() -> CommandSnapshot.fromStored(invalidJson))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var capture = new CommandSnapshot.Capture(operation, snapshot);
    assertThat(new String(capture.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8))
        .contains("game-design-command-source-capture/v1");

    var wrongCommit =
        DraftCommitBinding.create(
            selected.target(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            selected.baseCommitId(),
            selected.revisions(),
            selected.affectedUnits());
    assertThatThrownBy(
            () ->
                new CommandSnapshot.Capture(
                    operation,
                    new CommandSnapshot(
                        wrongCommit, "0", null, "sha256:" + "a".repeat(64), List.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void freshGenesisReceiptRequiresExactVersionInsertTransactionIdentity() {
    var receipt = new CommandSource.NewDraftGenesisReceipt(TARGET, UUID.randomUUID(), "1234567");
    assertThat(receipt.digest()).startsWith("sha256:");
    assertThat(new String(receipt.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8))
        .contains("1234567");
    assertThatThrownBy(
            () -> new CommandSource.NewDraftGenesisReceipt(TARGET, UUID.randomUUID(), "0"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void gameplayRulePayloadRemainsASeparateTypedSibling() {
    var payload =
        net.firedevops.firemud.common.gamedesign.GameplayRuleSource.deletePayload(
            net.firedevops.firemud.common.gamelogic.GameplayRuleManifest.Family.STATS, "strength");
    var input =
        binding(
            UUID.randomUUID(),
            List.of(revision("0", UUID.randomUUID(), payload)),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    TARGET.canonicalVersionId().toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    assertThat(CommandSource.mutations(input)).isEmpty();
    assertThat(CommandSource.hasRealmPolicyRevision(input)).isFalse();
    assertThat(net.firedevops.firemud.common.gamedesign.GameplayRuleSource.mutations(input))
        .hasSize(1);
    assertThat(RealmPolicySource.isPolicyRevision(input.revisions().getFirst())).isFalse();
  }

  @Test
  void templateConfigPayloadRemainsASeparateTypedSibling() {
    var input =
        binding(
            UUID.randomUUID(),
            List.of(
                revision(
                    "0",
                    UUID.randomUUID(),
                    "{\"schemaVersion\":1,\"revisionKind\":\"TEMPLATE_CONFIG\","
                        + "\"operation\":\"DELETE\",\"templateId\":\"7\"}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    TemplateConfigSource.SCOPE,
                    TARGET.canonicalVersionId().toString(),
                    TemplateConfigSource.SCOPE,
                    TemplateConfigSource.SCOPE_ID,
                    "0")));

    assertThat(CommandSource.mutations(input)).isEmpty();
    assertThat(CommandSource.hasRealmPolicyRevision(input)).isFalse();
  }

  private static CommandSource.Definition definition(
      String commandId, String alias, UUID commitId, UUID revisionId, String revisionOrder) {
    return new CommandSource.Definition(
        commandId, command(commandId, alias), commitId, revisionId, revisionOrder);
  }

  private static String command(String id, String alias) {
    return "{\"schemaVersion\":1,\"commandId\":\""
        + id
        + "\",\"semanticOwner\":\"WORLD\","
        + "\"executionDiscipline\":\"DURABLE_GAMEPLAY\",\"stageRequirement\":\"GAMEPLAY\","
        + "\"promptPolicy\":\"NEVER\",\"actionCategory\":\"GAMEPLAY\",\"historyRecordable\":true,"
        + "\"aliases\":[\""
        + alias
        + "\"],\"actionTags\":[\"WORLD_BROWSE\"],\"effects\":[],"
        + "\"legacyExtension\":{\"kept\":true}}";
  }

  private static DraftCommitBinding.RevisionPayload revision(
      String order, UUID id, String payload) {
    return new DraftCommitBinding.RevisionPayload(
        order, id, Owner.GAME_DESIGN_CONTROL_PLANE, payload);
  }

  private static DraftCommitBinding.AffectedUnit commandScope(String epoch) {
    return new DraftCommitBinding.AffectedUnit(
        Owner.GAME_DESIGN_CONTROL_PLANE,
        CommandSource.SCOPE,
        TARGET.canonicalVersionId().toString(),
        CommandSource.SCOPE,
        CommandSource.SCOPE_ID,
        epoch);
  }

  private static DraftCommitBinding.AffectedUnit assetScope(String epoch) {
    return new DraftCommitBinding.AffectedUnit(
        Owner.GAME_DESIGN_CONTROL_PLANE,
        AssetSource.SCOPE,
        TARGET.canonicalVersionId().toString(),
        AssetSource.SCOPE,
        AssetSource.SCOPE_ID,
        epoch);
  }

  private static DraftCommitBinding binding(
      UUID commitId,
      List<DraftCommitBinding.RevisionPayload> revisions,
      List<DraftCommitBinding.AffectedUnit> units) {
    return DraftCommitBinding.create(
        TARGET, UUID.randomUUID(), commitId, "ISOLATED-exact-base", revisions, units);
  }
}
