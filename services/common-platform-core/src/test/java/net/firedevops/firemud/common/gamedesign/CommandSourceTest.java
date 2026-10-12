package net.firedevops.firemud.common.gamedesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.junit.jupiter.api.Test;

class CommandSourceTest {
  private static final TargetProof TARGET =
      new TargetProof(id("tenant"), id("version"), 1L, "private", 2L, "private", "NEW_GAME_ROW");

  @Test
  void acceptsTheIntegerSchemaVersionOne() {
    String definition = commandDefinition("1");

    var mutations = CommandSource.mutations(binding(definition));

    assertThat(mutations).hasSize(1);
    assertThat(mutations.getFirst().operation()).isEqualTo(CommandSource.OperationKind.UPSERT);
    assertThat(mutations.getFirst().definitionJson()).isEqualTo(definition);
  }

  @Test
  void rejectsTextAndFloatingPointDefinitionSchemaVersions() {
    for (String version : List.of("\"1\"", "1.0", "1e0")) {
      assertThatThrownBy(() -> CommandSource.mutations(binding(commandDefinition(version))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("command definition schema");
    }
  }

  private static DraftCommitBinding binding(String definitionJson) {
    String payload =
        CommandSource.canonical(
            Map.of(
                "schemaVersion",
                1,
                "revisionKind",
                CommandSource.REVISION_KIND,
                "operation",
                "UPSERT",
                "definitionJson",
                definitionJson));
    return DraftCommitBinding.create(
        TARGET,
        id("request"),
        id("commit"),
        "base-0",
        List.of(new RevisionPayload("0", id("revision"), Owner.GAME_DESIGN_CONTROL_PLANE, payload)),
        List.of(
            new AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                CommandSource.SCOPE,
                TARGET.canonicalVersionId().toString(),
                CommandSource.SCOPE,
                CommandSource.SCOPE_ID,
                "0")));
  }

  private static String commandDefinition(String schemaVersion) {
    return "{\"schemaVersion\":"
        + schemaVersion
        + ",\"commandId\":\"LOOK\",\"semanticOwner\":\"GAME_SESSION\","
        + "\"executionDiscipline\":\"DURABLE_GAMEPLAY\",\"stageRequirement\":\"GAMEPLAY\","
        + "\"promptPolicy\":\"WHEN_GAMEPLAY\",\"actionCategory\":\"GAMEPLAY\","
        + "\"historyRecordable\":true,\"aliases\":[],\"actionTags\":[\"WORLD_BROWSE\"],"
        + "\"effects\":[]}";
  }

  private static UUID id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
