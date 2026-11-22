package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver;
import org.jooq.DSLContext;

/** Explicit manual composition; this factory is not registered with Spring or a default path. */
final class CanonicalGameplayRegionLeaseOwnerFactory {
  private CanonicalGameplayRegionLeaseOwnerFactory() {}

  static CanonicalGameplayRegionLeaseOwner compose(
      DSLContext dsl,
      RedisClient coordinationRedis,
      RedisURI coordinationUri,
      RedisScriptCatalog scriptCatalog,
      CanonicalGameplayRegionIncarnationSource incarnationSource,
      CanonicalInitialPlayerLocationRegionResolver worldRegionResolver,
      RuntimeIdentity runtimeIdentity,
      Duration leaseTtl,
      Clock clock) {
    return compose(
        dsl,
        coordinationRedis,
        coordinationUri,
        scriptCatalog,
        incarnationSource,
        CanonicalGameplayRegionLeaseOwner.PlacementSource.world(
            Objects.requireNonNull(worldRegionResolver, "worldRegionResolver")),
        runtimeIdentity,
        leaseTtl,
        clock);
  }

  /** Same composition over an explicit placement port (World, or the pre-v1 fixture stand-in). */
  static CanonicalGameplayRegionLeaseOwner compose(
      DSLContext dsl,
      RedisClient coordinationRedis,
      RedisURI coordinationUri,
      RedisScriptCatalog scriptCatalog,
      CanonicalGameplayRegionIncarnationSource incarnationSource,
      CanonicalGameplayRegionLeaseOwner.PlacementSource placementSource,
      RuntimeIdentity runtimeIdentity,
      Duration leaseTtl,
      Clock clock) {
    Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(coordinationRedis, "coordinationRedis");
    Objects.requireNonNull(scriptCatalog, "scriptCatalog");
    Objects.requireNonNull(incarnationSource, "incarnationSource");
    return new CanonicalGameplayRegionLeaseOwner(
        new CanonicalGameplayBindingInventoryRepository(dsl),
        new CanonicalGameplayRegionRuntimeStatusRepository(dsl),
        new CanonicalGameplayRegionLeaseRedisOwner(
            coordinationRedis, coordinationUri, scriptCatalog),
        incarnationSource,
        Objects.requireNonNull(placementSource, "placementSource"),
        Objects.requireNonNull(runtimeIdentity, "runtimeIdentity"),
        Objects.requireNonNull(leaseTtl, "leaseTtl"),
        Objects.requireNonNull(clock, "clock"));
  }
}
