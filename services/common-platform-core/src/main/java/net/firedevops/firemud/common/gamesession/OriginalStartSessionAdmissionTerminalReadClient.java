package net.firedevops.firemud.common.gamesession;

/** Reads one exact durable terminal result for Account's original StartSession protection. */
public interface OriginalStartSessionAdmissionTerminalReadClient {
  /** Returns only a complete COMMITTED or positively fenced ABORTED owner result. */
  OriginalStartSessionAdmissionTerminalResult read(
      OriginalStartSessionAdmissionTerminalRequest request);
}
