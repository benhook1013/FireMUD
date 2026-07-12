package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;

/** Builds an instance-scoped command registry from built-ins and the admitted release artifact. */
@Component
public final class AdmittedTextCommandRegistryResolver {
  private final AdmittedCommandDefinitionReader admittedCommandDefinitionReader;
  private final TextCommandRegistry builtIns =
      new AggregatingTextCommandRegistry(List.of(new BuiltInTextCommandDefinitionProvider()));

  AdmittedTextCommandRegistryResolver(
      AdmittedCommandDefinitionReader admittedCommandDefinitionReader) {
    this.admittedCommandDefinitionReader = admittedCommandDefinitionReader;
  }

  public TextCommandRegistry resolve(SessionContext context) {
    return admittedCommandDefinitionReader
        .definitionsFor(context)
        .<TextCommandRegistry>map(
            definitions ->
                new AggregatingTextCommandRegistry(
                    List.of(new BuiltInTextCommandDefinitionProvider(), () -> definitions)))
        .orElse(builtIns);
  }
}
