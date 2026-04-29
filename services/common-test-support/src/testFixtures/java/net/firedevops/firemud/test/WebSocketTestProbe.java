package net.firedevops.firemud.test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Shared low-level websocket probe for integration and cross-service tests. */
public final class WebSocketTestProbe implements AutoCloseable {
  private final List<String> responses = new CopyOnWriteArrayList<>();
  private final AtomicReference<WebSocketSession> sessionRef = new AtomicReference<>();
  private final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
  private final AtomicBoolean downstreamClosed = new AtomicBoolean(false);

  private WebSocketTestProbe() {}

  public static WebSocketTestProbe connect(String websocketUrl, WebSocketHttpHeaders headers)
      throws Exception {
    WebSocketTestProbe probe = new WebSocketTestProbe();
    StandardWebSocketClient client = new StandardWebSocketClient();
    client
        .execute(
            new TextWebSocketHandler() {
              @Override
              public void afterConnectionEstablished(WebSocketSession session) {
                probe.sessionRef.set(session);
              }

              @Override
              protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                probe.responses.add(message.getPayload());
              }

              @Override
              public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                probe.closeStatus.set(status);
                probe.downstreamClosed.set(true);
              }
            },
            headers,
            URI.create(websocketUrl))
        .get(5, TimeUnit.SECONDS);
    return probe;
  }

  public void send(String payload) throws IOException {
    sessionRef.get().sendMessage(new TextMessage(payload));
  }

  public String awaitMessage(Predicate<String> matcher, String description, Duration timeout)
      throws InterruptedException {
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    int cursor = 0;
    while (System.nanoTime() < deadlineNanos) {
      for (; cursor < responses.size(); cursor++) {
        String candidate = responses.get(cursor);
        if (matcher.test(candidate)) {
          return candidate;
        }
      }
      Thread.sleep(25L);
    }
    throw new AssertionError(
        "Expected websocket message matching '" + description + "', got " + responses);
  }

  public String awaitStartsWith(String prefix, Duration timeout) throws InterruptedException {
    return awaitMessage(payload -> payload.startsWith(prefix), "prefix " + prefix, timeout);
  }

  public boolean awaitClosed(Duration timeout) throws InterruptedException {
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      if (downstreamClosed.get()) {
        return true;
      }
      Thread.sleep(25L);
    }
    return downstreamClosed.get();
  }

  public List<String> responses() {
    return List.copyOf(responses);
  }

  public boolean downstreamClosed() {
    return downstreamClosed.get();
  }

  public CloseStatus closeStatus() {
    return closeStatus.get();
  }

  @Override
  public void close() throws Exception {
    WebSocketSession session = sessionRef.get();
    if (session != null && session.isOpen()) {
      session.close();
    }
  }
}
