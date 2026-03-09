package net.firedevops.firemud.loggingadmin.repository;

import net.firedevops.firemud.loggingadmin.entity.ModerationAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {}
