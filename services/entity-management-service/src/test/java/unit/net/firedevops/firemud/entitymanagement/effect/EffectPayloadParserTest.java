package net.firedevops.firemud.entitymanagement.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EffectPayloadParserTest {
  private final EffectPayloadParser parser = new EffectPayloadParser(new ObjectMapper());

  @Test
  void parsesModifiersFromCanonicalPayload() {
    EffectSource source = new EffectSource("CONDITION", "bless:1", "7");

    var modifiers =
        parser.parseModifiers(
            """
            {"modifiers":[
              {"operation":"ADD","target_key":"strength","value":2,"priority":5},
              {"operation":"GRANT_CONDITION","state_key":"guarded"}
            ]}
            """,
            source);

    assertEquals(2, modifiers.size());
    assertEquals(EffectOperation.ADD, modifiers.get(0).operation());
    assertEquals("strength", modifiers.get(0).targetKey());
    assertEquals(BigDecimal.valueOf(2), modifiers.get(0).value());
    assertEquals(5, modifiers.get(0).priority());
    assertEquals(source, modifiers.get(0).source());
    assertEquals(EffectOperation.GRANT_CONDITION, modifiers.get(1).operation());
    assertEquals("guarded", modifiers.get(1).targetKey());
  }
}
