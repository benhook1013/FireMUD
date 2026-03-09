package net.firedevops.firemud.automationscripting.repository;

import net.firedevops.firemud.automationscripting.entity.Faction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FactionRepository extends JpaRepository<Faction, Long> {}
