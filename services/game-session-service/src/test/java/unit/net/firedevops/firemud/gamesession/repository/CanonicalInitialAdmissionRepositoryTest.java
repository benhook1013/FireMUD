package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CanonicalInitialAdmissionRepositoryTest {
  @Test
  void pointerAndAuditVersionsUseValueEqualityBeyondLongCache() {
    Long attemptVersion = Long.valueOf(129L);
    Long pointerVersion = Long.valueOf(129L);
    Long auditVersion = Long.valueOf(129L);

    assertThat(attemptVersion).isNotSameAs(pointerVersion);
    assertThat(pointerVersion).isNotSameAs(auditVersion);
    assertThat(
            CanonicalInitialAdmissionRepository.pointerVersionMatches(
                attemptVersion, pointerVersion))
        .isTrue();
    assertThat(
            CanonicalInitialAdmissionRepository.pointerVersionMatches(attemptVersion, auditVersion))
        .isTrue();
  }

  @Test
  void pointerAndAuditVersionComparisonRejectsNullMismatch() {
    assertThat(CanonicalInitialAdmissionRepository.pointerVersionMatches(null, 129L)).isFalse();
    assertThat(CanonicalInitialAdmissionRepository.pointerVersionMatches(129L, null)).isFalse();
  }
}
