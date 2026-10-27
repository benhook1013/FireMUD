package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.client.CanonicalPreseededActorRuntimeReferenceClient;
import net.firedevops.firemud.gamesession.client.CanonicalPlayerCreatedRuntimeReferenceClient;
import net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver;
import org.jooq.DSLContext;

/** Explicit manual composition; this first-NEW bridge has no Spring or default registration. */
final class CanonicalGameplayRegionBindingBridgeOwnerFactory {
  private CanonicalGameplayRegionBindingBridgeOwnerFactory() {}

  static CanonicalGameplayRegionBindingBridgeOwner compose(
      DSLContext dsl,
      RedisClient coordinationRedis,
      RedisScriptCatalog scriptCatalog,
      CanonicalGameplayRegionIncarnationSource incarnationSource,
      CanonicalInitialPlayerLocationRegionResolver worldRegionResolver,
      CanonicalPreseededActorRuntimeReferenceClient entityReferenceClient,
      RuntimeIdentity runtimeIdentity,
      Duration leaseTtl,
      Clock clock) {
    return compose(dsl, coordinationRedis, scriptCatalog, incarnationSource, worldRegionResolver,
        entityReferenceClient, null, runtimeIdentity, leaseTtl, clock);
  }

  static CanonicalGameplayRegionBindingBridgeOwner compose(
      DSLContext dsl,
      RedisClient coordinationRedis,
      RedisScriptCatalog scriptCatalog,
      CanonicalGameplayRegionIncarnationSource incarnationSource,
      CanonicalInitialPlayerLocationRegionResolver worldRegionResolver,
      CanonicalPreseededActorRuntimeReferenceClient entityReferenceClient,
      CanonicalPlayerCreatedRuntimeReferenceClient createdReferenceClient,
      RuntimeIdentity runtimeIdentity,
      Duration leaseTtl,
      Clock clock) {
    Objects.requireNonNull(entityReferenceClient, "entityReferenceClient");
    return new CanonicalGameplayRegionBindingBridgeOwner(
        CanonicalGameplayRegionLeaseOwnerFactory.compose(
            dsl,
            coordinationRedis,
            scriptCatalog,
            incarnationSource,
            worldRegionResolver,
            runtimeIdentity,
            leaseTtl,
            clock),
        entityReferenceClient, createdReferenceClient);
  }
}
