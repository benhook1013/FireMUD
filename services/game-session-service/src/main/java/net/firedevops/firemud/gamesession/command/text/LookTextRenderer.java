package net.firedevops.firemud.gamesession.command.text;

import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamesession.presentation.LookViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LookTextRenderer {
  private final TextPlayerOutputRenderer renderer;

  @Autowired
  public LookTextRenderer(TextPlayerOutputRenderer renderer) {
    this.renderer = renderer;
  }

  public PlayerOutput toPlayerOutput(LookResult result) {
    return toPlayerOutput(result, true);
  }

  public PlayerOutput toPlayerOutput(LookResult result, boolean includeLongDescription) {
    return PlayerOutput.view(LookViewOutput.from(result, includeLongDescription));
  }

  public String render(LookResult result) {
    return render(result, true);
  }

  public String render(LookResult result, boolean includeLongDescription) {
    return renderer.render(toPlayerOutput(result, includeLongDescription));
  }
}
