package net.firedevops.firemud.accountservice.repository;

import net.firedevops.firemud.accountservice.entity.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProfileRepository extends JpaRepository<Profile, Long> {
  java.util.Optional<Profile> findByAccountIdAndTenantId(Long accountId, Long tenantId);
}
