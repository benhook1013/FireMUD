package net.firedevops.firemud.gamesession.dto;

/** Result of attempting to enqueue a command. */
public record CommandEnqueueResult(
    boolean accepted, String commandId, String errorCode, String errorMessage) {
  public static CommandEnqueueResult success() {
    return success(null);
  }

  public static CommandEnqueueResult success(String commandId) {
    return new CommandEnqueueResult(true, commandId, null, null);
  }

  public static CommandEnqueueResult failure(String errorCode, String errorMessage) {
    return new CommandEnqueueResult(false, null, errorCode, errorMessage);
  }

  public static CommandEnqueueResult failure(
      String commandId, String errorCode, String errorMessage) {
    return new CommandEnqueueResult(false, commandId, errorCode, errorMessage);
  }

  public boolean hasError() {
    return errorCode != null;
  }
}
