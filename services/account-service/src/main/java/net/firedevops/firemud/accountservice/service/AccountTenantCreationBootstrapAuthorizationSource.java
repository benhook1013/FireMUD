package net.firedevops.firemud.accountservice.service;

import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.dto.AccountTenantCreationBootstrapResult;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;

/**
 * Account-owned authorization/source participant required for a new creator bootstrap commit.
 *
 * <p>The implementation must authenticate the initiating Account and exact source operation, verify
 * the current Account authorization named by the creator evidence, and hold or participate in that
 * source's commit fence until {@code mutationWhileCurrent} returns and the enclosing Account
 * transaction commits. The immutable DTO alone is never authorization. No production implementation
 * is registered by this storage slice, so new creator bootstrap attempts are default-denied.
 */
public interface AccountTenantCreationBootstrapAuthorizationSource {
  AccountTenantCreationBootstrapResult withCurrentAuthorizationAndSourceParticipation(
      FreshTenantCreatorEvidence evidence,
      Supplier<AccountTenantCreationBootstrapResult> mutationWhileCurrent);
}
