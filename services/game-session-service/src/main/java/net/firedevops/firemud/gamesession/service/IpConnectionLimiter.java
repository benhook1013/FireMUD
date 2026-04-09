package net.firedevops.firemud.gamesession.service;

/** Tracks active connections per IP address across the cluster. */
public interface IpConnectionLimiter {
  /** Returns whether another connection from the IP can be accepted. */
  boolean canAccept(String ip);

  /**
   * Returns whether another connection from the IP can be accepted, allowing replacement of an
   * existing session already registered on the same IP when provided.
   */
  default boolean canAccept(String ip, Long replacingSessionId) {
    return canAccept(ip);
  }

  /** Atomically reserve a connection slot for the IP and session. */
  boolean tryRegister(String ip, long sessionId);

  /**
   * Transfer an existing IP reservation from one session to another without changing the bounded
   * counter.
   */
  default boolean transferRegistration(String ip, long previousSessionId, long newSessionId) {
    return false;
  }

  /** Release resources when a session ends. */
  void release(long sessionId);
}
