package net.firedevops.firemud.gamesession.service;

/** Normalized elevated-player role carried by live gameplay presence. */
public enum GameplayPresenceRole {
  GOD,
  ADMIN,
  MODERATOR,
  PLAYER;

  public boolean isElevated() {
    return this != PLAYER;
  }

  public int presenceOrdering() {
    return switch (this) {
      case GOD -> 0;
      case ADMIN -> 1;
      case MODERATOR -> 2;
      case PLAYER -> 3;
    };
  }
}
