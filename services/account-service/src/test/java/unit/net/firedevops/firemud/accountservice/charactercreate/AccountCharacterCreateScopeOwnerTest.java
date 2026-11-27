package net.firedevops.firemud.accountservice.charactercreate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.time.*;
import java.util.*;
import net.firedevops.firemud.accountservice.config.AccountTokenProperties;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.repository.*;
import net.firedevops.firemud.accountservice.service.AccountCanonicalConnectScopeReader;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.gamesession.v1.ResolveCharacterCreatePublicTargetResponse;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** Controlled owner composition. This does not establish real signing, RPC or SQL authority. */
class AccountCharacterCreateScopeOwnerTest {
  private final JwtUtil jwt = mock(JwtUtil.class);
  private final FreshTenantIdentityAssociationRepository tenants =
      mock(FreshTenantIdentityAssociationRepository.class);
  private final AccountConnectScopeRepository scopes = mock(AccountConnectScopeRepository.class);
  private final AccountCanonicalConnectScopeReader reader =
      mock(AccountCanonicalConnectScopeReader.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final AccountCharacterCreatePrincipal principal =
      mock(AccountCharacterCreatePrincipal.class);
  private final AccountPlayerBootstrapCallerVerifier.VerifiedCaller caller =
      mock(AccountPlayerBootstrapCallerVerifier.VerifiedCaller.class);
  private final AccountCharacterCreatePublicSourceClient.AuthenticatedTarget target =
      mock(AccountCharacterCreatePublicSourceClient.AuthenticatedTarget.class);
  private final ResolveCharacterCreatePublicTargetResponse response =
      mock(ResolveCharacterCreatePublicTargetResponse.class, RETURNS_DEEP_STUBS);
  private final UUID account = UUID.randomUUID(), tenant = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-11-16T00:00:00Z");

  private AccountCharacterCreateScopeOwner owner() {
    var properties = mock(AccountTokenProperties.class);
    when(properties.getConnectScopeExpirationMs()).thenReturn(60000L);
    return new AccountCharacterCreateScopeOwner(
        "proof",
        jwt,
        Clock.fixed(now, ZoneOffset.UTC),
        properties,
        tenants,
        scopes,
        reader,
        transactions);
  }

  private void target() {
    when(principal.authenticateCurrentCaller()).thenReturn(caller);
    when(caller.accountUuid()).thenReturn(account);
    when(caller.accountId()).thenReturn(42L);
    when(principal.authenticateCurrentAccount()).thenReturn(account);
    when(target.response()).thenReturn(response);
    when(response.getRequest().getTargetNamespace()).thenReturn("proof");
    var catalog = response.getCatalog();
    when(catalog.getVisible()).thenReturn(true);
    when(catalog.getPublicProduction()).thenReturn(true);
    when(catalog.getStateScope()).thenReturn("SHARED");
    when(catalog.getCharacterCreationPolicy()).thenReturn("PLAYER_CREATED");
    when(catalog.getTenantId()).thenReturn(tenant.toString());
    when(catalog.getRealmId()).thenReturn(UUID.randomUUID().toString());
    when(catalog.getTenantSlug()).thenReturn("tenant");
    when(catalog.getWorldSlug()).thenReturn("world");
    when(catalog.getRealmSlug()).thenReturn("realm");
    when(catalog.getPlayableStateNamespaceId()).thenReturn(UUID.randomUUID().toString());
    when(catalog.getCatalogRevision()).thenReturn(1L);
    when(response.getCanonicalGameInstanceId()).thenReturn(UUID.randomUUID().toString());
    when(response.getPointer().getPointerVersion()).thenReturn(1L);
  }

  @Test
  void preseededTargetNeverMintsCreationScope() {
    target();
    when(response.getCatalog().getCharacterCreationPolicy()).thenReturn("PRESEEDED_ONLY");
    assertThatThrownBy(() -> owner().issueCanonicalCharacterCreateScope(principal, target))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(jwt, scopes, tenants, reader, transactions);
  }

  @Test
  void wrongNamespaceNeverMintsCreationScope() {
    target();
    when(response.getRequest().getTargetNamespace()).thenReturn("other");
    assertThatThrownBy(() -> owner().issueCanonicalCharacterCreateScope(principal, target))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(jwt, scopes, tenants, reader, transactions);
  }

  @Test
  void persistsExactSignedTargetWithCurrentTenantAndReadsItBack() {
    target();
    var signed = mock(Claims.class);
    Jws<Claims> jws = mock(Jws.class);
    when(jws.getPayload()).thenReturn(signed);
    when(jwt.parseToken("signed-scope")).thenReturn(jws);
    when(jwt.generateToken(eq(account.toString()), eq(now.plusSeconds(60)), anyMap()))
        .thenAnswer(
            call -> {
              Map<String, Object> claims = call.getArgument(2);
              when(signed.get(anyString())).thenAnswer(read -> claims.get(read.getArgument(0)));
              when(signed.getSubject()).thenReturn(account.toString());
              when(signed.getExpiration()).thenReturn(Date.from(now.plusSeconds(60)));
              when(signed.getIssuedAt()).thenReturn(Date.from(now));
              return "signed-scope";
            });
    var source = mock(FreshTenantCreationEvidence.class);
    when(source.targetNamespace()).thenReturn("proof");
    when(source.canonicalTenantId()).thenReturn(tenant);
    when(source.operationId()).thenReturn(UUID.randomUUID());
    when(source.evidenceDigest()).thenReturn("sha256:" + "a".repeat(64));
    when(tenants.read(tenant)).thenReturn(Optional.of(source));
    final CanonicalJoinScopeV2[] retained = new CanonicalJoinScopeV2[1];
    doAnswer(
            call -> {
              retained[0] = call.getArgument(1);
              return null;
            })
        .when(scopes)
        .insertCanonical(eq(42L), any(), any());
    when(reader.read(account, "signed-scope")).thenAnswer(call -> retained[0]);
    var result = owner().issueCanonicalCharacterCreateScope(principal, target);
    assertThat(result).isSameAs(retained[0]);
    assertThat(result.accountId()).isEqualTo(account);
    assertThat(result.tenantId()).isEqualTo(tenant);
    assertThat(result.connectScopeExpiresAt()).isEqualTo(now.plusSeconds(60).toString());
    verify(principal, times(2)).authenticateCurrentAccount();
    verify(scopes).insertCanonical(eq(42L), eq(result), any());
    verify(reader).read(account, "signed-scope");
  }
}
