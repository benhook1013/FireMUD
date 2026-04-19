package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.v1.DeleteSettingsDomainOverrideRequest;
import net.firedevops.firemud.gamedesign.v1.DeleteSettingsDomainOverrideResponse;
import net.firedevops.firemud.gamedesign.v1.GetScopedSettingsOverridesRequest;
import net.firedevops.firemud.gamedesign.v1.GetScopedSettingsOverridesResponse;
import net.firedevops.firemud.gamedesign.v1.PutSettingsDomainOverrideRequest;
import net.firedevops.firemud.gamedesign.v1.PutSettingsDomainOverrideResponse;
import net.firedevops.firemud.gamedesign.v1.SettingsDomain;
import net.firedevops.firemud.gamedesign.v1.SettingsOverrides;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameDesignGrpcServiceSettingsAuthorityTest {
  private final PingService pingService = Mockito.mock(PingService.class);
  private final RevisionService revisionService = Mockito.mock(RevisionService.class);
  private final VersionService versionService = Mockito.mock(VersionService.class);
  private final LaunchDescriptorService launchDescriptorService =
      Mockito.mock(LaunchDescriptorService.class);
  private final VersionAssetArtifactService versionAssetArtifactService =
      Mockito.mock(VersionAssetArtifactService.class);
  private final SettingsAuthorityService settingsAuthorityService =
      Mockito.mock(SettingsAuthorityService.class);

  private GameDesignGrpcService grpcService;

  @BeforeEach
  void setUp() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    grpcService =
        new GameDesignGrpcService(
            pingService,
            revisionService,
            versionService,
            launchDescriptorService,
            versionAssetArtifactService,
            settingsAuthorityService,
            new SimpleMeterRegistry());
  }

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void getScopedSettingsOverridesReturnsAuthorityPayload() {
    when(settingsAuthorityService.getScopedOverrides("42", 7L))
        .thenReturn(
            new ScopedSettingsSnapshot(
                new ScopedSettingsOverrides(
                    null,
                    null,
                    new ScopedSettingsOverrides.PresentationOverride(
                        null,
                        ScopedSettingsOverrides.PresentationOverride.ColorMode.BASIC,
                        null,
                        null),
                    null,
                    null),
                ScopedSettingsOverrides.empty()));
    CapturingObserver<GetScopedSettingsOverridesResponse> observer = new CapturingObserver<>();

    grpcService.getScopedSettingsOverrides(
        GetScopedSettingsOverridesRequest.newBuilder()
            .setTenantId("42")
            .setGameInstanceId(7L)
            .build(),
        observer);

    assertThat(observer.value).isNotNull();
    assertThat(observer.value.hasTenantOverrides()).isTrue();
    assertThat(observer.value.getTenantOverrides().getPresentation().getDefaultColorMode().name())
        .isEqualTo("PRESENTATION_COLOR_MODE_BASIC");
  }

  @Test
  void putAndDeleteSettingsDomainOverrideDelegateToAuthorityService() {
    CapturingObserver<PutSettingsDomainOverrideResponse> putObserver = new CapturingObserver<>();
    SettingsOverrides overrides =
        SettingsOverrides.newBuilder()
            .setMovement(
                net.firedevops.firemud.gamedesign.v1.MovementSettingsOverride.newBuilder()
                    .setPostMoveLookEnabled(false)
                    .build())
            .build();

    grpcService.putSettingsDomainOverride(
        PutSettingsDomainOverrideRequest.newBuilder()
            .setTenantId("42")
            .setGameInstanceId(7L)
            .setDomain(SettingsDomain.SETTINGS_DOMAIN_MOVEMENT)
            .setOverrides(overrides)
            .build(),
        putObserver);

    verify(settingsAuthorityService)
        .putDomainOverride(
            "42",
            7L,
            ScopedSettingsOverrides.SettingsDomain.MOVEMENT,
            new ScopedSettingsOverrides(
                null, null, null, new ScopedSettingsOverrides.MovementOverride(false), null));
    assertThat(putObserver.value.hasError()).isFalse();

    CapturingObserver<DeleteSettingsDomainOverrideResponse> deleteObserver =
        new CapturingObserver<>();
    grpcService.deleteSettingsDomainOverride(
        DeleteSettingsDomainOverrideRequest.newBuilder()
            .setTenantId("42")
            .setGameInstanceId(7L)
            .setDomain(SettingsDomain.SETTINGS_DOMAIN_MOVEMENT)
            .build(),
        deleteObserver);

    verify(settingsAuthorityService)
        .deleteDomainOverride("42", 7L, ScopedSettingsOverrides.SettingsDomain.MOVEMENT);
    assertThat(deleteObserver.value.hasError()).isFalse();
  }

  private static final class CapturingObserver<T> implements StreamObserver<T> {
    private T value;

    @Override
    public void onNext(T value) {
      this.value = value;
    }

    @Override
    public void onError(Throwable throwable) {
      throw new AssertionError("Unexpected gRPC error", throwable);
    }

    @Override
    public void onCompleted() {}
  }
}
