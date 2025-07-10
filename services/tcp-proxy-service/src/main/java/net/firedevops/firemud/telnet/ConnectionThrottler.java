package net.firedevops.firemud.telnet;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Simple per-IP connection limiter. */
public class ConnectionThrottler {
  private final int maxConnectionsPerIp;
  private final ConcurrentHashMap<String, AtomicInteger> connections = new ConcurrentHashMap<>();

  public ConnectionThrottler(int maxConnectionsPerIp) {
    this.maxConnectionsPerIp = maxConnectionsPerIp;
  }

  public boolean tryAcquire(SocketAddress remote) {
    if (!(remote instanceof InetSocketAddress address)) {
      return true;
    }
    String ip = address.getAddress().getHostAddress();
    AtomicInteger count = connections.computeIfAbsent(ip, k -> new AtomicInteger());
    int current = count.incrementAndGet();
    if (current > maxConnectionsPerIp) {
      count.decrementAndGet();
      return false;
    }
    return true;
  }

  public void release(SocketAddress remote) {
    if (!(remote instanceof InetSocketAddress address)) {
      return;
    }
    String ip = address.getAddress().getHostAddress();
    connections.computeIfPresent(
        ip,
        (key, counter) -> {
          if (counter.decrementAndGet() <= 0) {
            return null;
          }
          return counter;
        });
  }
}
