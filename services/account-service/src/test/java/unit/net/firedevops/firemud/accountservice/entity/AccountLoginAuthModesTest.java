package net.firedevops.firemud.accountservice.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AccountLoginAuthModesTest {
  @Test
  void normalizeWritesAcceptedModeCombinationsInCanonicalOrder() {
    assertThat(AccountLoginAuthModes.normalize("email_otp,password"))
        .isEqualTo("PASSWORD,EMAIL_OTP");
  }

  @Test
  void normalizeRejectsAnEmptyRequestedModeSet() {
    assertThatThrownBy(() -> AccountLoginAuthModes.normalize(Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Account must have at least one login authentication mode");
  }
}
