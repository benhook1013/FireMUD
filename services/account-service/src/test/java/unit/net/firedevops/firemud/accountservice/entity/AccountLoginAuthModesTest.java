package net.firedevops.firemud.accountservice.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountLoginAuthModesTest {
  @Test
  void normalizeWritesAcceptedModeCombinationsInCanonicalOrder() {
    assertThat(AccountLoginAuthModes.normalize("email_otp,password"))
        .isEqualTo("PASSWORD,EMAIL_OTP");
  }
}
