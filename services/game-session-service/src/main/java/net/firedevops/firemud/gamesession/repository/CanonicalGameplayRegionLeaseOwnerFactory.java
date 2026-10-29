package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
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
      RedisScriptCatalog scriptCatalog,
      CanonicalGameplayRegionIncarnationSource incarnationSource,
      CanonicalInitialPlayerLocationRegionResolver worldRegionResolver,
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
        new CanonicalGameplayRegionLeaseRedisOwner(coordinationRedis, scriptCatalog),
        incarnationSource,
        Objects.requireNonNull(worldRegionResolver, "worldRegionResolver"),
        Objects.requireNonNull(runtimeIdentity, "runtimeIdentity"),
        Objects.requireNonNull(leaseTtl, "leaseTtl"),
        Objects.requireNonNull(clock, "clock"));
  }
}
