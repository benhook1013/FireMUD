package net.firedevops.firemud.gamedesign.publication;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;

/** Empty new-Draft baseline receipt, distinct from every actual authored commit. */
public record RealmPolicyGenesis(TargetProof target, UUID receiptId, String creationTransactionId) {
  public RealmPolicyGenesis {
    if (target == null
        || receiptId == null
        || new UUID(0, 0).equals(receiptId)
        || creationTransactionId == null
        || !creationTransactionId.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException("Exact persisted new-Draft genesis identity required");
    }
  }

  public String canonicalJson() {
    return RealmPolicySource.canonical(
        Map.of(
            "schema",
            "game-design-realm-policy-genesis/v1",
            "receiptId",
            receiptId.toString(),
            "creationTransactionId",
            creationTransactionId,
            "canonicalTenantId",
            target.canonicalTenantId().toString(),
            "canonicalVersionId",
            target.canonicalVersionId().toString(),
            "gameDesignVersionRowId",
            Long.toString(target.gameDesignVersionRowId()),
            "gameDesignVersionTenantKey",
            target.gameDesignVersionTenantKey(),
            "sourceGameRowId",
            Long.toString(target.sourceGameRowId()),
            "sourceGameTenantKey",
            target.sourceGameTenantKey(),
            "sourceProvenanceKind",
            target.sourceProvenanceKind()));
  }

  public byte[] canonicalBytes() {
    return canonicalJson().getBytes(StandardCharsets.UTF_8);
  }
}
