package net.firedevops.firemud.accountservice.repository;

import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.ExternalAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ExternalAccountRepository extends JpaRepository<ExternalAccount, Long> {
  Optional<ExternalAccount> findByTenantIdAndProviderAndExternalId(
      Long tenantId, String provider, String externalId);

  boolean existsByTenantIdAndAccountIdAndProvider(Long tenantId, Long accountId, String provider);
}
