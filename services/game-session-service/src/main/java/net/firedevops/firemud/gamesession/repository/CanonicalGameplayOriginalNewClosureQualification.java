package net.firedevops.firemud.gamesession.repository;

import java.util.Optional;

/**
 * External maintenance authority for original-candidate closure, without a production provider. SQL
 * receipts, Redis presence and process-local flags cannot supply this qualification.
 */
@FunctionalInterface
public interface CanonicalGameplayOriginalNewClosureQualification {
  Optional<Lease> acquire(String familyScope, String protectedBindingId);

  /**
   * Excludes reset, restore and promotion for the exact session family and binding until close,
   * including the entire Account dispatch and its reverse-owner read. Compatible protected reads
   * may hold concurrent leases; no lease grants candidate creation or gameplay admission. Periodic
   * currentness checks without continuously held external exclusion are insufficient.
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
