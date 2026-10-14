package net.firedevops.firemud.gamesession.service;

import java.util.Optional;

/**
 * External recovery/maintenance authority, deliberately without a production provider. Neither
 * Redis presence, a SQL receipt nor process-local pause state may implement this qualification.
 */
@FunctionalInterface
public interface InitialGameplayLoginCoordinationQualification {
  Optional<Lease> acquire(String globalFamilyScope, String protectedBindingId);

  /**
   * A current authority-issued lease excluding reset, restore and promotion for the exact global
   * family and protected binding. Exclusion MUST remain held until close(), including the entire
   * closure continuation and its Account dispatch. requireCurrent() fails if qualification was
   * lost; periodic checks alone cannot provide the required exclusion. fence is positive and
   * belongs to that external authority, never a process-local counter.
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
