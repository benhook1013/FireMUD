package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.accountservice.repository.AccountHostingBillingPolicyRepository.Policy;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountHostingBillingPolicyRepositoryTest {
  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void policyDigestBindsExactPriceVersionQuantityAmountAndCurrency() {
    var initial = new Policy("price_exact", 1, 1, 2400, "usd");
    for (var changed :
        java.util.List.of(
            new Policy("price_other", 1, 1, 2400, "usd"),
            new Policy("price_exact", 2, 1, 2400, "usd"),
            new Policy("price_exact", 1, 2, 2400, "usd"),
            new Policy("price_exact", 1, 1, 2500, "usd"),
            new Policy("price_exact", 1, 1, 2400, "eur"))) {
      assertThat(changed.digest()).isNotEqualTo(initial.digest());
    }
  }

  @Test
  void absentCurrentPolicyRefusesWithoutInventingPriceBalanceTaxOrCurrencyDefault() {
    var dsl = mock(DSLContext.class);
    var policies = new AccountHostingBillingPolicyRepository(dsl);
    var quote = new AccountHostingPlanQuote("hosting", "price_exact", 1, 10, 1, 100);
    assertThatThrownBy(() -> policies.requireCurrent(quote))
        .hasMessageContaining("owner transaction");
    verifyNoInteractions(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> policies.requireCurrent(quote))
        .hasMessageContaining("policy unavailable");
    assertThatThrownBy(() -> new Policy("price_exact", 1, 1, 0, "usd"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Policy("price_exact", 1, 1, 2400, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
