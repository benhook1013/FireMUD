package net.firedevops.firemud.gamesession.command.text;

import java.util.stream.Collectors;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import org.springframework.stereotype.Component;

@Component
public class LookTextRenderer {
  public String render(LookResult result) {
    StringBuilder out = new StringBuilder();
    String roomId = result.getRoomInstance().getRoomInstanceId();
    out.append("Room: ").append(result.getRoomName()).append(" (ID: ").append(roomId).append(")\n");
    out.append("Short: ").append(result.getShortDescription()).append("\n");
    out.append("Long: ").append(result.getLongDescription()).append("\n");
    out.append("Exits: ");
    out.append(
        result.getExitsList().stream()
            .map(e -> e.getLabel() + " (" + e.getDescription() + ")")
            .collect(Collectors.joining(", ")));
    out.append("\nEntities:\n");
    for (var entity : result.getEntitiesList()) {
      out.append("- ")
          .append(entity.getEntityType())
          .append(" \"")
          .append(entity.getDisplayName())
          .append("\"")
          .append(entity.getRole().isEmpty() ? "" : " (" + entity.getRole() + ")");
      if (!entity.getStateFlagsList().isEmpty()) {
        out.append(" [").append(String.join(",", entity.getStateFlagsList())).append("]");
      }
      out.append("\n");
    }
    return out.toString().trim();
  }
}
