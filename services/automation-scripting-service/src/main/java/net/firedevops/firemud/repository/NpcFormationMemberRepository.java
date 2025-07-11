package net.firedevops.firemud.repository;

import java.util.List;
import net.firedevops.firemud.entity.NpcFormationMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NpcFormationMemberRepository extends JpaRepository<NpcFormationMember, Long> {
  List<NpcFormationMember> findByFormation_TenantIdAndFormation_Id(Long tenantId, Long formationId);
}
