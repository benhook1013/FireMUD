package net.firedevops.firemud.socialgroups.repository;

import java.util.Optional;
import net.firedevops.firemud.socialgroups.entity.GuildMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GuildMemberRepository extends JpaRepository<GuildMember, Long> {
  Optional<GuildMember> findFirstByTenantIdAndGuildIdAndAccountId(
      Long tenantId, Long guildId, Long accountId);
}
