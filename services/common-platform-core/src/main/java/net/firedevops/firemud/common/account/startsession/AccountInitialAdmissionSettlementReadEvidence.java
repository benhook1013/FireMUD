package net.firedevops.firemud.common.account.startsession;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;

/**
 * Complete terminal binding for Account settlement. Integrity checks do not authenticate Game
 * Session or establish that the referenced owner transaction committed.
 */
public record AccountInitialAdmissionSettlementReadEvidence(
    UUID readRequestId,
    AccountStartSessionAdmissionProtectionEvidence protection,
    GameSessionCanonicalInitialAdmissionOwnerProof terminal) {
  public static final int MAX_OWNER_PROOF_BYTES = 66_048;

  public AccountInitialAdmissionSettlementReadEvidence {
    new AccountInitialAdmissionSettlementReadInput(readRequestId, protection);
    Objects.requireNonNull(terminal, "Game Session terminal required");
    if (terminal.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING) {
      throw new IllegalArgumentException("Nonterminal Game Session proof cannot settle protection");
    }
    if (!Arrays.equals(
        protection.request().worldAdmissionHoldIdentityBytes(),
        terminal.holdIdentity().canonicalBytes())) {
      throw new IllegalArgumentException(
          "Game Session terminal changed the complete protected hold");
    }
    if (GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(terminal).length
        > MAX_OWNER_PROOF_BYTES) {
      throw new IllegalArgumentException("Game Session terminal exceeds its proof byte limit");
    }
  }

  public AccountInitialAdmissionSettlementReadInput input() {
    return new AccountInitialAdmissionSettlementReadInput(readRequestId, protection);
  }
}
