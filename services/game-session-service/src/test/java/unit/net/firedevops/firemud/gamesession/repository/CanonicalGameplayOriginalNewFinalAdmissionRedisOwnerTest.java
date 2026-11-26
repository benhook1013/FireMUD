package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CanonicalGameplayOriginalNewFinalAdmissionRedisOwnerTest {

  @Test
  void closeReadbackRetainsReturnedBytesAfterSuccessfulClose() {
    byte[] readback = {1, 2, 3};

    CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.closeReadbackOrWipe(
        () -> assertThat(readback).containsExactly(1, 2, 3), readback);

    assertThat(readback).containsExactly(1, 2, 3);
  }

  @Test
  void closeReadbackWipesUnreturnedBytesWithoutSwallowingCloseFailure() {
    byte[] readback = {1, 2, 3};
    var closeFailure = new IllegalStateException("controlled local close failure");

    assertThat(
            assertThrows(
                IllegalStateException.class,
                () ->
                    CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.closeReadbackOrWipe(
                        () -> {
                          throw closeFailure;
                        },
                        readback)))
        .isSameAs(closeFailure);

    assertThat(readback).containsExactly(0, 0, 0);
  }
}
