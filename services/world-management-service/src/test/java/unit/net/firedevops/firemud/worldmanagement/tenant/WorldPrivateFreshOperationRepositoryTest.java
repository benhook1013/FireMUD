package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.util.UUID;
import net.firedevops.firemud.worldmanagement.tenant.WorldPrivateFreshOperation.SourceProtectionBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldPrivateFreshOperation.Terminal;
import net.firedevops.firemud.worldmanagement.tenant.WorldPrivateFreshOperationRepository.HeldPermission;
import net.firedevops.firemud.worldmanagement.tenant.WorldPrivateFreshOperationRepository.HeldSourceProtection;
import net.firedevops.firemud.worldmanagement.tenant.WorldPrivateFreshOperationRepository.PermissionUnavailableException;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled source/permission leases are distinct. No production authority is stipulated. */
class WorldPrivateFreshOperationRepositoryTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void defaultConstructionRefusesMaterializationAndAbortBeforeSql() {
    var dsl = mock(DSLContext.class);
    var manager = mock(PlatformTransactionManager.class);
    var owner = new WorldPrivateFreshOperationRepository(dsl, manager, "world_test", "test");
    var input = input();
    assertThatThrownBy(() -> owner.materialize(input))
        .isInstanceOf(PermissionUnavailableException.class);
    assertThatThrownBy(() -> owner.abort(input)).isInstanceOf(PermissionUnavailableException.class);
    verifyNoInteractions(dsl, manager);
  }

  @Test
  void permissionDoesNotEstablishSourceCurrentnessAndBothLeasesCloseOnRefusal() {
    var dsl = mock(DSLContext.class);
    var manager = mock(PlatformTransactionManager.class);
    var input = input();
    var permission = permission(input);
    var source = mock(HeldSourceProtection.class);
    doThrow(new IllegalStateException("Controlled pending source expired"))
        .when(source)
        .requireHeld();
    var owner =
        new WorldPrivateFreshOperationRepository(
            dsl, manager, "world_test", "test", ignored -> permission, ignored -> source);
    assertThatThrownBy(() -> owner.materialize(input)).hasMessageContaining("source expired");
    verify(source).close();
    verify(permission).close();
    verifyNoInteractions(dsl, manager);
  }

  @Test
  void differentSourceHoldOrOriginalOperationCannotReachSql() {
    var dsl = mock(DSLContext.class);
    var manager = mock(PlatformTransactionManager.class);
    var input = input();
    var permission = permission(input);
    var source = mock(HeldSourceProtection.class);
    when(source.binding())
        .thenReturn(new SourceProtectionBinding(UUID.randomUUID(), UUID.randomUUID(), 2, DIGEST));
    var owner =
        new WorldPrivateFreshOperationRepository(
            dsl, manager, "world_test", "test", ignored -> permission, ignored -> source);
    assertThatThrownBy(() -> owner.materialize(input))
        .hasMessageContaining("source protection differs");
    var expectedProtection = input.protection();
    when(source.binding()).thenReturn(expectedProtection);
    when(permission.operationId()).thenReturn(UUID.randomUUID());
    assertThatThrownBy(() -> owner.materialize(input))
        .isInstanceOf(PermissionUnavailableException.class);
    verifyNoInteractions(dsl, manager);
  }

  @Test
  void ambientTransactionRefusesBeforePermissionOrSql() {
    var dsl = mock(DSLContext.class);
    var manager = mock(PlatformTransactionManager.class);
    var owner = new WorldPrivateFreshOperationRepository(dsl, manager, "world_test", "test");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> owner.materialize(input()))
          .hasMessageContaining("ambient transactions");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verifyNoInteractions(dsl, manager);
  }

  @Test
  void terminalAndSourceBindingsRejectIncompleteIdentityAndNeverAdmit() {
    var applied =
        new Terminal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DIGEST,
            "APPLIED",
            1L,
            2L,
            ByteString.copyFromUtf8("controlled terminal"));
    var aborted =
        new Terminal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DIGEST,
            "NOT_APPLIED",
            null,
            null,
            ByteString.copyFromUtf8("controlled abort"));
    assertThat(applied.admissionAllowed()).isFalse();
    assertThat(aborted.admissionAllowed()).isFalse();
    assertThatThrownBy(
            () ->
                new Terminal(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    DIGEST,
                    "APPLIED",
                    null,
                    null,
                    ByteString.copyFromUtf8("bad")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new SourceProtectionBinding(UUID.randomUUID(), new UUID(0, 0), 1, DIGEST))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static WorldPrivateFreshOperation input() {
    var input = mock(WorldPrivateFreshOperation.class);
    when(input.namespace()).thenReturn("test");
    when(input.operationId()).thenReturn(UUID.randomUUID());
    when(input.inputDigest()).thenReturn(DIGEST);
    when(input.protection())
        .thenReturn(new SourceProtectionBinding(UUID.randomUUID(), UUID.randomUUID(), 1, DIGEST));
    return input;
  }

  private static HeldPermission permission(WorldPrivateFreshOperation input) {
    var operationId = input.operationId();
    var inputDigest = input.inputDigest();
    var permission = mock(HeldPermission.class);
    when(permission.operationId()).thenReturn(operationId);
    when(permission.inputDigest()).thenReturn(inputDigest);
    when(permission.originalOperation())
        .thenReturn(
            ByteString.copyFromUtf8("controlled original actor operation, not source evidence"));
    return permission;
  }
}
