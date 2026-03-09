package net.firedevops.firemud.accountservice.repository;

import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
  Optional<Account> findByTenantIdAndUsername(Long tenantId, String username);

  Optional<Account> findByTenantIdAndEmail(Long tenantId, String email);
}
