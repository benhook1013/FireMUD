package net.firedevops.firemud.accountservice.repository;

import java.security.MessageDigest;
import java.util.Objects;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceOperation.Lifecycle;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;

/** Exact committed Account source receipt and its purpose-bound encrypted response envelope. */
public record AccountCommittedConnectSource(
    AccountConnectTokenIssuanceOperation operation,
    AccountConnectTokenResponseEnvelope responseEnvelope) {

  public AccountCommittedConnectSource {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(responseEnvelope, "responseEnvelope");
    AccountEnvelopeBinding binding = responseEnvelope.binding();
    if (operation.lifecycle() != Lifecycle.COMMITTED
        || !"SUCCESS".equals(operation.outcomeCode())
        || operation.tokenIdentity() == null
        || operation.tokenIdentity().isBlank()
        || operation.tokenHash() == null
        || operation.contextEvidenceDigest() == null
        || operation.authorityTupleDigest() == null
        || operation.issuanceFenceDigest() == null
        || operation.postconditionDigest() == null
        || !operation.operationId().equals(responseEnvelope.operationId())
        || binding.operationKind() != AccountEnvelopeBinding.OperationKind.CONNECT_TOKEN_ISSUANCE
        || !operation.operationId().toString().equals(binding.operationId())
        || !operation.requestId().equals(binding.requestId())
        || !Long.toString(operation.accountId()).equals(binding.accountId())
        || !operation.tenantId().toString().equals(binding.tenantId())
        || !operation
            .connectScopeHash()
            .equals(AccountJoinDigest.tokenHash(binding.connectScopeId()))
        || !sameDigest(operation.requestDigest(), binding.requestDigest())
        || !sameDigest(operation.contextEvidenceDigest(), binding.contextEvidenceDigest())
        || !sameDigest(operation.authorityTupleDigest(), binding.authorityTupleDigest())
        || !sameDigest(operation.issuanceFenceDigest(), binding.issuanceFenceDigest())
        || !sameDigest(operation.postconditionDigest(), binding.postconditionDigest())) {
      throw new IllegalArgumentException(
          "Committed Account connect source evidence is incomplete or mismatched");
    }
  }

  @Override
  public String toString() {
    return "AccountCommittedConnectSource[operationId="
        + operation.operationId()
        + ", lifecycle="
        + operation.lifecycle()
        + ", encryptedResponse=<redacted>]";
  }

  private static boolean sameDigest(byte[] left, byte[] right) {
    return left != null && right != null && MessageDigest.isEqual(left, right);
  }
}
