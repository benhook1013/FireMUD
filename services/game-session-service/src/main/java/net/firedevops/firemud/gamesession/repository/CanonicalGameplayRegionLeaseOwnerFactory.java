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
    Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(coordinationRedis, "coordinationRedis");
    var redisOptions = coordinationRedis.getOptions();
    if (redisOptions == null || redisOptions.isAutoReconnect()) {
      throw new IllegalArgumentException("coordinationRedis requires autoReconnect=false");
    }
    Objects.requireNonNull(scriptCatalog, "scriptCatalog");
    Objects.requireNonNull(incarnationSource, "incarnationSource");
    long installationMillis =
        CanonicalGameplayOriginalNewCapacityExecutionBudget.MAX_OPERATION_MILLIS;
    Objects.requireNonNull(leaseTtl, "leaseTtl");
    if (leaseTtl.compareTo(Duration.ofMillis(installationMillis + 1)) < 0) {
      throw new IllegalArgumentException("leaseTtl must exceed the bounded installation window");
    }
    return new CanonicalGameplayRegionLeaseOwner(
        new CanonicalGameplayBindingInventoryRepository(dsl),
        new CanonicalGameplayRegionRuntimeStatusRepository(dsl),
        new CanonicalGameplayRegionLeaseRedisOwner(
            coordinationRedis, coordinationUri, scriptCatalog),
        incarnationSource,
        Objects.requireNonNull(worldRegionResolver, "worldRegionResolver"),
        Objects.requireNonNull(runtimeIdentity, "runtimeIdentity"),
        leaseTtl,
        Objects.requireNonNull(clock, "clock"),
        true);
  }
}
