package net.firedevops.firemud.gamesession.command.text;

import java.util.Objects;
import net.firedevops.firemud.gamesession.presentation.WorldsViewOutput;
import org.springframework.stereotype.Component;

/** Handles public world-browse commands before login and after login. */
@Component
public class WorldsCommandHandler {
  private final GameplayWorldCatalog worldCatalog;

  public WorldsCommandHandler(GameplayWorldCatalog worldCatalog) {
    this.worldCatalog = Objects.requireNonNull(worldCatalog, "worldCatalog must not be null");
  }

  public String describe() {
    return worldCatalog.describeWorlds();
  }

  public WorldsViewOutput browseView() {
    return worldCatalog.browseView();
  }
}
