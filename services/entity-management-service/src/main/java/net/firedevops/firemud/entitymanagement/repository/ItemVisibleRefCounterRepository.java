package net.firedevops.firemud.entitymanagement.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.ItemVisibleRefCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

@Repository
public interface ItemVisibleRefCounterRepository
    extends JpaRepository<ItemVisibleRefCounter, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<ItemVisibleRefCounter> findByTenantIdAndVisibleRefToken(Long tenantId, String token);
}
