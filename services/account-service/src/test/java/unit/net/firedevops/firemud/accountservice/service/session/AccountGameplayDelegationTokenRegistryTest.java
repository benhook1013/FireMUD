package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBindingFixture;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingRegistryCandidate;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.AccountCoordinationPinnedConnectionProvider;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.AcknowledgementRequirements;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.PendingRegistrationOutcome;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisScriptContribution;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile.AccountSecurityCutoff;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.IssuanceBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

class AccountGameplayDelegationTokenRegistryTest {
  private static final String ACCOUNT_ID = "4cae05e8-7a6b-4b14-9d44-665e3eec450b";
  private static final String JTI = "2921ba03-bf74-49ac-b24b-a9255f5de308";
  private static final String OPERATION_ID = "5414e55d-0393-4561-ac3d-cb916a08d3f0";
  private static final String REQUEST_ID = "1ee95a1e-83f2-4a63-a7ba-6288e246ac76";
  private static final long NOW = 1_800_000_000L;
  private static final long EXPIRY_MILLIS = (NOW + 120L) * 1000L + 30_000L;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private AccountGameplayDelegationIssuanceRepository repository;
  private StatefulRedisConnection<byte[], byte[]> connection;
  private RedisCommands<byte[], byte[]> commands;
  private AccountGameplayDelegationTokenRegistry registry;
  private PendingRegistryCandidate candidate;
  private byte[] encodedRecord;
  private byte[] redisKey;

  @BeforeEach
  void setUp() throws Exception {
    repository = mock(AccountGameplayDelegationIssuanceRepository.class);
    connection = mock(StatefulRedisConnection.class);
    commands = mock(RedisCommands.class);
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(false).build());
    when(connection.isOpen()).thenReturn(true);
    when(connection.sync()).thenReturn(commands);
    AccountCoordinationPinnedConnectionProvider provider = () -> connection;
    GameSessionAccountDelegationRegistryRecord record =
        GameSessionAccountDelegationRegistryRecord.fromAccountSignedCompactJwt(
            token(claims()), "5", binding(), snapshot(), evidenceBundleReference(), NOW, 16_384L);
    encodedRecord = record.toCanonicalJsonBytes(16_384L);
    redisKey = ascii("session:auth:token:" + record.tokenHash());
    AccountGameplayDelegationPendingIdentity identity =
        new AccountGameplayDelegationPendingIdentity(
            UUID.fromString(OPERATION_ID),
            UUID.fromString(REQUEST_ID),
            UUID.fromString(ACCOUNT_ID),
            "spiffe://firemud/ns/test/sa/game-session-service",
            UUID.fromString("91d13625-0e03-4e46-b82e-18f69f091436"),
            AccountGameplayCredentialRequestBindingFixture.binding(),
            "a".repeat(64),
            UUID.fromString(JTI),
            NOW,
            NOW,
            NOW + 120L);
    candidate =
        new PendingRegistryCandidate(
            identity,
            record.tokenHash(),
            record.kid(),
            "5",
            NOW + 120L,
            encodedRecord,
            snapshot(),
            evidenceBundleReference());
    when(repository.readPendingRegistryCandidate(UUID.fromString(REQUEST_ID)))
        .thenReturn(candidate);
    when(commands.aclWhoami())
        .thenReturn(AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY);
    when(commands.scriptLoad(any(byte[].class)))
        .thenAnswer(invocation -> sha1(invocation.getArgument(0)));
    when(commands.<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class)))
        .thenReturn(1L);
    doReturn(List.of(1L, 1L))
        .when(commands)
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    when(commands.get(redisKey)).thenReturn(encodedRecord.clone());
    when(commands.pexpiretime(redisKey)).thenReturn(EXPIRY_MILLIS);
    AccountGameplayDelegationRedisClient redisClient =
        new AccountGameplayDelegationRedisClient(
            provider,
            RedisScriptCatalog.loadInstalled(getClass().getClassLoader()),
            new AcknowledgementRequirements(1, 1, 1_000),
            16_384,
            getClass().getClassLoader());
    registry =
        new AccountGameplayDelegationTokenRegistry(
            repository,
            redisClient,
            Clock.fixed(Instant.ofEpochSecond(NOW).plusMillis(500L), ZoneOffset.UTC),
            16_384L,
            30_000L);
  }

  @Test
  void usesOnePinnedRoleCheckedConnectionForScriptLoadEvalshaWaitaofAndExactReadback()
      throws Exception {
    var receipt = registry.registerPending(UUID.fromString(REQUEST_ID));
    assertThat(receipt.outcome()).isEqualTo(PendingRegistrationOutcome.CREATED);
    assertThat(receipt.operationId()).isEqualTo(OPERATION_ID);
    assertThat(receipt.requestId()).isEqualTo(REQUEST_ID);
    assertThat(receipt.requestDigest()).isEqualTo(candidate.identity().requestDigest());
    assertThat(receipt.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(receipt.tokenJti()).isEqualTo(JTI);
    assertThat(receipt.tokenHash()).isEqualTo(candidate.tokenHash());
    assertThat(receipt.kid()).isEqualTo(candidate.kid());
    assertThat(receipt.signerGeneration()).isEqualTo(candidate.signerGeneration());
    assertThat(receipt.issuanceFence()).isEqualTo(candidate.authoritySnapshot().issuanceFence());
    assertThat(receipt.registryVersion()).isEqualTo(1L);
    assertThat(receipt.canonicalRecordSha256()).isEqualTo(sha256(encodedRecord));
    assertThat(receipt.aclIdentity())
        .isEqualTo(AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY);
    assertThat(receipt.evidenceBundleReference()).isEqualTo(candidate.evidenceBundleReference());
    assertThat(receipt.absoluteExpiryMillis()).isEqualTo(EXPIRY_MILLIS);
    assertThat(receipt.localAofCount()).isEqualTo(1L);
    assertThat(receipt.replicaAofCount()).isEqualTo(1L);
    assertThat(receipt.toString()).doesNotContain("compact", "eyJ");

    InOrder order = inOrder(connection, commands);
    order.verify(connection).isOpen();
    order.verify(connection).getOptions();
    order.verify(connection).sync();
    order.verify(commands).aclWhoami();
    org.mockito.ArgumentCaptor<byte[]> scriptBytes =
        org.mockito.ArgumentCaptor.forClass(byte[].class);
    org.mockito.ArgumentCaptor<String> loadedSha =
        org.mockito.ArgumentCaptor.forClass(String.class);
    order.verify(commands).scriptLoad(scriptBytes.capture());
    ArgumentCaptor<byte[][]> keys = ArgumentCaptor.forClass(byte[][].class);
    ArgumentCaptor<byte[][]> arguments = ArgumentCaptor.forClass(byte[][].class);
    order
        .verify(commands)
        .<Long>evalsha(
            loadedSha.capture(), eq(ScriptOutputType.INTEGER), keys.capture(), arguments.capture());
    assertThat(keys.getValue().length).isEqualTo(1);
    assertThat(keys.getValue()[0]).containsExactly(redisKey);
    assertThat(arguments.getValue().length).isEqualTo(2);
    assertThat(arguments.getValue()[0]).containsExactly(encodedRecord);
    assertThat(arguments.getValue()[1]).containsExactly(ascii(Long.toString(EXPIRY_MILLIS)));
    ArgumentCaptor<ProtocolKeyword> waitAofCommand = ArgumentCaptor.forClass(ProtocolKeyword.class);
    ArgumentCaptor<CommandOutput> waitAofOutput = ArgumentCaptor.forClass(CommandOutput.class);
    ArgumentCaptor<CommandArgs> waitAofArguments = ArgumentCaptor.forClass(CommandArgs.class);
    order
        .verify(commands)
        .dispatch(waitAofCommand.capture(), waitAofOutput.capture(), waitAofArguments.capture());
    assertThat(waitAofCommand.getValue().toString()).isEqualTo("WAITAOF");
    assertThat(waitAofCommand.getValue().getBytes()).containsExactly(ascii("WAITAOF"));
    assertThat(waitAofOutput.getValue()).isInstanceOf(ArrayOutput.class);
    assertThat(waitAofArguments.getValue().toCommandString()).isEqualTo("1 1 1000");
    order.verify(commands).get(redisKey);
    order.verify(commands).pexpiretime(redisKey);
    order.verify(connection).close();
    verifyNoMoreInteractions(connection, commands);
    String script = new String(scriptBytes.getValue(), StandardCharsets.UTF_8);
    assertThat(script)
        .contains("redis.call('PEXPIRETIME', key)")
        .contains("'PXAT', ARGV[2]")
        .contains("return 0")
        .contains("return 1")
        .doesNotContain("PTTL", "redis.call('TIME'");
    assertThat(loadedSha.getValue()).isEqualTo(sha1(scriptBytes.getValue()));
  }

  @Test
  void matchesAuthorityGenerationsFromTheCanonicalDecodedRecordDecimalStringDomain() {
    Map<?, ?> tuple = (Map<?, ?>) recordFields(encodedRecord).get("authorityTuple");
    assertThat(tuple.get("issuerAuthGeneration")).isEqualTo("7");
    assertThat(tuple.get("accountAuthorityGeneration")).isEqualTo("11");
    assertThat(tuple.get("accountSecurityCutoff"))
        .isEqualTo(
            GameSessionAccountDelegationProfile.authorityTuple(7L, 11L, Optional.of(cutoff()))
                .get("accountSecurityCutoff"));

    assertThat(registry.registerPending(UUID.fromString(REQUEST_ID)).outcome())
        .isEqualTo(PendingRegistrationOutcome.CREATED);
  }

  @Test
  void exactRetryReassertsSameAbsoluteExpiryAndDoesNotExtendThePendingRecord() {
    when(commands.<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class)))
        .thenReturn(0L);

    assertThat(registry.registerPending(UUID.fromString(REQUEST_ID)).outcome())
        .isEqualTo(PendingRegistrationOutcome.EXACT_RETRY);

    org.mockito.ArgumentCaptor<byte[][]> evalArguments =
        org.mockito.ArgumentCaptor.forClass(byte[][].class);
    verify(commands)
        .<Long>evalsha(
            anyString(),
            eq(ScriptOutputType.INTEGER),
            any(byte[][].class),
            evalArguments.capture());
    assertThat(evalArguments.getValue().length).isEqualTo(2);
    assertThat(evalArguments.getValue()[0]).containsExactly(encodedRecord);
    assertThat(evalArguments.getValue()[1]).containsExactly(ascii(Long.toString(EXPIRY_MILLIS)));
    verify(commands)
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    verify(commands).pexpiretime(redisKey);
  }

  @Test
  void rejectsAnyLiveRedisAclIdentityOtherThanAccountCoordAppBeforeLoadingScript() {
    when(commands.aclWhoami()).thenReturn("default");

    assertThatThrownBy(() -> registry.registerPending(UUID.fromString(REQUEST_ID)))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);

    verify(commands).aclWhoami();
    verify(commands, org.mockito.Mockito.never()).scriptLoad(any(byte[].class));
    verify(commands, org.mockito.Mockito.never())
        .<Long>evalsha(anyString(), any(), any(byte[][].class), any(byte[][].class));
    verify(commands, org.mockito.Mockito.never())
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    verify(connection).close();
  }

  @Test
  void rejectsAutoReconnectingConnectionBeforeRedisCommands() {
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(true).build());

    assertThatThrownBy(() -> registry.registerPending(UUID.fromString(REQUEST_ID)))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);

    verify(connection).getOptions();
    verify(connection).isOpen();
    verify(connection).close();
    verify(commands, org.mockito.Mockito.never()).aclWhoami();
    verify(commands, org.mockito.Mockito.never()).scriptLoad(any(byte[].class));
  }

  @Test
  void rejectsClosedConnectionBeforeRedisCommands() {
    when(connection.isOpen()).thenReturn(false);

    assertThatThrownBy(() -> registry.registerPending(UUID.fromString(REQUEST_ID)))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);

    verify(connection).isOpen();
    verify(connection).close();
    verify(commands, org.mockito.Mockito.never()).aclWhoami();
    verify(commands, org.mockito.Mockito.never()).scriptLoad(any(byte[].class));
  }

  @Test
  void rejectsScriptBytesThatDoNotMatchTheImmutableOwnerDescriptorDigest() {
    RedisScriptDescriptor descriptor =
        new AccountGameplayDelegationRedisScriptContribution().descriptors().iterator().next();
    RedisScriptDescriptor alteredDigest =
        new RedisScriptDescriptor(
            descriptor.id(),
            descriptor.owner(),
            descriptor.scriptResource(),
            "0".repeat(64),
            descriptor.redisRole(),
            descriptor.category(),
            descriptor.keys(),
            descriptor.arguments(),
            descriptor.resetSensitivity(),
            descriptor.lossOutcomeClass(),
            descriptor.tailLossBehavior(),
            descriptor.outcomes());
    AccountGameplayDelegationRedisClient alteredClient =
        new AccountGameplayDelegationRedisClient(
            () -> connection,
            RedisScriptCatalog.fromContributions(List.of(contribution(alteredDigest))),
            new AcknowledgementRequirements(1, 1, 1_000),
            16_384,
            getClass().getClassLoader());

    assertThatThrownBy(
            () ->
                alteredClient.registerPending(candidate.tokenHash(), encodedRecord, EXPIRY_MILLIS))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    verifyNoMoreInteractions(connection);
  }

  @Test
  void belowThresholdOrMismatchedReadbackNeverReturnsRegistrationSuccess() {
    doReturn(List.of(1L, 0L))
        .when(commands)
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    assertThatThrownBy(() -> registry.registerPending(UUID.fromString(REQUEST_ID)))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    verify(commands, org.mockito.Mockito.never()).get(redisKey);

    doReturn(List.of("1", 1L))
        .when(commands)
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    assertThatThrownBy(() -> registry.registerPending(UUID.fromString(REQUEST_ID)))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    verify(commands, org.mockito.Mockito.never()).get(redisKey);

    doReturn(List.of(1L, 1L))
        .when(commands)
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    when(commands.get(redisKey)).thenReturn(ascii("different-record"));
    assertThatThrownBy(() -> registry.registerPending(UUID.fromString(REQUEST_ID)))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
  }

  @Test
  void connectionCloseFailureNeverReturnsRegistrationSuccess() {
    doThrow(new IllegalStateException("close failed")).when(connection).close();

    assertThatThrownBy(() -> registry.registerPending(UUID.fromString(REQUEST_ID)))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
  }

  @Test
  void pendingRegistryWriterRejectsRecordOmittingApplicableCutoffBeforeRedis() throws Exception {
    Map<String, Object> mismatchedFields = new LinkedHashMap<>(recordFields(encodedRecord));
    Map<String, Object> tupleWithoutCutoff = new LinkedHashMap<>();
    ((Map<?, ?>) mismatchedFields.get("authorityTuple"))
        .forEach((key, value) -> tupleWithoutCutoff.put((String) key, value));
    tupleWithoutCutoff.remove("accountSecurityCutoff");
    mismatchedFields.put("authorityTuple", tupleWithoutCutoff);
    byte[] mismatchedBytes = canonicalJson(mismatchedFields);
    PendingRegistryCandidate mismatched =
        new PendingRegistryCandidate(
            candidate.identity(),
            candidate.tokenHash(),
            candidate.kid(),
            candidate.signerGeneration(),
            candidate.expiresAtEpochSecond(),
            mismatchedBytes,
            snapshot(),
            evidenceBundleReference());
    when(repository.readPendingRegistryCandidate(UUID.fromString(REQUEST_ID)))
        .thenReturn(mismatched);

    assertThatThrownBy(() -> registry.registerPending(UUID.fromString(REQUEST_ID)))
        .isInstanceOf(IllegalArgumentException.class);

    verify(commands, org.mockito.Mockito.never()).scriptLoad(any(byte[].class));
    verify(commands, org.mockito.Mockito.never())
        .<Long>evalsha(anyString(), any(), any(byte[][].class), any(byte[][].class));
  }

  private static Map<String, Object> claims() {
    return Map.ofEntries(
        Map.entry("iss", GameSessionAccountDelegationProfile.ISSUER),
        Map.entry("sub", ACCOUNT_ID),
        Map.entry("accountId", ACCOUNT_ID),
        Map.entry("jti", JTI),
        Map.entry("aud", GameSessionAccountDelegationProfile.AUDIENCE),
        Map.entry("iat", NOW),
        Map.entry("nbf", NOW),
        Map.entry("exp", NOW + 120L),
        Map.entry("tokenGeneration", "1"),
        Map.entry(
            "authorityTuple",
            GameSessionAccountDelegationProfile.authorityTuple(7L, 11L, Optional.of(cutoff()))),
        Map.entry("membershipVersion", Map.of()),
        Map.entry("issuanceFence", "11"));
  }

  private static String token(Map<String, Object> claims) throws Exception {
    String header =
        JSON.writeValueAsString(Map.of("alg", "RS256", "kid", "test-key", "typ", "JWT"));
    String payload = JSON.writeValueAsString(claims);
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8))
        + "."
        + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + "."
        + encoder.encodeToString("signature-bytes".getBytes(StandardCharsets.UTF_8));
  }

  private static IssuanceBinding binding() {
    return new IssuanceBinding(OPERATION_ID, REQUEST_ID, "a".repeat(64), ACCOUNT_ID);
  }

  private static AccountAuthoritySnapshot snapshot() {
    return new AccountAuthoritySnapshot(
        UUID.fromString(ACCOUNT_ID), 7L, 7L, 11L, 11L, 11L, 11L, Optional.of(cutoff()));
  }

  private static EvidenceBundleReference evidenceBundleReference() {
    return new EvidenceBundleReference("1", "11", "9", "3", "d".repeat(64));
  }

  private static AccountSecurityCutoff cutoff() {
    return new AccountSecurityCutoff("11", "account:auth-authority:v1:account/" + ACCOUNT_ID, "10");
  }

  private static Map<String, Object> recordFields(byte[] bytes) {
    return GameSessionAccountDelegationRegistryRecord.decode(bytes, 16_384L).fields();
  }

  private static byte[] canonicalJson(Map<String, Object> value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static net.firedevops.firemud.common.redis.contracts.RedisScriptContribution contribution(
      RedisScriptDescriptor descriptor) {
    return new net.firedevops.firemud.common.redis.contracts.RedisScriptContribution() {
      @Override
      public String ownerId() {
        return "account-service";
      }

      @Override
      public List<RedisScriptDescriptor> descriptors() {
        return List.of(descriptor);
      }
    };
  }

  private static String sha1(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
