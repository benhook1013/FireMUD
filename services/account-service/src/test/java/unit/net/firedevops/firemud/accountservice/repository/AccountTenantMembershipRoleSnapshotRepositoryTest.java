package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AccountTenantMembershipRoleSnapshotRepositoryTest {
  @Test
  void canonicalizesRoleIdentifiersByUnsignedAsciiBytesAndPreservesCase() {
    assertThat(
            AccountTenantMembershipRoleSnapshotRepository.canonicalizeRoleSet(
                List.of("player_2", "player-admin", "player", "Player")))
        .containsExactly("Player", "player", "player-admin", "player_2");
    assertThat(
            AccountTenantMembershipRoleSnapshotRepository.compareRoleIdentifierBytes(
                "player", "player-admin"))
        .isLessThan(0);
    assertThat(
            AccountTenantMembershipRoleSnapshotRepository.compareRoleIdentifierBytes(
                "Player", "player"))
        .isLessThan(0);
  }

  @Test
  void rejectsMalformedDuplicateOrNonCanonicalRoleEvidence() {
    assertThatThrownBy(
            () ->
                AccountTenantMembershipRoleSnapshotRepository.canonicalizeRoleSet(
                    List.of("player", "player")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountTenantMembershipRoleSnapshotRepository.canonicalizeRoleSet(
                    List.of("_player")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountTenantMembershipRoleSnapshotRepository.canonicalizeRoleSet(
                    List.of("player admin")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountTenantMembershipRoleSnapshotRepository.canonicalizeRoleSet(
                    List.of("pläyer")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountTenantMembershipRoleSnapshotRepository.requireCanonicalRoleSet(
                    List.of("player_2", "player")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void emptyRoleSetIsAnAuthoritativeSnapshotOnlyWhenVersionedAndPresent() {
    AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot snapshot =
        new AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot(
            11L, 7L, 701L, 3L, List.of());

    assertThat(snapshot.roles()).isEmpty();
    assertThat(snapshot.snapshotVersion()).isEqualTo(3L);
    assertThatThrownBy(
            () ->
                new AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot(
                    11L, 7L, 701L, 0L, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void persistenceMethodsRequireTheCallingJoinTransaction() throws ReflectiveOperationException {
    assertMandatory(
        AccountTenantMembershipRoleSnapshotRepository.class.getMethod(
            "findForUpdate", long.class, long.class, long.class, long.class));
    assertMandatory(
        AccountTenantMembershipRoleSnapshotRepository.class.getMethod(
            "replace",
            net.firedevops.firemud.accountservice.entity.AccountTenantMembership.class,
            long.class,
            java.util.Collection.class));
    assertMandatory(
        AccountTenantMembershipRoleSnapshotRepository.class.getMethod(
            "findForCanonicalUpdate",
            UUID.class,
            UUID.class,
            VerifiedTenantProvenance.class,
            long.class,
            long.class));
    assertMandatory(
        AccountTenantMembershipRoleSnapshotRepository.class.getMethod(
            "replaceCanonical",
            AccountTenantMembership.class,
            UUID.class,
            UUID.class,
            VerifiedTenantProvenance.class,
            long.class,
            java.util.Collection.class));
  }

  @Test
  void canonicalRoleSnapshotRetainsFreshUuidAndFullSourceWithoutNumericAlias() {
    UUID accountUuid = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID tenantUuid = UUID.fromString("22222222-2222-4222-8222-222222222222");
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            null,
            TenantProvenanceKind.FRESH_GAME_DESIGN,
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "sha256:" + "a".repeat(64));

    AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot snapshot =
        new AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot(
            11L, null, 701L, 1L, List.of("player"), accountUuid, tenantUuid, provenance);

    assertThat(snapshot.accountUuid()).isEqualTo(accountUuid);
    assertThat(snapshot.tenantUuid()).isEqualTo(tenantUuid);
    assertThat(snapshot.tenantId()).isNull();
    assertThat(snapshot.tenantProvenance()).isEqualTo(provenance);
    assertThatThrownBy(
            () ->
                new AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot(
                    11L, 7L, 701L, 1L, List.of("player"), accountUuid, tenantUuid, provenance))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void canonicalMembershipAndRoleMethodsRequireAnActiveOwnerTransactionWithoutSpringProxy() {
    UUID accountUuid = UUID.randomUUID();
    UUID tenantUuid = UUID.randomUUID();
    AccountTenantMembershipRoleSnapshotRepository roles =
        new AccountTenantMembershipRoleSnapshotRepository(DSL.using(SQLDialect.POSTGRES));

    assertThatThrownBy(() -> roles.findForCanonicalUpdate(accountUuid, tenantUuid, null, 1L, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    assertThatThrownBy(
            () -> roles.replaceCanonical(null, accountUuid, tenantUuid, null, 1L, List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
  }

  private void assertMandatory(java.lang.reflect.Method method) {
    Transactional annotation = method.getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
  }
}
