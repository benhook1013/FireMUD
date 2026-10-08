package net.firedevops.firemud.common.redis.contracts;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Immutable aggregation of the owner contributions installed in this class loader.
 *
 * <p>This is a shared schema/aggregation surface, not proof that every repository owner or script
 * has contributed to a complete repository-wide catalog.
 */
public final class RedisScriptCatalog {
  public static final int MAX_CONTRIBUTIONS = 128;
  public static final int MAX_DESCRIPTORS = 1024;

  private final Map<String, RedisScriptDescriptor> descriptorsById;

  private RedisScriptCatalog(Collection<? extends RedisScriptContribution> contributions) {
    Objects.requireNonNull(contributions, "Redis script contributions are required");
    if (contributions.size() > MAX_CONTRIBUTIONS) {
      throw new IllegalArgumentException("Redis script contribution count exceeds its bound");
    }
    Map<String, RedisScriptDescriptor> collected = new LinkedHashMap<>();
    int count = 0;
    for (RedisScriptContribution contribution : contributions) {
      Objects.requireNonNull(contribution, "Redis script contribution is required");
      String ownerId =
          Objects.requireNonNull(contribution.ownerId(), "Contribution owner is required");
      Collection<RedisScriptDescriptor> descriptors =
          Objects.requireNonNull(
              contribution.descriptors(), "Contribution descriptors are required");
      if (descriptors.isEmpty()) {
        throw new IllegalArgumentException("Redis script contribution cannot be empty");
      }
      for (RedisScriptDescriptor descriptor : descriptors) {
        Objects.requireNonNull(descriptor, "Redis script descriptor is required");
        if (!ownerId.equals(descriptor.owner())) {
          throw new IllegalArgumentException(
              "Redis script descriptor owner does not match contribution");
        }
        if (++count > MAX_DESCRIPTORS) {
          throw new IllegalArgumentException("Redis script descriptor count exceeds its bound");
        }
        if (collected.putIfAbsent(descriptor.scriptId(), descriptor) != null) {
          throw new IllegalArgumentException("Duplicate Redis script descriptor identity");
        }
      }
    }
    this.descriptorsById = Map.copyOf(collected);
  }

  /** Aggregates service-provider contributions installed in the supplied class loader. */
  public static RedisScriptCatalog loadInstalled(ClassLoader classLoader) {
    Objects.requireNonNull(classLoader, "Class loader is required");
    return new RedisScriptCatalog(
        ServiceLoader.load(RedisScriptContribution.class, classLoader).stream()
            .map(ServiceLoader.Provider::get)
            .toList());
  }

  /** Bounded constructor surface for owner-local tests and explicitly assembled applications. */
  public static RedisScriptCatalog fromContributions(
      Collection<? extends RedisScriptContribution> contributions) {
    return new RedisScriptCatalog(contributions);
  }

  public RedisScriptDescriptor require(String id) {
    Objects.requireNonNull(id, "Redis script id is required");
    RedisScriptDescriptor descriptor = descriptorsById.get(id);
    if (descriptor == null) throw new IllegalArgumentException("Redis script is not registered");
    return descriptor;
  }

  public Collection<RedisScriptDescriptor> descriptors() {
    return descriptorsById.values();
  }
}
