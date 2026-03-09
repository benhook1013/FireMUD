package net.firedevops.firemud.accountservice.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import net.firedevops.firemud.accountservice.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
  Optional<PasswordResetToken> findByTokenAndTenantId(String token, Long tenantId);

  @Modifying
  @Transactional
  @Query("delete from PasswordResetToken t where t.expiresAt < :now")
  void deleteExpired(@Param("now") LocalDateTime now);
}
