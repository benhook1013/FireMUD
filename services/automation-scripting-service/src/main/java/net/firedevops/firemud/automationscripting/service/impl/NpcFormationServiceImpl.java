package net.firedevops.firemud.automationscripting.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.entity.NpcFormation;
import net.firedevops.firemud.automationscripting.entity.NpcFormationMember;
import net.firedevops.firemud.automationscripting.model.FormationType;
import net.firedevops.firemud.automationscripting.repository.NpcFormationMemberRepository;
import net.firedevops.firemud.automationscripting.repository.NpcFormationRepository;
import net.firedevops.firemud.automationscripting.service.NpcFormationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NpcFormationServiceImpl implements NpcFormationService {
  private final NpcFormationRepository formationRepository;
  private final NpcFormationMemberRepository memberRepository;

  @Override
  @Transactional
  @Timed(value = "formation.create")
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
  @Timed(value = "formation.addMember")
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
  @Timed(value = "formation.getMembers")
  public List<Long> getMembers(Long tenantId, Long formationId) {
    return memberRepository.findByFormation_TenantIdAndFormation_Id(tenantId, formationId).stream()
        .map(NpcFormationMember::getNpcId)
        .collect(Collectors.toList());
  }
}
