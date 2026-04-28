package net.firedevops.firemud.gamesession.testsupport;

import java.util.List;

/** Shared chained-gameplay websocket scenario helpers for multi-actor proof. */
public final class GameplayWebSocketScenarios {

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

  private static void enterReady(GameplayWebSocketDriver driver, Admission admission)
      throws Exception {
    if (admission.characterName() == null || admission.characterName().isBlank()) {
      driver.enterGameplayAndWaitReady(
          admission.email(), admission.password(), admission.world(), admission.readyText());
      return;
    }
    driver.enterGameplayAndWaitReady(
        admission.email(),
        admission.password(),
        admission.world(),
        admission.characterName(),
        admission.readyText());
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
