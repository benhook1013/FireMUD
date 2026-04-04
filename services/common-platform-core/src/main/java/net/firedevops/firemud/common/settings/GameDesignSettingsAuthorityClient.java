package net.firedevops.firemud.common.settings;

import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamedesign.v1.GameDesignServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.GetScopedSettingsOverridesRequest;

/** Cached gRPC reader for the shared persisted settings authority in Game Design. */
public class GameDesignSettingsAuthorityClient
    extends AbstractReloadingBlockingGrpcClient<GameDesignServiceGrpc.GameDesignServiceBlockingStub>
    implements SharedSettingsAuthorityReader {
  private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(5);

  private final ConcurrentHashMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
  private final Clock clock;
  private final Duration cacheTtl;
  private volatile boolean available;

  public GameDesignSettingsAuthorityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory) {
    this(endpoints, tlsProps, channelFactory, Clock.systemUTC(), DEFAULT_CACHE_TTL);
  }

  GameDesignSettingsAuthorityClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      Clock clock,
      Duration cacheTtl) {
    super(endpoints, tlsProps, channelFactory, GameDesignSettingsAuthorityClient.class);
    this.clock = clock;
    this.cacheTtl = cacheTtl;
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    try {
      initReloadingClient();
      available = true;
    } catch (Exception ex) {
      available = false;
      logger().warn("Shared settings authority unavailable; using operator defaults", ex);
    }
  }

  @Override
  public ScopedSettingsSnapshot readOverrides(long tenantId, Long gameInstanceId) {
    if (tenantId <= 0L) {
      return ScopedSettingsSnapshot.empty();
    }
    if (!available) {
      return ScopedSettingsSnapshot.empty();
    }
    CacheKey key = new CacheKey(tenantId, normalizeGameInstanceId(gameInstanceId));
    CacheEntry cached = cache.get(key);
    long now = clock.millis();
    if (cached != null && now - cached.cachedAtMs() < cacheTtl.toMillis()) {
      return cached.snapshot();
    }
    return fetchAndCache(key, now);
  }

  @Override
  public ScopedSettingsSnapshot refreshOverrides(long tenantId, Long gameInstanceId) {
    if (tenantId <= 0L || !available) {
      return ScopedSettingsSnapshot.empty();
    }
    CacheKey key = new CacheKey(tenantId, normalizeGameInstanceId(gameInstanceId));
    return fetchAndCache(key, clock.millis());
  }

  @Override
  public void invalidateOverrides(long tenantId, Long gameInstanceId) {
    if (tenantId <= 0L) {
      return;
    }
    cache.remove(new CacheKey(tenantId, normalizeGameInstanceId(gameInstanceId)));
  }

  private ScopedSettingsSnapshot fetchAndCache(CacheKey key, long now) {
    GetScopedSettingsOverridesRequest.Builder request =
        GetScopedSettingsOverridesRequest.newBuilder().setTenantId(Long.toString(key.tenantId()));
    if (key.gameInstanceId() != null) {
      request.setGameInstanceId(key.gameInstanceId());
    }

    try {
      var response =
          stub()
              .withDeadlineAfter(250L, TimeUnit.MILLISECONDS)
              .getScopedSettingsOverrides(request.build());
      ScopedSettingsSnapshot snapshot =
          response.hasError()
              ? ScopedSettingsSnapshot.empty()
              : GameDesignSettingsProtoMapper.fromProto(response);
      cache.put(key, new CacheEntry(snapshot, now));
      return snapshot;
    } catch (StatusRuntimeException ex) {
      logger().warn("Shared settings authority unavailable; using operator defaults", ex);
      ScopedSettingsSnapshot fallback = ScopedSettingsSnapshot.empty();
      cache.put(key, new CacheEntry(fallback, now));
      return fallback;
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameDesignService();
  }

  @Override
  protected String defaultTarget() {
    return "game-design-service:6565";
  }

  @Override
  protected GameDesignServiceGrpc.GameDesignServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return GameDesignServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  private static Long normalizeGameInstanceId(Long gameInstanceId) {
    return gameInstanceId != null && gameInstanceId > 0L ? gameInstanceId : null;
  }

  private record CacheKey(long tenantId, Long gameInstanceId) {}

  private record CacheEntry(ScopedSettingsSnapshot snapshot, long cachedAtMs) {}
}
