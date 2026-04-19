package net.firedevops.firemud.gamesession.repository;

import java.util.List;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointerEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameplayAdmissionPointerEventRepository
    extends JpaRepository<GameplayAdmissionPointerEvent, Long> {
  List<GameplayAdmissionPointerEvent> findByWorldSlugAndRealmSlugOrderByOccurredAtDesc(
      String worldSlug, String realmSlug);
}
