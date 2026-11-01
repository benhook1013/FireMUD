package net.firedevops.firemud.accountservice.service.session;

import java.util.Optional;

/**
 * Account creation-specific use of the external recovery/maintenance authority. No production
 * provider is registered; Redis presence, SQL history and process-local locks cannot qualify it.
 */
@FunctionalInterface
public interface AccountTenantCreationAdmissionCoordinationQualification {
  Optional<Lease> acquire(String familyScope, String protectedBindingId);

  /**
   * Holds reset/restore/promotion exclusion for the exact protected binding and both the canonical
   * token comparison and creation receipt family until close. The caller retains this exclusion
   * through final SQL admission and any original dispatch within the callback. A periodic current
   * check without held exclusion is insufficient. The positive fence belongs to that authority.
   */
  interface Lease extends AutoCloseable {
    String scope();

    String protectedBindingId();

    long fence();

    void requireCurrent();

    @Override
    void close();
  }
}
