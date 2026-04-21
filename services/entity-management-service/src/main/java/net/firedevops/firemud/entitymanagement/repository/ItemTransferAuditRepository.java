package net.firedevops.firemud.entitymanagement.repository;

import net.firedevops.firemud.entitymanagement.entity.ItemTransferAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ItemTransferAuditRepository extends JpaRepository<ItemTransferAudit, Long> {}
