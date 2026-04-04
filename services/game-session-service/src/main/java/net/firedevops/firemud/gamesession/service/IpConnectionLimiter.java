package net.firedevops.firemud.gamesession.service;

/** Tracks active connections per IP address across the cluster. */
public interface IpConnectionLimiter {
  /** Returns whether another connection from the IP can be accepted. */
  boolean canAccept(String ip);

  /** Atomically reserve a connection slot for the IP and session. */
  boolean tryRegister(String ip, long sessionId);

  /** Release resources when a session ends. */
  void release(long sessionId);
}
