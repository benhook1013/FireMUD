package net.firedevops.firemud.common.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetScopedSettingsOverridesResponse;
import org.junit.jupiter.api.Test;

class GameDesignSettingsAuthorityClientTest {

  @Test
  void initFailureFallsBackToEmptyOverrides() throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setCaCert("missing-ca.crt");
    GameDesignSettingsAuthorityClient client =
        new GameDesignSettingsAuthorityClient(
            endpoints, grpc, new FailingGrpcChannelFactory(), BlockingGrpcStubCustomizer.noop());

    client.init();

    assertThat(client.readOverrides(1L, 2L)).isEqualTo(ScopedSettingsSnapshot.empty());
  }

  @Test
  void readRefreshAndInvalidateUseExplicitLocalCacheSemantics() throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);

    AtomicLong now = new AtomicLong(1_000L);
    MutableClock clock = new MutableClock(now);
    GameDesignServiceGrpc.GameDesignServiceBlockingStub stub =
        mock(GameDesignServiceGrpc.GameDesignServiceBlockingStub.class);
    when(stub.withDeadlineAfter(any(Long.class), any())).thenReturn(stub);
    when(stub.getScopedSettingsOverrides(any()))
        .thenReturn(
            GetScopedSettingsOverridesResponse.newBuilder()
                .setTenantOverrides(
                    net.firedevops.firemud.gamedesign.v1.SettingsOverrides.newBuilder()
                        .setMovement(
                            net.firedevops.firemud.gamedesign.v1.MovementSettingsOverride
                                .newBuilder()
                                .setPostMoveLookEnabled(true)
                                .build())
                        .setCommandCapabilities(
                            net.firedevops.firemud.gamedesign.v1.CommandCapabilitiesSettingsOverride
                                .newBuilder()
                                .setSocialEnabled(false)
                                .setInventoryEnabled(false)
                                .build())
                        .build())
                .build(),
            GetScopedSettingsOverridesResponse.newBuilder()
                .setTenantOverrides(
                    net.firedevops.firemud.gamedesign.v1.SettingsOverrides.newBuilder()
                        .setMovement(
                            net.firedevops.firemud.gamedesign.v1.MovementSettingsOverride
                                .newBuilder()
                                .setPostMoveLookEnabled(false)
                                .build())
                        .build())
                .build(),
            GetScopedSettingsOverridesResponse.newBuilder()
                .setTenantOverrides(
                    net.firedevops.firemud.gamedesign.v1.SettingsOverrides.newBuilder()
                        .setMovement(
                            net.firedevops.firemud.gamedesign.v1.MovementSettingsOverride
                                .newBuilder()
                                .setPostMoveLookEnabled(true)
                                .build())
                        .build())
                .build());

    GameDesignSettingsAuthorityClient client =
        new TestGameDesignSettingsAuthorityClient(
            endpoints,
            grpc,
            new PassingGrpcChannelFactory(),
            clock,
            Duration.ofSeconds(5),
            BlockingGrpcStubCustomizer.noop(),
            stub);

    client.init();

    ScopedSettingsSnapshot first = client.readOverrides(22L, 7L);
    ScopedSettingsSnapshot cached = client.readOverrides(22L, 7L);
    ScopedSettingsSnapshot refreshed = client.refreshOverrides(22L, 7L);
    client.invalidateOverrides(22L, 7L);
    ScopedSettingsSnapshot afterInvalidate = client.readOverrides(22L, 7L);

    assertThat(first.tenantOverrides().movement().postMoveLookEnabled()).isTrue();
    assertThat(first.tenantOverrides().commandCapabilities().socialEnabled()).isFalse();
    assertThat(first.tenantOverrides().commandCapabilities().presenceEnabled()).isNull();
    assertThat(first.tenantOverrides().commandCapabilities().inventoryEnabled()).isFalse();
    assertThat(cached).isEqualTo(first);
    assertThat(refreshed.tenantOverrides().movement().postMoveLookEnabled()).isFalse();
    assertThat(afterInvalidate.tenantOverrides().movement().postMoveLookEnabled()).isTrue();
  }

  @Test
  void buildStubAppliesInjectedStubCustomizer() {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    CommonGrpcClientProperties grpc = new CommonGrpcClientProperties();
    grpc.setPlaintext(true);
    GameDesignServiceGrpc.GameDesignServiceBlockingStub stub =
        mock(GameDesignServiceGrpc.GameDesignServiceBlockingStub.class);
    AtomicReference<GameDesignServiceGrpc.GameDesignServiceBlockingStub> customized =
        new AtomicReference<>();
    BlockingGrpcStubCustomizer stubCustomizer =
        new BlockingGrpcStubCustomizer() {
          @Override
          public <T extends io.grpc.stub.AbstractStub<T>> T customize(T candidate) {
            customized.set((GameDesignServiceGrpc.GameDesignServiceBlockingStub) candidate);
            return candidate;
          }
        };
    TestGameDesignSettingsAuthorityClient client =
        new TestGameDesignSettingsAuthorityClient(
            endpoints,
            grpc,
            new PassingGrpcChannelFactory(),
            Clock.systemUTC(),
            Duration.ofSeconds(5),
            stubCustomizer,
            stub);

    client.buildStub(mock(ManagedChannel.class));

    assertThat(customized.get()).isSameAs(stub);
  }

  private static final class FailingGrpcChannelFactory extends GrpcChannelFactory {
    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive)
        throws SSLException {
      throw new SSLException("boom");
    }
  }

  private static final class PassingGrpcChannelFactory extends GrpcChannelFactory {
    @Override
    public ManagedChannel buildChannel(
        String target, int defaultPort, CommonGrpcClientProperties properties, boolean keepAlive) {
      return mock(ManagedChannel.class);
    }
  }

  private static final class TestGameDesignSettingsAuthorityClient
      extends GameDesignSettingsAuthorityClient {
    private final GameDesignServiceGrpc.GameDesignServiceBlockingStub stub;

    private TestGameDesignSettingsAuthorityClient(
        ServiceEndpointsProperties endpoints,
        CommonGrpcClientProperties tlsProps,
        GrpcChannelFactory channelFactory,
        Clock clock,
        Duration cacheTtl,
        BlockingGrpcStubCustomizer stubCustomizer,
        GameDesignServiceGrpc.GameDesignServiceBlockingStub stub) {
      super(endpoints, tlsProps, channelFactory, stubCustomizer, clock, cacheTtl);
      this.stub = stub;
    }

    @Override
    protected GameDesignServiceGrpc.GameDesignServiceBlockingStub buildStub(
        ManagedChannel channel) {
      return applyStubCustomizer(stub);
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicLong now;

    private MutableClock(AtomicLong now) {
      this.now = now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(now.get());
    }
  }
}
