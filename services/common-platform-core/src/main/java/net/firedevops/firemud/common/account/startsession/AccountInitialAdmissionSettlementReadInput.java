package net.firedevops.firemud.common.account.startsession;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact lookup-only selector; retained Account values do not authenticate their producer. */
public record AccountInitialAdmissionSettlementReadInput(
    UUID readRequestId, AccountStartSessionAdmissionProtectionEvidence protection) {
  public AccountInitialAdmissionSettlementReadInput {
    if (readRequestId == null || new UUID(0L, 0L).equals(readRequestId)) {
      throw new IllegalArgumentException("Non-nil admission settlement read ID required");
    }
    Objects.requireNonNull(protection, "Complete Account admission protection required");
  }

  public String targetNamespace() {
    return protection.request().worldAdmissionHoldIdentity().request().targetNamespace();
  }

  public void requireTargetNamespace(String expectedNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(expectedNamespace)
        || !expectedNamespace.equals(targetNamespace())) {
      throw new IllegalArgumentException("Admission settlement read namespace differs");
    }
  }
}
