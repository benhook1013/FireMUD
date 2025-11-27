package net.firedevops.firemud.command.text;

import java.util.Objects;
import org.springframework.stereotype.Component;

/** Handles the minimal LOOK gameplay command by returning a deterministic room description. */
@Component
public final class LookCommandHandler {
  public static final String DEFAULT_ROOM_DESCRIPTION = LookCommandConstants.ROOM_DESCRIPTION;

  private final String roomDescription;

  public LookCommandHandler() {
    this(DEFAULT_ROOM_DESCRIPTION);
  }

  LookCommandHandler(String roomDescription) {
    this.roomDescription = Objects.requireNonNull(roomDescription, "roomDescription must not be null");
  }

  public String describe(String sessionId) {
    return roomDescription;
  }
}
