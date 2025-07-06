package net.firedevops.firemud.repository;

import net.firedevops.firemud.entity.PlayerReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlayerReportRepository extends JpaRepository<PlayerReport, Long> {}
