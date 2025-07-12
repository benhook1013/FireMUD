package net.firedevops.firemud.repository;

import net.firedevops.firemud.entity.GuildMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GuildMemberRepository extends JpaRepository<GuildMember, Long> {}
