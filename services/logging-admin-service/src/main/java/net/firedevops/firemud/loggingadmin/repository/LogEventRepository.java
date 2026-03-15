package net.firedevops.firemud.loggingadmin.repository;

import java.util.List;
import net.firedevops.firemud.loggingadmin.entity.LogEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LogEventRepository extends JpaRepository<LogEvent, Long> {
  List<LogEvent> findByTenantIdAndMessageContainingIgnoreCase(Long tenantId, String message);
}
