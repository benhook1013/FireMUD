package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CommandSourceTest {
  private static final String VALID_DEFINITION =
      """
      {
        "schemaVersion": 1,
        "commandId": "salute",
        "semanticOwner": "GAME_LOGIC",
        "executionDiscipline": "DURABLE_GAMEPLAY",
        "stageRequirement": "GAMEPLAY",
        "promptPolicy": "WHEN_GAMEPLAY",
        "actionCategory": "SOCIAL",
        "historyRecordable": true,
        "aliases": ["salute"],
        "actionTags": ["COMMUNICATION"],
        "effects": [
          {
            "effectKind": "APPLY_ACTION_STATE",
            "schemaVersion": 1,
            "targeting": "SELF",
            "replayPolicy": "EFFECT_IDEMPOTENT",
            "payload": {
              "conditionKey": "SALUTING",
              "durationSeconds": 30,
              "effectPayload": {
                "modifiers": [
                  {
                    "operation": "ADD",
                    "target_key": "salute_count",
                    "value": 1,
                    "scope_kind": "ACTION_FAMILY",
                    "scope_key": "social",
                    "priority": 2
                  }
                ]
              }
            }
          }
        ]
      }
      """;

  @Test
  void acceptsCanonicalDefinitionAndDefinedOptionalModifierFields() {
    assertThat(CommandSource.upsertPayload(VALID_DEFINITION)).isNotBlank();
  }

  @Test
  void acceptsCanonicalDefinitionWhenAllOptionalModifierFieldsAreAbsent() {
    String definitionWithoutOptionalModifierFields =
        VALID_DEFINITION.replace(
            ",\n              \"scope_kind\": \"ACTION_FAMILY\",\n              \"scope_key\": \"social\",\n              \"priority\": 2",
            "");

    assertThat(definitionWithoutOptionalModifierFields)
        .doesNotContain("scope_kind", "scope_key", "priority");
    assertThat(CommandSource.upsertPayload(definitionWithoutOptionalModifierFields)).isNotBlank();
  }

  @Test
  void rejectsUnknownDefinitionFieldThatCouldCarryAnUnsupportedReference() {
    assertRejected("\"commandId\": \"salute\",", "\"targetSelectionPolicyKey\": \"self\",");
  }

  @Test
  void rejectsUnknownEffectFieldThatCouldCarryAnUnsupportedReference() {
    assertRejected("\"effectKind\": \"APPLY_ACTION_STATE\",", "\"targetSetKey\": \"SOURCE\",");
  }

  @Test
  void rejectsUnknownPayloadFieldThatCouldCarryAnUnsupportedReference() {
    assertRejected("\"conditionKey\": \"SALUTING\",", "\"sourceRuleId\": \"rule-1\",");
  }

  @Test
  void rejectsUnknownEffectPayloadFieldThatCouldCarryAnUnsupportedReference() {
    assertRejected("\"effectPayload\": {", "\"targetRefs\": [],");
  }

  @Test
  void rejectsUnknownModifierFieldThatCouldCarryAnUnsupportedReference() {
    assertRejected("\"operation\": \"ADD\",", "\"targetTemplateId\": \"item-1\",");
  }

  private static void assertRejected(String marker, String insertion) {
    String unsupported = VALID_DEFINITION.replace(marker, marker + insertion);
    assertThat(unsupported).isNotEqualTo(VALID_DEFINITION);
    assertThatThrownBy(() -> CommandSource.upsertPayload(unsupported))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
