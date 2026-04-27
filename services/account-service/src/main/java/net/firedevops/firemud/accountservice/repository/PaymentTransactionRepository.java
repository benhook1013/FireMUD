package net.firedevops.firemud.accountservice.repository;

import net.firedevops.firemud.accountservice.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

  @Modifying
  @Transactional
  @Query(
      "delete from PaymentTransaction pt "
          + "where pt.account.id = :accountId and pt.tenantId = :tenantId")
  void deleteByAccountId(@Param("accountId") Long accountId, @Param("tenantId") Long tenantId);

  @Modifying
  @Transactional
  @Query("delete from PaymentTransaction pt where pt.account.id = :accountId")
  void deleteByAccountId(@Param("accountId") Long accountId);
}
