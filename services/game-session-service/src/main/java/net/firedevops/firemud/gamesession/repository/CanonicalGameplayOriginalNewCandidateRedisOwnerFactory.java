package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import java.util.Objects;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;

/** Explicit manual construction for the unregistered original NEW candidate Redis owner. */
public final class CanonicalGameplayOriginalNewCandidateRedisOwnerFactory {
  private CanonicalGameplayOriginalNewCandidateRedisOwnerFactory() {}

  public static CanonicalGameplayOriginalNewCandidateClosureRedisOwner createClosure(
      RedisClient redisClient,
      RedisScriptCatalog scriptCatalog,
      CanonicalGameplayBindingInventoryRepository inventoryRepository,
      CanonicalGameplayTenantGameplayTagRepository tenantGameplayTags,
      CanonicalGameplayOriginalNewClosureQualification qualification) {
    return new CanonicalGameplayOriginalNewCandidateClosureRedisOwner(
        redisClient, scriptCatalog, inventoryRepository, tenantGameplayTags, qualification);
  }

  public static CanonicalGameplayOriginalNewCandidateRedisOwner create(
      RedisClient redisClient,
      RedisScriptCatalog scriptCatalog,
      CanonicalGameplayBindingInventoryRepository inventoryRepository,
      CanonicalGameplayTenantGameplayTagRepository tenantGameplayTags) {
    return new CanonicalGameplayOriginalNewCandidateRedisOwner(
        Objects.requireNonNull(redisClient, "redisClient"),
        Objects.requireNonNull(scriptCatalog, "scriptCatalog"),
        Objects.requireNonNull(inventoryRepository, "inventoryRepository"),
        Objects.requireNonNull(tenantGameplayTags, "tenantGameplayTags"));
  }
}
