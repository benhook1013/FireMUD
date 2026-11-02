package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import org.junit.jupiter.api.Test;

class AccountControlUiSigningSpecTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

  @Test
  void accountScopeCarriesEmptyTenantMapsAndTheCapturedGlobalRoles() {
    var source = mock(AccountControlUiAuthority.Snapshot.class);
    var roles = List.of("billingAdmin", "platformAdmin", "support");
    when(source.actor()).thenReturn(ACCOUNT_ID);
    when(source.scopeKind()).thenReturn(AccountControlUiAuthority.ScopeKind.ACCOUNT);
    when(source.authorityTuple())
        .thenReturn(
            Map.of(
                "issuerAuthGeneration",
                11L,
                "accountAuthorityGeneration",
                17L,
                "tenantAuthorityGeneration",
                Map.of(),
                "membershipAuthorityGeneration",
                Map.of(),
                "privateRealmGrantVersions",
                List.of()));
    when(source.membershipVersion()).thenReturn(Map.of());
    when(source.issuanceFence()).thenReturn(23L);
    when(source.scopedRoles()).thenReturn(Map.of());
    when(source.globalRoles()).thenReturn(roles);

    var spec =
        new AccountControlUiSigningSpec(
            UUID.fromString("20000000-0000-4000-8000-000000000002"),
            UUID.fromString("30000000-0000-4000-8000-000000000003"),
            UUID.fromString("40000000-0000-4000-8000-000000000004"),
            source,
            Instant.parse("2026-10-08T11:00:00Z"),
            Instant.parse("2026-10-08T11:05:00Z"));

    assertThat(spec.claims())
        .containsEntry("sub", ACCOUNT_ID.toString())
        .containsEntry("accountId", ACCOUNT_ID.toString())
        .containsEntry("membershipVersion", Map.of())
        .containsEntry("scopedRoles", Map.of())
        .containsEntry("globalRoles", roles);
    @SuppressWarnings("unchecked")
    Map<String, Object> authorityTuple = (Map<String, Object>) spec.claims().get("authorityTuple");
    assertThat(authorityTuple)
        .containsEntry("issuerAuthGeneration", 11L)
        .containsEntry("accountAuthorityGeneration", 17L)
        .containsEntry("tenantAuthorityGeneration", Map.of())
        .containsEntry("membershipAuthorityGeneration", Map.of())
        .containsEntry("privateRealmGrantVersions", List.of());
    verify(source, never()).tenant();
  }
}
