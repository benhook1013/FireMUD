package net.firedevops.firemud.common.redis.contracts;

import java.util.List;
import java.util.OptionalInt;

/** Immutable proof that an invocation matched one registered Redis script contract. */
public final class RedisInvocationContract {

  private final RedisScriptDescriptor descriptor;
  private final List<String> keys;
  private final List<String> arguments;
  private final Integer clusterSlot;

  RedisInvocationContract(
      RedisScriptDescriptor descriptor,
      List<String> keys,
      List<String> arguments,
      Integer clusterSlot) {
    this.descriptor = descriptor;
    this.keys = List.copyOf(keys);
    this.arguments = List.copyOf(arguments);
    this.clusterSlot = clusterSlot;
  }

  public RedisScriptDescriptor descriptor() {
    return descriptor;
  }

  public List<String> keys() {
    return keys;
  }

  public List<String> arguments() {
    return arguments;
  }

  /** Empty when no KEYS entry declared a hash tag. */
  public OptionalInt clusterSlot() {
    return clusterSlot == null ? OptionalInt.empty() : OptionalInt.of(clusterSlot);
  }
}
