package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import org.junit.jupiter.api.Test;

class AccountTenantCreationOwnerChecksTest {
  @Test
  void missingNonStringAndBlankSignerKeyIdsUseTheOwningDenial() {
    var denied = new IllegalStateException("Owning tenant-creation denial");
    byte[] absent = AccountControlUiAuthority.canonical(Map.of());
    assertThatThrownBy(() -> AccountTenantCreationOwnerChecks.originalKid(absent, () -> denied))
        .isSameAs(denied);
    for (Object invalid : new Object[] {7L, true, List.of("kid"), "", " \t\n"}) {
      byte[] receipt = AccountControlUiAuthority.canonical(Map.of("kid", invalid));
      assertThatThrownBy(() -> AccountTenantCreationOwnerChecks.originalKid(receipt, () -> denied))
          .isSameAs(denied);
    }
  }

  @Test
  void nonblankSignerKeyIdIsReturnedWithoutNormalization() {
    byte[] receipt = AccountControlUiAuthority.canonical(Map.of("kid", " original-key "));
    assertThat(
            AccountTenantCreationOwnerChecks.originalKid(
                receipt, () -> new IllegalStateException("Owning tenant-creation denial")))
        .isEqualTo(" original-key ");
  }
}
