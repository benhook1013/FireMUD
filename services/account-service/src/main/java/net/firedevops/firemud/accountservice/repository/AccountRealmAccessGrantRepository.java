package net.firedevops.firemud.accountservice.repository;

import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.AccountRealmAccessGrant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRealmAccessGrantRepository
    extends JpaRepository<AccountRealmAccessGrant, Long> {
  Optional<AccountRealmAccessGrant> findByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
      Long accountId, Long tenantId, String worldSlug, String realmSlug);

  boolean existsByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
      Long accountId, Long tenantId, String worldSlug, String realmSlug);

  void deleteByAccountIdAndTenantIdAndWorldSlugAndRealmSlug(
      Long accountId, Long tenantId, String worldSlug, String realmSlug);
}
