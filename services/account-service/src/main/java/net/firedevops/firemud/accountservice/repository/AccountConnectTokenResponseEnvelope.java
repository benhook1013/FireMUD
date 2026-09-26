package net.firedevops.firemud.accountservice.repository;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;

/**
 * Exact persisted ciphertext plus the caller-verified binding needed for authenticated recovery.
 */
public record AccountConnectTokenResponseEnvelope(
    UUID operationId, AccountEnvelopeBinding binding, AccountEncryptedEnvelope envelope) {

  public AccountConnectTokenResponseEnvelope {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(envelope, "envelope");
    if (binding.operationKind() != AccountEnvelopeBinding.OperationKind.CONNECT_TOKEN_ISSUANCE
        || !operationId.toString().equals(binding.operationId())
        || envelope.purpose() != AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE) {
      throw new IllegalArgumentException("Connect-token response envelope binding is invalid");
    }
  }
}
