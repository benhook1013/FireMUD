package net.firedevops.firemud.accountservice.repository;

/** Deterministic, non-production keyed-binding fixture shared by the Account owner proofs. */
public final class AccountGameplayCredentialRequestBindingFixture {
  private AccountGameplayCredentialRequestBindingFixture() {}

  public static AccountGameplayCredentialRequestBinding binding() {
    return new AccountGameplayCredentialRequestBinding(
        1, "test-account-login-key", "ab".repeat(32));
  }
}
