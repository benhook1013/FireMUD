package net.firedevops.firemud.gamedesign.repository;

import net.firedevops.firemud.gamedesign.entity.Revision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RevisionRepository extends JpaRepository<Revision, Long> {}
