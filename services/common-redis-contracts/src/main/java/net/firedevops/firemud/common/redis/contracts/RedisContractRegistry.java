package net.firedevops.firemud.common.redis.contracts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates owner declarations and prepares immutable invocation contracts. */
public final class RedisContractRegistry {

  private static final int REDIS_CLUSTER_SLOT_COUNT = 16384;
  private final Map<String, RedisScriptDescriptor> descriptorsById;

  public RedisContractRegistry(
      Collection<PrefixDeclaration> prefixDeclarations,
      Collection<RedisScriptDescriptor> descriptors) {
    Objects.requireNonNull(prefixDeclarations, "prefixDeclarations are required");
    Objects.requireNonNull(descriptors, "descriptors are required");
    if (prefixDeclarations.isEmpty()) {
      throw new IllegalArgumentException("at least one owner prefix declaration is required");
    }

    Map<String, PrefixDeclaration> byPrefix = new HashMap<>();
    List<PrefixDeclaration> declarations = new ArrayList<>(prefixDeclarations);
    Set<PrefixDeclaration> uniqueDeclarations = new HashSet<>();
    for (PrefixDeclaration declaration : declarations) {
      Objects.requireNonNull(declaration, "prefix declaration is required");
      if (!uniqueDeclarations.add(declaration)) {
        throw new IllegalArgumentException(
            "duplicate owner prefix declaration: " + declaration.prefix());
      }
      PrefixDeclaration existing = byPrefix.putIfAbsent(declaration.prefix(), declaration);
      if (existing != null) {
        throw new IllegalArgumentException(
            "conflicting owner prefix declarations: " + declaration.prefix());
      }
    }
    rejectOverlappingOwnership(declarations);

    Map<String, RedisScriptDescriptor> byId = new HashMap<>();
    Set<String> resourcePaths = new HashSet<>();
    for (RedisScriptDescriptor descriptor : descriptors) {
      Objects.requireNonNull(descriptor, "script descriptor is required");
      if (byId.putIfAbsent(descriptor.scriptId(), descriptor) != null) {
        throw new IllegalArgumentException("duplicate script identifier: " + descriptor.scriptId());
      }
      if (!resourcePaths.add(descriptor.resourcePath())) {
        throw new IllegalArgumentException(
            "duplicate script resource path: " + descriptor.resourcePath());
      }
      validateDeclaredOwnership(descriptor, byPrefix);
    }

    this.descriptorsById = Map.copyOf(byId);
  }

  /**
   * Validates all supplied invocation metadata before returning a handle. The returned handle is
   * declarative only; this registry does not load or execute Redis scripts.
   */
  public RedisInvocationContract prepareInvocation(InvocationRequest request) {
    Objects.requireNonNull(request, "invocation request is required");
    RedisScriptDescriptor descriptor = descriptorsById.get(request.scriptId());
    if (descriptor == null) {
      throw new IllegalArgumentException("unregistered script identifier: " + request.scriptId());
    }

    requireEquals(descriptor.resourcePath(), request.resourcePath(), "script resource path");
    requireEquals(descriptor.sha256(), request.sha256(), "script SHA-256 digest");
    requireEquals(descriptor.owner(), request.owner(), "script owner");
    requireEquals(descriptor.principal(), request.principal(), "Redis principal");
    requireEquals(descriptor.role(), request.role(), "Redis role");

    if (request.keys().size() != descriptor.keys().size()) {
      throw new IllegalArgumentException(
          "KEYS arity mismatch: expected "
              + descriptor.keys().size()
              + ", got "
              + request.keys().size());
    }
    if (request.arguments().size() != descriptor.arguments().size()) {
      throw new IllegalArgumentException(
          "ARGV arity mismatch: expected "
              + descriptor.arguments().size()
              + ", got "
              + request.arguments().size());
    }

    Integer commonSlot = null;
    String commonTag = null;
    for (int index = 0; index < descriptor.keys().size(); index++) {
      RedisScriptDescriptor.KeySpec keySpec = descriptor.keys().get(index);
      String key = request.keys().get(index);
      if (key.isEmpty()
          || containsControlCharacter(key)
          || !key.startsWith(keySpec.ownedPrefix())) {
        throw new IllegalArgumentException(
            "KEYS[" + index + "] does not match its declared owner prefix");
      }

      if (keySpec.hashTagDeclaration() == RedisScriptDescriptor.HashTagDeclaration.REQUIRED) {
        String tag = extractHashTag(key);
        if (tag == null) {
          throw new IllegalArgumentException(
              "KEYS[" + index + "] is missing a non-empty Redis hash tag");
        }
        int slot = clusterSlot(key);
        if (commonTag != null && !commonTag.equals(tag)) {
          throw new IllegalArgumentException("multi-key invocation uses different Redis hash tags");
        }
        if (commonSlot != null && commonSlot != slot) {
          throw new IllegalArgumentException("multi-key invocation crosses Redis Cluster slots");
        }
        commonTag = tag;
        commonSlot = slot;
      }
    }

    return new RedisInvocationContract(descriptor, request.keys(), request.arguments(), commonSlot);
  }

  /** Returns the Redis Cluster slot for a key using the first non-empty {@code {...}} tag. */
  public static int clusterSlot(String key) {
    Objects.requireNonNull(key, "key is required");
    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
    int openingBrace = -1;
    int closingBrace = -1;
    for (int index = 0; index < keyBytes.length; index++) {
      if (keyBytes[index] == '{') {
        openingBrace = index;
        break;
      }
    }
    if (openingBrace >= 0) {
      for (int index = openingBrace + 1; index < keyBytes.length; index++) {
        if (keyBytes[index] == '}') {
          closingBrace = index;
          break;
        }
      }
    }

    byte[] hashInput = keyBytes;
    int offset = 0;
    int length = keyBytes.length;
    if (openingBrace >= 0 && closingBrace > openingBrace + 1) {
      offset = openingBrace + 1;
      length = closingBrace - openingBrace - 1;
    }
    int crc = crc16(hashInput, offset, length);
    return crc % REDIS_CLUSTER_SLOT_COUNT;
  }

  private static void rejectOverlappingOwnership(List<PrefixDeclaration> declarations) {
    for (int leftIndex = 0; leftIndex < declarations.size(); leftIndex++) {
      PrefixDeclaration left = declarations.get(leftIndex);
      for (int rightIndex = leftIndex + 1; rightIndex < declarations.size(); rightIndex++) {
        PrefixDeclaration right = declarations.get(rightIndex);
        boolean overlaps =
            left.prefix().startsWith(right.prefix()) || right.prefix().startsWith(left.prefix());
        boolean samePrincipalScope =
            left.owner().equals(right.owner())
                && left.role() == right.role()
                && left.principal().equals(right.principal());
        if (overlaps && !samePrincipalScope) {
          throw new IllegalArgumentException(
              "overlapping Redis prefixes have conflicting ownership: "
                  + left.prefix()
                  + " and "
                  + right.prefix());
        }
      }
    }
  }

  private static void validateDeclaredOwnership(
      RedisScriptDescriptor descriptor, Map<String, PrefixDeclaration> prefixDeclarations) {
    for (RedisScriptDescriptor.KeySpec key : descriptor.keys()) {
      PrefixDeclaration declaration = prefixDeclarations.get(key.ownedPrefix());
      if (declaration == null) {
        throw new IllegalArgumentException(
            "script "
                + descriptor.scriptId()
                + " references an unowned prefix: "
                + key.ownedPrefix());
      }
      if (!declaration.owner().equals(descriptor.owner())
          || declaration.role() != descriptor.role()
          || !declaration.principal().equals(descriptor.principal())) {
        throw new IllegalArgumentException(
            "script " + descriptor.scriptId() + " has a wrong owner, principal, or Redis role");
      }
    }
  }

  private static String extractHashTag(String key) {
    int openingBrace = key.indexOf('{');
    if (openingBrace < 0) {
      return null;
    }
    int closingBrace = key.indexOf('}', openingBrace + 1);
    int nestedOpeningBrace = key.indexOf('{', openingBrace + 1);
    if (closingBrace <= openingBrace + 1
        || (nestedOpeningBrace >= 0 && nestedOpeningBrace < closingBrace)) {
      return null;
    }
    return key.substring(openingBrace + 1, closingBrace);
  }

  private static int crc16(byte[] bytes, int offset, int length) {
    int crc = 0;
    for (int index = offset; index < offset + length; index++) {
      crc ^= (bytes[index] & 0xff) << 8;
      for (int bit = 0; bit < 8; bit++) {
        crc = (crc & 0x8000) == 0 ? crc << 1 : (crc << 1) ^ 0x1021;
      }
      crc &= 0xffff;
    }
    return crc;
  }

  private static boolean containsControlCharacter(String value) {
    return value.chars().anyMatch(Character::isISOControl);
  }

  private static void requireEquals(Object expected, Object actual, String fieldName) {
    if (!Objects.equals(expected, actual)) {
      throw new IllegalArgumentException(
          "invocation " + fieldName + " does not match its registration");
    }
  }

  public record PrefixDeclaration(
      String owner, RedisScriptDescriptor.RedisRole role, String principal, String prefix) {
    public PrefixDeclaration {
      Objects.requireNonNull(role, "prefix Redis role is required");
      if (owner == null || !owner.matches("[a-z][a-z0-9-]*")) {
        throw new IllegalArgumentException("prefix owner must be a lowercase service identifier");
      }
      if (principal == null || !principal.matches("[a-z][a-z0-9_]*")) {
        throw new IllegalArgumentException("prefix principal must be a lowercase Redis principal");
      }
      if (prefix == null || !prefix.matches("[a-z][a-z0-9_-]*(?::[a-z][a-z0-9_-]*)*:")) {
        throw new IllegalArgumentException("prefix must be colon-delimited and end with a colon");
      }
    }
  }

  /** The supplied identity and exact KEYS/ARGV values to validate before any Redis call. */
  public record InvocationRequest(
      String scriptId,
      String resourcePath,
      String sha256,
      String owner,
      String principal,
      RedisScriptDescriptor.RedisRole role,
      List<String> keys,
      List<String> arguments) {
    public InvocationRequest {
      keys = immutableValues(keys, "KEYS values are required");
      arguments = immutableValues(arguments, "ARGV values are required");
    }

    public List<String> keys() {
      return List.copyOf(keys);
    }

    public List<String> arguments() {
      return List.copyOf(arguments);
    }

    private static List<String> immutableValues(List<String> values, String message) {
      if (values == null) {
        throw new IllegalArgumentException(message);
      }
      return List.copyOf(values);
    }
  }
}
