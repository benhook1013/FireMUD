package net.firedevops.firemud.gamesession.dto;

/** Result of attempting to enqueue a command. */
public record CommandEnqueueResult(boolean accepted, String errorCode, String errorMessage) {
  public static CommandEnqueueResult success() {
    return new CommandEnqueueResult(true, null, null);
  }

  public static CommandEnqueueResult failure(String errorCode, String errorMessage) {
    return new CommandEnqueueResult(false, errorCode, errorMessage);
  }

  public boolean hasError() {
    return errorCode != null;
  }
}
