package net.firedevops.firemud.gamesession.repository;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameplayAdmissionPointerRepository
    extends JpaRepository<GameplayAdmissionPointer, Long> {
  Optional<GameplayAdmissionPointer> findByWorldSlugAndRealmSlug(
      String worldSlug, String realmSlug);

  List<GameplayAdmissionPointer> findAllByOrderByWorldSlugAscRealmSlugAsc();
}
