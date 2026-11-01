package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.service.session.AccountCoordinationPinnedConnectionProvider.AcknowledgementRequirements;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptContribution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit Redis/qualification doubles only; not live recovery authority or physical proof. */
class AccountTenantCreationAdmissionRedisClientTest {
  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactExistingReassertionRequiresWriteAckFreshReadbackAndHeldCallback() throws Exception {
    var f = new Fixture();
    doReturn(0L)
        .when(f.writer)
        .evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
    var retained =
        new AtomicReference<AccountTenantCreationAdmissionRedisClient.QualifiedReceipt>();
    String result =
        f.client.withExistingReceipt(
            f.binding,
            receipt -> {
              verify(f.lease, never()).close();
              receipt.requireCurrent();
              assertThat(receipt.binding()).isEqualTo(f.binding);
              assertThat(receipt.localAofCount()).isEqualTo(1L);
              assertThat(receipt.replicaAofCount()).isEqualTo(1L);
              assertThat(receipt.qualificationFence()).isEqualTo(19L);
              byte[] copied = receipt.canonicalReceipt();
              copied[0] = 0;
              assertThat(receipt.canonicalReceipt()).isEqualTo(f.binding.canonicalReceipt());
              retained.set(receipt);
              return "only-callback-result";
            });
    assertThat(result).isEqualTo("only-callback-result");
    var order = inOrder(f.writer, f.writeConnection, f.reader, f.readConnection, f.lease);
    order.verify(f.writer).aclWhoami();
    order.verify(f.writer).scriptLoad(any(byte[].class));
    order
        .verify(f.writer)
        .evalsha(
            anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
    order.verify(f.writer).dispatch(any(), any(), any());
    order.verify(f.writeConnection).close();
    order.verify(f.reader).aclWhoami();
    order.verify(f.reader).get(any(byte[].class));
    order.verify(f.reader).pexpiretime(any(byte[].class));
    order.verify(f.readConnection).close();
    order.verify(f.lease).close();
    assertThatThrownBy(retained.get()::requireCurrent).isInstanceOf(IllegalStateException.class);
    assertThat(
            Arrays.stream(
                    AccountTenantCreationAdmissionRedisClient.QualifiedReceipt.class
                        .getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())))
        .isTrue();
  }

  @Test
  void initialReceiptChecksOriginalRecordDigestAndNeverUsesAmbientSqlLocks() throws Exception {
    var f = new Fixture();
    String readBackDigest =
        f.client.withInitialReceipt(
            f.binding,
            AccountTenantCreationAdmissionBindingTest.active(),
            receipt -> receipt.recordDigest());
    assertThat(readBackDigest)
        .isEqualTo(AccountControlUiCoordination.digest("SHA-256", f.binding.canonicalReceipt()));
    var changed = AccountTenantCreationAdmissionBindingTest.active();
    changed[0] = 0;
    assertThatThrownBy(() -> f.client.withInitialReceipt(f.binding, changed, receipt -> true))
        .isInstanceOf(IllegalStateException.class);

    var locked = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> locked.client.withExistingReceipt(locked.binding, receipt -> true))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(locked.provider, locked.qualification);
  }

  @Test
  void missingQualificationWrongBindingAndUnavailablePrincipalDenyBeforeMutation()
      throws Exception {
    var missing = new Fixture();
    when(missing.qualification.acquire(anyString(), anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> missing.client.withExistingReceipt(missing.binding, receipt -> true))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(missing.provider);

    var wrongBinding = new Fixture();
    when(wrongBinding.lease.protectedBindingId()).thenReturn("different-binding");
    assertThatThrownBy(
            () -> wrongBinding.client.withExistingReceipt(wrongBinding.binding, receipt -> true))
        .isInstanceOf(IllegalStateException.class);
    verify(wrongBinding.lease).close();
    verifyNoInteractions(wrongBinding.provider);

    var wrongPrincipal = new Fixture();
    when(wrongPrincipal.writer.aclWhoami()).thenReturn("another_owner");
    assertThatThrownBy(
            () ->
                wrongPrincipal.client.withExistingReceipt(wrongPrincipal.binding, receipt -> true))
        .isInstanceOf(IllegalStateException.class);
    verify(wrongPrincipal.writer, never()).scriptLoad(any(byte[].class));

    var reconnecting = new Fixture();
    when(reconnecting.writeConnection.getOptions())
        .thenReturn(ClientOptions.builder().autoReconnect(true).build());
    assertThatThrownBy(
            () -> reconnecting.client.withExistingReceipt(reconnecting.binding, receipt -> true))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(reconnecting.writer);
  }

  @Test
  void insufficientLostOrMalformedAcknowledgementNeverCallsReadbackOrCallback() throws Exception {
    for (List<Object> counts :
        List.<List<Object>>of(List.of(0L, 1L), List.of(1L, 0L), List.of(1L), List.of("1", "1"))) {
      var f = new Fixture();
      doReturn(counts).when(f.writer).dispatch(any(), any(), any());
      var called = new AtomicBoolean();
      assertThatThrownBy(
              () ->
                  f.client.withInitialReceipt(
                      f.binding,
                      AccountTenantCreationAdmissionBindingTest.active(),
                      receipt -> called.compareAndSet(false, true)))
          .isInstanceOf(IllegalStateException.class);
      assertThat(called).isFalse();
      verifyNoInteractions(f.reader);
    }
    var lost = new Fixture();
    when(lost.writer.dispatch(any(), any(), any()))
        .thenThrow(new IllegalStateException("lost ACK"));
    assertThatThrownBy(() -> lost.client.withExistingReceipt(lost.binding, receipt -> true))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(lost.reader);
  }

  @Test
  void changedExpiryInterruptedReadbackOrDeadlineCrossingCannotPublishEvidence() throws Exception {
    var wrongExpiry = new Fixture();
    when(wrongExpiry.reader.pexpiretime(any(byte[].class)))
        .thenReturn(wrongExpiry.binding.receiptCleanupDeadlineEpochMillis() + 1L);
    assertThatThrownBy(
            () -> wrongExpiry.client.withExistingReceipt(wrongExpiry.binding, receipt -> true))
        .isInstanceOf(IllegalStateException.class);

    var interrupted = new Fixture();
    when(interrupted.reader.get(any(byte[].class)))
        .thenThrow(new IllegalStateException("lost read"));
    assertThatThrownBy(
            () -> interrupted.client.withExistingReceipt(interrupted.binding, receipt -> true))
        .isInstanceOf(IllegalStateException.class);

    var late = new Fixture();
    when(late.reader.get(any(byte[].class)))
        .thenAnswer(
            ignored -> {
              when(late.clock.millis()).thenReturn(late.binding.operationDeadlineEpochMillis());
              return late.binding.canonicalReceipt();
            });
    var called = new AtomicBoolean();
    assertThatThrownBy(
            () ->
                late.client.withInitialReceipt(
                    late.binding,
                    AccountTenantCreationAdmissionBindingTest.active(),
                    receipt -> called.compareAndSet(false, true)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(called).isFalse();
  }

  @Test
  void unknownOutcomeOrMissingRecoveryCannotObtainDurabilityReceipt() throws Exception {
    for (long outcome : new long[] {-1L, -2L, -3L, -4L, -5L, 2L, 1L}) {
      var f = new Fixture();
      doReturn(outcome)
          .when(f.writer)
          .evalsha(
              anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
      assertThatThrownBy(() -> f.client.withExistingReceipt(f.binding, receipt -> true))
          .isInstanceOf(IllegalStateException.class);
      verify(f.writer, never()).dispatch(any(), any(), any());
    }
  }

  @Test
  void lostExternalExclusionAfterPhysicalReadbackDoesNotEnterCallback() throws Exception {
    var f = new Fixture();
    when(f.reader.get(any(byte[].class)))
        .thenAnswer(
            ignored -> {
              doThrow(new IllegalStateException("Controlled external exclusion loss"))
                  .when(f.lease)
                  .requireCurrent();
              return f.binding.canonicalReceipt();
            });
    var called = new AtomicBoolean();
    assertThatThrownBy(
            () ->
                f.client.withExistingReceipt(
                    f.binding, receipt -> called.compareAndSet(false, true)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(called).isFalse();
    verify(f.lease).close();
  }

  @SuppressWarnings("unchecked")
  private static final class Fixture {
    final AccountTenantCreationAdmissionBinding binding =
        AccountTenantCreationAdmissionBindingTest.binding();
    final AccountCoordinationPinnedConnectionProvider provider =
        mock(AccountCoordinationPinnedConnectionProvider.class);
    final StatefulRedisConnection<byte[], byte[]> writeConnection =
        mock(StatefulRedisConnection.class);
    final StatefulRedisConnection<byte[], byte[]> readConnection =
        mock(StatefulRedisConnection.class);
    final RedisCommands<byte[], byte[]> writer = mock(RedisCommands.class);
    final RedisCommands<byte[], byte[]> reader = mock(RedisCommands.class);
    final AccountTenantCreationAdmissionCoordinationQualification qualification =
        mock(AccountTenantCreationAdmissionCoordinationQualification.class);
    final AccountTenantCreationAdmissionCoordinationQualification.Lease lease =
        mock(AccountTenantCreationAdmissionCoordinationQualification.Lease.class);
    final Clock clock = mock(Clock.class);
    final AccountTenantCreationAdmissionRedisClient client;

    Fixture() throws Exception {
      when(clock.millis()).thenReturn(AccountTenantCreationAdmissionBindingTest.NOW);
      when(provider.openPinnedConnection()).thenReturn(writeConnection, readConnection);
      when(writeConnection.isOpen()).thenReturn(true);
      when(readConnection.isOpen()).thenReturn(true);
      when(writeConnection.getOptions())
          .thenReturn(ClientOptions.builder().autoReconnect(false).build());
      when(readConnection.getOptions())
          .thenReturn(ClientOptions.builder().autoReconnect(false).build());
      when(writeConnection.sync()).thenReturn(writer);
      when(readConnection.sync()).thenReturn(reader);
      when(writer.aclWhoami()).thenReturn("account_coord_app");
      when(reader.aclWhoami()).thenReturn("account_coord_app");
      byte[] source;
      try (var input =
          getClass()
              .getClassLoader()
              .getResourceAsStream(AccountTenantCreationAdmissionRedisContract.RESOURCE)) {
        if (input == null) throw new IllegalStateException("Missing admission Lua");
        source = input.readAllBytes();
      }
      when(writer.scriptLoad(any(byte[].class)))
          .thenReturn(AccountControlUiCoordination.digest("SHA-1", source));
      doReturn(0L)
          .when(writer)
          .evalsha(
              anyString(), eq(ScriptOutputType.INTEGER), any(byte[][].class), any(byte[][].class));
      doReturn(List.of(1L, 1L)).when(writer).dispatch(any(), any(), any());
      when(reader.get(any(byte[].class))).thenReturn(binding.canonicalReceipt());
      when(reader.pexpiretime(any(byte[].class)))
          .thenReturn(binding.receiptCleanupDeadlineEpochMillis());
      when(qualification.acquire(anyString(), anyString())).thenReturn(Optional.of(lease));
      when(lease.scope()).thenReturn(AccountTenantCreationAdmissionRedisKeys.QUALIFICATION_SCOPE);
      when(lease.protectedBindingId()).thenReturn("test-only-account-coordination");
      when(lease.fence()).thenReturn(19L);
      RedisScriptContribution contribution =
          new RedisScriptContribution() {
            @Override
            public String ownerId() {
              return "account-service";
            }

            @Override
            public Collection<RedisScriptDescriptor> descriptors() {
              return List.of(AccountTenantCreationAdmissionRedisContract.descriptor());
            }
          };
      client =
          new AccountTenantCreationAdmissionRedisClient(
              provider,
              new AcknowledgementRequirements(1, 1, 1000),
              RedisScriptCatalog.fromContributions(List.of(contribution)),
              qualification,
              "test-only-account-coordination",
              clock);
    }
  }
}
