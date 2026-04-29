package net.firedevops.firemud.gamesession.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import net.firedevops.firemud.common.security.JwtUtil;

/** FireMUD-specific websocket gameplay test driver for chained login/play/command flows. */
public final class GameplayWebSocketDriver implements AutoCloseable {
  public record CloseEvent(int statusCode, String reason) {}

  private final Duration waitTimeout;
  private final CopyOnWriteArrayList<String> responses;
  private final WebSocket webSocket;
  private final CompletableFuture<CloseEvent> closeFuture;

  private GameplayWebSocketDriver(
      WebSocket webSocket,
      Duration waitTimeout,
      CopyOnWriteArrayList<String> responses,
      CompletableFuture<CloseEvent> closeFuture) {
    this.webSocket = webSocket;
    this.waitTimeout = waitTimeout;
    this.responses = responses;
    this.closeFuture = closeFuture;
  }

  public static GameplayWebSocketDriver connect(
      URI uri, Duration waitTimeout, Map<String, String> headers) {
    HttpClient client = HttpClient.newHttpClient();
    WebSocket.Builder builder = client.newWebSocketBuilder();
    headers.forEach(builder::header);
    CopyOnWriteArrayList<String> responses = new CopyOnWriteArrayList<>();
    CompletableFuture<CloseEvent> closeFuture = new CompletableFuture<>();
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

                  @Override
                  public CompletionStage<?> onClose(
                      WebSocket webSocket, int statusCode, String reason) {
                    closeFuture.complete(new CloseEvent(statusCode, reason));
                    return Listener.super.onClose(webSocket, statusCode, reason);
                  }

                  @Override
                  public void onError(WebSocket webSocket, Throwable error) {
                    closeFuture.completeExceptionally(error);
                    Listener.super.onError(webSocket, error);
                  }
                })
            .join();
    return new GameplayWebSocketDriver(webSocket, waitTimeout, responses, closeFuture);
  }

  public static GameplayWebSocketDriver connectGameplaySession(
      URI uri, Duration waitTimeout, long tenantId, long sessionId) {
    return connectGameplaySession(uri, waitTimeout, tenantId, sessionId, Map.of());
  }

  public static GameplayWebSocketDriver connectGameplaySession(
      URI uri,
      Duration waitTimeout,
      long tenantId,
      long sessionId,
      Map<String, String> extraHeaders) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Game-Instance-Id", Long.toString(sessionId));
    headers.put("X-Tenant-Id", Long.toString(tenantId));
    headers.putAll(extraHeaders);
    return connect(uri, waitTimeout, headers);
  }

  public static GameplayWebSocketDriver connectFirstPartyWeb(
      URI uri,
      Duration waitTimeout,
      String transportSessionId,
      String jwtSecret,
      Map<String, Object> connectClaims) {
    String connectContextToken =
        new JwtUtil(jwtSecret, 60_000L).generateToken("123", connectClaims);
    return connect(
        uri,
        waitTimeout,
        Map.of(
            "X-Firemud-Connection-Mode", "first_party_web",
            "X-Firemud-Transport-Session-Id", transportSessionId,
            "X-Firemud-Connect-Context", connectContextToken));
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

  public void enterGameplayAndWaitReady(
      String email, String password, String world, String expectedLookSubstring) throws Exception {
    login(email, password);
    play(world);
    lookUntilReady(expectedLookSubstring);
  }

  public void enterGameplayAndWaitReady(
      String email,
      String password,
      String world,
      String characterName,
      String expectedLookSubstring)
      throws Exception {
    login(email, password);
    play(world, characterName);
    lookUntilReady(expectedLookSubstring);
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

  public CloseEvent awaitClosed() throws Exception {
    try {
      return closeFuture.get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException ex) {
      throw new AssertionError("Expected websocket close event, got responses " + responses, ex);
    }
  }

  public void abort() {
    webSocket.abort();
  }

  @Override
  public void close() {
    if (closeFuture.isDone()) {
      return;
    }
    try {
      webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    } catch (CompletionException ex) {
      if (!(ex.getCause() instanceof IOException)) {
        throw ex;
      }
    }
  }
}
