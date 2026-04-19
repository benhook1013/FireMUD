package net.firedevops.firemud.entitymanagement.repository;

import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ItemRepository extends JpaRepository<Item, Long> {
  Optional<Item> findByIdAndTenantId(Long id, Long tenantId);

  java.util.List<Item> findByTenantIdOrderByIdAsc(Long tenantId);
}
