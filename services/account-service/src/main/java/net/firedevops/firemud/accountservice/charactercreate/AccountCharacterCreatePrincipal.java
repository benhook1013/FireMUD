package net.firedevops.firemud.accountservice.charactercreate;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier;

/**
 * Private-composition identity capability backed by the existing signed bootstrap, persisted
 * Account provenance and active-session verifier. It supplies identity only, never creation
 * permission.
 */
public final class AccountCharacterCreatePrincipal {
  private final AccountPlayerBootstrapCallerVerifier verifier;
  private final String bootstrapToken;
  private final UUID originalAccount;

  private AccountCharacterCreatePrincipal(
      AccountPlayerBootstrapCallerVerifier verifier, String bootstrapToken, UUID originalAccount) {
    this.verifier = verifier;
    this.bootstrapToken = bootstrapToken;
    this.originalAccount = originalAccount;
  }

  public static AccountCharacterCreatePrincipal authenticate(
      AccountPlayerBootstrapCallerVerifier verifier, String bootstrapToken) {
    AccountCharacterCreateDelegationBindings.outsideSql();
    Objects.requireNonNull(verifier);
    UUID account = Objects.requireNonNull(verifier.requireCaller(bootstrapToken)).accountUuid();
    return new AccountCharacterCreatePrincipal(verifier, bootstrapToken, account);
  }

  public UUID authenticateCurrentAccount() {
    return authenticateCurrentCaller().accountUuid();
  }

  AccountPlayerBootstrapCallerVerifier.VerifiedCaller authenticateCurrentCaller() {
    AccountCharacterCreateDelegationBindings.outsideSql();
    var caller = Objects.requireNonNull(verifier.requireCaller(bootstrapToken));
    UUID current = caller.accountUuid();
    if (!originalAccount.equals(current)) {
      throw new IllegalStateException("Bootstrap Account identity changed");
    }
    return caller;
  }

  @Override
  public String toString() {
    return "AccountCharacterCreatePrincipal[credential=<redacted>]";
  }
}
