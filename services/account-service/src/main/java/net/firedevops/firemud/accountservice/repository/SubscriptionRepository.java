package net.firedevops.firemud.accountservice.repository;

import net.firedevops.firemud.accountservice.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

  @Modifying
  @Transactional
  @Query(
      "delete from Subscription s " + "where s.account.id = :accountId and s.tenantId = :tenantId")
  void deleteByAccountId(@Param("accountId") Long accountId, @Param("tenantId") Long tenantId);
}
