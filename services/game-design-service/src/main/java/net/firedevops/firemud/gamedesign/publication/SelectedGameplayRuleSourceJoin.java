package net.firedevops.firemud.gamedesign.publication;

import java.util.Arrays;
import java.util.Objects;

/**
 * Canonical byte equality for the selected Gameplay capture, source read and Game Logic receipt.
 */
final class SelectedGameplayRuleSourceJoin {
  private SelectedGameplayRuleSourceJoin() {}

  static boolean matches(
      GameDesignPublicationOperation operation,
      GameplayRuleSnapshot.Capture capture,
      GameplayRuleSnapshot selectedSnapshot,
      SelectedDraftGameLogicReceipt receipt) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(capture, "capture");
    Objects.requireNonNull(selectedSnapshot, "selectedSnapshot");
    Objects.requireNonNull(receipt, "receipt");

    var selection = operation.account().input().selection();
    return Arrays.equals(capture.operation().canonicalBytes(), operation.canonicalBytes())
        && selection.selectedCommit().equals(capture.snapshot().binding())
        && Arrays.equals(capture.snapshot().canonicalBytes(), selectedSnapshot.canonicalBytes())
        && Arrays.equals(
            capture.snapshot().canonicalBytes(), receipt.authorization().source().canonicalBytes())
        && Arrays.equals(selection.canonicalBytes(), receipt.selection().canonicalBytes())
        && selection.selectedCommit().equals(selectedSnapshot.binding())
        && Arrays.equals(
            selectedSnapshot.canonicalBytes(), receipt.authorization().source().canonicalBytes());
  }
}
