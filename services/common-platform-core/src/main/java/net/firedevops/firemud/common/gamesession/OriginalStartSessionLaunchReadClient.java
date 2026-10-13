package net.firedevops.firemud.common.gamesession;

/**
 * Reads exact original Account StartSession launch and retained tuple evidence from Game Session.
 */
public interface OriginalStartSessionLaunchReadClient {
  /** Reads one exact original launch without granting currentness or lifecycle authority. */
  OriginalStartSessionLaunchReadEvidence.Result read(
      OriginalStartSessionLaunchReadEvidence.Request request);
}
