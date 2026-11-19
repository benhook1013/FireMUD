package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CanonicalGameplayCapacityOriginTest {
  @Test
  void everyClaimIsExclusiveAndCannotReopen() {
    for (var current : CanonicalGameplayCapacityOrigin.State.values()) {
      for (var target : CanonicalGameplayCapacityOrigin.State.values()) {
        if (target != CanonicalGameplayCapacityOrigin.State.OPEN
            && (current == CanonicalGameplayCapacityOrigin.State.OPEN || current == target)) {
          assertThat(CanonicalGameplayCapacityOrigin.claim(current, target)).isEqualTo(target);
        } else {
          assertThatThrownBy(() -> CanonicalGameplayCapacityOrigin.claim(current, target))
              .isInstanceOf(IllegalStateException.class);
        }
      }
    }
  }

  @Test
  void missingCustodyIsNeverAnOpenOrigin() {
    assertThatThrownBy(
            () ->
                CanonicalGameplayCapacityOrigin.claim(
                    null, CanonicalGameplayCapacityOrigin.State.CLOSURE_CLAIMED))
        .isInstanceOf(NullPointerException.class);
  }
}
