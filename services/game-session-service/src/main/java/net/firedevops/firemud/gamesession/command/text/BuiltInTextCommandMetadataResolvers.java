package net.firedevops.firemud.gamesession.command.text;

import java.util.List;

/** Public factory for canonical built-in command metadata readers outside the command package. */
public final class BuiltInTextCommandMetadataResolvers {
  private BuiltInTextCommandMetadataResolvers() {}

  public static TextCommandMetadataResolver builtInOnly() {
    return new RegistryBackedTextCommandMetadataResolver(
        new AggregatingTextCommandRegistry(List.of(new BuiltInTextCommandDefinitionProvider())));
  }
}
