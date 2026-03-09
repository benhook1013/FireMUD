package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.entity.NpcFormation;
import net.firedevops.firemud.entity.NpcFormationMember;
import net.firedevops.firemud.model.FormationType;
import net.firedevops.firemud.repository.NpcFormationMemberRepository;
import net.firedevops.firemud.repository.NpcFormationRepository;
import net.firedevops.firemud.service.NpcFormationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NpcFormationServiceImplTest {
  private NpcFormationRepository formationRepo;
  private NpcFormationMemberRepository memberRepo;
  private NpcFormationService service;

  @BeforeEach
  void setup() {
    formationRepo = mock(NpcFormationRepository.class);
    memberRepo = mock(NpcFormationMemberRepository.class);
    service = new NpcFormationServiceImpl(formationRepo, memberRepo);
  }

  @Test
  void createFormationPersistsEntity() {
    NpcFormation saved = new NpcFormation();
    saved.setId(10L);
    when(formationRepo.save(any(NpcFormation.class))).thenReturn(saved);
    Long id = service.createFormation(1L, "alpha", 2L, FormationType.LINE);
    assertEquals(10L, id);
  }

  @Test
  void addMemberValidatesTenant() {
    NpcFormation formation = new NpcFormation();
    formation.setId(5L);
    formation.setTenantId(1L);
    when(formationRepo.findById(5L)).thenReturn(Optional.of(formation));

    service.addMember(1L, 5L, 3L);
    verify(memberRepo).save(any(NpcFormationMember.class));
  }

  @Test
  void getMembersReturnsNpcIds() {
    when(memberRepo.findByFormation_TenantIdAndFormation_Id(1L, 5L))
        .thenReturn(List.of(createMember(3L), createMember(4L)));
    List<Long> ids = service.getMembers(1L, 5L);
    assertEquals(List.of(3L, 4L), ids);
  }

  private NpcFormationMember createMember(Long npcId) {
    NpcFormationMember m = new NpcFormationMember();
    m.setNpcId(npcId);
    return m;
  }
}
