package net.firedevops.firemud.gamesession.command.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltInTextCommandAliasResolverTest {
  @Test
  void resolvesBuiltInAliasCaseInsensitively() {
    BuiltInTextCommandAliasResolver resolver =
        new BuiltInTextCommandAliasResolver(
            new AggregatingTextCommandRegistry(
                List.of(new BuiltInTextCommandDefinitionProvider())));

    assertEquals("logoff", resolver.resolve("LoGoFf").orElseThrow());
  }

  @Test
  void doesNotResolveUnknownAlias() {
    BuiltInTextCommandAliasResolver resolver =
        new BuiltInTextCommandAliasResolver(
            new AggregatingTextCommandRegistry(
                List.of(new BuiltInTextCommandDefinitionProvider())));

    assertFalse(resolver.resolve("teleport").isPresent());
  }
}
