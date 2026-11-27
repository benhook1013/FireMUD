package net.firedevops.firemud.accountservice.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountLeaveMembershipBindingTest {
  @Test
  void exactBindingIsStableAndEveryIdentityComponentChangesTheDigest() {
    UUID account = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    var binding = new AccountLeaveMembershipBinding("request:é", account, tenant, "caller:绑定");
    assertThat(
            new AccountLeaveMembershipBinding(
                    binding.requestId(), account, tenant, binding.callerBinding())
                .digest())
        .isEqualTo(binding.digest());
    assertThat(
            new AccountLeaveMembershipBinding("different", account, tenant, binding.callerBinding())
                .digest())
        .isNotEqualTo(binding.digest());
    assertThat(
            new AccountLeaveMembershipBinding(
                    binding.requestId(), UUID.randomUUID(), tenant, binding.callerBinding())
                .digest())
        .isNotEqualTo(binding.digest());
    assertThat(
            new AccountLeaveMembershipBinding(
                    binding.requestId(), account, UUID.randomUUID(), binding.callerBinding())
                .digest())
        .isNotEqualTo(binding.digest());
    assertThat(
            new AccountLeaveMembershipBinding(binding.requestId(), account, tenant, "different")
                .digest())
        .isNotEqualTo(binding.digest());
  }

  @Test
  void refusesAbsentOrMalformedCallerAndRequestIdentity() {
    UUID account = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    for (String invalid :
        new String[] {
          "", " ", "x".repeat(257), String.valueOf((char) 0xD800), "a" + (char) 0 + "b"
        }) {
      assertThatThrownBy(
              () -> new AccountLeaveMembershipBinding(invalid, account, tenant, "caller"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new AccountLeaveMembershipBinding("request", account, tenant, " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new AccountLeaveMembershipBinding("request", new UUID(0, 0), tenant, "caller"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
