package net.firedevops.firemud.gamesession.command.text;

import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
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
    return toPlayerOutput(result, true, LookViewOutput.RefreshReason.EXPLICIT_LOOK);
  }

  public PlayerOutput toPlayerOutput(LookResult result, boolean includeLongDescription) {
    return toPlayerOutput(
        result,
        includeLongDescription,
        includeLongDescription
            ? LookViewOutput.RefreshReason.EXPLICIT_LOOK
            : LookViewOutput.RefreshReason.QUICKLOOK);
  }

  public PlayerOutput toPlayerOutput(
      LookResult result,
      boolean includeLongDescription,
      LookViewOutput.RefreshReason refreshReason) {
    return toPlayerOutput(
        result,
        includeLongDescription,
        refreshReason,
        LookViewOutput.defaultBriefRenderingHint(refreshReason, includeLongDescription));
  }

  public PlayerOutput toPlayerOutput(
      LookResult result,
      boolean includeLongDescription,
      LookViewOutput.RefreshReason refreshReason,
      LookViewOutput.BriefRenderingHint briefRenderingHint) {
    return PlayerOutput.view(
        LookViewOutput.from(result, includeLongDescription, refreshReason, briefRenderingHint));
  }

  public String render(LookResult result) {
    return render(result, true, LookViewOutput.RefreshReason.EXPLICIT_LOOK);
  }

  public String render(LookResult result, boolean includeLongDescription) {
    return render(
        result,
        includeLongDescription,
        includeLongDescription
            ? LookViewOutput.RefreshReason.EXPLICIT_LOOK
            : LookViewOutput.RefreshReason.QUICKLOOK);
  }

  public String render(
      LookResult result,
      boolean includeLongDescription,
      LookViewOutput.RefreshReason refreshReason) {
    return render(
        result,
        includeLongDescription,
        refreshReason,
        LookViewOutput.defaultBriefRenderingHint(refreshReason, includeLongDescription));
  }

  public String render(
      LookResult result,
      boolean includeLongDescription,
      LookViewOutput.RefreshReason refreshReason,
      LookViewOutput.BriefRenderingHint briefRenderingHint) {
    return render(result, includeLongDescription, refreshReason, briefRenderingHint, null, null);
  }

  public String render(
      LookResult result,
      boolean includeLongDescription,
      LookViewOutput.RefreshReason refreshReason,
      LookViewOutput.BriefRenderingHint briefRenderingHint,
      String localeTag,
      PresentationProperties effectivePresentationProperties) {
    if (effectivePresentationProperties == null) {
      return renderer.render(
          toPlayerOutput(result, includeLongDescription, refreshReason, briefRenderingHint),
          localeTag);
    }
    return renderer.render(
        toPlayerOutput(result, includeLongDescription, refreshReason, briefRenderingHint),
        localeTag,
        effectivePresentationProperties);
  }
}
