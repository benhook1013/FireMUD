package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.loggingadmin.dto.FeatureFlagDto;
import net.firedevops.firemud.loggingadmin.dto.ToggleFeatureFlagRequest;
import net.firedevops.firemud.loggingadmin.entity.FeatureFlag;
import net.firedevops.firemud.loggingadmin.mapper.FeatureFlagMapper;
import net.firedevops.firemud.loggingadmin.repository.FeatureFlagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class FeatureFlagServiceImplTest {
  @Mock FeatureFlagRepository repository;
  @Mock FeatureFlagMapper mapper;

  @InjectMocks FeatureFlagServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
  }

  @Test
  void toggleCreatesNewFlag() {
    ToggleFeatureFlagRequest request = new ToggleFeatureFlagRequest(1L, "demo", true);
    FeatureFlag saved = new FeatureFlag();
    when(repository.findByTenantIdAndName(1L, "demo")).thenReturn(Optional.empty());
    when(repository.save(any())).thenReturn(saved);
    FeatureFlagDto dto = new FeatureFlagDto(null, 1L, "demo", true);
    when(mapper.toDto(saved)).thenReturn(dto);

    FeatureFlagDto result = service.toggleFlag(request);

    assertEquals(dto, result);
    verify(repository).save(any(FeatureFlag.class));
  }
}
