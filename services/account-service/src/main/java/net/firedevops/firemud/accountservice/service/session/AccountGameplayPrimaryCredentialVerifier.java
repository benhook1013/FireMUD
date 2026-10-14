package net.firedevops.firemud.accountservice.service.session;

import java.util.Objects;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCanonicalLoginOwner.CredentialVerification;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCanonicalLoginOwner.CredentialVerifier;

/**
 * Unregistered adapter to Account's existing password/OTP authority. It issues no token or session.
 *
 * <p>Canonical verification performs two KDF verifications using the existing configured Account
 * password/OTP source profile, padding with non-authorizing process-local random decoy evidence.
 * Representative operational timing parity remains separate proof; this adapter is not activated.
 */
public final class AccountGameplayPrimaryCredentialVerifier implements CredentialVerifier {
  private final AccountServiceImpl primaryAuthority;

  public AccountGameplayPrimaryCredentialVerifier(AccountServiceImpl primaryAuthority) {
    this.primaryAuthority =
        Objects.requireNonNull(primaryAuthority, "Account primary authority required");
  }

  @Override
  public void verifyAbsentAccountCredential(String presentedCredential) {
    primaryAuthority.verifyAbsentGameplayPrimaryCredential(presentedCredential);
  }

  @Override
  public CredentialVerification verify(Account lockedAccount, String presentedCredential) {
    return primaryAuthority.verifyGameplayPrimaryCredential(lockedAccount, presentedCredential);
  }

  @Override
  public void consumeOneTimeCredential(Account lockedAccount, CredentialVerification verification) {
    primaryAuthority.consumeGameplayPrimaryCredential(lockedAccount, verification);
  }
}
