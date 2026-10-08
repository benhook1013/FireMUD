package net.firedevops.firemud.accountservice.service.session;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingIntent;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjectionRedisContract;
import net.firedevops.firemud.accountservice.service.AccountRedisScriptDescriptorLoader;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Private, pinned-connection Account Coordination client for issuance and source projections.
 *
 * <p>The caller must supply an Account-private connection provider. Every operation independently
 * proves the live ACL identity with {@code ACL WHOAMI}; a caller-supplied label is not accepted.
 * This class is deliberately not Spring-wired. Endpoint/TLS/ACL provisioning remains an external
 * protected binding and is not established by this helper.
 */
public final class AccountGameplayDelegationRedisClient {
  public static final String SCRIPT_ID = "account-game-session-delegation-pending-register.v1";
  public static final String ISSUER_PROJECTION_SCRIPT_ID =
      "account-game-session-delegation-authority-project-issuer.v1";
  public static final String ACCOUNT_PROJECTION_SCRIPT_ID =
      AccountGenerationProjectionRedisContract.SCRIPT_ID;
  public static final String TENANT_PROJECTION_SCRIPT_ID =
      "account.selected-tenant-generation-projection.v1";
  public static final String MEMBERSHIP_PROJECTION_SCRIPT_ID =
      "account.selected-membership-generation-projection.v1";
  public static final String ACTIVE_TRANSITION_SCRIPT_ID =
      "account-game-session-delegation-activate-committed.v1";
  private static final String AUTHORITY_PROJECTION_SCRIPT_RESOURCE =
      "redis/lua/account-game-session-delegation-authority-project.lua";
  private static final String ACTIVE_TRANSITION_SCRIPT_RESOURCE =
      "redis/lua/account-game-session-delegation-activate-committed.lua";
  public static final String TOKEN_KEY_PREFIX = "session:auth:token:";
  public static final String REQUIRED_ACL_IDENTITY = "account_coord_app";
  private static final int MAX_SCRIPT_SOURCE_BYTES = 32 * 1024;
  private static final long MAX_CLEANUP_MARGIN_MILLIS = 300_000L;
  private static final Pattern TOKEN_HASH = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  private final AccountCoordinationPinnedConnectionProvider connectionProvider;
  private final RedisScriptCatalog scriptCatalog;
  private final AcknowledgementRequirements acknowledgementRequirements;
  private final int maxRecordBytes;
  private final ClassLoader resourceClassLoader;

  public AccountGameplayDelegationRedisClient(
      AccountCoordinationPinnedConnectionProvider connectionProvider,
      RedisScriptCatalog scriptCatalog,
      AcknowledgementRequirements acknowledgementRequirements,
      int maxRecordBytes,
      ClassLoader resourceClassLoader) {
    this.connectionProvider =
        Objects.requireNonNull(
            connectionProvider, "Private Account Coordination provider is required");
    this.scriptCatalog = Objects.requireNonNull(scriptCatalog, "Redis script catalog is required");
    this.acknowledgementRequirements =
        Objects.requireNonNull(acknowledgementRequirements, "WAITAOF requirements are required");
    if (maxRecordBytes <= 0
        || maxRecordBytes > GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES) {
      throw new IllegalArgumentException("Registry byte bound exceeds the finite profile ceiling");
    }
    this.maxRecordBytes = maxRecordBytes;
    this.resourceClassLoader =
        Objects.requireNonNull(resourceClassLoader, "Resource class loader is required");
  }

  /**
   * Registers or reasserts the exact pending record and acknowledges its write on one physical
   * connection. The absolute deadline is fixed by the Account caller and is never extended.
   */
  public PendingRegistrationReceipt registerPending(
      String tokenHash, byte[] canonicalRecordBytes, long absoluteExpiryMillis) {
    if (tokenHash == null || !TOKEN_HASH.matcher(tokenHash).matches()) {
      throw new IllegalArgumentException("Account token hash is malformed");
    }
    Objects.requireNonNull(canonicalRecordBytes, "Canonical pending record is required");
    if (canonicalRecordBytes.length == 0 || canonicalRecordBytes.length > maxRecordBytes) {
      throw new IllegalArgumentException("Pending registry record exceeds its finite byte bound");
    }
    if (absoluteExpiryMillis <= 0L) {
      throw new IllegalArgumentException("Pending registry absolute expiry is malformed");
    }
    byte[] recordBytes = canonicalRecordBytes.clone();
    GameSessionAccountDelegationRegistryRecord record =
        GameSessionAccountDelegationRegistryRecord.decode(recordBytes, maxRecordBytes);
    if (!"pending".equals(record.state()) || !tokenHash.equals(record.tokenHash())) {
      throw new IllegalArgumentException("Pending registry identity does not match its key");
    }
    long tokenExpiryMillis;
    try {
      tokenExpiryMillis = Math.multiplyExact(record.expiresAtEpochSecond(), 1000L);
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException("Pending token expiry cannot be represented");
    }
    long cleanupMarginMillis = absoluteExpiryMillis - tokenExpiryMillis;
    if (cleanupMarginMillis < 0L || cleanupMarginMillis > MAX_CLEANUP_MARGIN_MILLIS) {
      throw new IllegalArgumentException("Pending registry cleanup margin is outside its bound");
    }

    RedisScriptDescriptor descriptor = requirePendingDescriptor();
    byte[] scriptSource = readScriptSource(descriptor);
    if (!descriptor.sha256().equals(sha256(scriptSource))) {
      throw unavailable();
    }
    byte[] key = (TOKEN_KEY_PREFIX + tokenHash).getBytes(StandardCharsets.US_ASCII);
    byte[] deadline = Long.toString(absoluteExpiryMillis).getBytes(StandardCharsets.US_ASCII);

    final StatefulRedisConnection<byte[], byte[]> connection;
    try {
      connection = connectionProvider.openPinnedConnection();
    } catch (RuntimeException ex) {
      throw unavailable();
    }
    if (connection == null) throw unavailable();

    try {
      if (!connection.isOpen()) throw unavailable();
      ClientOptions clientOptions = connection.getOptions();
      if (clientOptions == null || clientOptions.isAutoReconnect()) {
        throw unavailable();
      }
      RedisCommands<byte[], byte[]> commands = connection.sync();
      requireAccountAclIdentity(commands);
      String loadedSha = commands.scriptLoad(scriptSource);
      if (loadedSha == null || !SHA1.matcher(loadedSha).matches()) throw unavailable();
      String expectedSha = sha1(scriptSource);
      if (!expectedSha.equals(loadedSha)) throw unavailable();

      Long result =
          commands.evalsha(
              loadedSha, ScriptOutputType.INTEGER, new byte[][] {key}, recordBytes, deadline);
      if (result == null || (result != 0L && result != 1L)) throw unavailable();

      Acknowledgement acknowledgement = requireDurabilityAcknowledgement(commands);

      byte[] readback = commands.get(key);
      Long observedExpiry = commands.pexpiretime(key);
      if (!Arrays.equals(recordBytes, readback)
          || observedExpiry == null
          || observedExpiry.longValue() != absoluteExpiryMillis) {
        throw unavailable();
      }
      GameSessionAccountDelegationRegistryRecord verified =
          GameSessionAccountDelegationRegistryRecord.decode(readback, maxRecordBytes);
      if (!"pending".equals(verified.state()) || !tokenHash.equals(verified.tokenHash())) {
        throw unavailable();
      }
      return new PendingRegistrationReceipt(
          verified,
          sha256(recordBytes),
          absoluteExpiryMillis,
          result == 1L
              ? PendingRegistrationOutcome.CREATED
              : PendingRegistrationOutcome.EXACT_RETRY,
          acknowledgement.localAofCount(),
          acknowledgement.replicaAofCount());
    } catch (RuntimeException ex) {
      if (ex instanceof AccountCoordinationRedisException) throw ex;
      throw unavailable();
    } finally {
      try {
        connection.close();
      } catch (RuntimeException ex) {
        throw unavailable();
      }
    }
  }

  /**
   * Applies the exact PENDING-to-ACTIVE Redis projection transition only when supplied the
   * repository's typed, current COMMITTED-candidate readback. This helper is deliberately not wired
   * to Authenticate, response recovery, or a runtime token validator. Its receipt proves only the
   * observed Redis transition and the immutable owner facts supplied for this call; it does not
   * establish freshness through a later response or player-admission decision.
   */
  public ActiveRegistrationReceipt activateCommittedPending(
      PendingRegistrationReceipt pendingReceipt,
      CommittedCandidateVerificationData committedCandidate) {
    Objects.requireNonNull(pendingReceipt, "Pinned pending-registration receipt is required");
    Objects.requireNonNull(
        committedCandidate, "Repository-produced COMMITTED candidate evidence is required");
    requireOwnerReceiptCorrespondence(pendingReceipt, committedCandidate);

    RedisScriptDescriptor descriptor = requireActiveTransitionDescriptor();
    byte[] scriptSource = readScriptSource(descriptor);
    if (!descriptor.sha256().equals(sha256(scriptSource))) throw unavailable();

    byte[] key = ascii(pendingReceipt.tokenKey());
    byte[] deadline =
        Long.toString(pendingReceipt.absoluteExpiryMillis()).getBytes(StandardCharsets.US_ASCII);
    final StatefulRedisConnection<byte[], byte[]> connection;
    try {
      connection = connectionProvider.openPinnedConnection();
    } catch (RuntimeException ex) {
      throw unavailable();
    }
    if (connection == null) throw unavailable();

    try {
      RedisCommands<byte[], byte[]> commands = requirePinnedCommands(connection);
      requireAccountAclIdentity(commands);
      String loadedSha = commands.scriptLoad(scriptSource);
      if (loadedSha == null
          || !SHA1.matcher(loadedSha).matches()
          || !sha1(scriptSource).equals(loadedSha)) {
        throw unavailable();
      }

      byte[] pendingBytes = commands.get(key);
      Long observedExpiry = commands.pexpiretime(key);
      if (!boundedRegistryRecord(pendingBytes)
          || observedExpiry == null
          || observedExpiry.longValue() != pendingReceipt.absoluteExpiryMillis()) {
        throw unavailable();
      }
      GameSessionAccountDelegationRegistryRecord observedRecord =
          GameSessionAccountDelegationRegistryRecord.decode(pendingBytes, maxRecordBytes);
      final GameSessionAccountDelegationRegistryRecord pendingRecord;
      final byte[] exactPendingBytes;
      final byte[] activeBytes;
      if ("pending".equals(observedRecord.state()) && observedRecord.registryVersion() == 1L) {
        pendingRecord = observedRecord;
        exactPendingBytes = pendingBytes;
        requirePendingMatchesOwner(
            pendingRecord, exactPendingBytes, pendingReceipt, committedCandidate);
        activeBytes = activeRecordBytes(pendingRecord);
      } else if ("active".equals(observedRecord.state())
          && observedRecord.registryVersion() == 2L) {
        activeBytes = pendingBytes;
        exactPendingBytes = pendingRecordBytes(observedRecord);
        pendingRecord =
            GameSessionAccountDelegationRegistryRecord.decode(exactPendingBytes, maxRecordBytes);
        requirePendingMatchesOwner(
            pendingRecord, exactPendingBytes, pendingReceipt, committedCandidate);
        if (!Arrays.equals(activeRecordBytes(pendingRecord), activeBytes)) throw unavailable();
      } else {
        throw unavailable();
      }
      GameSessionAccountDelegationRegistryRecord expectedActive =
          GameSessionAccountDelegationRegistryRecord.decode(activeBytes, maxRecordBytes);
      if (!"active".equals(expectedActive.state())
          || expectedActive.registryVersion() != 2L
          || !expectedActive.tokenHash().equals(committedCandidate.tokenSha256())) {
        throw unavailable();
      }

      Long result =
          commands.evalsha(
              loadedSha,
              ScriptOutputType.INTEGER,
              new byte[][] {key},
              exactPendingBytes,
              activeBytes,
              deadline);
      if (result == null || (result != 0L && result != 1L)) throw unavailable();
      Acknowledgement acknowledgement = requireDurabilityAcknowledgement(commands);

      byte[] activeReadback = commands.get(key);
      Long activeExpiry = commands.pexpiretime(key);
      if (!Arrays.equals(activeBytes, activeReadback)
          || activeExpiry == null
          || activeExpiry.longValue() != pendingReceipt.absoluteExpiryMillis()) {
        throw unavailable();
      }
      GameSessionAccountDelegationRegistryRecord verifiedActive =
          GameSessionAccountDelegationRegistryRecord.decode(activeReadback, maxRecordBytes);
      if (!"active".equals(verifiedActive.state())
          || verifiedActive.registryVersion() != 2L
          || !verifiedActive.tokenHash().equals(committedCandidate.tokenSha256())) {
        throw unavailable();
      }
      return new ActiveRegistrationReceipt(
          verifiedActive,
          sha256(activeReadback),
          committedCandidate.commitProofSha256(),
          pendingReceipt.absoluteExpiryMillis(),
          result == 1L
              ? ActiveRegistrationOutcome.ACTIVATED
              : ActiveRegistrationOutcome.EXACT_RETRY,
          acknowledgement.localAofCount(),
          acknowledgement.replicaAofCount());
    } catch (RuntimeException ex) {
      if (ex instanceof AccountCoordinationRedisException) throw ex;
      throw unavailable();
    } finally {
      try {
        connection.close();
      } catch (RuntimeException ex) {
        throw unavailable();
      }
    }
  }

  /**
   * Recovers the original exact registration receipt from repository-validated COMMITTED proof,
   * then performs the same fenced pending-to-active CAS. The observed Redis deadline is compared to
   * the immutable SQL proof; it is never used to manufacture or extend that deadline.
   */
  ActiveRegistrationReceipt activateCommittedCandidate(
      CommittedCandidateVerificationData committedCandidate) {
    Objects.requireNonNull(
        committedCandidate, "Repository-produced COMMITTED candidate evidence is required");
    PendingRegistrationReceipt registration = readCommittedRegistrationReceipt(committedCandidate);
    return activateCommittedPending(registration, committedCandidate);
  }

  private PendingRegistrationReceipt readCommittedRegistrationReceipt(
      CommittedCandidateVerificationData committedCandidate) {
    String tokenHash = committedCandidate.tokenSha256();
    if (tokenHash == null || !TOKEN_HASH.matcher(tokenHash).matches()) throw unavailable();
    byte[] key = ascii(TOKEN_KEY_PREFIX + tokenHash);
    final StatefulRedisConnection<byte[], byte[]> connection;
    try {
      connection = connectionProvider.openPinnedConnection();
    } catch (RuntimeException ex) {
      throw unavailable();
    }
    if (connection == null) throw unavailable();

    try {
      if (!connection.isOpen()) throw unavailable();
      ClientOptions clientOptions = connection.getOptions();
      if (clientOptions == null || clientOptions.isAutoReconnect()) throw unavailable();
      RedisCommands<byte[], byte[]> commands = connection.sync();
      requireAccountAclIdentity(commands);
      byte[] currentBytes = commands.get(key);
      Long observedExpiry = commands.pexpiretime(key);
      if (!boundedRegistryRecord(currentBytes)
          || observedExpiry == null
          || observedExpiry.longValue() != committedCandidate.registryAbsoluteExpiryMillis()) {
        throw unavailable();
      }
      GameSessionAccountDelegationRegistryRecord currentRecord =
          GameSessionAccountDelegationRegistryRecord.decode(currentBytes, maxRecordBytes);
      final byte[] exactPendingBytes;
      final GameSessionAccountDelegationRegistryRecord pendingRecord;
      if ("pending".equals(currentRecord.state()) && currentRecord.registryVersion() == 1L) {
        exactPendingBytes = currentBytes;
        pendingRecord = currentRecord;
      } else if ("active".equals(currentRecord.state()) && currentRecord.registryVersion() == 2L) {
        exactPendingBytes = pendingRecordBytes(currentRecord);
        pendingRecord =
            GameSessionAccountDelegationRegistryRecord.decode(exactPendingBytes, maxRecordBytes);
      } else {
        throw unavailable();
      }
      if (!committedCandidate.canonicalRegistryRecordSha256().equals(sha256(exactPendingBytes))) {
        throw unavailable();
      }
      PendingRegistrationReceipt reconstructed =
          new PendingRegistrationReceipt(
              pendingRecord,
              committedCandidate.canonicalRegistryRecordSha256(),
              committedCandidate.registryAbsoluteExpiryMillis(),
              committedCandidate.registrationOutcome(),
              committedCandidate.registrationLocalAofCount(),
              committedCandidate.registrationReplicaAofCount());
      requireOwnerReceiptCorrespondence(reconstructed, committedCandidate);
      requirePendingMatchesOwner(
          pendingRecord, exactPendingBytes, reconstructed, committedCandidate);
      return reconstructed;
    } catch (RuntimeException ex) {
      if (ex instanceof AccountCoordinationRedisException) throw ex;
      throw unavailable();
    } finally {
      try {
        connection.close();
      } catch (RuntimeException ex) {
        throw unavailable();
      }
    }
  }

  /**
   * Advances the two canonical Account authority projections on one private pinned connection. Each
   * key uses a one-key script because issuer and Account keys intentionally have different Redis
   * Cluster slots. No result is successful until one WAITAOF and exact byte readback of both
   * projections completes on that same connection.
   */
  public ProjectionPublicationOutcome publishAuthorityProjections(
      java.util.UUID accountId, byte[] issuerProjectionBytes, byte[] accountProjectionBytes) {
    return publishProjections(accountId, issuerProjectionBytes, accountProjectionBytes, null, null);
  }

  public ProjectionPublicationOutcome publishSelectedAuthorityProjections(
      java.util.UUID accountId, java.util.UUID tenantId, byte[][] values) {
    if (TransactionSynchronizationManager.isActualTransactionActive() || tenantId == null)
      throw unavailable();
    if (values == null || values.length != 4) throw unavailable();
    AccountSelectedGameplayAuthorityProjection.decode(values[2], null, tenantId);
    AccountSelectedGameplayAuthorityProjection.decode(values[3], accountId, tenantId);
    return publishProjections(
        accountId, values[0], values[1], tenantId, new byte[][] {values[2], values[3]});
  }

  private ProjectionPublicationOutcome publishProjections(
      java.util.UUID accountId,
      byte[] issuerProjectionBytes,
      byte[] accountProjectionBytes,
      java.util.UUID tenantId,
      byte[][] selected) {
    Objects.requireNonNull(accountId, "Account UUID is required");
    AccountGameplayDelegationAuthorityProjection.ProjectionValue issuer =
        AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
            issuerProjectionBytes, "issuer", GameSessionAccountDelegationProfile.ISSUER);
    AccountGameplayDelegationAuthorityProjection.ProjectionValue account =
        AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
            accountProjectionBytes, "account", accountId.toString());
    if (!(AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX
                + GameSessionAccountDelegationProfile.ISSUER)
            .equals(issuer.key())
        || !(AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX + accountId)
            .equals(account.key())) {
      throw unavailable();
    }

    RedisScriptDescriptor issuerDescriptor = requireIssuerProjectionDescriptor();
    RedisScriptDescriptor accountDescriptor = requireCanonicalAccountProjectionDescriptor();
    byte[] scriptSource = readScriptSource(issuerDescriptor);
    byte[] accountScriptSource = readScriptSource(accountDescriptor);
    if (!issuerDescriptor.sha256().equals(sha256(scriptSource))
        || !accountDescriptor.sha256().equals(sha256(accountScriptSource))) {
      throw unavailable();
    }
    final StatefulRedisConnection<byte[], byte[]> connection;
    try {
      connection = connectionProvider.openPinnedConnection();
    } catch (RuntimeException ex) {
      throw unavailable();
    }
    if (connection == null) throw unavailable();

    try {
      RedisCommands<byte[], byte[]> commands = requirePinnedCommands(connection);
      requireAccountAclIdentity(commands);
      String loadedSha = commands.scriptLoad(scriptSource);
      if (loadedSha == null
          || !SHA1.matcher(loadedSha).matches()
          || !sha1(scriptSource).equals(loadedSha)) {
        throw unavailable();
      }
      byte[][] issuerProjectionArguments = {issuerProjectionBytes};
      Long issuerResult =
          commands.evalsha(
              loadedSha,
              ScriptOutputType.INTEGER,
              new byte[][] {ascii(issuer.key())},
              issuerProjectionArguments);
      requireProjectionResult(issuerResult);
      String accountSha = commands.scriptLoad(accountScriptSource);
      if (accountSha == null || !sha1(accountScriptSource).equals(accountSha)) throw unavailable();
      byte[] accountKey = ascii(account.key());
      byte[] priorAccountBytes = commands.get(accountKey);
      if (priorAccountBytes != null) {
        AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
            priorAccountBytes, "account", accountId.toString());
        AccountGenerationProjection incoming =
            AccountGenerationProjection.parse(
                new String(accountProjectionBytes, StandardCharsets.UTF_8));
        AccountGenerationProjection stored =
            AccountGenerationProjection.parse(
                new String(priorAccountBytes, StandardCharsets.UTF_8));
        if (incoming.generationValue().compareTo(stored.generationValue()) < 0
            || incoming.sourceVersionValue().compareTo(stored.sourceVersionValue()) < 0
            || incoming.outboxSequenceValue().compareTo(stored.outboxSequenceValue()) < 0
            || (incoming.outboxSequenceValue().equals(stored.outboxSequenceValue())
                && !incoming.equals(stored))) throw unavailable();
      }
      boolean exactAccount = Arrays.equals(priorAccountBytes, accountProjectionBytes);
      Long accountResult =
          commands.evalsha(
              accountSha,
              ScriptOutputType.INTEGER,
              new byte[][] {accountKey},
              ascii(priorAccountBytes == null ? "ABSENT" : exactAccount ? "VERIFY" : "PRESENT"),
              priorAccountBytes == null ? new byte[0] : priorAccountBytes,
              exactAccount ? new byte[0] : accountProjectionBytes);
      requireProjectionResult(accountResult);
      boolean selectedReplay = true;
      if (selected != null) {
        if (!Long.valueOf(-1L).equals(commands.pttl(ascii(issuer.key())))) throw unavailable();
        for (int index = 0; index < 2; index++) {
          var value =
              AccountSelectedGameplayAuthorityProjection.decode(
                  selected[index], index == 0 ? null : accountId, tenantId);
          var descriptor = requireSelectedProjectionDescriptor(index == 0);
          byte[] selectedScript = readScriptSource(descriptor);
          if (!descriptor.sha256().equals(sha256(selectedScript))) throw unavailable();
          String selectedSha = commands.scriptLoad(selectedScript);
          if (!sha1(selectedScript).equals(selectedSha)) throw unavailable();
          byte[] key = ascii(value.key());
          byte[] prior = commands.get(key);
          value.requireSuccessorOf(
              prior == null
                  ? null
                  : AccountSelectedGameplayAuthorityProjection.decode(
                      prior, index == 0 ? null : accountId, tenantId));
          boolean exact = Arrays.equals(prior, selected[index]);
          Long result =
              commands.evalsha(
                  selectedSha,
                  ScriptOutputType.INTEGER,
                  new byte[][] {key},
                  ascii(prior == null ? "ABSENT" : exact ? "VERIFY" : "PRESENT"),
                  prior == null ? new byte[0] : prior,
                  exact ? new byte[0] : selected[index]);
          requireProjectionResult(result);
          selectedReplay &= result == 0L;
        }
      }
      requireDurabilityAcknowledgement(commands);
      byte[] issuerReadback = commands.get(ascii(issuer.key()));
      byte[] accountReadback = commands.get(ascii(account.key()));
      if (!Arrays.equals(issuerProjectionBytes, issuerReadback)
          || !Arrays.equals(accountProjectionBytes, accountReadback)
          || !Long.valueOf(-1L).equals(commands.pttl(accountKey))) {
        throw unavailable();
      }
      AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
          issuerReadback, "issuer", GameSessionAccountDelegationProfile.ISSUER);
      AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
          accountReadback, "account", accountId.toString());
      if (selected != null) {
        for (int index = 0; index < 2; index++) {
          var value =
              AccountSelectedGameplayAuthorityProjection.decode(
                  selected[index], index == 0 ? null : accountId, tenantId);
          if (!Arrays.equals(selected[index], commands.get(ascii(value.key())))
              || !Long.valueOf(-1L).equals(commands.pttl(ascii(value.key())))) throw unavailable();
        }
      }
      return issuerResult == 0L && accountResult == 0L && selectedReplay
          ? ProjectionPublicationOutcome.EXACT_RETRY
          : ProjectionPublicationOutcome.PUBLISHED;
    } catch (RuntimeException ex) {
      if (ex instanceof AccountCoordinationRedisException) throw ex;
      throw unavailable();
    } finally {
      try {
        connection.close();
      } catch (RuntimeException ex) {
        throw unavailable();
      }
    }
  }

  /** Reads both exact canonical authority keys over one role-checked pinned connection. */
  public byte[][] readAuthorityProjections(java.util.UUID accountId) {
    return readProjections(accountId, null);
  }

  public byte[][] readSelectedAuthorityProjections(
      java.util.UUID accountId, java.util.UUID tenantId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    Objects.requireNonNull(tenantId, "Selected tenant UUID is required");
    return readProjections(accountId, tenantId);
  }

  private byte[][] readProjections(java.util.UUID accountId, java.util.UUID tenantId) {
    Objects.requireNonNull(accountId, "Account UUID is required");
    if (accountId.equals(new java.util.UUID(0L, 0L))
        || accountId.version() != 4
        || accountId.variant() != 2) {
      throw new IllegalArgumentException("Canonical Account UUID is required");
    }
    final StatefulRedisConnection<byte[], byte[]> connection;
    try {
      connection = connectionProvider.openPinnedConnection();
    } catch (RuntimeException ex) {
      throw unavailable();
    }
    if (connection == null) throw unavailable();
    try {
      RedisCommands<byte[], byte[]> commands = requirePinnedCommands(connection);
      requireAccountAclIdentity(commands);
      byte[] issuer =
          commands.get(
              ascii(
                  AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX
                      + GameSessionAccountDelegationProfile.ISSUER));
      byte[] account =
          commands.get(
              ascii(AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX + accountId));
      if (!boundedProjection(issuer) || !boundedProjection(account)) throw unavailable();
      if (!Long.valueOf(-1L)
          .equals(
              commands.pttl(
                  ascii(
                      AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX
                          + accountId)))) {
        throw unavailable();
      }
      AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
          issuer, "issuer", GameSessionAccountDelegationProfile.ISSUER);
      AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
          account, "account", accountId.toString());
      if (tenantId != null) {
        if (!Long.valueOf(-1L)
            .equals(
                commands.pttl(
                    ascii(
                        AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX
                            + GameSessionAccountDelegationProfile.ISSUER)))) throw unavailable();
        byte[] tenantKey =
            ascii(AccountSelectedGameplayAuthorityProjection.TENANT_PREFIX + tenantId);
        byte[] membershipKey =
            ascii(
                AccountSelectedGameplayAuthorityProjection.MEMBERSHIP_PREFIX
                    + accountId
                    + ":"
                    + tenantId);
        byte[] tenant = commands.get(tenantKey);
        byte[] membership = commands.get(membershipKey);
        AccountSelectedGameplayAuthorityProjection.decode(tenant, null, tenantId);
        AccountSelectedGameplayAuthorityProjection.decode(membership, accountId, tenantId);
        if (!Long.valueOf(-1L).equals(commands.pttl(tenantKey))
            || !Long.valueOf(-1L).equals(commands.pttl(membershipKey))) throw unavailable();
        return new byte[][] {issuer.clone(), account.clone(), tenant.clone(), membership.clone()};
      }
      return new byte[][] {issuer.clone(), account.clone()};
    } catch (RuntimeException ex) {
      if (ex instanceof AccountCoordinationRedisException) throw ex;
      throw unavailable();
    } finally {
      try {
        connection.close();
      } catch (RuntimeException ex) {
        throw unavailable();
      }
    }
  }

  /** Pure exact PENDING observation; no script, WAITAOF, registration, activation or renewal. */
  public void requirePendingRecord(String tokenHash, byte[] expected, long expectedAbsoluteExpiry) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || tokenHash == null
        || !TOKEN_HASH.matcher(tokenHash).matches()
        || !boundedRegistryRecord(expected)
        || expectedAbsoluteExpiry <= System.currentTimeMillis()) throw unavailable();
    try (var connection = connectionProvider.openPinnedConnection()) {
      if (connection == null) throw unavailable();
      var commands = requirePinnedCommands(connection);
      requireAccountAclIdentity(commands);
      byte[] key = ascii(TOKEN_KEY_PREFIX + tokenHash);
      if (!"string".equals(commands.type(key))) throw unavailable();
      byte[] actual = commands.get(key);
      Long ttl = commands.pttl(key);
      if (!Arrays.equals(actual, expected)
          || !Long.valueOf(expectedAbsoluteExpiry).equals(commands.pexpiretime(key))
          || ttl == null
          || ttl <= 0) throw unavailable();
      var record = GameSessionAccountDelegationRegistryRecord.decode(actual, maxRecordBytes);
      if (!"pending".equals(record.state())
          || !tokenHash.equals(record.tokenHash())
          || record.expiresAtEpochSecond() <= Instant.now().getEpochSecond()
          || !Arrays.equals(actual, commands.get(key))
          || !Long.valueOf(expectedAbsoluteExpiry).equals(commands.pexpiretime(key)))
        throw unavailable();
    } catch (RuntimeException rejected) {
      throw unavailable();
    }
  }

  /**
   * Reads one ACTIVE token record through Account's pinned, role-checked coordination connection.
   * The returned value is deliberately metadata-only; callers must still compare it with the
   * current committed Account issuance and verify the JWT signature/profile independently.
   */
  public ActiveRegistryReadback readActiveRecord(String tokenHash) {
    if (tokenHash == null || !TOKEN_HASH.matcher(tokenHash).matches()) {
      throw new IllegalArgumentException("Account token hash is malformed");
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw unavailable();
    }

    final StatefulRedisConnection<byte[], byte[]> connection;
    try {
      connection = connectionProvider.openPinnedConnection();
    } catch (RuntimeException ex) {
      throw unavailable();
    }
    if (connection == null) throw unavailable();

    byte[] activeBytes = null;
    byte[] pendingBytes = null;
    try {
      RedisCommands<byte[], byte[]> commands = requirePinnedCommands(connection);
      requireAccountAclIdentity(commands);
      byte[] key = ascii(TOKEN_KEY_PREFIX + tokenHash);
      activeBytes = commands.get(key);
      Long absoluteExpiryMillis = commands.pexpiretime(key);
      if (!boundedRegistryRecord(activeBytes)
          || absoluteExpiryMillis == null
          || absoluteExpiryMillis <= 0L) {
        throw unavailable();
      }
      GameSessionAccountDelegationRegistryRecord active =
          GameSessionAccountDelegationRegistryRecord.decode(activeBytes, maxRecordBytes);
      if (!"active".equals(active.state())
          || active.registryVersion() != 2L
          || !tokenHash.equals(active.tokenHash())) {
        throw unavailable();
      }
      pendingBytes = pendingRecordBytes(active);
      return new ActiveRegistryReadback(
          active.tokenHash(),
          active.accountId(),
          active.jti(),
          active.expiresAtEpochSecond(),
          absoluteExpiryMillis,
          sha256(pendingBytes));
    } catch (RuntimeException ex) {
      if (ex instanceof AccountCoordinationRedisException) throw ex;
      throw unavailable();
    } finally {
      if (activeBytes != null) Arrays.fill(activeBytes, (byte) 0);
      if (pendingBytes != null) Arrays.fill(pendingBytes, (byte) 0);
      try {
        connection.close();
      } catch (RuntimeException ex) {
        throw unavailable();
      }
    }
  }

  private RedisScriptDescriptor requirePendingDescriptor() {
    return requireExactDescriptor(
        SCRIPT_ID, "account-game-session-delegation-pending-register.json");
  }

  private RedisScriptDescriptor requireActiveTransitionDescriptor() {
    return requireExactDescriptor(
        ACTIVE_TRANSITION_SCRIPT_ID, "account-game-session-delegation-activate-committed.json");
  }

  private void requireOwnerReceiptCorrespondence(
      PendingRegistrationReceipt receipt, CommittedCandidateVerificationData committed) {
    AccountGameplayDelegationPendingIdentity identity = committed.identity();
    var authority = committed.authoritySnapshot();
    var signer = committed.signerCorrespondence();
    if (!REQUIRED_ACL_IDENTITY.equals(receipt.aclIdentity())
        || !receipt.tokenKey().equals(TOKEN_KEY_PREFIX + receipt.tokenHash())
        || !receipt.operationId().equals(identity.operationId().toString())
        || !receipt.requestId().equals(identity.requestId().toString())
        || !receipt.requestDigest().equals(identity.requestDigest())
        || !receipt.accountId().equals(identity.accountId().toString())
        || !receipt.tokenJti().equals(identity.tokenJti().toString())
        || !receipt.tokenHash().equals(committed.tokenSha256())
        || !receipt.kid().equals(committed.signerKid())
        || !receipt.signerGeneration().equals(committed.signerGeneration())
        || !receipt.canonicalRecordSha256().equals(committed.canonicalRegistryRecordSha256())
        || !receipt.evidenceBundleReference().equals(committed.evidenceBundleReference())
        || receipt.issuedAtEpochSecond() != identity.issuedAtEpochSecond()
        || receipt.notBeforeEpochSecond() != identity.notBeforeEpochSecond()
        || receipt.expiresAtEpochSecond() != identity.expiresAtEpochSecond()
        || receipt.issuanceFence() != authority.issuanceFence()
        || receipt.registryVersion() != 1L
        || receipt.localAofCount() < 1L
        || receipt.replicaAofCount() < 1L
        || !SHA256.matcher(committed.commitProofSha256()).matches()
        || !"COMMITTED".equals(signer.promotionStatus())
        || !committed.signerKid().equals(signer.targetKid())
        || !committed.signerGeneration().equals(signer.targetGeneration())) {
      throw unavailable();
    }
    long expiresAtMillis;
    try {
      expiresAtMillis = Math.multiplyExact(identity.expiresAtEpochSecond(), 1_000L);
      long cleanupMargin = Math.subtractExact(receipt.absoluteExpiryMillis(), expiresAtMillis);
      if (cleanupMargin < 0L || cleanupMargin > MAX_CLEANUP_MARGIN_MILLIS) throw unavailable();
    } catch (ArithmeticException ex) {
      throw unavailable();
    }
    if (identity.expiresAtEpochSecond() <= Instant.now().getEpochSecond()) throw unavailable();
    PendingIntent intent =
        new PendingIntent(
            identity.operationId(),
            identity.requestId(),
            identity.callerWorkload(),
            identity.callerContextId(),
            identity.tokenJti(),
            identity.issuedAtEpochSecond(),
            identity.notBeforeEpochSecond(),
            identity.expiresAtEpochSecond(),
            authority,
            identity.credentialRequestBinding());
    if (!AccountGameplayDelegationIssuanceRepository.requestDigest(intent)
        .equals(identity.requestDigest())) {
      throw unavailable();
    }
  }

  private void requirePendingMatchesOwner(
      GameSessionAccountDelegationRegistryRecord record,
      byte[] canonicalBytes,
      PendingRegistrationReceipt receipt,
      CommittedCandidateVerificationData committed) {
    AccountGameplayDelegationPendingIdentity identity = committed.identity();
    var authority = committed.authoritySnapshot();
    if (!"pending".equals(record.state())
        || record.registryVersion() != 1L
        || !record.tokenHash().equals(receipt.tokenHash())
        || !record.tokenHash().equals(committed.tokenSha256())
        || !record.kid().equals(committed.signerKid())
        || !record.signerGeneration().equals(committed.signerGeneration())
        || !record.operationId().equals(identity.operationId().toString())
        || !record.requestId().equals(identity.requestId().toString())
        || !record.accountId().equals(identity.accountId().toString())
        || !record.requestDigest().equals(identity.requestDigest())
        || !record.jti().equals(identity.tokenJti().toString())
        || record.issuedAtEpochSecond() != identity.issuedAtEpochSecond()
        || record.notBeforeEpochSecond() != identity.notBeforeEpochSecond()
        || record.expiresAtEpochSecond() != identity.expiresAtEpochSecond()
        || record.issuanceFence() != authority.issuanceFence()
        || !record.evidenceBundleReference().equals(committed.evidenceBundleReference())
        || !SHA256.matcher(committed.canonicalRegistryRecordSha256()).matches()
        || !committed.canonicalRegistryRecordSha256().equals(sha256(canonicalBytes))
        || !canonicalMapEquals(
            record.fields().get("authorityTuple"),
            GameSessionAccountDelegationProfile.authorityTuple(
                authority.issuerGeneration(),
                authority.accountGeneration(),
                authority.accountSecurityCutoff()))
        || !canonicalMapEquals(record.fields().get("membershipVersion"), Map.of())
        || !canonicalMapEquals(
            record.fields().get("authoritySourceVersions"), authority.sourceVersions())
        || !"1".equals(record.fields().get("tokenGeneration"))) {
      throw unavailable();
    }
  }

  private byte[] activeRecordBytes(GameSessionAccountDelegationRegistryRecord pendingRecord) {
    if (!"pending".equals(pendingRecord.state()) || pendingRecord.registryVersion() != 1L) {
      throw unavailable();
    }
    Map<String, Object> activeFields = new LinkedHashMap<>(pendingRecord.fields());
    activeFields.put("registryVersion", 2);
    activeFields.put("state", "active");
    try {
      byte[] canonical =
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(activeFields));
      if (!boundedRegistryRecord(canonical)) throw unavailable();
      return canonical;
    } catch (IOException | RuntimeException ex) {
      throw unavailable();
    }
  }

  private byte[] pendingRecordBytes(GameSessionAccountDelegationRegistryRecord activeRecord) {
    if (!"active".equals(activeRecord.state()) || activeRecord.registryVersion() != 2L) {
      throw unavailable();
    }
    Map<String, Object> pendingFields = new LinkedHashMap<>(activeRecord.fields());
    pendingFields.put("registryVersion", 1);
    pendingFields.put("state", "pending");
    try {
      byte[] canonical =
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(pendingFields));
      if (!boundedRegistryRecord(canonical)) throw unavailable();
      return canonical;
    } catch (IOException | RuntimeException ex) {
      throw unavailable();
    }
  }

  private static boolean canonicalMapEquals(Object value, Map<String, Object> expected) {
    if (!(value instanceof Map<?, ?> actual)) return false;
    try {
      return Arrays.equals(canonicalJson(actual), canonicalJson(expected));
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private static byte[] canonicalJson(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException | RuntimeException ex) {
      throw unavailable();
    }
  }

  private boolean boundedRegistryRecord(byte[] value) {
    return value != null && value.length > 0 && value.length <= maxRecordBytes;
  }

  private static Long exactInteger(Object value) {
    if (!(value instanceof Number number)) return null;
    String encoded = number.toString();
    if (!encoded.matches("0|[1-9][0-9]{0,18}")) return null;
    try {
      return Long.valueOf(encoded);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private RedisScriptDescriptor requireIssuerProjectionDescriptor() {
    return requireExactDescriptor(
        ISSUER_PROJECTION_SCRIPT_ID,
        "account-game-session-delegation-authority-project-issuer.json");
  }

  private RedisScriptDescriptor requireCanonicalAccountProjectionDescriptor() {
    RedisScriptDescriptor expected = AccountGenerationProjectionRedisContract.descriptor();
    RedisScriptDescriptor actual = scriptCatalog.require(ACCOUNT_PROJECTION_SCRIPT_ID);
    if (!expected.equals(actual)) throw unavailable();
    return actual;
  }

  private RedisScriptDescriptor requireSelectedProjectionDescriptor(boolean tenant) {
    return requireExactDescriptor(
        tenant ? TENANT_PROJECTION_SCRIPT_ID : MEMBERSHIP_PROJECTION_SCRIPT_ID,
        tenant
            ? "account-selected-tenant-generation-projection.json"
            : "account-selected-membership-generation-projection.json");
  }

  private RedisScriptDescriptor requireExactDescriptor(String scriptId, String resource) {
    RedisScriptDescriptor descriptor = scriptCatalog.require(scriptId);
    RedisScriptDescriptor expected =
        AccountRedisScriptDescriptorLoader.load(
            AccountGameplayDelegationRedisClient.class, "/redis/scripts/" + resource);
    if (!scriptId.equals(expected.scriptId()) || !expected.equals(descriptor)) throw unavailable();
    return descriptor;
  }

  private static RedisCommands<byte[], byte[]> requirePinnedCommands(
      StatefulRedisConnection<byte[], byte[]> connection) {
    if (!connection.isOpen()) throw unavailable();
    ClientOptions options = connection.getOptions();
    if (options == null || options.isAutoReconnect()) throw unavailable();
    return connection.sync();
  }

  private boolean boundedProjection(byte[] value) {
    return value != null
        && value.length > 0
        && value.length <= AccountGameplayDelegationAuthorityProjection.MAX_PROJECTION_BYTES;
  }

  private static void requireProjectionResult(Long result) {
    if (result == null || (result != 0L && result != 1L)) throw unavailable();
  }

  private byte[] readScriptSource(RedisScriptDescriptor descriptor) {
    try (InputStream input = resourceClassLoader.getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) throw unavailable();
      byte[] bytes = input.readNBytes(MAX_SCRIPT_SOURCE_BYTES + 1);
      if (bytes.length == 0 || bytes.length > MAX_SCRIPT_SOURCE_BYTES) throw unavailable();
      return bytes;
    } catch (IOException ex) {
      throw unavailable();
    }
  }

  private static void requireAccountAclIdentity(RedisCommands<byte[], byte[]> commands) {
    String identity = commands.aclWhoami();
    if (!REQUIRED_ACL_IDENTITY.equals(identity)) {
      throw unavailable();
    }
  }

  private Acknowledgement requireDurabilityAcknowledgement(RedisCommands<byte[], byte[]> commands) {
    CommandArgs<byte[], byte[]> arguments =
        new CommandArgs<>(ByteArrayCodec.INSTANCE)
            .add(acknowledgementRequirements.requiredLocalAofCount())
            .add(acknowledgementRequirements.requiredReplicaAofCount())
            .add(acknowledgementRequirements.timeoutMillis());
    List<Object> counts =
        commands.dispatch(
            WaitAofCommand.INSTANCE, new ArrayOutput<>(ByteArrayCodec.INSTANCE), arguments);
    if (counts == null || counts.size() != 2) throw unavailable();
    Object localCount = counts.get(0);
    Object replicaCount = counts.get(1);
    if (!(localCount instanceof Long local)
        || !(replicaCount instanceof Long replicas)
        || local < acknowledgementRequirements.requiredLocalAofCount()
        || replicas < acknowledgementRequirements.requiredReplicaAofCount()) {
      throw unavailable();
    }
    return new Acknowledgement(local, replicas);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String sha256(byte[] bytes) {
    return digest("SHA-256", bytes);
  }

  private static String sha1(byte[] bytes) {
    return digest("SHA-1", bytes);
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw unavailable();
    }
  }

  private static AccountCoordinationRedisException unavailable() {
    return new AccountCoordinationRedisException();
  }

  @FunctionalInterface
  public interface AccountCoordinationPinnedConnectionProvider {
    /**
     * Opens one private, dedicated, non-clustered Lettuce connection used for identity, script,
     * acknowledgement, and readback. The provider must not return an ambient/default Spring
     * connection, must disable automatic reconnect, and must preserve one pinned physical Redis
     * connection for this invocation. This client checks the open and no-auto-reconnect contract
     * before issuing any Redis command.
     */
    StatefulRedisConnection<byte[], byte[]> openPinnedConnection();
  }

  public record AcknowledgementRequirements(
      int requiredLocalAofCount, int requiredReplicaAofCount, int timeoutMillis) {
    public AcknowledgementRequirements {
      if (requiredLocalAofCount != 1
          || requiredReplicaAofCount < 1
          || requiredReplicaAofCount > 16
          || timeoutMillis < 1
          || timeoutMillis > 60_000) {
        throw new IllegalArgumentException("WAITAOF requirements are outside the supported bound");
      }
    }
  }

  public enum PendingRegistrationOutcome {
    CREATED,
    EXACT_RETRY
  }

  /**
   * Exact non-authorizing proof that a PENDING registry value was written and read back after
   * required WAITAOF acknowledgement on one role-checked pinned connection. The private constructor
   * prevents callers from turning Redis presence, a DTO, or a configured role label into
   * registration evidence.
   */
  public static final class PendingRegistrationReceipt {
    private final String tokenKey;
    private final String canonicalRecordSha256;
    private final String aclIdentity;
    private final String operationId;
    private final String requestId;
    private final String requestDigest;
    private final String accountId;
    private final String tokenJti;
    private final String tokenHash;
    private final String kid;
    private final String signerGeneration;
    private final long issuedAtEpochSecond;
    private final long notBeforeEpochSecond;
    private final long expiresAtEpochSecond;
    private final long issuanceFence;
    private final long registryVersion;
    private final GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference
        evidenceBundleReference;
    private final long absoluteExpiryMillis;
    private final PendingRegistrationOutcome outcome;
    private final long localAofCount;
    private final long replicaAofCount;

    private PendingRegistrationReceipt(
        GameSessionAccountDelegationRegistryRecord record,
        String canonicalRecordSha256,
        long absoluteExpiryMillis,
        PendingRegistrationOutcome outcome,
        long localAofCount,
        long replicaAofCount) {
      Objects.requireNonNull(record, "Verified pending registry record is required");
      if (!"pending".equals(record.state())
          || record.registryVersion() != 1L
          || !TOKEN_HASH.matcher(canonicalRecordSha256).matches()
          || absoluteExpiryMillis <= 0L
          || localAofCount < 1L
          || replicaAofCount < 1L) {
        throw unavailable();
      }
      long tokenExpiryMillis;
      try {
        tokenExpiryMillis = Math.multiplyExact(record.expiresAtEpochSecond(), 1_000L);
        long cleanupMarginMillis = Math.subtractExact(absoluteExpiryMillis, tokenExpiryMillis);
        if (cleanupMarginMillis < 0L || cleanupMarginMillis > MAX_CLEANUP_MARGIN_MILLIS) {
          throw unavailable();
        }
      } catch (ArithmeticException ex) {
        throw unavailable();
      }
      this.tokenKey = TOKEN_KEY_PREFIX + record.tokenHash();
      this.canonicalRecordSha256 = canonicalRecordSha256;
      this.aclIdentity = REQUIRED_ACL_IDENTITY;
      this.operationId = record.operationId();
      this.requestId = record.requestId();
      this.requestDigest = record.requestDigest();
      this.accountId = record.accountId();
      this.tokenJti = record.jti();
      this.tokenHash = record.tokenHash();
      this.kid = record.kid();
      this.signerGeneration = record.signerGeneration();
      this.issuedAtEpochSecond = record.issuedAtEpochSecond();
      this.notBeforeEpochSecond = record.notBeforeEpochSecond();
      this.expiresAtEpochSecond = record.expiresAtEpochSecond();
      this.issuanceFence = record.issuanceFence();
      this.registryVersion = record.registryVersion();
      this.evidenceBundleReference = record.evidenceBundleReference();
      this.absoluteExpiryMillis = absoluteExpiryMillis;
      this.outcome = Objects.requireNonNull(outcome);
      this.localAofCount = localAofCount;
      this.replicaAofCount = replicaAofCount;
    }

    public String tokenKey() {
      return tokenKey;
    }

    public String canonicalRecordSha256() {
      return canonicalRecordSha256;
    }

    public String aclIdentity() {
      return aclIdentity;
    }

    public String operationId() {
      return operationId;
    }

    public String requestId() {
      return requestId;
    }

    public String requestDigest() {
      return requestDigest;
    }

    public String accountId() {
      return accountId;
    }

    public String tokenJti() {
      return tokenJti;
    }

    public String tokenHash() {
      return tokenHash;
    }

    public String kid() {
      return kid;
    }

    public String signerGeneration() {
      return signerGeneration;
    }

    public long issuedAtEpochSecond() {
      return issuedAtEpochSecond;
    }

    public long notBeforeEpochSecond() {
      return notBeforeEpochSecond;
    }

    public long expiresAtEpochSecond() {
      return expiresAtEpochSecond;
    }

    public long issuanceFence() {
      return issuanceFence;
    }

    public long registryVersion() {
      return registryVersion;
    }

    public GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference
        evidenceBundleReference() {
      return evidenceBundleReference;
    }

    public long absoluteExpiryMillis() {
      return absoluteExpiryMillis;
    }

    public PendingRegistrationOutcome outcome() {
      return outcome;
    }

    public long localAofCount() {
      return localAofCount;
    }

    public long replicaAofCount() {
      return replicaAofCount;
    }

    @Override
    public String toString() {
      return "PendingRegistrationReceipt[redacted]";
    }
  }

  public enum ActiveRegistrationOutcome {
    ACTIVATED,
    EXACT_RETRY
  }

  /**
   * Private-constructor metadata for an exact ACTIVE registry readback. This value is not a token
   * credential and is not proof of player admission, response recovery, or current signer state
   * beyond the committed owner data supplied to the transition.
   */
  public static final class ActiveRegistrationReceipt {
    private final String tokenKey;
    private final String tokenHash;
    private final String operationId;
    private final String requestId;
    private final String accountId;
    private final String canonicalActiveRecordSha256;
    private final String commitProofSha256;
    private final long registryVersion;
    private final long absoluteExpiryMillis;
    private final ActiveRegistrationOutcome outcome;
    private final long localAofCount;
    private final long replicaAofCount;

    private ActiveRegistrationReceipt(
        GameSessionAccountDelegationRegistryRecord record,
        String canonicalActiveRecordSha256,
        String commitProofSha256,
        long absoluteExpiryMillis,
        ActiveRegistrationOutcome outcome,
        long localAofCount,
        long replicaAofCount) {
      Objects.requireNonNull(record, "Verified active registry record is required");
      if (!"active".equals(record.state())
          || record.registryVersion() != 2L
          || !SHA256.matcher(canonicalActiveRecordSha256).matches()
          || !SHA256.matcher(commitProofSha256).matches()
          || absoluteExpiryMillis <= 0L
          || localAofCount < 1L
          || replicaAofCount < 1L) {
        throw unavailable();
      }
      this.tokenKey = TOKEN_KEY_PREFIX + record.tokenHash();
      this.tokenHash = record.tokenHash();
      this.operationId = record.operationId();
      this.requestId = record.requestId();
      this.accountId = record.accountId();
      this.canonicalActiveRecordSha256 = canonicalActiveRecordSha256;
      this.commitProofSha256 = commitProofSha256;
      this.registryVersion = record.registryVersion();
      this.absoluteExpiryMillis = absoluteExpiryMillis;
      this.outcome = Objects.requireNonNull(outcome);
      this.localAofCount = localAofCount;
      this.replicaAofCount = replicaAofCount;
    }

    public String tokenKey() {
      return tokenKey;
    }

    public String tokenHash() {
      return tokenHash;
    }

    public String operationId() {
      return operationId;
    }

    public String requestId() {
      return requestId;
    }

    public String accountId() {
      return accountId;
    }

    public String canonicalActiveRecordSha256() {
      return canonicalActiveRecordSha256;
    }

    public String commitProofSha256() {
      return commitProofSha256;
    }

    public long registryVersion() {
      return registryVersion;
    }

    public long absoluteExpiryMillis() {
      return absoluteExpiryMillis;
    }

    public ActiveRegistrationOutcome outcome() {
      return outcome;
    }

    public long localAofCount() {
      return localAofCount;
    }

    public long replicaAofCount() {
      return replicaAofCount;
    }

    @Override
    public String toString() {
      return "ActiveRegistrationReceipt[redacted]";
    }
  }

  private record Acknowledgement(long localAofCount, long replicaAofCount) {}

  public enum ProjectionPublicationOutcome {
    PUBLISHED,
    EXACT_RETRY
  }

  public static final class AccountCoordinationRedisException extends IllegalStateException {
    private AccountCoordinationRedisException() {
      super("Account Coordination Redis operation is unavailable or ambiguous");
    }
  }

  /** Metadata-only observation of a Redis ACTIVE record; it does not authenticate a caller. */
  public static final class ActiveRegistryReadback {
    private final String tokenHash;
    private final String accountId;
    private final String jti;
    private final long tokenExpiryEpochSecond;
    private final long absoluteExpiryMillis;
    private final String canonicalPendingRecordSha256;

    private ActiveRegistryReadback(
        String tokenHash,
        String accountId,
        String jti,
        long tokenExpiryEpochSecond,
        long absoluteExpiryMillis,
        String canonicalPendingRecordSha256) {
      this.tokenHash = Objects.requireNonNull(tokenHash);
      this.accountId = Objects.requireNonNull(accountId);
      this.jti = Objects.requireNonNull(jti);
      this.tokenExpiryEpochSecond = tokenExpiryEpochSecond;
      this.absoluteExpiryMillis = absoluteExpiryMillis;
      this.canonicalPendingRecordSha256 = Objects.requireNonNull(canonicalPendingRecordSha256);
    }

    public String tokenHash() {
      return tokenHash;
    }

    public String accountId() {
      return accountId;
    }

    public String jti() {
      return jti;
    }

    public long tokenExpiryEpochSecond() {
      return tokenExpiryEpochSecond;
    }

    public long absoluteExpiryMillis() {
      return absoluteExpiryMillis;
    }

    public String canonicalPendingRecordSha256() {
      return canonicalPendingRecordSha256;
    }

    @Override
    public String toString() {
      return "ActiveRegistryReadback[redacted]";
    }
  }

  private enum WaitAofCommand implements ProtocolKeyword {
    INSTANCE;

    private static final byte[] BYTES = ascii("WAITAOF");

    @Override
    public byte[] getBytes() {
      return BYTES.clone();
    }

    @Override
    public String toString() {
      return "WAITAOF";
    }
  }
}
