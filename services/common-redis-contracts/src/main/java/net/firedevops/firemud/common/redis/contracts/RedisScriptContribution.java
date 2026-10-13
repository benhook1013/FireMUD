package net.firedevops.firemud.common.redis.contracts;

import java.util.Collection;

/** Service-provider entry point for owner-local Redis script descriptors. */
public interface RedisScriptContribution {
  /** Exact service owner identifier declared by every descriptor returned here. */
  String ownerId();

  Collection<RedisScriptDescriptor> descriptors();
}
