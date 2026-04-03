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
    boolean includeLongDescription,
    RefreshReason refreshReason,
    BriefRenderingHint briefRenderingHint,
    List<LookViewExit> exits,
    List<LookViewEntity> entities)
    implements PlayerOutputPayload {
  public LookViewOutput(
      String roomId,
      String roomName,
      String shortDescription,
      String longDescription,
      boolean includeLongDescription,
      List<LookViewExit> exits,
      List<LookViewEntity> entities) {
    this(
        roomId,
        roomName,
        shortDescription,
        longDescription,
        includeLongDescription,
        includeLongDescription ? RefreshReason.EXPLICIT_LOOK : RefreshReason.QUICKLOOK,
        defaultBriefRenderingHint(
            includeLongDescription ? RefreshReason.EXPLICIT_LOOK : RefreshReason.QUICKLOOK,
            includeLongDescription),
        exits,
        entities);
  }

  public LookViewOutput(
      String roomId,
      String roomName,
      String shortDescription,
      String longDescription,
      boolean includeLongDescription,
      RefreshReason refreshReason,
      List<LookViewExit> exits,
      List<LookViewEntity> entities) {
    this(
        roomId,
        roomName,
        shortDescription,
        longDescription,
        includeLongDescription,
        refreshReason,
        defaultBriefRenderingHint(refreshReason, includeLongDescription),
        exits,
        entities);
  }

  public LookViewOutput {
    Objects.requireNonNull(roomId, "roomId must not be null");
    Objects.requireNonNull(roomName, "roomName must not be null");
    Objects.requireNonNull(shortDescription, "shortDescription must not be null");
    Objects.requireNonNull(longDescription, "longDescription must not be null");
    Objects.requireNonNull(refreshReason, "refreshReason must not be null");
    Objects.requireNonNull(briefRenderingHint, "briefRenderingHint must not be null");
    exits = List.copyOf(Objects.requireNonNull(exits, "exits must not be null"));
    entities = List.copyOf(Objects.requireNonNull(entities, "entities must not be null"));
  }

  public static LookViewOutput from(LookResult result) {
    return from(result, true, RefreshReason.EXPLICIT_LOOK);
  }

  public static LookViewOutput from(LookResult result, boolean includeLongDescription) {
    return from(
        result,
        includeLongDescription,
        includeLongDescription ? RefreshReason.EXPLICIT_LOOK : RefreshReason.QUICKLOOK,
        defaultBriefRenderingHint(
            includeLongDescription ? RefreshReason.EXPLICIT_LOOK : RefreshReason.QUICKLOOK,
            includeLongDescription));
  }

  public static LookViewOutput from(
      LookResult result, boolean includeLongDescription, RefreshReason refreshReason) {
    return from(
        result,
        includeLongDescription,
        refreshReason,
        defaultBriefRenderingHint(refreshReason, includeLongDescription));
  }

  public static LookViewOutput from(
      LookResult result,
      boolean includeLongDescription,
      RefreshReason refreshReason,
      BriefRenderingHint briefRenderingHint) {
    Objects.requireNonNull(result, "result must not be null");
    return new LookViewOutput(
        result.getRoomInstance().getRoomInstanceId(),
        result.getRoomName(),
        result.getShortDescription(),
        result.getLongDescription(),
        includeLongDescription,
        refreshReason,
        briefRenderingHint,
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

  public static BriefRenderingHint defaultBriefRenderingHint(
      RefreshReason refreshReason, boolean includeLongDescription) {
    if (!includeLongDescription) {
      return BriefRenderingHint.PREFER_BRIEF;
    }
    return refreshReason == RefreshReason.MOVE_REFRESH
        ? BriefRenderingHint.PREFER_BRIEF
        : BriefRenderingHint.FOLLOW_DEFAULT;
  }

  public enum BriefRenderingHint {
    FOLLOW_DEFAULT,
    PREFER_BRIEF
  }

  public enum RefreshReason {
    EXPLICIT_LOOK,
    QUICKLOOK,
    MOVE_REFRESH,
    RECONNECT_REFRESH
  }
}
