package net.firedevops.firemud.accountservice.service.session;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.AccountCoordinationPinnedConnectionProvider;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.AcknowledgementRequirements;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Actual private Coordination reader/CAS with exact catalog, ACL, physical connection and WAITAOF.
 */
public final class AccountControlUiCoordination {
  private final AccountCoordinationPinnedConnectionProvider connections;
  private final AcknowledgementRequirements acknowledgements;
  private final RedisScriptCatalog catalog;

  public AccountControlUiCoordination(
      AccountCoordinationPinnedConnectionProvider connections,
      AcknowledgementRequirements acknowledgements,
      RedisScriptCatalog catalog) {
    this.connections = Objects.requireNonNull(connections);
    this.acknowledgements = Objects.requireNonNull(acknowledgements);
    this.catalog = Objects.requireNonNull(catalog);
  }

  /** Read-only pinned GET and physical expiry; no repair or gameplay-profile interpretation. */
  public byte[] readActive(String tokenHash) {
    outsideSql();
    return withConnection(
        commands -> {
          byte[] value = commands.get(key(tokenHash));
          if (value == null || value.length == 0 || value.length > 32768) {
            throw denied();
          }
          var record = AccountControlUiIssuanceRepository.object(value);
          if (!"control-ui".equals(record.get("profile"))
              || !"control-ui".equals(record.get("type"))
              || !"control-ui".equals(record.get("audience"))
              || !"active".equals(record.get("state"))
              || !tokenHash.equals(record.get("tokenHash"))) {
            throw denied();
          }
          Object encodedExpiry = record.get("exp");
          if (!(encodedExpiry instanceof Number number)
              || !number.toString().matches("[1-9][0-9]{0,18}")) {
            throw denied();
          }
          long exactExpiry = Math.multiplyExact(Long.parseLong(number.toString()), 1000L);
          Long physicalExpiry = commands.pexpiretime(key(tokenHash));
          // This distinct minimum profile uses zero cleanup margin, never an inferred/renewed TTL.
          if (physicalExpiry == null || physicalExpiry != exactExpiry) {
            throw denied();
          }
          return value.clone();
        });
  }

  /** Exact readback for the owner's protected delivery workflow. */
  public void requireExactActive(String tokenHash, byte[] exactActive, long expiryMillis) {
    outsideSql();
    withConnection(
        commands -> {
          byte[] key = key(tokenHash);
          if (!Arrays.equals(exactActive, commands.get(key))) {
            throw denied();
          }
          Long expiry = commands.pexpiretime(key);
          if (expiry == null || expiry != expiryMillis) {
            throw denied();
          }
          return null;
        });
  }

  /** Only package-local owner workflow may register a non-authorizing pending candidate. */
  Receipt registerPending(String tokenHash, byte[] exactPending, long expiryMillis) {
    return cas(tokenHash, new byte[0], exactPending, expiryMillis);
  }

  /** The private committed owner readback is mandatory; arbitrary byte callers cannot activate. */
  Receipt activate(AccountControlUiIssuanceRepository.Committed original) {
    return cas(
        original.tokenHash(),
        original.pendingRegistry(),
        original.activeRegistry(),
        original.expiryMillis());
  }

  /** Durable owner intent is non-authorizing even if this external CAS cannot complete. */
  Receipt revoke(AccountControlUiIssuanceRepository.Stored original) {
    outsideSql();
    byte[] replacement = AccountControlUiIssuanceRepository.revokedRegistry(original);
    byte[] expected =
        withConnection(
            commands -> {
              byte[] observed = commands.get(key(original.tokenHash));
              Long expiry = commands.pexpiretime(key(original.tokenHash));
              if (expiry == null
                  || expiry != original.expiryMillis()
                  || (!Arrays.equals(observed, original.pendingRegistry)
                      && !Arrays.equals(observed, original.activeRegistry)
                      && !Arrays.equals(observed, replacement))) {
                throw denied();
              }
              return observed;
            });
    return cas(original.tokenHash, expected, replacement, original.expiryMillis());
  }

  private Receipt cas(String tokenHash, byte[] expected, byte[] replacement, long expiryMillis) {
    outsideSql();
    if (expected == null
        || replacement == null
        || replacement.length == 0
        || replacement.length > 32768
        || expected.length > 32768
        || expiryMillis <= 0) {
      throw denied();
    }
    var descriptor = AccountControlUiRegistryContract.descriptor();
    if (!descriptor.equals(catalog.require(descriptor.scriptId()))) {
      throw denied();
    }
    byte[] source;
    try (var stream = getClass().getClassLoader().getResourceAsStream(descriptor.resourcePath())) {
      if (stream == null) {
        throw denied();
      }
      source = stream.readNBytes(32769);
      if (source.length == 0
          || source.length > 32768
          || !digest("SHA-256", source).equals(descriptor.sha256())) {
        throw denied();
      }
    } catch (java.io.IOException | RuntimeException failure) {
      throw denied();
    }
    return withConnection(
        commands -> {
          byte[] key = key(tokenHash);
          String sha = commands.scriptLoad(source);
          if (!digest("SHA-1", source).equals(sha)) {
            throw denied();
          }
          Long outcome =
              commands.evalsha(
                  sha,
                  ScriptOutputType.INTEGER,
                  new byte[][] {key},
                  expected,
                  replacement,
                  Long.toString(expiryMillis).getBytes(StandardCharsets.US_ASCII));
          if (outcome == null || (outcome != 0L && outcome != 1L)) {
            throw denied();
          }
          List<Object> counts =
              commands.dispatch(
                  WaitAof.INSTANCE,
                  new ArrayOutput<>(ByteArrayCodec.INSTANCE),
                  new CommandArgs<>(ByteArrayCodec.INSTANCE)
                      .add(acknowledgements.requiredLocalAofCount())
                      .add(acknowledgements.requiredReplicaAofCount())
                      .add(acknowledgements.timeoutMillis()));
          if (counts == null
              || counts.size() != 2
              || !(counts.get(0) instanceof Long local)
              || !(counts.get(1) instanceof Long replicas)
              || local < acknowledgements.requiredLocalAofCount()
              || replicas < acknowledgements.requiredReplicaAofCount()) {
            throw denied();
          }
          Long expiry = commands.pexpiretime(key);
          if (!Arrays.equals(replacement, commands.get(key))
              || expiry == null
              || expiry != expiryMillis) {
            throw denied();
          }
          return new Receipt(
              tokenHash,
              digest("SHA-256", replacement),
              expiryMillis,
              acknowledgements.requiredLocalAofCount(),
              acknowledgements.requiredReplicaAofCount());
        });
  }

  private <T> T withConnection(
      java.util.function.Function<RedisCommands<byte[], byte[]>, T> action) {
    try (var connection = connections.openPinnedConnection()) {
      if (connection == null
          || !connection.isOpen()
          || connection.getOptions() == null
          || connection.getOptions().isAutoReconnect()) {
        throw denied();
      }
      var commands = connection.sync();
      if (!"account_coord_app".equals(commands.aclWhoami())) {
        throw denied();
      }
      return action.apply(commands);
    } catch (RuntimeException failure) {
      throw denied();
    }
  }

  private static byte[] key(String tokenHash) {
    if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) {
      throw denied();
    }
    return ("session:auth:token:" + tokenHash).getBytes(StandardCharsets.US_ASCII);
  }

  static String digest(String algorithm, byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value));
    } catch (java.security.NoSuchAlgorithmException | RuntimeException failure) {
      throw denied();
    }
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Coordination calls cannot retain owner SQL locks");
    }
  }

  /** Non-authorizing receipt, constructible only after actual durable exact Redis readback. */
  static final class Receipt {
    final String tokenHash;
    final String recordDigest;
    final long expiryMillis;
    final long localAof;
    final long replicas;

    private Receipt(
        String tokenHash, String recordDigest, long expiryMillis, long localAof, long replicas) {
      this.tokenHash = tokenHash;
      this.recordDigest = recordDigest;
      this.expiryMillis = expiryMillis;
      this.localAof = localAof;
      this.replicas = replicas;
    }
  }

  private enum WaitAof implements ProtocolKeyword {
    INSTANCE;

    @Override
    public byte[] getBytes() {
      return "WAITAOF".getBytes(StandardCharsets.US_ASCII);
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Exact Account control-ui Coordination evidence unavailable");
  }
}
