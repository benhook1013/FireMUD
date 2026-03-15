package net.firedevops.firemud.gamelogic.logic.command;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Basic parser with hard-coded command aliases. */
public class DefaultCommandParser implements CommandParser {
  private final Map<String, ActionType> actionMap = new HashMap<>();

  public DefaultCommandParser() {
    // movement aliases
    actionMap.put("north", ActionType.MOVE);
    actionMap.put("south", ActionType.MOVE);
    actionMap.put("east", ActionType.MOVE);
    actionMap.put("west", ActionType.MOVE);
    // attack aliases
    actionMap.put("attack", ActionType.ATTACK);
    actionMap.put("hit", ActionType.ATTACK);
    // emote aliases
    actionMap.put("say", ActionType.EMOTE);
    actionMap.put("emote", ActionType.EMOTE);
    // interact alias
    actionMap.put("use", ActionType.INTERACT);
    // procedural generation
    actionMap.put("generate-dungeon", ActionType.PROCEDURAL);
  }

  @Override
  public Command parse(String input) {
    if (input == null || input.isBlank()) {
      return new Command(ActionType.UNKNOWN, "", "");
    }
    String trimmed = input.trim();
    String[] parts = trimmed.split("\\s+", 2);
    String verb = parts[0].toLowerCase(Locale.US);
    ActionType action = actionMap.getOrDefault(verb, ActionType.UNKNOWN);
    String target = parts.length > 1 ? parts[1] : "";
    if (action == ActionType.MOVE && target.isEmpty()) {
      target = verb;
    }
    boolean solo = action == ActionType.PROCEDURAL;
    return new Command(action, target, input, solo);
  }
}
