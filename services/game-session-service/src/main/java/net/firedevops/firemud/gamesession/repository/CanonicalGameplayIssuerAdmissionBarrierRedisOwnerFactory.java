package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import java.util.Objects;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;

/** Explicit manual factory; it does not register a runtime or ServiceLoader provider. */
final class CanonicalGameplayIssuerAdmissionBarrierRedisOwnerFactory {
  private CanonicalGameplayIssuerAdmissionBarrierRedisOwnerFactory() {}

  static CanonicalGameplayIssuerAdmissionBarrierRedisOwner create(
      RedisClient redisClient,
      RedisScriptCatalog catalog,
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner.ProjectionRepository repository,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource currentStateSource) {
    return new CanonicalGameplayIssuerAdmissionBarrierRedisOwner(
        Objects.requireNonNull(redisClient, "redisClient"),
        Objects.requireNonNull(catalog, "catalog"),
        Objects.requireNonNull(repository, "repository"),
        Objects.requireNonNull(currentStateSource, "currentStateSource"));
  }
}
