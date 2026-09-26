package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
  }

  private void assertMandatory(java.lang.reflect.Method method) {
    Transactional annotation = method.getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
  }
}
