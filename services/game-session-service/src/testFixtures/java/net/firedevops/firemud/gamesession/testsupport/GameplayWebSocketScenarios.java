package net.firedevops.firemud.gamesession.testsupport;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Shared chained-gameplay websocket scenario helpers for multi-actor proof. */
public final class GameplayWebSocketScenarios {
  public static final String DEMO_USERNAME = "demo@example.com";
  public static final String DEMO_PASSWORD = "swordfish";
  public static final String DEMO_WORLD = "demo";

  public static Admission demoAdmission(String readyText) {
    return Admission.unnamed(DEMO_USERNAME, DEMO_PASSWORD, DEMO_WORLD, readyText);
  }

  public static Admission demoAdmission(String characterName, String readyText) {
    return Admission.named(DEMO_USERNAME, DEMO_PASSWORD, DEMO_WORLD, characterName, readyText);
  }

  @FunctionalInterface
  public interface DriverFactory {
    GameplayWebSocketDriver open(String connectionId) throws Exception;
  }

  public record Admission(
      String email, String password, String world, String characterName, String readyText) {

    public static Admission named(
        String email, String password, String world, String characterName, String readyText) {
      return new Admission(email, password, world, characterName, readyText);
    }

    public static Admission unnamed(String email, String password, String world, String readyText) {
      return new Admission(email, password, world, null, readyText);
    }
  }

  @FunctionalInterface
  public interface DriverExercise {
    void accept(GameplayWebSocketDriver driver) throws Exception;
  }

  public enum DisconnectMode {
    CLOSE,
    ABORT
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Cross-service scenario helpers intentionally expose live test drivers.")
  public record ReconnectScenario(List<String> firstResponses, GameplayWebSocketDriver reconnecting)
      implements AutoCloseable {
    public ReconnectScenario {
      firstResponses = List.copyOf(firstResponses);
    }

    @Override
    public List<String> firstResponses() {
      return List.copyOf(firstResponses);
    }

    @Override
    public void close() throws Exception {
      reconnecting.close();
    }
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Cross-service scenario helpers intentionally expose live test drivers.")
  public record LoginThenPlayScenario(GameplayWebSocketDriver driver) implements AutoCloseable {
    public List<String> responses() {
      return driver.responses();
    }

    @Override
    public void close() throws Exception {
      driver.close();
    }
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Cross-service scenario helpers intentionally expose live test drivers.")
  public record TakeoverScenario(
      List<String> firstResponses, GameplayWebSocketDriver first, GameplayWebSocketDriver takeover)
      implements AutoCloseable {
    public TakeoverScenario {
      firstResponses = List.copyOf(firstResponses);
    }

    @Override
    public List<String> firstResponses() {
      return List.copyOf(firstResponses);
    }

    @Override
    public void close() throws Exception {
      Exception firstFailure = null;
      try {
        takeover.close();
      } catch (Exception ex) {
        firstFailure = ex;
      }
      try {
        first.close();
      } catch (Exception ex) {
        if (firstFailure != null) {
          ex.addSuppressed(firstFailure);
        }
        throw ex;
      }
      if (firstFailure != null) {
        throw firstFailure;
      }
    }
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Cross-service scenario helpers intentionally expose live test drivers.")
  public record TwoPlayerScenario(GameplayWebSocketDriver actor, GameplayWebSocketDriver target)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      Exception first = null;
      try {
        target.close();
      } catch (Exception ex) {
        first = ex;
      }
      try {
        actor.close();
      } catch (Exception ex) {
        if (first != null) {
          ex.addSuppressed(first);
        }
        throw ex;
      }
      if (first != null) {
        throw first;
      }
    }
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Cross-service scenario helpers intentionally expose live test drivers.")
  public record ThreePlayerScenario(
      GameplayWebSocketDriver actor,
      GameplayWebSocketDriver target,
      GameplayWebSocketDriver observer)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      Exception first = null;
      try {
        observer.close();
      } catch (Exception ex) {
        first = ex;
      }
      try {
        target.close();
      } catch (Exception ex) {
        if (first == null) {
          first = ex;
        } else {
          first.addSuppressed(ex);
        }
      }
      try {
        actor.close();
      } catch (Exception ex) {
        if (first != null) {
          ex.addSuppressed(first);
        }
        throw ex;
      }
      if (first != null) {
        throw first;
      }
    }
  }

  private GameplayWebSocketScenarios() {}

  public static GameplayWebSocketDriver openReady(
      DriverFactory factory, String connectionId, Admission admission) throws Exception {
    GameplayWebSocketDriver driver = factory.open(connectionId);
    try {
      enterReady(driver, admission);
      return driver;
    } catch (Exception ex) {
      closeQuietly(driver, ex);
      throw ex;
    }
  }

  public static GameplayWebSocketDriver openReady(
      DriverFactory factory, String connectionId, String readyText) throws Exception {
    return openReady(factory, connectionId, demoAdmission(readyText));
  }

  public static GameplayWebSocketDriver openReady(
      URI websocketUrl, Duration commandWait, long tenantId, long sessionId, String readyText)
      throws Exception {
    return openReady(websocketUrl, commandWait, tenantId, sessionId, demoAdmission(readyText));
  }

  public static GameplayWebSocketDriver openReady(
      URI websocketUrl, Duration commandWait, long tenantId, long sessionId, Admission admission)
      throws Exception {
    return openReady(
        ignored -> openGameplaySession(websocketUrl, commandWait, tenantId, sessionId),
        "gateway-" + sessionId,
        admission);
  }

  public static GameplayWebSocketDriver openReady(
      URI websocketUrl,
      Duration commandWait,
      long tenantId,
      long sessionId,
      String readyText,
      String connectionId)
      throws Exception {
    return openReady(
        websocketUrl, commandWait, tenantId, sessionId, demoAdmission(readyText), connectionId);
  }

  public static GameplayWebSocketDriver openReady(
      URI websocketUrl,
      Duration commandWait,
      long tenantId,
      long sessionId,
      Admission admission,
      String connectionId)
      throws Exception {
    return openReady(
        ignored -> openGameplaySession(websocketUrl, commandWait, tenantId, sessionId),
        connectionId,
        admission);
  }

  public static GameplayWebSocketDriver openReady(
      URI websocketUrl,
      Duration commandWait,
      long tenantId,
      long sessionId,
      String readyText,
      String connectionId,
      Map<String, String> extraHeaders)
      throws Exception {
    return openReady(
        ignored ->
            openGameplaySession(websocketUrl, commandWait, tenantId, sessionId, extraHeaders),
        connectionId,
        demoAdmission(readyText));
  }

  public static GameplayWebSocketDriver openReady(
      URI websocketUrl,
      Duration commandWait,
      long tenantId,
      long sessionId,
      Admission admission,
      String connectionId,
      Map<String, String> extraHeaders)
      throws Exception {
    return openReady(
        ignored ->
            openGameplaySession(websocketUrl, commandWait, tenantId, sessionId, extraHeaders),
        connectionId,
        admission);
  }

  public static GameplayWebSocketDriver openGameplaySession(
      URI websocketUrl, Duration commandWait, long tenantId, long sessionId) {
    return GameplayWebSocketDriver.connectGameplaySession(
        websocketUrl, commandWait, tenantId, sessionId);
  }

  public static GameplayWebSocketDriver openGameplaySession(
      URI websocketUrl,
      Duration commandWait,
      long tenantId,
      long sessionId,
      Map<String, String> extraHeaders) {
    return GameplayWebSocketDriver.connectGameplaySession(
        websocketUrl, commandWait, tenantId, sessionId, extraHeaders);
  }

  public static DriverFactory proxyGatewayDriverFactory(
      URI websocketUrl, Duration commandWait, long tenantId, long sessionId) {
    return connectionId ->
        openGameplaySession(
            websocketUrl,
            commandWait,
            tenantId,
            sessionId,
            Map.of("X-Proxy-Connection-Id", connectionId));
  }

  public static GameplayWebSocketDriver openReady(
      DriverFactory factory, String connectionId, String characterName, String readyText)
      throws Exception {
    return openReady(factory, connectionId, demoAdmission(characterName, readyText));
  }

  public static GameplayWebSocketDriver openAdmitted(
      DriverFactory factory, String connectionId, Admission admission) throws Exception {
    GameplayWebSocketDriver driver = factory.open(connectionId);
    try {
      enterAdmitted(driver, admission);
      return driver;
    } catch (Exception ex) {
      closeQuietly(driver, ex);
      throw ex;
    }
  }

  public static GameplayWebSocketDriver openAdmitted(
      DriverFactory factory, String connectionId, String readyText) throws Exception {
    return openAdmitted(factory, connectionId, demoAdmission(readyText));
  }

  public static TwoPlayerScenario openReadyPair(
      DriverFactory factory,
      String actorConnectionId,
      Admission actorAdmission,
      String targetConnectionId,
      Admission targetAdmission)
      throws Exception {
    GameplayWebSocketDriver actor = factory.open(actorConnectionId);
    GameplayWebSocketDriver target = factory.open(targetConnectionId);
    try {
      enterReady(actor, actorAdmission);
      enterReady(target, targetAdmission);
      return new TwoPlayerScenario(actor, target);
    } catch (Exception ex) {
      closeQuietly(target, actor, ex);
      throw ex;
    }
  }

  public static ThreePlayerScenario openReadyTrio(
      DriverFactory factory,
      String actorConnectionId,
      Admission actorAdmission,
      String targetConnectionId,
      Admission targetAdmission,
      String observerConnectionId,
      Admission observerAdmission)
      throws Exception {
    GameplayWebSocketDriver actor = factory.open(actorConnectionId);
    GameplayWebSocketDriver target = factory.open(targetConnectionId);
    GameplayWebSocketDriver observer = factory.open(observerConnectionId);
    try {
      enterReady(actor, actorAdmission);
      enterReady(target, targetAdmission);
      enterReady(observer, observerAdmission);
      return new ThreePlayerScenario(actor, target, observer);
    } catch (Exception ex) {
      closeQuietly(observer, target, actor, ex);
      throw ex;
    }
  }

  public static ReconnectScenario reconnectAfterReady(
      DriverFactory factory,
      String firstConnectionId,
      String reconnectConnectionId,
      Admission admission,
      DisconnectMode disconnectMode,
      DriverExercise firstSessionExercise)
      throws Exception {
    GameplayWebSocketDriver first = openReady(factory, firstConnectionId, admission);
    try {
      firstSessionExercise.accept(first);
      List<String> firstResponses = List.copyOf(first.responses());
      if (disconnectMode == DisconnectMode.ABORT) {
        first.abort();
      } else {
        first.close();
      }
      GameplayWebSocketDriver reconnecting = openReady(factory, reconnectConnectionId, admission);
      return new ReconnectScenario(firstResponses, reconnecting);
    } catch (Exception ex) {
      closeQuietly(first, ex);
      throw ex;
    }
  }

  public static ReconnectScenario reconnectAfterReady(
      DriverFactory factory,
      String firstConnectionId,
      String reconnectConnectionId,
      String readyText,
      DisconnectMode disconnectMode,
      DriverExercise firstSessionExercise)
      throws Exception {
    return reconnectAfterReady(
        factory,
        firstConnectionId,
        reconnectConnectionId,
        demoAdmission(readyText),
        disconnectMode,
        firstSessionExercise);
  }

  public static TakeoverScenario takeoverAfterReady(
      DriverFactory factory,
      String firstConnectionId,
      String takeoverConnectionId,
      Admission admission,
      DriverExercise firstSessionExercise)
      throws Exception {
    GameplayWebSocketDriver first = openReady(factory, firstConnectionId, admission);
    try {
      firstSessionExercise.accept(first);
      List<String> firstResponses = List.copyOf(first.responses());
      GameplayWebSocketDriver takeover = openReady(factory, takeoverConnectionId, admission);
      return new TakeoverScenario(firstResponses, first, takeover);
    } catch (Exception ex) {
      closeQuietly(first, ex);
      throw ex;
    }
  }

  public static TakeoverScenario takeoverAfterReady(
      DriverFactory factory,
      String firstConnectionId,
      String takeoverConnectionId,
      String readyText,
      DriverExercise firstSessionExercise)
      throws Exception {
    return takeoverAfterReady(
        factory,
        firstConnectionId,
        takeoverConnectionId,
        demoAdmission(readyText),
        firstSessionExercise);
  }

  public static LoginThenPlayScenario loginThenAttemptPlay(
      DriverFactory factory,
      String connectionId,
      Admission admission,
      DriverExercise playOutcomeAssertion)
      throws Exception {
    GameplayWebSocketDriver driver = factory.open(connectionId);
    try {
      driver.login(admission.email(), admission.password());
      attemptPlay(driver, admission);
      playOutcomeAssertion.accept(driver);
      return new LoginThenPlayScenario(driver);
    } catch (Exception ex) {
      closeQuietly(driver, ex);
      throw ex;
    }
  }

  public static LoginThenPlayScenario loginThenAttemptPlay(
      DriverFactory factory,
      String connectionId,
      String readyText,
      DriverExercise playOutcomeAssertion)
      throws Exception {
    return loginThenAttemptPlay(
        factory, connectionId, demoAdmission(readyText), playOutcomeAssertion);
  }

  private static void enterAdmitted(GameplayWebSocketDriver driver, Admission admission)
      throws Exception {
    driver.login(admission.email(), admission.password());
    if (admission.characterName() == null || admission.characterName().isBlank()) {
      driver.play(admission.world());
      return;
    }
    driver.play(admission.world(), admission.characterName());
  }

  private static void enterReady(GameplayWebSocketDriver driver, Admission admission)
      throws Exception {
    enterAdmitted(driver, admission);
    driver.lookUntilReady(admission.readyText());
  }

  private static void attemptPlay(GameplayWebSocketDriver driver, Admission admission)
      throws Exception {
    if (admission.characterName() == null || admission.characterName().isBlank()) {
      driver.send("PLAY " + admission.world());
      return;
    }
    driver.send("PLAY " + admission.world() + " " + admission.characterName());
  }

  private static void closeQuietly(
      GameplayWebSocketDriver first,
      GameplayWebSocketDriver second,
      GameplayWebSocketDriver third,
      Exception root) {
    closeQuietly(first, root);
    closeQuietly(second, root);
    closeQuietly(third, root);
  }

  private static void closeQuietly(
      GameplayWebSocketDriver first, GameplayWebSocketDriver second, Exception root) {
    closeQuietly(first, root);
    closeQuietly(second, root);
  }

  private static void closeQuietly(GameplayWebSocketDriver driver, Exception root) {
    try {
      driver.close();
    } catch (Exception closeEx) {
      root.addSuppressed(closeEx);
    }
  }
}
