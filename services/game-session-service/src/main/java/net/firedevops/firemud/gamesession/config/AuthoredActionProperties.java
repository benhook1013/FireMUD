package net.firedevops.firemud.gamesession.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import net.firedevops.firemud.gamesession.command.text.TextCommandActionCategory;
import net.firedevops.firemud.gamesession.command.text.TextCommandPromptPolicy;
import net.firedevops.firemud.gamesession.command.text.TextCommandStageRequirement;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "game-session.authored-actions")
public class AuthoredActionProperties {
  private List<Action> actions = new ArrayList<>();

  @Getter
  @Setter
  public static class Action {
    private String actionId;
    private String commandId;
    private List<String> aliases = new ArrayList<>();
    private TextCommandStageRequirement stageRequirement = TextCommandStageRequirement.GAMEPLAY;
    private TextCommandPromptPolicy promptPolicy = TextCommandPromptPolicy.WHEN_GAMEPLAY;
    private TextCommandActionCategory actionCategory = TextCommandActionCategory.GAMEPLAY;
    private List<net.firedevops.firemud.gamesession.command.text.TextCommandActionTag> actionTags =
        new ArrayList<>();
    private String targetingMode = "NONE";
    private String cooldownKey;
    private long cooldownMs;
    private String costKey;
    private long costAmount;
    private String executionHook;
    private String helpSummary;
    private String helpDetails;
  }
}
