package net.firedevops.firemud.repository;

import java.util.Optional;
import net.firedevops.firemud.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, Long> {
  Optional<EmailVerificationToken> findByTokenAndTenantId(String token, Long tenantId);
}
