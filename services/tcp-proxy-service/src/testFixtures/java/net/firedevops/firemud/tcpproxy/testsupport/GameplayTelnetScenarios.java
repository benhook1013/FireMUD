package net.firedevops.firemud.tcpproxy.testsupport;

import java.util.List;

/** Shared chained-gameplay telnet scenario helpers for multi-actor proof. */
public final class GameplayTelnetScenarios {

  @FunctionalInterface
  public interface DriverFactory {
    GameplayTelnetDriver open() throws Exception;
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
    void accept(GameplayTelnetDriver driver) throws Exception;
  }

  public record ReconnectScenario(List<String> firstResponses, GameplayTelnetDriver reconnecting)
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

  public record LoginThenPlayScenario(GameplayTelnetDriver driver) implements AutoCloseable {
    public List<String> responses() {
      return driver.responses();
    }

    @Override
    public void close() throws Exception {
      driver.close();
    }
  }

  public record TakeoverScenario(
      List<String> firstResponses, GameplayTelnetDriver first, GameplayTelnetDriver takeover)
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

  public record TwoPlayerScenario(GameplayTelnetDriver actor, GameplayTelnetDriver target)
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
      GameplayTelnetDriver actor, GameplayTelnetDriver target, GameplayTelnetDriver observer)
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

  private GameplayTelnetScenarios() {}

  public static GameplayTelnetDriver openAdmitted(DriverFactory factory, Admission admission)
      throws Exception {
    GameplayTelnetDriver driver = factory.open();
    try {
      driver.awaitInitialGuidance();
      if (admission.characterName() == null || admission.characterName().isBlank()) {
        driver.login(admission.email(), admission.password());
        driver.play(admission.world());
        return driver;
      }
      driver.login(admission.email(), admission.password());
      driver.play(admission.world(), admission.characterName());
      return driver;
    } catch (Exception ex) {
      closeQuietly(driver, ex);
      throw ex;
    }
  }

  public static GameplayTelnetDriver openReady(DriverFactory factory, Admission admission)
      throws Exception {
    GameplayTelnetDriver driver = factory.open();
    try {
      enterReady(driver, admission);
      return driver;
    } catch (Exception ex) {
      closeQuietly(driver, ex);
      throw ex;
    }
  }

  public static TwoPlayerScenario openReadyPair(
      DriverFactory factory, Admission actorAdmission, Admission targetAdmission) throws Exception {
    GameplayTelnetDriver actor = factory.open();
    GameplayTelnetDriver target = factory.open();
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
      Admission actorAdmission,
      Admission targetAdmission,
      Admission observerAdmission)
      throws Exception {
    GameplayTelnetDriver actor = factory.open();
    GameplayTelnetDriver target = factory.open();
    GameplayTelnetDriver observer = factory.open();
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
      DriverFactory factory, Admission admission, DriverExercise firstSessionExercise)
      throws Exception {
    GameplayTelnetDriver first = openReady(factory, admission);
    try {
      firstSessionExercise.accept(first);
      List<String> firstResponses = List.copyOf(first.responses());
      first.close();
      GameplayTelnetDriver reconnecting = openReady(factory, admission);
      return new ReconnectScenario(firstResponses, reconnecting);
    } catch (Exception ex) {
      closeQuietly(first, ex);
      throw ex;
    }
  }

  public static TakeoverScenario takeoverAfterAdmitted(
      DriverFactory factory, Admission admission, DriverExercise firstSessionExercise)
      throws Exception {
    GameplayTelnetDriver first = openAdmitted(factory, admission);
    try {
      firstSessionExercise.accept(first);
      List<String> firstResponses = List.copyOf(first.responses());
      GameplayTelnetDriver takeover = openAdmitted(factory, admission);
      return new TakeoverScenario(firstResponses, first, takeover);
    } catch (Exception ex) {
      closeQuietly(first, ex);
      throw ex;
    }
  }

  public static LoginThenPlayScenario loginThenAttemptPlay(
      DriverFactory factory, Admission admission, DriverExercise playOutcomeAssertion)
      throws Exception {
    GameplayTelnetDriver driver = factory.open();
    try {
      driver.awaitInitialGuidance();
      driver.login(admission.email(), admission.password());
      attemptPlay(driver, admission);
      playOutcomeAssertion.accept(driver);
      return new LoginThenPlayScenario(driver);
    } catch (Exception ex) {
      closeQuietly(driver, ex);
      throw ex;
    }
  }

  private static void enterReady(GameplayTelnetDriver driver, Admission admission)
      throws Exception {
    driver.awaitInitialGuidance();
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

  private static void attemptPlay(GameplayTelnetDriver driver, Admission admission)
      throws Exception {
    if (admission.characterName() == null || admission.characterName().isBlank()) {
      driver.sendLine("PLAY " + admission.world());
      return;
    }
    driver.sendLine("PLAY " + admission.world() + " " + admission.characterName());
  }

  private static void closeQuietly(
      GameplayTelnetDriver first,
      GameplayTelnetDriver second,
      GameplayTelnetDriver third,
      Exception root) {
    closeQuietly(first, root);
    closeQuietly(second, root);
    closeQuietly(third, root);
  }

  private static void closeQuietly(
      GameplayTelnetDriver first, GameplayTelnetDriver second, Exception root) {
    closeQuietly(first, root);
    closeQuietly(second, root);
  }

  private static void closeQuietly(GameplayTelnetDriver driver, Exception root) {
    try {
      driver.close();
    } catch (Exception closeEx) {
      root.addSuppressed(closeEx);
    }
  }
}
