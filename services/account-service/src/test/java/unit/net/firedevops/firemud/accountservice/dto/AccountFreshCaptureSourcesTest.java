package unit.net.firedevops.firemud.accountservice.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountFreshCaptureSources;
import net.firedevops.firemud.accountservice.dto.AccountMembershipCaptureSources;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountGlobalRoleSourceRepository.FreshEmptySource;
import org.junit.jupiter.api.Test;

class AccountFreshCaptureSourcesTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("a50fdfca-f085-4a65-9ea1-ec99cda0f645");
  private static final UUID TENANT_UUID = UUID.fromString("8e35f45a-fd75-496e-aa40-a2a4da03ee4c");

  @Test
  void retainsExactMembershipSourcesAndFreshRoleBirthEvidenceSeparately() {
    AccountMembershipCaptureSources membershipSources =
        membershipSources(ACCOUNT_UUID, TENANT_UUID);
    FreshEmptySource roleSource = freshSource(ACCOUNT_UUID, 73L);

    AccountFreshCaptureSources sources =
        new AccountFreshCaptureSources(ACCOUNT_UUID, TENANT_UUID, membershipSources, roleSource);

    assertThat(sources.membershipSources()).isSameAs(membershipSources);
    assertThat(sources.freshGlobalRoleSource()).isSameAs(roleSource);
    assertThat(sources.freshGlobalRoleSource().globalRoleSourceVersion()).isEqualTo(1L);
  }

  @Test
  void rejectsMembershipSourcesFromAnotherRequestedAccountOrTenant() {
    AccountMembershipCaptureSources wrongAccount =
        membershipSources(UUID.randomUUID(), TENANT_UUID);
    AccountMembershipCaptureSources wrongTenant =
        membershipSources(ACCOUNT_UUID, UUID.randomUUID());

    assertThatThrownBy(
            () ->
                new AccountFreshCaptureSources(
                    ACCOUNT_UUID, TENANT_UUID, wrongAccount, freshSource(ACCOUNT_UUID, 73L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("membership sources differ");
    assertThatThrownBy(
            () ->
                new AccountFreshCaptureSources(
                    ACCOUNT_UUID, TENANT_UUID, wrongTenant, freshSource(ACCOUNT_UUID, 73L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("membership sources differ");
  }

  @Test
  void rejectsFreshGlobalRoleEvidenceForAnotherAccount() {
    AccountMembershipCaptureSources membershipSources =
        membershipSources(ACCOUNT_UUID, TENANT_UUID);

    assertThatThrownBy(
            () ->
                new AccountFreshCaptureSources(
                    ACCOUNT_UUID,
                    TENANT_UUID,
                    membershipSources,
                    freshSource(UUID.randomUUID(), 74L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fresh Account identity");
  }

  private static AccountMembershipCaptureSources membershipSources(
      UUID accountUuid, UUID tenantUuid) {
    AccountMembershipCaptureSources sources = mock(AccountMembershipCaptureSources.class);
    when(sources.requestedAccountUuid()).thenReturn(accountUuid);
    when(sources.requestedTenantUuid()).thenReturn(tenantUuid);
    return sources;
  }

  private static FreshEmptySource freshSource(UUID accountUuid, long accountRowId) {
    return new FreshEmptySource(
        accountUuid,
        accountRowId,
        accountRowId,
        AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT,
        List.of(),
        1L);
  }
}
