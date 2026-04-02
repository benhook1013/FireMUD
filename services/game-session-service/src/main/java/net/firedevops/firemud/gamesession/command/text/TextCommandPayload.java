package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import org.springframework.util.StringUtils;

/** Small stable payload shapes for the first normalized text-command envelope rollout. */
public sealed interface TextCommandPayload
    permits TextCommandPayload.None,
        TextCommandPayload.Tokens,
        TextCommandPayload.Credentials,
        TextCommandPayload.Selection,
        TextCommandPayload.Message,
        TextCommandPayload.TargetedMessage,
        TextCommandPayload.Directional,
        TextCommandPayload.ViewRequest {

  record None() implements TextCommandPayload {}

  record Tokens(List<String> values) implements TextCommandPayload {
    public Tokens {
      values = List.copyOf(values == null ? List.of() : values);
    }
  }

  record Credentials(String loginName, String password, String otp) implements TextCommandPayload {}

  record Selection(String primary, String secondary) implements TextCommandPayload {}

  record Message(String text) implements TextCommandPayload {}

  record TargetedMessage(String target, String message) implements TextCommandPayload {}

  record Directional(String direction) implements TextCommandPayload {}

  record ViewRequest(String viewName) implements TextCommandPayload {}

  static TextCommandPayload fromLegacy(TextCommandType type, List<String> args) {
    List<String> safeArgs = args == null ? List.of() : List.copyOf(args);
    return switch (type) {
      case NOOP -> new None();
      case WORLDS, LOOK -> new ViewRequest(type.name());
      case LOGIN ->
          safeArgs.size() >= 2
              ? new Credentials(
                  safeArgs.get(0), safeArgs.get(1), safeArgs.size() > 2 ? safeArgs.get(2) : "")
              : new Tokens(safeArgs);
      case PLAY ->
          !safeArgs.isEmpty()
              ? new Selection(safeArgs.get(0), safeArgs.size() > 1 ? safeArgs.get(1) : null)
              : new Tokens(safeArgs);
      case SAY ->
          !safeArgs.isEmpty() && StringUtils.hasText(safeArgs.get(0))
              ? new Message(safeArgs.get(0))
              : new Tokens(safeArgs);
      case WHISPER, TELL ->
          safeArgs.size() >= 2
              ? new TargetedMessage(safeArgs.get(0), safeArgs.get(1))
              : new Tokens(safeArgs);
      case MOVE ->
          !safeArgs.isEmpty() && StringUtils.hasText(safeArgs.get(0))
              ? new Directional(safeArgs.get(0))
              : new Tokens(safeArgs);
      case UNKNOWN -> new Tokens(safeArgs);
    };
  }
}
