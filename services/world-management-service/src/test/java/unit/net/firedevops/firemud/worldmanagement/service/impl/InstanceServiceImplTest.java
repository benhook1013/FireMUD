package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.firedevops.firemud.worldmanagement.dto.InstanceDto;
import net.firedevops.firemud.worldmanagement.entity.Instance;
import net.firedevops.firemud.worldmanagement.mapper.InstanceMapper;
import net.firedevops.firemud.worldmanagement.repository.InstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class InstanceServiceImplTest {
  private InstanceRepository repository;
  private InstanceMapper mapper = Mappers.getMapper(InstanceMapper.class);
  private InstanceServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = mock(InstanceRepository.class);
    service = new InstanceServiceImpl(repository, mapper);
    service.setExpirationHours(1L);
  }

  @Test
  void createInstanceSetsExpiration() {
    InstanceDto request = new InstanceDto(null, 1L, 2L, null, null, null);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    InstanceDto result = service.createInstance(request);
    assertNotNull(result.expiresAt());
    verify(repository).save(any(Instance.class));
  }
}
