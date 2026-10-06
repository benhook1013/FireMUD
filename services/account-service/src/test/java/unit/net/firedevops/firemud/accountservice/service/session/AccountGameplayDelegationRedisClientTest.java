package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceCheckpoint;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingIntent;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisScriptContribution;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.IssuanceBinding;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class AccountGameplayDelegationRedisClientTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void selectedDescriptorsPinExistingExactByteCasAndDisjointCanonicalKeys() throws Exception {
    var descriptors = new AccountGameplayDelegationRedisScriptContribution().descriptors();
    for (String id :
        List.of(
            AccountGameplayDelegationRedisClient.TENANT_PROJECTION_SCRIPT_ID,
            AccountGameplayDelegationRedisClient.MEMBERSHIP_PROJECTION_SCRIPT_ID)) {
      var descriptor =
          descriptors.stream().filter(value -> id.equals(value.id())).findFirst().orElseThrow();
      assertThat(descriptor.owner()).isEqualTo("account-service");
      assertThat(descriptor.redisRole()).isEqualTo(RedisScriptDescriptor.RedisRole.COORDINATION);
      assertThat(descriptor.keys()).hasSize(1);
      assertThat(descriptor.keys().getFirst().allowedPrefix())
          .isEqualTo(
              id.equals(AccountGameplayDelegationRedisClient.TENANT_PROJECTION_SCRIPT_ID)
                  ? "session:auth:generation:tenant:"
                  : "session:auth:generation:membership:");
      assertThat(descriptor.arguments())
          .containsExactly("expectedMode", "expectedBytes", "candidateBytes");
      try (var source =
          getClass().getClassLoader().getResourceAsStream(descriptor.scriptResource())) {
        assertThat(source).isNotNull();
        assertThat(sha256(source.readAllBytes())).isEqualTo(descriptor.sourceSha256());
      }
    }
  }

  @Test
  void selectedPublicationUsesFourCasCallsOnePinnedAckAndExactNoTtlReadback() throws Exception {
    UUID tenant =
        net.firedevops.firemud.accountservice.service.session
            .AccountSelectedGameplayAuthorityProjectionTest.TENANT;
    byte[][] values =
        net.firedevops.firemud.accountservice.service.session
            .AccountSelectedGameplayAuthorityProjectionTest.selected(
            AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(snapshot()),
            ACCOUNT_ID,
            tenant);
    var connection = connection();
    var commands = connection.sync();
    Map<String, byte[]> stored = new java.util.HashMap<>();
    stored.put("session:auth:generation:issuer:firemud-account-service", values[0]);
    stored.put("session:auth:generation:account:" + ACCOUNT_ID, values[1]);
    when(commands.scriptLoad(any(byte[].class)))
        .thenAnswer(invocation -> sha1(invocation.getArgument(0)));
    when(commands.get(any(byte[].class)))
        .thenAnswer(
            invocation ->
                stored.get(new String(invocation.getArgument(0), StandardCharsets.UTF_8)));
    when(commands.<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class)))
        .thenAnswer(
            invocation -> {
              byte[][] keys = invocation.getArgument(2);
              String key = new String(keys[0], StandardCharsets.UTF_8);
              if (key.startsWith("session:auth:generation:tenant:")) {
                stored.put(key, values[2]);
                return 1L;
              }
              if (key.startsWith("session:auth:generation:membership:")) {
                stored.put(key, values[3]);
                return 1L;
              }
              return 0L;
            });
    doReturn(List.of(1L, 1L))
        .when(commands)
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    assertThat(client(connection).publishSelectedAuthorityProjections(ACCOUNT_ID, tenant, values))
        .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
    verify(commands, times(4))
        .<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
    verify(commands, times(1))
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    verify(connection).close();
    assertThat(stored.get("session:auth:generation:membership:" + ACCOUNT_ID + ":" + tenant))
        .containsExactly(values[3]);
  }

  @Test
  void selectedObservationRejectsMissingMembershipAndAnySelectedTtl() throws Exception {
    UUID tenant =
        net.firedevops.firemud.accountservice.service.session
            .AccountSelectedGameplayAuthorityProjectionTest.TENANT;
    byte[][] values =
        net.firedevops.firemud.accountservice.service.session
            .AccountSelectedGameplayAuthorityProjectionTest.selected(
            AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(snapshot()),
            ACCOUNT_ID,
            tenant);
    for (boolean missing : List.of(true, false)) {
      var connection = connection();
      var commands = connection.sync();
      when(commands.get(any(byte[].class)))
          .thenAnswer(
              invocation -> {
                String key = new String(invocation.getArgument(0), StandardCharsets.UTF_8);
                if (key.contains(":issuer:")) return values[0];
                if (key.contains(":account:")) return values[1];
                if (key.contains(":tenant:")) return values[2];
                return missing ? null : values[3];
              });
      if (!missing)
        when(commands.pttl(any(byte[].class)))
            .thenAnswer(
                invocation ->
                    new String(invocation.getArgument(0), StandardCharsets.UTF_8)
                            .contains(":membership:")
                        ? 500L
                        : -1L);
      assertThatThrownBy(
              () -> client(connection).readSelectedAuthorityProjections(ACCOUNT_ID, tenant))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
      verify(connection).close();
    }
  }

  @Test
  void authorityProjectionUsesTwoExactOneKeyCasCallsOneAofAckAndSameConnectionReadback()
      throws Exception {
    byte[][] values =
        AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(snapshot());
    StatefulRedisConnection<byte[], byte[]> connection = connection();
    RedisCommands<byte[], byte[]> commands = connection.sync();
    when(commands.scriptLoad(any(byte[].class)))
        .thenAnswer(invocation -> sha1(invocation.getArgument(0)));
    when(commands.<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class)))
        .thenReturn(1L, 0L);
    doReturn(List.of(1L, 1L))
        .when(commands)
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    when(commands.get(any(byte[].class)))
        .thenAnswer(
            invocation -> {
              byte[] key = invocation.getArgument(0);
              if (Arrays.equals(
                  key, ascii("session:auth:generation:issuer:firemud-account-service"))) {
                return values[0].clone();
              }
              if (Arrays.equals(key, ascii("session:auth:generation:account:" + ACCOUNT_ID))) {
                return values[1].clone();
              }
              return null;
            });
    AccountGameplayDelegationRedisClient client = client(connection);

    assertThat(client.publishAuthorityProjections(ACCOUNT_ID, values[0], values[1]))
        .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);

    ArgumentCaptor<byte[][]> keys = ArgumentCaptor.forClass(byte[][].class);
    ArgumentCaptor<byte[][]> args = ArgumentCaptor.forClass(byte[][].class);
    verify(commands, times(2))
        .<Long>evalsha(anyString(), eq(ScriptOutputType.INTEGER), keys.capture(), args.capture());
    assertThat(keys.getAllValues()).hasSize(2);
    assertThat(
            Arrays.equals(
                keys.getAllValues().get(0)[0],
                ascii("session:auth:generation:issuer:firemud-account-service")))
        .isTrue();
    assertThat(
            Arrays.equals(
                keys.getAllValues().get(1)[0],
                ascii("session:auth:generation:account:" + ACCOUNT_ID)))
        .isTrue();
    assertThat(Arrays.equals(args.getAllValues().get(0)[0], values[0])).isTrue();
    assertThat(new String(args.getAllValues().get(1)[0], StandardCharsets.US_ASCII))
        .isEqualTo("VERIFY");
    assertThat(args.getAllValues().get(1)[1]).containsExactly(values[1]);
    assertThat(args.getAllValues().get(1)[2]).isEmpty();
    verify(commands).aclWhoami();
    verify(commands, times(1))
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    verify(commands)
        .get(
            argThat(
                key ->
                    Arrays.equals(
                        key, ascii("session:auth:generation:issuer:firemud-account-service"))));
    verify(commands, times(2))
        .get(
            argThat(
                key -> Arrays.equals(key, ascii("session:auth:generation:account:" + ACCOUNT_ID))));
    verify(connection).close();
  }

  @Test
  void wrongLiveAccountRoleDeniesProjectionBeforeScriptLoad() throws Exception {
    byte[][] values =
        AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(snapshot());
    StatefulRedisConnection<byte[], byte[]> connection = connection();
    RedisCommands<byte[], byte[]> commands = connection.sync();
    when(commands.aclWhoami()).thenReturn("account_coord_admin");

    assertThatThrownBy(
            () -> client(connection).publishAuthorityProjections(ACCOUNT_ID, values[0], values[1]))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);

    verify(commands).aclWhoami();
    verify(commands, org.mockito.Mockito.never()).scriptLoad(any(byte[].class));
    verify(commands, org.mockito.Mockito.never())
        .<Long>evalsha(anyString(), any(), any(byte[][].class), any(byte[][].class));
    verify(connection).close();
  }

  @Test
  void accountObservationRejectsProjectionWithAnyTtl() throws Exception {
    byte[][] pair =
        AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(snapshot());
    var connection = connection();
    var commands = connection.sync();
    when(commands.get(any(byte[].class)))
        .thenAnswer(
            invocation -> {
              byte[] key = invocation.getArgument(0);
              return Arrays.equals(key, ascii("session:auth:generation:account:" + ACCOUNT_ID))
                  ? pair[1]
                  : pair[0];
            });
    when(commands.pttl(any(byte[].class))).thenReturn(1000L);
    assertThatThrownBy(() -> client(connection).readAuthorityProjections(ACCOUNT_ID))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    verify(connection).close();
  }

  @Test
  void acknowledgementOrReadbackUncertaintyNeverReturnsProjectionSuccess() throws Exception {
    byte[][] values =
        AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(snapshot());
    StatefulRedisConnection<byte[], byte[]> noAckConnection = connection();
    RedisCommands<byte[], byte[]> noAckCommands = noAckConnection.sync();
    when(noAckCommands.scriptLoad(any(byte[].class)))
        .thenAnswer(invocation -> sha1(invocation.getArgument(0)));
    when(noAckCommands.<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class)))
        .thenReturn(1L, 1L);
    doReturn(List.of(1L, 0L))
        .when(noAckCommands)
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));

    assertThatThrownBy(
            () ->
                client(noAckConnection)
                    .publishAuthorityProjections(ACCOUNT_ID, values[0], values[1]))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    verify(noAckCommands, times(1)).get(any(byte[].class));

    StatefulRedisConnection<byte[], byte[]> readbackConnection = connection();
    RedisCommands<byte[], byte[]> readbackCommands = readbackConnection.sync();
    when(readbackCommands.scriptLoad(any(byte[].class)))
        .thenAnswer(invocation -> sha1(invocation.getArgument(0)));
    when(readbackCommands.<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class)))
        .thenReturn(1L, 1L);
    doReturn(List.of(1L, 1L))
        .when(readbackCommands)
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    when(readbackCommands.get(any(byte[].class))).thenReturn(null, ascii("wrong projection"));

    assertThatThrownBy(
            () ->
                client(readbackConnection)
                    .publishAuthorityProjections(ACCOUNT_ID, values[0], values[1]))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
  }

  @Test
  void staleOrConflictingIssuerProjectionStopsBeforeAccountWriteOrDurabilityAck() throws Exception {
    byte[][] values =
        AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(snapshot());
    for (long result : List.of(-1L, -2L, -4L, -5L)) {
      StatefulRedisConnection<byte[], byte[]> connection = connection();
      RedisCommands<byte[], byte[]> commands = connection.sync();
      when(commands.scriptLoad(any(byte[].class)))
          .thenAnswer(invocation -> sha1(invocation.getArgument(0)));
      when(commands.<Long>evalsha(
              anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class)))
          .thenReturn(result);

      assertThatThrownBy(
              () ->
                  client(connection).publishAuthorityProjections(ACCOUNT_ID, values[0], values[1]))
          .isInstanceOf(
              AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);

      verify(commands, times(1))
          .<Long>evalsha(
              anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
      verify(commands, org.mockito.Mockito.never())
          .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
      verify(commands, org.mockito.Mockito.never()).get(any(byte[].class));
      verify(connection).close();
    }
  }

  @Test
  void projectionDescriptorsPinExactRoleKeyArgumentAndLuaDigest() throws Exception {
    List<RedisScriptDescriptor> descriptors =
        new AccountGameplayDelegationRedisScriptContribution()
            .descriptors().stream()
                .filter(descriptor -> descriptor.id().contains("authority-project"))
                .toList();
    assertThat(descriptors).hasSize(1);
    RedisScriptDescriptor issuer =
        descriptors.stream()
            .filter(
                descriptor ->
                    descriptor
                        .id()
                        .equals(AccountGameplayDelegationRedisClient.ISSUER_PROJECTION_SCRIPT_ID))
            .findFirst()
            .orElseThrow();
    RedisScriptDescriptor account =
        net.firedevops.firemud.accountservice.service.AccountGenerationProjectionRedisContract
            .descriptor();
    assertThat(issuer.owner()).isEqualTo("account-service");
    assertThat(account.owner()).isEqualTo("account-service");
    assertThat(issuer.redisRole()).isEqualTo(RedisScriptDescriptor.RedisRole.COORDINATION);
    assertThat(account.redisRole()).isEqualTo(RedisScriptDescriptor.RedisRole.COORDINATION);
    assertThat(issuer.keys()).hasSize(1);
    assertThat(account.keys()).hasSize(1);
    assertThat(issuer.category()).isEqualTo("account_auth_generation_projection");
    assertThat(account.category()).isEqualTo("account_generation_projection_cas");
    assertThat(issuer.keys().getFirst().name()).isEqualTo("authorityProjection");
    assertThat(issuer.keys().getFirst().allowedPrefix())
        .isEqualTo(AccountGameplayDelegationAuthorityProjection.ISSUER_KEY_PREFIX);
    assertThat(issuer.keys().getFirst().requiredHashTag()).isEmpty();
    assertThat(account.keys().getFirst().name()).isEqualTo("accountGenerationProjection");
    assertThat(account.keys().getFirst().allowedPrefix())
        .isEqualTo(AccountGameplayDelegationAuthorityProjection.ACCOUNT_KEY_PREFIX);
    assertThat(account.keys().getFirst().requiredHashTag()).isEmpty();
    assertThat(issuer.arguments()).containsExactly("canonicalProjectionUtf8");
    assertThat(account.arguments())
        .containsExactly("expectedMode", "expectedBytes", "candidateBytes");
    assertThat(account.scriptResource()).isEqualTo("redis/account_generation_projection_cas.lua");
    assertThat(issuer.scriptResource())
        .isEqualTo("redis/lua/account-game-session-delegation-authority-project.lua");
    assertThat(issuer.sourceSha256()).isNotEqualTo(account.sourceSha256());

    byte[] source;
    try (var input = getClass().getClassLoader().getResourceAsStream(issuer.scriptResource())) {
      assertThat(input).isNotNull();
      source = input.readAllBytes();
    }
    String lua = new String(source, StandardCharsets.UTF_8);
    assertThat(sha256(source)).isEqualTo(issuer.sourceSha256());
    assertThat(lua)
        .doesNotContain("MAX_SIGNED_BIGINT")
        .contains("return string.len(left) > string.len(right)")
        .contains("return left > right")
        .contains("local function decrement_decimal(value)")
        .doesNotContain("tonumber(value.generation)")
        .doesNotContain("tonumber(value.sourceVersion)")
        .doesNotContain("tonumber(value.issuanceFence)");
  }

  @Test
  void committedActivationDescriptorAndLuaPinOnlyExactLifecycleByteChange() throws Exception {
    RedisScriptDescriptor descriptor =
        new AccountGameplayDelegationRedisScriptContribution()
            .descriptors().stream()
                .filter(
                    item ->
                        item.id()
                            .equals(
                                AccountGameplayDelegationRedisClient.ACTIVE_TRANSITION_SCRIPT_ID))
                .findFirst()
                .orElseThrow();
    assertThat(descriptor.owner()).isEqualTo("account-service");
    assertThat(descriptor.redisRole()).isEqualTo(RedisScriptDescriptor.RedisRole.COORDINATION);
    assertThat(descriptor.category()).isEqualTo("account_auth_token_registry");
    assertThat(descriptor.keys()).hasSize(1);
    assertThat(descriptor.keys().getFirst().name()).isEqualTo("tokenRecord");
    assertThat(descriptor.keys().getFirst().allowedPrefix())
        .isEqualTo(AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX);
    assertThat(descriptor.keys().getFirst().requiredHashTag()).isEmpty();
    assertThat(descriptor.arguments())
        .containsExactly(
            "exactCanonicalPendingRecordUtf8",
            "exactCanonicalActiveRecordUtf8",
            "originalAbsoluteExpiryEpochMillis");

    byte[] source;
    try (var input = getClass().getClassLoader().getResourceAsStream(descriptor.scriptResource())) {
      assertThat(input).isNotNull();
      source = input.readAllBytes();
    }
    String lua = new String(source, StandardCharsets.UTF_8);
    assertThat(sha256(source)).isEqualTo(descriptor.sourceSha256());
    assertThat(lua)
        .contains("string.gsub(pending, '\"registryVersion\":1', '\"registryVersion\":2')")
        .contains("string.gsub(versioned, '\"state\":\"pending\"', '\"state\":\"active\"')")
        .contains("if current == pending then")
        .contains("if current == active then")
        .contains("'PXAT', deadline, 'XX'")
        .doesNotContain("cjson.decode", "cjson.encode", "redis.call('EXPIRE'", "PSETEX");
  }

  @Test
  void committedActivationRequiresRepositoryProducedOwnerValueAndDoesNotOpenWithoutIt() {
    StatefulRedisConnection<byte[], byte[]> connection = connection();
    AccountGameplayDelegationRedisClient client = client(connection);

    assertThatThrownBy(() -> client.activateCommittedPending(null, null))
        .isInstanceOf(NullPointerException.class);

    verify(connection, org.mockito.Mockito.never()).sync();
    verify(connection, org.mockito.Mockito.never()).close();
  }

  @Test
  void committedActivationChangesOnlyLifecycleFieldsAndExactRetryKeepsAbsoluteDeadline()
      throws Exception {
    ActivationFixture fixture = activationFixture();

    var activated =
        fixture.client().activateCommittedPending(fixture.pendingReceipt(), fixture.owner());
    assertThat(activated.outcome())
        .isEqualTo(AccountGameplayDelegationRedisClient.ActiveRegistrationOutcome.ACTIVATED);
    assertThat(activated.absoluteExpiryMillis()).isEqualTo(fixture.absoluteExpiryMillis());
    assertThat(fixture.absoluteExpiry().get()).isEqualTo(fixture.absoluteExpiryMillis());
    var active =
        GameSessionAccountDelegationRegistryRecord.decode(
            fixture.registryValue().get(),
            GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES);
    assertThat(active.state()).isEqualTo("active");
    assertThat(active.registryVersion()).isEqualTo(2L);
    Map<String, Object> expected = new LinkedHashMap<>(fixture.pendingRecord().fields());
    expected.put("registryVersion", active.fields().get("registryVersion"));
    expected.put("state", "active");
    assertThat(
            active.toCanonicalJsonBytes(
                GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES))
        .containsExactly(canonicalBytes(expected));

    var exactRetry =
        fixture.client().activateCommittedPending(fixture.pendingReceipt(), fixture.owner());
    assertThat(exactRetry.outcome())
        .isEqualTo(AccountGameplayDelegationRedisClient.ActiveRegistrationOutcome.EXACT_RETRY);
    assertThat(exactRetry.canonicalActiveRecordSha256())
        .isEqualTo(activated.canonicalActiveRecordSha256());
    assertThat(fixture.absoluteExpiry().get()).isEqualTo(fixture.absoluteExpiryMillis());
    verify(fixture.commands(), times(3))
        .<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
    verify(fixture.commands(), times(3))
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    verify(fixture.connection(), times(3)).close();
  }

  @Test
  void committedActivationRejectsChangedOwnerRecordAndMissingOrStaleRegistryValues()
      throws Exception {
    ActivationFixture ownerMismatch = activationFixture();
    doReturn("f".repeat(64)).when(ownerMismatch.owner()).tokenSha256();
    assertThatThrownBy(
            () ->
                ownerMismatch
                    .client()
                    .activateCommittedPending(
                        ownerMismatch.pendingReceipt(), ownerMismatch.owner()))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    verify(ownerMismatch.commands(), times(1))
        .<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));

    ActivationFixture missing = activationFixture();
    missing.registryValue().set(null);
    assertThatThrownBy(
            () ->
                missing
                    .client()
                    .activateCommittedPending(missing.pendingReceipt(), missing.owner()))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    verify(missing.commands(), times(1))
        .<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));

    ActivationFixture stale = activationFixture();
    Map<String, Object> revokedFields = new LinkedHashMap<>(stale.pendingRecord().fields());
    revokedFields.put("state", "revoked");
    stale.registryValue().set(canonicalBytes(revokedFields));
    assertThatThrownBy(
            () -> stale.client().activateCommittedPending(stale.pendingReceipt(), stale.owner()))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    verify(stale.commands(), times(1))
        .<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
  }

  @Test
  void committedActivationRejectsWrongRoleExpiryReadbackAndInsufficientAof() throws Exception {
    ActivationFixture wrongRole = activationFixture();
    doReturn("account_coord_admin").when(wrongRole.commands()).aclWhoami();
    assertThatThrownBy(
            () ->
                wrongRole
                    .client()
                    .activateCommittedPending(wrongRole.pendingReceipt(), wrongRole.owner()))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    verify(wrongRole.commands(), times(1))
        .<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));

    ActivationFixture wrongExpiry = activationFixture();
    doReturn(wrongExpiry.absoluteExpiryMillis() + 1L)
        .when(wrongExpiry.commands())
        .pexpiretime(any(byte[].class));
    assertThatThrownBy(
            () ->
                wrongExpiry
                    .client()
                    .activateCommittedPending(wrongExpiry.pendingReceipt(), wrongExpiry.owner()))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    verify(wrongExpiry.commands(), times(1))
        .<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));

    ActivationFixture wrongReadback = activationFixture();
    AtomicInteger reads = new AtomicInteger();
    doAnswer(
            invocation ->
                reads.incrementAndGet() == 2
                    ? ascii("unexpected-active-readback")
                    : wrongReadback.registryValue().get().clone())
        .when(wrongReadback.commands())
        .get(any(byte[].class));
    assertThatThrownBy(
            () ->
                wrongReadback
                    .client()
                    .activateCommittedPending(
                        wrongReadback.pendingReceipt(), wrongReadback.owner()))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    assertThat(wrongReadback.absoluteExpiry().get())
        .isEqualTo(wrongReadback.absoluteExpiryMillis());

    ActivationFixture insufficientAof = activationFixture();
    doReturn(List.of(1L, 0L))
        .when(insufficientAof.commands())
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    assertThatThrownBy(
            () ->
                insufficientAof
                    .client()
                    .activateCommittedPending(
                        insufficientAof.pendingReceipt(), insufficientAof.owner()))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    assertThat(
            GameSessionAccountDelegationRegistryRecord.decode(
                    insufficientAof.registryValue().get(),
                    GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES)
                .state())
        .isEqualTo("active");
    verify(insufficientAof.commands(), times(2)).get(any(byte[].class));
  }

  /** Unit-only fake owner metadata exercises low-level Lua mechanics; it is not COMMITTED proof. */
  private static ActivationFixture activationFixture() throws Exception {
    String accountStream = "account:auth-authority:v1:account/" + ACCOUNT_ID;
    var authority =
        new net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord
            .AccountAuthoritySnapshot(
            ACCOUNT_ID,
            7L,
            7L,
            11L,
            11L,
            11L,
            11L,
            Optional.of(
                new GameSessionAccountDelegationProfile.AccountSecurityCutoff(
                    "11", accountStream, "10")));
    long now = java.time.Instant.now().getEpochSecond();
    PendingIntent intent =
        new PendingIntent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "spiffe://firemud/ns/test/sa/game-session-service",
            UUID.randomUUID(),
            UUID.randomUUID(),
            now,
            now,
            Math.addExact(now, 180L),
            authority,
            net.firedevops.firemud.accountservice.repository
                .AccountGameplayCredentialRequestBindingFixture.binding());
    String compact = compactToken(intent);
    EvidenceBundleReference bundle =
        new EvidenceBundleReference("1", "11", "9", "12345678", "d".repeat(64));
    GameSessionAccountDelegationRegistryRecord pendingRecord =
        GameSessionAccountDelegationRegistryRecord.fromAccountSignedCompactJwt(
            compact,
            "42",
            new IssuanceBinding(
                intent.operationId().toString(),
                intent.requestId().toString(),
                AccountGameplayDelegationIssuanceRepository.requestDigest(intent),
                ACCOUNT_ID.toString()),
            authority,
            bundle,
            now,
            GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES);
    byte[] pendingBytes =
        pendingRecord.toCanonicalJsonBytes(
            GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES);
    long deadline =
        Math.addExact(Math.multiplyExact(intent.expiresAtEpochSecond(), 1_000L), 30_000L);

    AccountGameplayDelegationPendingIdentity identity =
        new AccountGameplayDelegationPendingIdentity(
            intent.operationId(),
            intent.requestId(),
            ACCOUNT_ID,
            intent.callerWorkload(),
            intent.callerContextId(),
            intent.credentialRequestBinding(),
            AccountGameplayDelegationIssuanceRepository.requestDigest(intent),
            intent.tokenJti(),
            intent.issuedAtEpochSecond(),
            intent.notBeforeEpochSecond(),
            intent.expiresAtEpochSecond());
    CommittedCandidateVerificationData owner = mock(CommittedCandidateVerificationData.class);
    var signer = mock(AccountGameplayDelegationSigner.AuthenticatedSignerCorrespondence.class);
    when(signer.promotionStatus()).thenReturn("COMMITTED");
    when(signer.targetKid()).thenReturn(pendingRecord.kid());
    when(signer.targetGeneration()).thenReturn(pendingRecord.signerGeneration());
    when(owner.identity()).thenReturn(identity);
    when(owner.authoritySnapshot()).thenReturn(authority);
    when(owner.evidenceBundleReference()).thenReturn(bundle);
    when(owner.tokenSha256()).thenReturn(pendingRecord.tokenHash());
    when(owner.signerKid()).thenReturn(pendingRecord.kid());
    when(owner.signerGeneration()).thenReturn(pendingRecord.signerGeneration());
    when(owner.canonicalRegistryRecordSha256()).thenReturn(sha256(pendingBytes));
    when(owner.commitProofSha256()).thenReturn("c".repeat(64));
    when(owner.signerCorrespondence()).thenReturn(signer);

    StatefulRedisConnection<byte[], byte[]> connection = connection();
    RedisCommands<byte[], byte[]> commands = connection.sync();
    AtomicReference<byte[]> stored = new AtomicReference<>(pendingBytes.clone());
    AtomicLong observedExpiry = new AtomicLong(deadline);
    when(commands.scriptLoad(any(byte[].class)))
        .thenAnswer(invocation -> sha1(invocation.getArgument(0)));
    when(commands.<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class)))
        .thenReturn(1L);
    when(commands.get(any(byte[].class)))
        .thenAnswer(invocation -> stored.get() == null ? null : stored.get().clone());
    when(commands.pexpiretime(any(byte[].class))).thenAnswer(invocation -> observedExpiry.get());
    doReturn(List.of(1L, 1L))
        .when(commands)
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));

    AccountGameplayDelegationRedisClient client = client(connection);
    var pendingReceipt = client.registerPending(pendingRecord.tokenHash(), pendingBytes, deadline);
    doAnswer(
            invocation -> {
              Object[] rawArguments = invocation.getRawArguments();
              if (rawArguments.length != 4
                  || !(rawArguments[3] instanceof byte[][] args)
                  || args.length != 3
                  || !Arrays.equals(
                      args[2], Long.toString(deadline).getBytes(StandardCharsets.US_ASCII))) {
                return -1L;
              }
              byte[] current = stored.get();
              if (Arrays.equals(current, args[0])) {
                stored.set(args[1].clone());
                return 1L;
              }
              if (Arrays.equals(current, args[1])) {
                return 0L;
              }
              return -1L;
            })
        .when(commands)
        .<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
    return new ActivationFixture(
        client,
        connection,
        commands,
        pendingRecord,
        pendingReceipt,
        owner,
        stored,
        observedExpiry,
        deadline);
  }

  private static String compactToken(PendingIntent intent) throws Exception {
    Map<String, Object> header = Map.of("alg", "RS256", "kid", "fixture-key", "typ", "JWT");
    Map<String, Object> claims =
        Map.ofEntries(
            Map.entry("iss", GameSessionAccountDelegationProfile.ISSUER),
            Map.entry("sub", ACCOUNT_ID.toString()),
            Map.entry("accountId", ACCOUNT_ID.toString()),
            Map.entry("jti", intent.tokenJti().toString()),
            Map.entry("aud", GameSessionAccountDelegationProfile.AUDIENCE),
            Map.entry("iat", intent.issuedAtEpochSecond()),
            Map.entry("nbf", intent.notBeforeEpochSecond()),
            Map.entry("exp", intent.expiresAtEpochSecond()),
            Map.entry("tokenGeneration", "1"),
            Map.entry(
                "authorityTuple",
                GameSessionAccountDelegationProfile.authorityTuple(
                    intent.authoritySnapshot().issuerGeneration(),
                    intent.authoritySnapshot().accountGeneration(),
                    intent.authoritySnapshot().accountSecurityCutoff())),
            Map.entry("membershipVersion", Map.of()),
            Map.entry("issuanceFence", Long.toString(intent.authoritySnapshot().issuanceFence())));
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString(canonicalBytes(header))
        + "."
        + encoder.encodeToString(canonicalBytes(claims))
        + "."
        + encoder.encodeToString(ascii("test-only-not-a-verified-signature"));
  }

  private static byte[] canonicalBytes(Object value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static AccountGameplayDelegationRedisClient client(
      StatefulRedisConnection<byte[], byte[]> connection) {
    return new AccountGameplayDelegationRedisClient(
        () -> connection,
        RedisScriptCatalog.fromContributions(
            List.of(
                new AccountGameplayDelegationRedisScriptContribution(),
                new net.firedevops.firemud.accountservice.service
                    .AccountGenerationProjectionRedisScriptContribution())),
        new AccountGameplayDelegationRedisClient.AcknowledgementRequirements(1, 1, 1_000),
        AccountGameplayDelegationAuthorityProjection.MAX_PROJECTION_BYTES,
        AccountGameplayDelegationRedisClientTest.class.getClassLoader());
  }

  @SuppressWarnings("unchecked")
  private static StatefulRedisConnection<byte[], byte[]> connection() {
    StatefulRedisConnection<byte[], byte[]> connection = mock(StatefulRedisConnection.class);
    RedisCommands<byte[], byte[]> commands = mock(RedisCommands.class);
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(false).build());
    when(connection.isOpen()).thenReturn(true);
    when(connection.sync()).thenReturn(commands);
    when(commands.pttl(any(byte[].class))).thenReturn(-1L);
    when(commands.aclWhoami())
        .thenReturn(AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY);
    return connection;
  }

  private static IssuerAccountSourceSnapshot snapshot() {
    return AccountGameplayDelegationAuthorityProjectionTest.snapshot(1L, 11L);
  }

  private static SourceCheckpoint checkpoint(String stream, long sequence) {
    return new SourceCheckpoint(
        stream,
        sequence,
        Optional.of("event-" + sequence),
        Optional.of("sha256:" + "a".repeat(64)));
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private record ActivationFixture(
      AccountGameplayDelegationRedisClient client,
      StatefulRedisConnection<byte[], byte[]> connection,
      RedisCommands<byte[], byte[]> commands,
      GameSessionAccountDelegationRegistryRecord pendingRecord,
      AccountGameplayDelegationRedisClient.PendingRegistrationReceipt pendingReceipt,
      CommittedCandidateVerificationData owner,
      AtomicReference<byte[]> registryValue,
      AtomicLong absoluteExpiry,
      long absoluteExpiryMillis) {}

  private static String sha1(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
  }

  private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
