package net.firedevops.firemud.socialgroups.repository;

import net.firedevops.firemud.socialgroups.entity.GuildMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GuildMemberRepository extends JpaRepository<GuildMember, Long> {}
