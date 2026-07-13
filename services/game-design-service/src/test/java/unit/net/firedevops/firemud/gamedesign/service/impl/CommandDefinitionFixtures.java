package net.firedevops.firemud.gamedesign.service.impl;

import java.util.Arrays;
import java.util.stream.Collectors;

final class CommandDefinitionFixtures {
  private CommandDefinitionFixtures() {}

  static String validCommandDefinition() {
    return commandDefinition("block", "block", "guard");
  }

  static String commandDefinition(String commandId, String... aliases) {
    String aliasesJson =
        Arrays.stream(aliases).map(alias -> "\"" + alias + "\"").collect(Collectors.joining(","));
    return ("{\"schemaVersion\":1,\"commandId\":\"%s\",\"semanticOwner\":\"GAME_LOGIC\","
            + "\"executionDiscipline\":\"DURABLE_GAMEPLAY\",\"stageRequirement\":\"GAMEPLAY\","
            + "\"promptPolicy\":\"WHEN_GAMEPLAY\",\"actionCategory\":\"GAMEPLAY\",\"historyRecordable\":true,\"aliases\":[%s],"
            + "\"actionTags\":[\"COMBAT\"],\"effects\":[{\"effectKind\":\"APPLY_ACTION_STATE\","
            + "\"schemaVersion\":1,\"targeting\":\"SELF\",\"replayPolicy\":\"EFFECT_IDEMPOTENT\","
            + "\"payload\":{\"conditionKey\":\"blocking\",\"durationSeconds\":5,\"effectPayload\":{\"modifiers\":[{"
            + "\"operation\":\"ADD\",\"target_key\":\"block_mitigation\",\"value\":1,\"scope_kind\":\"ACTION_FAMILY\","
            + "\"scope_key\":\"defense\"}]}}}]}")
        .formatted(commandId, aliasesJson);
  }
}
