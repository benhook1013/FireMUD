package net.firedevops.firemud.gamesession.service;

/** Tracks active connections per IP address across the cluster. */
public interface IpConnectionLimiter {
  /** Returns whether another connection from the IP can be accepted. */
  boolean canAccept(String ip);

  /** Register a session for the IP after acceptance. */
  void register(String ip, long sessionId);

  /** Release resources when a session ends. */
  void release(long sessionId);
}
