package net.firedevops.firemud.service.impl;

import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entity.NpcFormation;
import net.firedevops.firemud.entity.NpcFormationMember;
import net.firedevops.firemud.model.FormationType;
import net.firedevops.firemud.repository.NpcFormationMemberRepository;
import net.firedevops.firemud.repository.NpcFormationRepository;
import net.firedevops.firemud.service.NpcFormationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NpcFormationServiceImpl implements NpcFormationService {
  private final NpcFormationRepository formationRepository;
  private final NpcFormationMemberRepository memberRepository;

  @Override
  @Transactional
  public Long createFormation(Long tenantId, String name, Long leaderNpcId, FormationType type) {
    NpcFormation formation = new NpcFormation();
    formation.setTenantId(tenantId);
    formation.setName(name);
    formation.setLeaderNpcId(leaderNpcId);
    formation.setFormationType(type);
    return formationRepository.save(formation).getId();
  }

  @Override
  @Transactional
  public void addMember(Long tenantId, Long formationId, Long npcId) {
    NpcFormation formation = formationRepository.findById(formationId).orElseThrow();
    if (!formation.getTenantId().equals(tenantId)) {
      throw new IllegalArgumentException("tenant mismatch");
    }
    NpcFormationMember member = new NpcFormationMember();
    member.setFormation(formation);
    member.setNpcId(npcId);
    memberRepository.save(member);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> getMembers(Long tenantId, Long formationId) {
    return memberRepository.findByFormation_TenantIdAndFormation_Id(tenantId, formationId).stream()
        .map(NpcFormationMember::getNpcId)
        .collect(Collectors.toList());
  }
}
