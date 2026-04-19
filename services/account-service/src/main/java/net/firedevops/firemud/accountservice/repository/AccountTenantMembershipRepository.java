package net.firedevops.firemud.accountservice.repository;

import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountTenantMembershipRepository
    extends JpaRepository<AccountTenantMembership, Long> {
  Optional<AccountTenantMembership> findByAccountIdAndTenantId(Long accountId, Long tenantId);

  boolean existsByAccountIdAndTenantId(Long accountId, Long tenantId);

  boolean existsByAccountId(Long accountId);

  void deleteByAccountIdAndTenantId(Long accountId, Long tenantId);
}
