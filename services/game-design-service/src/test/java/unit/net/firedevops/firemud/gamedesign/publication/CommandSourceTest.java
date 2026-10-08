package unit.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.publication.CommandApplication;
import net.firedevops.firemud.gamedesign.publication.CommandSnapshot;
import net.firedevops.firemud.gamedesign.publication.CommandSource;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.publication.RealmPolicySource;
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
            CommandSource.upsertPayload(definition)
                .replace("\"operation\":\"UPSERT\"", "\"operation\":\"UPSERT\",\"extra\":true"),
            "{\"schemaVersion\":1,\"revisionKind\":\"UNSUPPORTED\",\"operation\":\"DELETE\",\"commandId\":\"look\"}",
            CommandSource.upsertPayload(definition)
                .replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
    for (String payload : malformed) {
      var bad =
          binding(
              commitId, List.of(revision("0", revisionId, payload)), List.of(commandScope("0")));
      assertThatThrownBy(() -> CommandSource.mutations(bad))
          .isInstanceOf(IllegalArgumentException.class);
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
                exactDefinition),
            new CommandSource.Mutation(
                "2",
                UUID.randomUUID(),
                commit,
                CommandSource.OperationKind.DELETE,
                "absent",
                null));

    var result = CommandSource.replay(inherited, operations);
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().commandId()).isEqualTo("LOOK");
    assertThat(result.getFirst().definitionJson()).isEqualTo(exactDefinition);
    assertThat(result.getFirst().sourceCommitId()).isEqualTo(commit);
    assertThat(result.getFirst().sourceRevisionId()).isEqualTo(upsertRevision);
    assertThat(CommandSource.replay(inherited, List.of())).containsExactlyElementsOf(inherited);
    assertThat(CommandSource.replay(result, List.of(operations.get(2))))
        .containsExactlyElementsOf(result);
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
            List.of(commandRevision, policyRevision),
            List.of(commandScope("0"), policyScope));

    var mutations = CommandSource.mutations(binding);
    assertThat(mutations).hasSize(1);
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

  private static DraftCommitBinding binding(
      UUID commitId,
      List<DraftCommitBinding.RevisionPayload> revisions,
      List<DraftCommitBinding.AffectedUnit> units) {
    return DraftCommitBinding.create(
        TARGET, UUID.randomUUID(), commitId, "ISOLATED-exact-base", revisions, units);
  }
}
