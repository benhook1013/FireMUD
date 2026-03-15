package net.firedevops.firemud.socialgroups.repository;

import net.firedevops.firemud.socialgroups.entity.Guild;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GuildRepository extends JpaRepository<Guild, Long> {}
