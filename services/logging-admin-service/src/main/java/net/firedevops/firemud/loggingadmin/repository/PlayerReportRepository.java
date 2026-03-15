package net.firedevops.firemud.loggingadmin.repository;

import net.firedevops.firemud.loggingadmin.entity.PlayerReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlayerReportRepository extends JpaRepository<PlayerReport, Long> {}
