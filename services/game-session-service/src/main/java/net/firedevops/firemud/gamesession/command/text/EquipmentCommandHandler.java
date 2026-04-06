package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles the first equipment command surface in the text session layer. */
@Component
public class EquipmentCommandHandler {
  @Timed(value = "gamesession.command.equipment")
  public TextCommandInterpretationResult handle(SessionContext context, TextCommand command) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(command, "command must not be null");

    return switch (command.type()) {
      case WEAR -> equipmentCommand(context, command, "WEAR");
      case REMOVE -> equipmentCommand(context, command, "REMOVE");
      default ->
          new TextCommandInterpretationResult(
              CommandEnqueueResult.failure("INVALID_COMMAND", "Unsupported equipment command"),
              List.of(
                  PlayerOutput.error(
                      "INVALID_COMMAND",
                      "Unsupported equipment command",
                      "error.equipment.invalid-command",
                      Map.of())));
    };
  }

  private TextCommandInterpretationResult equipmentCommand(
      SessionContext context, TextCommand command, String verb) {
    if (command.itemReferencePayload().isEmpty()) {
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure("INVALID_ARGUMENT", verb + " command requires an item"),
          List.of(
              PlayerOutput.error(
                  "INVALID_ARGUMENT",
                  verb + " command requires an item",
                  "error.equipment.item-required",
                  Map.of("verb", verb))));
    }

    TextCommandPayload.ItemReference itemReference = command.itemReferencePayload().orElseThrow();
    if (itemReference.quantity() <= 0) {
      return equipmentUnavailable(verb, itemReference.reference());
    }
    return equipmentUnavailable(verb, itemReference.reference());
  }

  private TextCommandInterpretationResult equipmentUnavailable(String verb, String itemReference) {
    String normalizedItem = StringUtils.hasText(itemReference) ? itemReference : "item";
    String message =
        verb
            + " "
            + normalizedItem
            + " is prepared in the command surface, but the equipment runtime is not yet wired.";
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure("EQUIPMENT_UNAVAILABLE", message),
        List.of(
            PlayerOutput.error(
                "EQUIPMENT_UNAVAILABLE",
                message,
                "error.equipment.unavailable",
                Map.of("verb", verb, "item", normalizedItem))));
  }
}
