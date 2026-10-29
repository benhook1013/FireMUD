package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewCandidateRedisScriptContribution;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manual, unregistered owner for one original public-production NEW candidate installation. The
 * result and physical readback remain explicitly non-admitting.
 */
public final class CanonicalGameplayOriginalNewCandidateRedisOwner {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final int MAX_SCRIPT_BYTES = 32 * 1024;
  // Candidate acceptance follows the codec. Retained V1048 and Lua storage guards allow
  // up to 512 KiB defensively; they do not widen the codec's 128 KiB input contract.
  private static final int MAX_CANDIDATE_BYTES =
      CanonicalGameplayOriginalNewCandidateCodec.MAX_CANDIDATE_BYTES;
  private static final long COMMAND_TIMEOUT_MILLIS = 5_000;
  private static final List<RedisScriptDescriptor.KeySpec> EXPECTED_KEYS =
      List.of(
          new RedisScriptDescriptor.KeySpec(
              "session", "session:game:", RedisScriptDescriptor.HashTagDeclaration.REQUIRED),
          new RedisScriptDescriptor.KeySpec(
              "characterIndex",
              "session:game:index:character:",
              RedisScriptDescriptor.HashTagDeclaration.REQUIRED),
          new RedisScriptDescriptor.KeySpec(
              "accountTenantIndex",
              "session:game:index:account-tenant:",
              RedisScriptDescriptor.HashTagDeclaration.REQUIRED),
          new RedisScriptDescriptor.KeySpec(
              "tenantIndex",
              "session:game:index:tenant:",
              RedisScriptDescriptor.HashTagDeclaration.REQUIRED));
  private static final List<RedisScriptDescriptor.ArgumentSpec> EXPECTED_ARGUMENTS =
      List.of(
          new RedisScriptDescriptor.ArgumentSpec("purpose"),
          new RedisScriptDescriptor.ArgumentSpec("tenantId"),
          new RedisScriptDescriptor.ArgumentSpec("accountId"),
          new RedisScriptDescriptor.ArgumentSpec("playableStateNamespaceId"),
          new RedisScriptDescriptor.ArgumentSpec("characterId"),
          new RedisScriptDescriptor.ArgumentSpec("gameInstanceId"),
          new RedisScriptDescriptor.ArgumentSpec("sessionId"),
          new RedisScriptDescriptor.ArgumentSpec("bindingGeneration"),
          new RedisScriptDescriptor.ArgumentSpec("tenantGameplayTag"),
          new RedisScriptDescriptor.ArgumentSpec("candidateBytes"));
  private static final List<RedisScriptDescriptor.OutcomeSpec> EXPECTED_OUTCOMES =
      List.of(
          outcome(
              "CANDIDATE_CREATED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          outcome(
              "EXISTING_STATE_REFUSED",
              RedisScriptDescriptor.OutcomeCategory.STALE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          outcome(
              "INVALID_ARGUMENTS",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING));

  private final RedisClient redisClient;
  private final RedisScriptCatalog catalog;
  private final CanonicalGameplayBindingInventoryRepository inventory;
  private final CanonicalGameplayTenantGameplayTagRepository tenantTags;

  CanonicalGameplayOriginalNewCandidateRedisOwner(
      RedisClient redisClient,
      RedisScriptCatalog catalog,
      CanonicalGameplayBindingInventoryRepository inventory,
      CanonicalGameplayTenantGameplayTagRepository tenantTags) {
    this.redisClient = Objects.requireNonNull(redisClient, "Configured Redis client required");
    this.catalog = Objects.requireNonNull(catalog, "Manual Redis script catalog required");
    this.inventory = Objects.requireNonNull(inventory, "Binding inventory repository required");
    this.tenantTags = Objects.requireNonNull(tenantTags, "Persisted tenant-tag owner required");
  }

  /**
   * Uses the one SQL-issued create permission or performs only current physical readback. Exact
   * readback is then accepted through the existing source/Account/runtime-guarded provisional CAS.
   */
  public Installation installOnce(
      net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision
          originalDecision) {
    Objects.requireNonNull(originalDecision, "original Account decision");
    requireNoAmbientTransaction();
    var source = inventory.readOriginalNewCandidateInstallationSource(originalDecision);
    CanonicalGameplayOriginalNewCandidate candidate = source.candidate();
    CanonicalGameplayTenantGameplayTagRepository.Assignment assignment;
    try {
      requireNoAmbientTransaction();
      assignment =
          tenantTags.assignmentForTransition(source.evidence().transition().transitionId());
      requireAssignment(assignment, candidate);
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
    Keys keys = keys(assignment, candidate);

    if (source.state()
        == CanonicalGameplayBindingInventoryRepository.OriginalNewCandidateInstallationState
            .NEVER_DISPATCHED) {
      try (PreparedCreate prepared = prepareCreate(candidate)) {
        // Revalidate every current SQL source after preflight and commit before the sole EVAL.
        var claim = inventory.claimOriginalNewCandidateInstallation(originalDecision);
        if (!claim.candidate().equals(candidate)) throw unavailable();
        if (claim.consumeCreatePermission()) {
          CreateAttempt attempt = createOnce(claim, assignment, keys, prepared);
          if (attempt == CreateAttempt.REFUSED) throw unavailable();
          // A lost reply remains uncertain until the separate exact physical read succeeds.
        }
      }
    }

    PhysicalReadback readback = readExact(candidate, assignment, keys);
    CanonicalGameplayBindingInventoryRepository.OriginalNewCandidateInstallationClaim freshClaim =
        inventory.claimOriginalNewCandidateInstallation(originalDecision);
    if (!freshClaim.candidate().equals(candidate)) throw unavailable();
    var provisional = inventory.markOriginalNewCandidateProvisional(freshClaim, readback);
    if (provisional.status()
            != net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot
                .Status.PROVISIONAL
        || !provisional.transitionId().equals(candidate.transition().transitionId())) {
      throw unavailable();
    }
    return new Installation(candidate, assignment, keys, readback, provisional);
  }

  private PreparedCreate prepareCreate(CanonicalGameplayOriginalNewCandidate candidate) {
    requireNoAmbientTransaction();
    byte[] script = scriptSource(requireDescriptor());
    byte[] candidateBytes = candidate.canonicalBytes();
    if (candidateBytes.length == 0 || candidateBytes.length > MAX_CANDIDATE_BYTES) {
      throw unavailable();
    }
    StatefulRedisConnection<byte[], byte[]> connection;
    try {
      connection = redisClient.connect(ByteArrayCodec.INSTANCE);
    } catch (RuntimeException failure) {
      throw unavailable();
    }
    try {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(await(commands.aclWhoami()));
      String loadedSha = await(commands.scriptLoad(script));
      if (!digest("SHA-1", script).equals(loadedSha)) throw unavailable();
      return new PreparedCreate(connection, commands, loadedSha);
    } catch (RuntimeException failure) {
      connection.close();
      throw unavailable();
    }
  }

  private CreateAttempt createOnce(
      CanonicalGameplayBindingInventoryRepository.OriginalNewCandidateInstallationClaim claim,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      Keys keys,
      PreparedCreate prepared) {
    requireNoAmbientTransaction();
    byte[] candidateBytes = claim.candidate().canonicalBytes();
    try {
      Long result =
          await(
              prepared
                  .commands()
                  .evalsha(
                      prepared.loadedSha(),
                      ScriptOutputType.INTEGER,
                      keyBytes(keys),
                      ascii(CanonicalGameplayOriginalNewCandidate.PURPOSE),
                      ascii(assignment.tenantId().toString()),
                      ascii(assignment.accountId().toString()),
                      ascii(assignment.playableStateNamespaceId().toString()),
                      ascii(assignment.characterId().toString()),
                      ascii(assignment.gameInstanceId().toString()),
                      ascii(assignment.sessionId()),
                      ascii(claim.candidate().transition().bindingGeneration().toString()),
                      ascii(assignment.tenantGameplayTag()),
                      candidateBytes));
      return Long.valueOf(1L).equals(result) ? CreateAttempt.CREATED : CreateAttempt.REFUSED;
    } catch (RuntimeException possibleLostReply) {
      // DISPATCH_POSSIBLE is already durable. The caller will only attempt an exact read.
      return CreateAttempt.REPLY_UNCERTAIN;
    }
  }

  private PhysicalReadback readExact(
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      Keys keys) {
    requireNoAmbientTransaction();
    byte[] candidateBytes = candidate.canonicalBytes();
    byte[] characterBytes = characterProjection(candidate);
    byte[] sessionKey = ascii(keys.session());
    byte[] characterKey = ascii(keys.characterIndex());
    byte[] accountTenantKey = ascii(keys.accountTenantIndex());
    byte[] tenantKey = ascii(keys.tenantIndex());
    byte[] sessionMember = ascii(candidate.bindingIdentity().sessionId());
    try (StatefulRedisConnection<byte[], byte[]> connection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(await(commands.aclWhoami()));
      if (!"string".equals(await(commands.type(sessionKey)))
          || !Long.valueOf(-1L).equals(await(commands.pttl(sessionKey)))
          || !Arrays.equals(candidateBytes, await(commands.get(sessionKey)))
          || !"string".equals(await(commands.type(characterKey)))
          || !Long.valueOf(-1L).equals(await(commands.pttl(characterKey)))
          || !Arrays.equals(characterBytes, await(commands.get(characterKey)))
          || !persistentMember(commands, accountTenantKey, sessionMember)
          || !persistentMember(commands, tenantKey, sessionMember)) {
        throw unavailable();
      }
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
    return new PhysicalReadback(candidate, assignment, keys, candidateBytes, characterBytes);
  }

  private static boolean persistentMember(
      RedisAsyncCommands<byte[], byte[]> commands, byte[] key, byte[] member) {
    String type = await(commands.type(key));
    Long ttl = await(commands.pttl(key));
    return "set".equals(type)
        && Long.valueOf(-1L).equals(ttl)
        && Boolean.TRUE.equals(await(commands.sismember(key, member)));
  }

  private RedisScriptDescriptor requireDescriptor() {
    try {
      RedisScriptDescriptor descriptor =
          catalog.require(CanonicalGameplayOriginalNewCandidateRedisScriptContribution.SCRIPT_ID);
      if (!CanonicalGameplayOriginalNewCandidateRedisScriptContribution.SCRIPT_ID.equals(
              descriptor.scriptId())
          || !CanonicalGameplayOriginalNewCandidateRedisScriptContribution.RESOURCE_PATH.equals(
              descriptor.resourcePath())
          || !"game-session-service".equals(descriptor.owner())
          || !PRINCIPAL.equals(descriptor.principal())
          || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION
          || !EXPECTED_KEYS.equals(descriptor.keys())
          || !EXPECTED_ARGUMENTS.equals(descriptor.arguments())
          || descriptor.category() != RedisScriptDescriptor.ScriptCategory.SESSION_CAS
          || !EXPECTED_OUTCOMES.equals(descriptor.outcomes())
          || descriptor.resetSensitivity() != RedisScriptDescriptor.ResetSensitivity.CLUSTER
          || descriptor.lossClass()
              != RedisScriptDescriptor.LossClass.EMPTY_REDIS_COLD_START_OR_RESET
          || descriptor.compatibilityLevel()
              != RedisScriptDescriptor.CompatibilityLevel.REQUIRES_CLUSTER_RESET) {
        throw unavailable();
      }
      return descriptor;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private byte[] scriptSource(RedisScriptDescriptor descriptor) {
    try (InputStream input =
        getClass().getClassLoader().getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) throw unavailable();
      byte[] bytes = input.readNBytes(MAX_SCRIPT_BYTES + 1);
      if (bytes.length == 0
          || bytes.length > MAX_SCRIPT_BYTES
          || !digest("SHA-256", bytes).equals(descriptor.sha256())) {
        throw unavailable();
      }
      return bytes;
    } catch (IOException | RuntimeException failure) {
      throw unavailable();
    }
  }

  private static Keys keys(
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayOriginalNewCandidate candidate) {
    var binding = candidate.bindingIdentity().bindingRef();
    return new Keys(
        CanonicalGameplaySessionRedisKeys.session(assignment, binding),
        CanonicalGameplaySessionRedisKeys.characterIndex(assignment, binding),
        CanonicalGameplaySessionRedisKeys.accountTenantIndex(assignment, binding),
        CanonicalGameplaySessionRedisKeys.tenantIndex(assignment, binding));
  }

  private static byte[] characterProjection(CanonicalGameplayOriginalNewCandidate candidate) {
    var binding = candidate.bindingIdentity();
    return ("{\"sessionId\":\""
            + binding.sessionId()
            + "\",\"gameInstanceId\":\""
            + binding.gameInstanceId()
            + "\",\"bindingGeneration\":\""
            + candidate.transition().bindingGeneration()
            + "\"}")
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static void requireAssignment(
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayOriginalNewCandidate candidate) {
    CanonicalGameplayBindingIdentity binding = candidate.bindingIdentity();
    if (!assignment.isBindingTransition()
        || !candidate.transition().transitionId().equals(assignment.transitionId())
        || !binding.tenantId().equals(assignment.tenantId())
        || !binding.accountId().equals(assignment.accountId())
        || !binding.playableStateNamespaceId().equals(assignment.playableStateNamespaceId())
        || !binding.characterId().equals(assignment.characterId())
        || !binding.gameInstanceId().equals(assignment.gameInstanceId())
        || !binding.sessionId().equals(assignment.sessionId())) {
      throw unavailable();
    }
    CanonicalGameplayTenantGameplayTagRepository.requireCanonicalTag(
        assignment.tenantGameplayTag());
  }

  private static byte[][] keyBytes(Keys keys) {
    return new byte[][] {
      ascii(keys.session()),
      ascii(keys.characterIndex()),
      ascii(keys.accountTenantIndex()),
      ascii(keys.tenantIndex())
    };
  }

  private static RedisAsyncCommands<byte[], byte[]> requireCommands(
      StatefulRedisConnection<byte[], byte[]> connection) {
    RedisAsyncCommands<byte[], byte[]> commands = connection.async();
    if (commands == null) throw unavailable();
    return commands;
  }

  private static <T> T await(RedisFuture<T> future) {
    try {
      T result = future.get(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      if (result == null) throw unavailable();
      return result;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (ExecutionException | TimeoutException failure) {
      throw unavailable();
    }
  }

  private static void requirePrincipal(String principal) {
    if (!PRINCIPAL.equals(principal)) throw unavailable();
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Original NEW Redis work cannot run inside an ambient SQL transaction or synchronization");
    }
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM digest algorithm is unavailable", impossible);
    }
  }

  private static RedisScriptDescriptor.OutcomeSpec outcome(
      String code,
      RedisScriptDescriptor.OutcomeCategory category,
      RedisScriptDescriptor.MutationEffect effect) {
    return new RedisScriptDescriptor.OutcomeSpec(code, category, effect);
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Original NEW candidate install/readback is unavailable");
  }

  private record PreparedCreate(
      StatefulRedisConnection<byte[], byte[]> connection,
      RedisAsyncCommands<byte[], byte[]> commands,
      String loadedSha)
      implements AutoCloseable {
    @Override
    public void close() {
      connection.close();
    }
  }

  private enum CreateAttempt {
    CREATED,
    REPLY_UNCERTAIN,
    REFUSED
  }

  private record Keys(
      String session, String characterIndex, String accountTenantIndex, String tenantIndex) {}

  /** Private-constructor physical proof from a separate exact read of all four scoped keys. */
  public static final class PhysicalReadback {
    private final String transitionId;
    private final CanonicalGameplayOriginalNewCandidate candidate;
    private final CanonicalGameplayTenantGameplayTagRepository.Assignment assignment;
    private final Keys keys;
    private final byte[] candidateBytes;
    private final byte[] characterBytes;
    private final String digest;

    private PhysicalReadback(
        CanonicalGameplayOriginalNewCandidate candidate,
        CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
        Keys keys,
        byte[] candidateBytes,
        byte[] characterBytes) {
      this.transitionId = candidate.transition().transitionId().toString();
      this.candidate = candidate;
      this.assignment = assignment;
      this.keys = keys;
      this.candidateBytes = candidateBytes.clone();
      this.characterBytes = characterBytes.clone();
      this.digest =
          exactReadbackDigest(
              keys, candidateBytes, characterBytes, candidate.bindingIdentity().sessionId());
    }

    String digest() {
      return digest;
    }

    boolean matches(
        CanonicalGameplayOriginalNewCandidate expected,
        CanonicalGameplayBindingProvisionalCasEvidence evidence) {
      try {
        requireAssignment(assignment, expected);
        Keys expectedKeys = keys(assignment, expected);
        return candidate.equals(expected)
            && transitionId.equals(evidence.transition().transitionId().toString())
            && expected.transition().transitionId().equals(evidence.transition().transitionId())
            && expected.matchesProvisionalSources(evidence)
            && keys.equals(expectedKeys)
            && Arrays.equals(candidateBytes, expected.canonicalBytes())
            && Arrays.equals(characterBytes, characterProjection(expected))
            && digest.equals(
                exactReadbackDigest(
                    keys, candidateBytes, characterBytes, expected.bindingIdentity().sessionId()));
      } catch (RuntimeException invalidSource) {
        return false;
      }
    }

    @Override
    public String toString() {
      return "PhysicalReadback[transitionId=" + transitionId + ", nonAdmitting=true]";
    }
  }

  /**
   * Owner result handed to Main for later exact candidate comparison and final-CAS preimage use.
   */
  public static final class Installation {
    private final CanonicalGameplayOriginalNewCandidate candidate;
    private final CanonicalGameplayTenantGameplayTagRepository.Assignment assignment;
    private final Keys keys;
    private final PhysicalReadback physicalReadback;
    private final net.firedevops.firemud.gamesession.binding
            .CanonicalGameplayBindingTransitionSnapshot
        provisional;

    private Installation(
        CanonicalGameplayOriginalNewCandidate candidate,
        CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
        Keys keys,
        PhysicalReadback physicalReadback,
        net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot
            provisional) {
      this.candidate = candidate;
      this.assignment = assignment;
      this.keys = keys;
      this.physicalReadback = physicalReadback;
      this.provisional = provisional;
    }

    public CanonicalGameplayOriginalNewCandidate candidate() {
      return candidate;
    }

    public byte[] exactCandidateBytes() {
      return candidate.canonicalBytes();
    }

    public String sessionKey() {
      return keys.session();
    }

    public String characterIndexKey() {
      return keys.characterIndex();
    }

    public String accountTenantIndexKey() {
      return keys.accountTenantIndex();
    }

    public String tenantIndexKey() {
      return keys.tenantIndex();
    }

    public CanonicalGameplayTenantGameplayTagRepository.Assignment assignment() {
      return assignment;
    }

    public PhysicalReadback physicalReadback() {
      return physicalReadback;
    }

    public net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot
        provisionalTransition() {
      return provisional;
    }

    public boolean isNonAdmittingCandidate() {
      return true;
    }

    @Override
    public String toString() {
      return "Installation[transitionId="
          + candidate.transition().transitionId()
          + ", purpose="
          + CanonicalGameplayOriginalNewCandidate.PURPOSE
          + ", nonAdmitting=true]";
    }
  }

  private static String exactReadbackDigest(
      Keys keys, byte[] candidateBytes, byte[] characterBytes, String sessionId) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(buffer)) {
        output.write("original-new-candidate-readback/v1".getBytes(StandardCharsets.US_ASCII));
        writeFrame(output, keys.session().getBytes(StandardCharsets.US_ASCII));
        writeFrame(output, candidateBytes);
        writeFrame(output, keys.characterIndex().getBytes(StandardCharsets.US_ASCII));
        writeFrame(output, characterBytes);
        writeFrame(output, keys.accountTenantIndex().getBytes(StandardCharsets.US_ASCII));
        writeFrame(output, sessionId.getBytes(StandardCharsets.US_ASCII));
        writeFrame(output, keys.tenantIndex().getBytes(StandardCharsets.US_ASCII));
        writeFrame(output, sessionId.getBytes(StandardCharsets.US_ASCII));
      }
      return "sha256:" + digest("SHA-256", buffer.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException("Cannot encode exact physical readback", impossible);
    }
  }

  private static void writeFrame(DataOutputStream output, byte[] bytes) throws IOException {
    output.writeInt(bytes.length);
    output.write(bytes);
  }
}
