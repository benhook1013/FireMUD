package net.firedevops.firemud.gamesession.service;

/** Limits the rate of commands per game session. */
public interface SessionRateLimiter {
  /** Returns {@code true} if the next command is allowed for the session. */
  boolean allow(long sessionId);
}
