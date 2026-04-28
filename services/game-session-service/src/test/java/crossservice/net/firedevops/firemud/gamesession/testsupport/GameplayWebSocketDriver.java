package net.firedevops.firemud.gamesession.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** FireMUD-specific websocket gameplay test driver for chained login/play/command flows. */
public final class GameplayWebSocketDriver implements AutoCloseable {
  private final Duration waitTimeout;
  private final CopyOnWriteArrayList<String> responses;
  private final WebSocket webSocket;

  private GameplayWebSocketDriver(
      WebSocket webSocket, Duration waitTimeout, CopyOnWriteArrayList<String> responses) {
    this.webSocket = webSocket;
    this.waitTimeout = waitTimeout;
    this.responses = responses;
  }

  public static GameplayWebSocketDriver connect(
      URI uri, Duration waitTimeout, Map<String, String> headers) {
    HttpClient client = HttpClient.newHttpClient();
    WebSocket.Builder builder = client.newWebSocketBuilder();
    headers.forEach(builder::header);
    CopyOnWriteArrayList<String> responses = new CopyOnWriteArrayList<>();
    WebSocket webSocket =
        builder
            .buildAsync(
                uri,
                new Listener() {
                  @Override
                  public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                  }

                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    responses.add(data.toString());
                    webSocket.request(1);
                    return Listener.super.onText(webSocket, data, last);
                  }
                })
            .join();
    return new GameplayWebSocketDriver(webSocket, waitTimeout, responses);
  }

  public void send(String text) {
    webSocket.sendText(text, true).join();
  }

  public void login(String email, String password) throws Exception {
    send("LOGIN " + email + " " + password);
    awaitStartsWith("OK LOGIN");
  }

  public void play(String world) throws Exception {
    send("PLAY " + world);
    awaitStartsWith("OK PLAY");
  }

  public void play(String world, String characterName) throws Exception {
    send("PLAY " + world + " " + characterName);
    awaitStartsWith("OK PLAY");
  }

  public void lookUntilReady(String expectedLookSubstring) throws Exception {
    send("LOOK");
    awaitContains(expectedLookSubstring);
  }

  public void awaitStartsWith(String expectedPrefix) throws Exception {
    awaitMatching(
        response -> response.startsWith(expectedPrefix),
        "response starting with '" + expectedPrefix + "'");
  }

  public void awaitContains(String expectedSubstring) throws Exception {
    awaitMatching(
        response -> response.contains(expectedSubstring),
        "response containing '" + expectedSubstring + "'");
  }

  public void awaitMatching(Predicate<String> predicate, String description) throws Exception {
    long deadline = System.currentTimeMillis() + waitTimeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (responses.stream().anyMatch(predicate)) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Expected " + description + ", got " + responses);
  }

  public List<String> responses() {
    return List.copyOf(responses);
  }

  @Override
  public void close() {
    try {
      webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    } catch (CompletionException ex) {
      if (!(ex.getCause() instanceof IOException)) {
        throw ex;
      }
    }
  }
}
