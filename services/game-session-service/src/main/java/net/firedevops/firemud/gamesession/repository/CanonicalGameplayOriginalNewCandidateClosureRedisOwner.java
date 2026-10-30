package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewCandidateClosureRedisScriptContribution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact-original, manually composed closure retaining exclusion through its continuation. */
public final class CanonicalGameplayOriginalNewCandidateClosureRedisOwner {
  public static final String PURPOSE = "original-new-never-admitted-closure/v1";
  public static final String FAMILY_SCOPE = "session:game:*";
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final int MAX_SCRIPT_BYTES = 32 * 1024;
  private final RedisClient redis;
  private final RedisScriptCatalog catalog;
  private final CanonicalGameplayBindingInventoryRepository inventory;
  private final CanonicalGameplayTenantGameplayTagRepository tags;
  private final CanonicalGameplayOriginalNewClosureQualification qualification;

  CanonicalGameplayOriginalNewCandidateClosureRedisOwner(
      RedisClient redis,
      RedisScriptCatalog catalog,
      CanonicalGameplayBindingInventoryRepository inventory,
      CanonicalGameplayTenantGameplayTagRepository tags,
      CanonicalGameplayOriginalNewClosureQualification qualification) {
    this.redis = Objects.requireNonNull(redis);
    this.catalog = Objects.requireNonNull(catalog);
    this.inventory = Objects.requireNonNull(inventory);
    this.tags = Objects.requireNonNull(tags);
    this.qualification = Objects.requireNonNull(qualification);
  }

  /** The callback, including Account's response, finishes before external exclusion is released. */
  public <T> T withClosedCandidate(
      java.util.UUID transitionId, Function<Readback, T> continuation) {
    requireNoSql();
    Objects.requireNonNull(continuation);
    var selected = inventory.selectOriginalNewAbandonment(transitionId);
    try (var held = acquire(selected)) {
      var assignment = assignment(selected);
      var keys = keys(selected, assignment);
      closePhysical(selected, assignment, keys, held);
      Readback actual = readPhysical(selected, assignment, keys, held);
      var closed = inventory.recordOriginalNewAbandonmentClosure(selected, actual);
      if (!actual.matches(closed)) throw unavailable();
      T result = continuation.apply(actual);
      held.requireCurrent();
      return result;
    }
  }

  /** A reverse-owner read rechecks physical state; a stored SQL closure digest is insufficient. */
  public <T> T withCurrentClosedCandidate(
      java.util.UUID transitionId, Function<Readback, T> continuation) {
    requireNoSql();
    Objects.requireNonNull(continuation);
    var source = inventory.readOriginalNewAbandonmentSource(transitionId);
    if (source.state()
        != CanonicalGameplayOriginalNewAbandonmentSource.State.NEVER_ADMITTED_CLOSED) {
      throw new CanonicalGameplayBindingInventoryConflictException(
          "Original NEW source has no retained negative closure");
    }
    try (var held = acquire(source)) {
      var assignment = assignment(source);
      Readback actual = readPhysical(source, assignment, keys(source, assignment), held);
      var current = inventory.readOriginalNewAbandonmentSource(transitionId);
      if (!actual.matches(current))
        throw new CanonicalGameplayBindingInventoryConflictException(
            "Original NEW closure differs from its retained source");
      T result = continuation.apply(actual);
      held.requireCurrent();
      return result;
    }
  }

  private Held acquire(CanonicalGameplayOriginalNewAbandonmentSource source) {
    String protectedBinding =
        HexFormat.of().formatHex(source.candidate().bindingIdentity().bindingRef().bytes());
    var lease =
        qualification
            .acquire(FAMILY_SCOPE, protectedBinding)
            .orElseThrow(CanonicalGameplayOriginalNewCandidateClosureRedisOwner::unavailable);
    var held = new Held(lease, protectedBinding);
    try {
      held.requireCurrent();
      return held;
    } catch (RuntimeException failure) {
      held.close();
      throw failure;
    }
  }

  private void closePhysical(
      CanonicalGameplayOriginalNewAbandonmentSource source,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      byte[][] keys,
      Held held) {
    requireNoSql();
    var expected =
        new CanonicalGameplayOriginalNewCandidateClosureRedisScriptContribution()
            .descriptors()
            .iterator()
            .next();
    if (!expected.equals(catalog.require(expected.scriptId()))) throw unavailable();
    byte[] script;
    try (InputStream input =
        getClass().getClassLoader().getResourceAsStream(expected.resourcePath())) {
      if (input == null) throw unavailable();
      script = input.readNBytes(MAX_SCRIPT_BYTES + 1);
      if (script.length == 0
          || script.length > MAX_SCRIPT_BYTES
          || !sha256(script).substring(7).equals(expected.sha256())) throw unavailable();
    } catch (IOException failure) {
      throw unavailable();
    }
    try (var connection = redis.connect(ByteArrayCodec.INSTANCE)) {
      var commands = connection.async();
      requirePrincipal(commands);
      String scriptSha = await(commands.scriptLoad(script));
      if (!hexDigest("SHA-1", script).equals(scriptSha)) throw unavailable();
      held.requireCurrent();
      var current = inventory.readOriginalNewAbandonmentSource(source.transition().transitionId());
      requireSameSelection(source, current);
      Long outcome =
          await(
              commands.evalsha(
                  scriptSha, ScriptOutputType.INTEGER, keys, arguments(source, assignment)));
      if (outcome == null || (outcome != 1L && outcome != 2L)) throw unavailable();
      held.requireCurrent();
    }
  }

  private Readback readPhysical(
      CanonicalGameplayOriginalNewAbandonmentSource source,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      byte[][] keys,
      Held held) {
    requireNoSql();
    held.requireCurrent();
    byte[] expectedFence = fence(source);
    try (var connection = redis.connect(ByteArrayCodec.INSTANCE)) {
      var commands = connection.async();
      requirePrincipal(commands);
      if (!"string".equals(await(commands.type(keys[0])))
          || !Long.valueOf(-1L).equals(await(commands.pttl(keys[0])))
          || !Arrays.equals(await(commands.get(keys[0])), expectedFence)) throw unavailable();
      String characterType = await(commands.type(keys[1]));
      if (!"none".equals(characterType) && !"string".equals(characterType)) throw unavailable();
      if (!Long.valueOf("none".equals(characterType) ? -2L : -1L)
          .equals(await(commands.pttl(keys[1])))) throw unavailable();
      if ("string".equals(characterType)
          && Arrays.equals(await(commands.get(keys[1])), characterProjection(source.candidate()))) {
        throw unavailable();
      }
      for (int index = 2; index < 4; index++) {
        String type = await(commands.type(keys[index]));
        if (!"none".equals(type) && !"set".equals(type)) throw unavailable();
        if (!Long.valueOf("none".equals(type) ? -2L : -1L).equals(await(commands.pttl(keys[index])))
            || Boolean.TRUE.equals(
                await(
                    commands.sismember(
                        keys[index], ascii(source.candidate().bindingIdentity().sessionId())))))
          throw unavailable();
      }
      if (!Arrays.equals(await(commands.get(keys[0])), expectedFence)
          || !Long.valueOf(-1L).equals(await(commands.pttl(keys[0])))) throw unavailable();
      held.requireCurrent();
      return new Readback(source, assignment, keys, expectedFence, held);
    }
  }

  private CanonicalGameplayTenantGameplayTagRepository.Assignment assignment(
      CanonicalGameplayOriginalNewAbandonmentSource source) {
    var assignment =
        tags.assignmentForOriginalNewAbandonment(
            source.transition().transitionId(), source.abandonmentId());
    var binding = source.candidate().bindingIdentity();
    if (!assignment.isBindingTransition()
        || !source.transition().transitionId().equals(assignment.transitionId())
        || !binding.tenantId().equals(assignment.tenantId())
        || !binding.accountId().equals(assignment.accountId())
        || !binding.playableStateNamespaceId().equals(assignment.playableStateNamespaceId())
        || !binding.characterId().equals(assignment.characterId())
        || !binding.gameInstanceId().equals(assignment.gameInstanceId())
        || !binding.sessionId().equals(assignment.sessionId())) throw unavailable();
    CanonicalGameplayTenantGameplayTagRepository.requireCanonicalTag(
        assignment.tenantGameplayTag());
    return assignment;
  }

  private static byte[][] keys(
      CanonicalGameplayOriginalNewAbandonmentSource source,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment) {
    var binding = source.candidate().bindingIdentity().bindingRef();
    return new byte[][] {
      ascii(CanonicalGameplaySessionRedisKeys.session(assignment, binding)),
      ascii(CanonicalGameplaySessionRedisKeys.characterIndex(assignment, binding)),
      ascii(CanonicalGameplaySessionRedisKeys.accountTenantIndex(assignment, binding)),
      ascii(CanonicalGameplaySessionRedisKeys.tenantIndex(assignment, binding))
    };
  }

  private static byte[][] arguments(
      CanonicalGameplayOriginalNewAbandonmentSource source,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment) {
    var candidate = source.candidate();
    var binding = candidate.bindingIdentity();
    return new byte[][] {
      ascii(PURPOSE),
      ascii(binding.tenantId().toString()),
      ascii(binding.accountId().toString()),
      ascii(binding.playableStateNamespaceId().toString()),
      ascii(binding.characterId().toString()),
      ascii(binding.gameInstanceId().toString()),
      ascii(binding.sessionId()),
      ascii(candidate.transition().bindingGeneration().toString()),
      ascii(assignment.tenantGameplayTag()),
      candidate.canonicalBytes(),
      ascii(source.abandonmentId().toString()),
      ascii(sha256(candidate.canonicalBytes()))
    };
  }

  private static byte[] fence(CanonicalGameplayOriginalNewAbandonmentSource source) {
    return ascii(
        PURPOSE + ":" + source.abandonmentId() + ":" + sha256(source.candidate().canonicalBytes()));
  }

  private static byte[] characterProjection(CanonicalGameplayOriginalNewCandidate candidate) {
    var binding = candidate.bindingIdentity();
    return ascii(
        "{\"sessionId\":\""
            + binding.sessionId()
            + "\",\"gameInstanceId\":\""
            + binding.gameInstanceId()
            + "\",\"bindingGeneration\":\""
            + candidate.transition().bindingGeneration()
            + "\"}");
  }

  private static void requireSameSelection(
      CanonicalGameplayOriginalNewAbandonmentSource expected,
      CanonicalGameplayOriginalNewAbandonmentSource actual) {
    if (!expected.abandonmentId().equals(actual.abandonmentId())
        || !expected.candidate().equals(actual.candidate()))
      throw new CanonicalGameplayBindingInventoryConflictException(
          "Original NEW closure selection changed");
  }

  private static void requirePrincipal(RedisAsyncCommands<byte[], byte[]> commands) {
    if (!PRINCIPAL.equals(await(commands.aclWhoami()))) throw unavailable();
  }

  private static <T> T await(RedisFuture<T> future) {
    try {
      return future.get(5, TimeUnit.SECONDS);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (ExecutionException | TimeoutException failure) {
      throw unavailable();
    }
  }

  private static String sha256(byte[] bytes) {
    return "sha256:" + hexDigest("SHA-256", bytes);
  }

  public static String candidateDigest(CanonicalGameplayOriginalNewCandidate candidate) {
    return sha256(Objects.requireNonNull(candidate).canonicalBytes());
  }

  private static String hexDigest(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM digest unavailable", impossible);
    }
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static void requireNoSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
  }

  private static UnavailableException unavailable() {
    return new UnavailableException();
  }

  /**
   * Missing physical closure or continuously held external qualification, never source conflict.
   */
  public static final class UnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public UnavailableException() {
      super("Original NEW negative closure is unavailable");
    }
  }

  /** Owner-produced proof valid only inside the continuously qualified continuation. */
  public static final class Readback {
    private final CanonicalGameplayOriginalNewAbandonmentSource source;
    private final CanonicalGameplayTenantGameplayTagRepository.Assignment assignment;
    private final byte[][] keys;
    private final byte[] fence;
    private final Held held;
    private final String digest;

    private Readback(
        CanonicalGameplayOriginalNewAbandonmentSource source,
        CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
        byte[][] keys,
        byte[] fence,
        Held held) {
      this.source = source;
      this.assignment = assignment;
      this.keys = Arrays.stream(keys).map(byte[]::clone).toArray(byte[][]::new);
      this.fence = fence.clone();
      this.held = held;
      try {
        var output = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(output)) {
          data.writeUTF(PURPOSE);
          for (byte[] key : keys) {
            data.writeInt(key.length);
            data.write(key);
          }
          data.writeInt(fence.length);
          data.write(fence);
          data.writeUTF("persistent-fence/exact-original-character-and-set-members-absent/v1");
        }
        this.digest = sha256(output.toByteArray());
      } catch (IOException impossible) {
        throw new IllegalStateException("Closure readback encoding failed", impossible);
      }
    }

    public boolean matches(CanonicalGameplayOriginalNewAbandonmentSource expected) {
      held.requireCurrent();
      requireSameSelection(source, expected);
      return Arrays.deepEquals(keys, keys(expected, assignment))
          && Arrays.equals(fence, fence(expected))
          && (expected.closureDigest() == null || expected.closureDigest().equals(digest));
    }

    public String digest() {
      held.requireCurrent();
      return digest;
    }

    public CanonicalGameplayOriginalNewAbandonmentSource source() {
      held.requireCurrent();
      return source;
    }

    @Override
    public String toString() {
      return "OriginalNewNegativeClosureReadback[redacted]";
    }
  }

  private static final class Held implements AutoCloseable {
    private final CanonicalGameplayOriginalNewClosureQualification.Lease lease;
    private final String protectedBinding;
    private final long fence;
    private boolean closed;

    private Held(CanonicalGameplayOriginalNewClosureQualification.Lease lease, String binding) {
      this.lease = Objects.requireNonNull(lease);
      this.protectedBinding = binding;
      this.fence = lease.fence();
    }

    private void requireCurrent() {
      if (closed
          || fence <= 0
          || lease.fence() != fence
          || !FAMILY_SCOPE.equals(lease.scope())
          || !protectedBinding.equals(lease.protectedBindingId())) throw unavailable();
      try {
        lease.requireCurrent();
      } catch (IllegalStateException withdrawn) {
        throw unavailable();
      }
    }

    @Override
    public void close() {
      closed = true;
      lease.close();
    }
  }
}
