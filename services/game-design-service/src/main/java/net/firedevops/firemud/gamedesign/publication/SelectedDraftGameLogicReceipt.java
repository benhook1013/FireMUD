package net.firedevops.firemud.gamedesign.publication;

import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeSettlementEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;

/** Derived evidence outside the authored preimage; canonical integrity grants no authority. */
public record SelectedDraftGameLogicReceipt(
    AuthoredDraftPublishSelection selection,
    GameLogicIntakeAuthorizationBinding authorization,
    AccountGameLogicIntakeSettlementEvidence receipt) {
  public static final int MAX_SELECTION_BYTES = 8 * 1024 * 1024;

  public SelectedDraftGameLogicReceipt {
    Objects.requireNonNull(receipt, "receipt");
    requireExactSelection(selection, authorization);
    requireRetainedTerminal(authorization, receipt.terminal());
    // Enforce each existing codec's closed payload limit before storage.
    authorization.canonicalBytes();
    receipt.canonicalBytes();
  }

  static void requireRetainedTerminal(
      GameLogicIntakeAuthorizationBinding authorization,
      GameLogicGameplayRuleIntakeTerminal terminal) {
    if (terminal.outcome() != GameLogicGameplayRuleIntakeTerminal.Outcome.RETAINED
        || !Arrays.equals(authorization.canonicalBytes(), terminal.authorizationBytes())
        || !authorization.digest().equals(terminal.authorizationDigest())
        || !Arrays.equals(
            authorization.source().canonicalBytes(), terminal.selectedSourceBytes())) {
      throw new IllegalArgumentException("Exact RETAINED Account settlement required");
    }
  }

  public String workflowIdentity() {
    return PublicationDigestRequestBinding.full(
            selection.intent().canonicalTenantId().toString(),
            Long.toString(selection.target().gameDesignVersionRowId()),
            selection.intent().publishRequestId())
        .derivedWorkflowIdentity();
  }

  public static void requireExactSelection(
      AuthoredDraftPublishSelection selection, GameLogicIntakeAuthorizationBinding authorization) {
    Objects.requireNonNull(selection, "selection");
    Objects.requireNonNull(authorization, "authorization");
    if (selection.canonicalBytes().length > MAX_SELECTION_BYTES)
      throw new IllegalArgumentException("Selected publication evidence exceeds 8 MiB");
    authorization.canonicalBytes();
    if (!selection.target().canonicalTenantId().equals(authorization.tenantId())
        || !selection.target().canonicalVersionId().equals(authorization.versionId())
        || !Arrays.equals(
            selection.selectedCommit().canonicalBytes(),
            authorization.source().binding().canonicalBytes())) {
      throw new IllegalArgumentException("Intake differs from complete selected Draft binding");
    }
  }

  public void requireExactRequest(
      AuthoredDraftPublishSelection expectedSelection,
      GameLogicIntakeAuthorizationBinding expectedAuthorization) {
    if (!Arrays.equals(selection.canonicalBytes(), expectedSelection.canonicalBytes())
        || !Arrays.equals(authorization.canonicalBytes(), expectedAuthorization.canonicalBytes())) {
      throw new IllegalStateException("Selected Game Logic receipt identity conflict");
    }
  }
}
