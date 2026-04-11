package net.firedevops.firemud.gamesession.service;

/** Derived gameplay presence activity state for AFK/idle-aware consumers. */
public enum GameplayPresenceActivityState {
  ACTIVE,
  AUTO_AFK,
  EXPLICIT_AFK
}
