package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjectionRedisScriptContribution;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/** The issuer-only operation uses the installed v2 one-key contract, not the obsolete v1 store. */
class AccountIssuerGenerationProjectionRedisClientTest {
  private static final IssuerGenerationProjection BASELINE =
      new IssuerGenerationProjection(
          GameSessionAccountDelegationProfile.ISSUER,
          "1",
          "1",
          "account:auth-authority:v1:issuer/" + GameSessionAccountDelegationProfile.ISSUER,
          "0",
          Optional.empty(),
          Optional.empty(),
          Optional.empty());

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void issuerOnlyUsesExactlyOneCanonicalKeyCasPinnedConnectionAckAndExactReadback(boolean retry)
      throws Exception {
    var f = fixture();
    byte[] bytes = BASELINE.toJson().getBytes(StandardCharsets.UTF_8);
    when(f.commands.get(any(byte[].class))).thenReturn(retry ? bytes : null, bytes);
    when(f.commands.<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class)))
        .thenReturn(retry ? 0L : 1L);
    assertThat(f.client.publishIssuerAuthorityProjection(BASELINE))
        .isEqualTo(
            retry
                ? AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.EXACT_RETRY
                : AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
    var keys = ArgumentCaptor.forClass(byte[][].class);
    var arguments = ArgumentCaptor.forClass(byte[][].class);
    verify(f.commands)
        .<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), keys.capture(), arguments.capture());
    assertThat(keys.getValue().length).isEqualTo(1);
    assertThat(keys.getValue()[0])
        .containsExactly(BASELINE.key().getBytes(StandardCharsets.US_ASCII));
    assertThat(new String(arguments.getValue()[0], StandardCharsets.US_ASCII))
        .isEqualTo(retry ? "VERIFY" : "ABSENT");
    assertThat(arguments.getValue()[1]).containsExactly(retry ? bytes : new byte[0]);
    assertThat(arguments.getValue()[2]).containsExactly(retry ? new byte[0] : bytes);
    verify(f.commands).aclWhoami();
    verify(f.commands, times(1))
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    verify(f.commands, times(2)).get(any(byte[].class));
    verify(f.connection).close();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"ROLE", "RECONNECT", "MALFORMED", "CAS_REFUSAL", "AOF", "READBACK", "TTL"})
  void issuerOnlyCannotReturnSuccessForUncertainOrNoncanonicalPublication(String failure)
      throws Exception {
    var f = fixture();
    byte[] bytes = BASELINE.toJson().getBytes(StandardCharsets.UTF_8);
    when(f.commands.get(any(byte[].class))).thenReturn(null, bytes);
    switch (failure) {
      case "ROLE" -> when(f.commands.aclWhoami()).thenReturn("cache_rate_limit_app");
      case "RECONNECT" -> when(f.connection.getOptions()).thenReturn(ClientOptions.create());
      case "MALFORMED" ->
          when(f.commands.get(any(byte[].class))).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
      case "CAS_REFUSAL" ->
          when(f.commands.<Long>evalsha(
                  anyString(),
                  eq(ScriptOutputType.INTEGER),
                  any(byte[][].class),
                  any(byte[][].class)))
              .thenReturn(-1L);
      case "AOF" ->
          doReturn(List.of(1L, 0L))
              .when(f.commands)
              .dispatch(
                  any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
      case "READBACK" -> when(f.commands.get(any(byte[].class))).thenReturn(null, new byte[0]);
      case "TTL" -> when(f.commands.pttl(any(byte[].class))).thenReturn(5000L);
      default -> throw new IllegalArgumentException(failure);
    }
    assertThatThrownBy(() -> f.client.publishIssuerAuthorityProjection(BASELINE))
        .isInstanceOf(AccountGameplayDelegationRedisClient.AccountCoordinationRedisException.class);
    verify(f.connection).close();
  }

  @Test
  void issuerOnlyPinsExactInstalledCoordinationIssuerV2Descriptor() {
    var catalog =
        RedisScriptCatalog.fromContributions(
            List.of(
                new AccountGameplayDelegationRedisScriptContribution(),
                new AccountGenerationProjectionRedisScriptContribution()));
    var contract =
        catalog.require(AccountGameplayDelegationRedisClient.ISSUER_PROJECTION_SCRIPT_ID);
    assertThat(contract.scriptId()).endsWith(".v2");
    assertThat(contract.principal()).isEqualTo("account_coord_app");
    assertThat(contract.role().name()).isEqualTo("COORDINATION");
    assertThat(contract.keys()).hasSize(1);
    assertThat(contract.keys().getFirst().ownedPrefix())
        .isEqualTo(IssuerGenerationProjection.KEY_PREFIX);
    assertThat(contract.arguments().stream().map(value -> value.name()).toList())
        .containsExactly("expectedMode", "expectedBytes", "candidateBytes");
  }

  @SuppressWarnings("unchecked")
  private static Fixture fixture() throws Exception {
    StatefulRedisConnection<byte[], byte[]> connection = mock(StatefulRedisConnection.class);
    RedisCommands<byte[], byte[]> commands = mock(RedisCommands.class);
    when(connection.getOptions()).thenReturn(ClientOptions.builder().autoReconnect(false).build());
    when(connection.isOpen()).thenReturn(true);
    when(connection.sync()).thenReturn(commands);
    when(commands.aclWhoami())
        .thenReturn(AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY);
    when(commands.pttl(any(byte[].class))).thenReturn(-1L);
    when(commands.scriptLoad(any(byte[].class)))
        .thenAnswer(
            invocation ->
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-1").digest(invocation.getArgument(0))));
    when(commands.<Long>evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class)))
        .thenReturn(1L);
    doReturn(List.of(1L, 1L))
        .when(commands)
        .dispatch(any(ProtocolKeyword.class), any(CommandOutput.class), any(CommandArgs.class));
    var client =
        new AccountGameplayDelegationRedisClient(
            () -> connection,
            RedisScriptCatalog.fromContributions(
                List.of(
                    new AccountGameplayDelegationRedisScriptContribution(),
                    new AccountGenerationProjectionRedisScriptContribution())),
            new AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements(1, 1, 1000),
            Clock.systemUTC(),
            GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES,
            AccountIssuerGenerationProjectionRedisClientTest.class.getClassLoader());
    return new Fixture(client, connection, commands);
  }

  private record Fixture(
      AccountGameplayDelegationRedisClient client,
      StatefulRedisConnection<byte[], byte[]> connection,
      RedisCommands<byte[], byte[]> commands) {}
}
