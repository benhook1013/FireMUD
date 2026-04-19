package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.gamesession.v1.ToggleFeatureFlagResponse;
import net.firedevops.firemud.loggingadmin.client.GameSessionClient;
import net.firedevops.firemud.loggingadmin.dto.FeatureFlagDto;
import net.firedevops.firemud.loggingadmin.dto.ToggleFeatureFlagRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class FeatureFlagServiceImplTest {
  @Mock GameSessionClient gameSessionClient;

  @InjectMocks FeatureFlagServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
  }

  @Test
  void toggleDelegatesToGameSessionRuntimeOwner() {
    ToggleFeatureFlagRequest request = new ToggleFeatureFlagRequest(1L, "demo", true);
    when(gameSessionClient.toggleFeatureFlag(1L, "demo", true))
        .thenReturn(ToggleFeatureFlagResponse.newBuilder().setSuccess(true).build());

    FeatureFlagDto result = service.toggleFlag(request);

    assertEquals(new FeatureFlagDto(null, 1L, "demo", true), result);
    verify(gameSessionClient).toggleFeatureFlag(1L, "demo", true);
  }
}
