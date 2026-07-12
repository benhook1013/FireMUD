package net.firedevops.firemud.gamesession.command.text;

/** Canonical unavailable outcome until authored action effects have an execution handler. */
public final class AuthoredActionExecutionOutcome {
  public static final String CODE = "AUTHORED_ACTION_EXECUTION_UNAVAILABLE";
  public static final String MESSAGE = "Authored action execution is not available";

  private AuthoredActionExecutionOutcome() {}
}
