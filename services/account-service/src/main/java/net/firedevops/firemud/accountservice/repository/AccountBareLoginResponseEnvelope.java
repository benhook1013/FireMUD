package net.firedevops.firemud.accountservice.repository;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.security.AccountEncryptedEnvelope;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;

/** Exact persisted ciphertext plus the binding required for authenticated bare-LOGIN recovery. */
public record AccountBareLoginResponseEnvelope(
    UUID operationId, AccountEnvelopeBinding binding, AccountEncryptedEnvelope envelope) {

  public AccountBareLoginResponseEnvelope {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(envelope, "envelope");
    if (binding.operationKind() != AccountEnvelopeBinding.OperationKind.BARE_LOGIN_EXCHANGE
        || !operationId.toString().equals(binding.operationId())
        || envelope.purpose() != AccountEnvelopePurpose.BARE_LOGIN_RESPONSE) {
      throw new IllegalArgumentException("Bare LOGIN response envelope binding is invalid");
    }
  }
}
