package net.firedevops.firemud.accountservice.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AccountIdentityProvenanceTest {
  @ParameterizedTest
  @EnumSource(AccountIdentityProvenance.class)
  void acceptsEveryPersistedCanonicalAccountIdentityProvenance(
      AccountIdentityProvenance provenance) {
    assertTrue(AccountIdentityProvenance.isAccepted(provenance));
  }

  @Test
  void doesNotTreatAbsentProvenanceAsAccepted() {
    assertFalse(AccountIdentityProvenance.isAccepted(null));
  }
}
