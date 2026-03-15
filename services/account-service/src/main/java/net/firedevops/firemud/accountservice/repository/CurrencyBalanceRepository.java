package net.firedevops.firemud.accountservice.repository;

import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.CurrencyBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CurrencyBalanceRepository extends JpaRepository<CurrencyBalance, Long> {
  Optional<CurrencyBalance> findByTenantIdAndAccountIdAndCurrencyCode(
      Long tenantId, Long accountId, String currencyCode);
}
