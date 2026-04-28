package net.firedevops.firemud.tcpproxy.testsupport;

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
