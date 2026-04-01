package net.firedevops.firemud.gamesession.presentation;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import net.firedevops.firemud.gamelogic.v1.LookResult;

/** Structured LOOK view payload for late rendering. */
public record LookViewOutput(
    String roomId,
    String roomName,
    String shortDescription,
    String longDescription,
    List<LookViewExit> exits,
    List<LookViewEntity> entities)
    implements PlayerOutputPayload {
  public LookViewOutput {
    Objects.requireNonNull(roomId, "roomId must not be null");
    Objects.requireNonNull(roomName, "roomName must not be null");
    Objects.requireNonNull(shortDescription, "shortDescription must not be null");
    Objects.requireNonNull(longDescription, "longDescription must not be null");
    exits = List.copyOf(Objects.requireNonNull(exits, "exits must not be null"));
    entities = List.copyOf(Objects.requireNonNull(entities, "entities must not be null"));
  }

  public static LookViewOutput from(LookResult result) {
    Objects.requireNonNull(result, "result must not be null");
    return new LookViewOutput(
        result.getRoomInstance().getRoomInstanceId(),
        result.getRoomName(),
        result.getShortDescription(),
        result.getLongDescription(),
        result.getExitsList().stream()
            .map(exit -> new LookViewExit(exit.getLabel(), exit.getDescription()))
            .collect(Collectors.toList()),
        result.getEntitiesList().stream()
            .map(
                entity ->
                    new LookViewEntity(
                        entity.getEntityType().name(),
                        entity.getDisplayName(),
                        entity.getRole(),
                        entity.getStateFlagsList().stream().collect(Collectors.toList())))
            .collect(Collectors.toList()));
  }
}
