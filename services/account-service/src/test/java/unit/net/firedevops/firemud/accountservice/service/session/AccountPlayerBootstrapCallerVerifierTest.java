package unit.net.firedevops.firemud.accountservice.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/** Controlled collaborators exercise the verifier; this is not cryptographic or Redis proof. */
@ExtendWith(MockitoExtension.class)
class AccountPlayerBootstrapCallerVerifierTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("7c9b1c20-e15c-4809-b6fb-d2ecc253460b");
  private static final UUID OTHER_UUID = UUID.fromString("e16c4af2-c203-4f1a-a0a8-ab1163234e46");
  private static final long ACCOUNT_ID = 41L;
  private static final String TOKEN = "exact-bootstrap-token";

  @Mock private JwtUtil jwtUtil;
  @Mock private AccountRepository accountRepository;
  @Mock private SessionService sessionService;
  @Mock private Jws<Claims> signedToken;
  @Mock private Claims claims;

  private AccountPlayerBootstrapCallerVerifier verifier;

  @BeforeEach
  void setUp() {
    verifier = new AccountPlayerBootstrapCallerVerifier(jwtUtil, accountRepository, sessionService);
  }

  @ParameterizedTest
  @EnumSource(AccountIdentityProvenance.class)
  void verifiesExactPersistedOwnerAndAccountSession(AccountIdentityProvenance provenance) {
    stubIdentityClaims(ACCOUNT_UUID.toString(), ACCOUNT_UUID.toString());
    Account account = account();
    account.setAccountUuidProvenance(provenance);
    when(accountRepository.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(account));
    when(sessionService.isAccountSessionActive(ACCOUNT_ID, TOKEN)).thenReturn(true);

    var caller = verifier.requireCaller(TOKEN);

    assertEquals(ACCOUNT_ID, caller.accountId());
    assertEquals(ACCOUNT_UUID, caller.accountUuid());
    verify(jwtUtil).parseToken(TOKEN);
    verify(accountRepository).findByAccountUuid(ACCOUNT_UUID);
    verify(sessionService).isAccountSessionActive(ACCOUNT_ID, TOKEN);
    verifyNoMoreInteractions(accountRepository, sessionService);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void missingTokenNeverReachesAnyAuthority(String token) {
    AuthenticationException failure =
        assertThrows(AuthenticationException.class, () -> verifier.requireCaller(token));

    assertEquals("CONNECT_CONTEXT_INVALID", failure.getCode());
    assertEquals("Missing bootstrap token", failure.getMessage());
    verifyNoInteractions(jwtUtil, accountRepository, sessionService);
  }

  @Test
  void invalidSignatureOrExpiredJwtNeverReadsAccountOrSession() {
    JwtException unavailable = new JwtException("invalid signed token");
    when(jwtUtil.parseToken(TOKEN)).thenThrow(unavailable);

    AuthenticationException failure = requireInvalidCaller();

    assertNull(failure.getCause());
    verifyNoInteractions(accountRepository, sessionService);
  }

  @Test
  void malformedJwtNeverReadsAccountOrSession() {
    IllegalArgumentException malformed = new IllegalArgumentException("malformed token");
    when(jwtUtil.parseToken(TOKEN)).thenThrow(malformed);

    assertNull(requireInvalidCaller().getCause());
    verifyNoInteractions(accountRepository, sessionService);
  }

  @Test
  void malformedAudienceClaimNeverReadsAccountOrSessionAndHasNoCause() {
    stubSignedClaims();
    when(claims.get("aud")).thenReturn(17);

    AuthenticationException failure = requireInvalidCaller();

    assertNull(failure.getCause());
    verifyNoInteractions(accountRepository, sessionService);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"control-ui", "gameplay-connect", "account-service"})
  void wrongAudienceNeverReadsAccountOrSession(String audience) {
    stubSignedClaims();
    when(claims.get("aud")).thenReturn(audience);

    requireInvalidCaller();

    verifyNoInteractions(accountRepository, sessionService);
  }

  @ParameterizedTest
  @MethodSource("invalidIdentityClaims")
  void rejectsNoncanonicalOrUnboundIdentity(Object subject, Object accountUuid) {
    stubIdentityClaims(subject, accountUuid);

    requireInvalidCaller();

    verifyNoInteractions(accountRepository, sessionService);
  }

  static Stream<Arguments> invalidIdentityClaims() {
    String canonical = ACCOUNT_UUID.toString();
    return Stream.of(
        Arguments.of(OTHER_UUID.toString(), canonical),
        Arguments.of(null, canonical),
        Arguments.of(canonical, null),
        Arguments.of(ACCOUNT_ID, canonical),
        Arguments.of(canonical, ACCOUNT_ID),
        Arguments.of(canonical.toUpperCase(java.util.Locale.ROOT), canonical),
        Arguments.of(canonical, canonical.toUpperCase(java.util.Locale.ROOT)),
        Arguments.of(" " + canonical, canonical),
        Arguments.of(canonical, "00000000-0000-0000-0000-000000000000"),
        Arguments.of("not-a-uuid", canonical),
        Arguments.of("1-1-1-1-1", "1-1-1-1-1"));
  }

  @Test
  void missingAccountIsUnavailableAndNeverReadsSession() {
    stubIdentityClaims(ACCOUNT_UUID.toString(), ACCOUNT_UUID.toString());
    when(accountRepository.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.empty());

    requireUnavailableCaller();

    verifyNoInteractions(sessionService);
  }

  @ParameterizedTest
  @MethodSource("invalidAccountRows")
  void rejectsMismatchedOrUnprovenPersistedRow(String description, Account account) {
    stubIdentityClaims(ACCOUNT_UUID.toString(), ACCOUNT_UUID.toString());
    when(accountRepository.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(account));

    requireUnavailableCaller();

    verifyNoInteractions(sessionService);
  }

  static Stream<Arguments> invalidAccountRows() {
    Account wrongUuid = account();
    wrongUuid.setAccountUuid(OTHER_UUID);
    Account absentUuid = account();
    absentUuid.setAccountUuid(null);
    Account nilUuid = account();
    nilUuid.setAccountUuid(new UUID(0L, 0L));
    Account absentId = account();
    absentId.setId(null);
    Account zeroId = account();
    zeroId.setId(0L);
    Account negativeId = account();
    negativeId.setId(-1L);
    Account wrongSource = account();
    wrongSource.setAccountUuidSourceNumericId(ACCOUNT_ID + 1L);
    Account absentSource = account();
    absentSource.setAccountUuidSourceNumericId(null);
    Account absentProvenance = account();
    absentProvenance.setAccountUuidProvenance(null);
    return Stream.of(
        Arguments.of("wrong UUID", wrongUuid),
        Arguments.of("absent UUID", absentUuid),
        Arguments.of("nil UUID", nilUuid),
        Arguments.of("absent private ID", absentId),
        Arguments.of("zero private ID", zeroId),
        Arguments.of("negative private ID", negativeId),
        Arguments.of("source ID mismatch", wrongSource),
        Arguments.of("absent source ID", absentSource),
        Arguments.of("absent provenance", absentProvenance));
  }

  @Test
  void repositoryFailureIsUnavailableAndNeverReadsSession() {
    stubIdentityClaims(ACCOUNT_UUID.toString(), ACCOUNT_UUID.toString());
    DataAccessResourceFailureException unavailable =
        new DataAccessResourceFailureException("account database unavailable");
    when(accountRepository.findByAccountUuid(ACCOUNT_UUID)).thenThrow(unavailable);

    assertSame(unavailable, requireUnavailableCaller().getCause());
    verifyNoInteractions(sessionService);
  }

  @Test
  void invalidRepositoryProvenanceReadbackIsUnavailable() {
    stubIdentityClaims(ACCOUNT_UUID.toString(), ACCOUNT_UUID.toString());
    IllegalStateException invalid = new IllegalStateException("Unknown Account UUID provenance");
    when(accountRepository.findByAccountUuid(ACCOUNT_UUID)).thenThrow(invalid);

    assertSame(invalid, requireUnavailableCaller().getCause());
    verifyNoInteractions(sessionService);
  }

  @Test
  void inactiveExactSessionIsInvalidRatherThanUnavailable() {
    stubIdentityClaims(ACCOUNT_UUID.toString(), ACCOUNT_UUID.toString());
    when(accountRepository.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(account()));
    when(sessionService.isAccountSessionActive(ACCOUNT_ID, TOKEN)).thenReturn(false);

    AuthenticationException failure = requireInvalidCaller();

    assertEquals("Bootstrap token expired", failure.getMessage());
    verify(sessionService).isAccountSessionActive(ACCOUNT_ID, TOKEN);
  }

  @Test
  void sessionOutagePreservesExistingUnavailableExceptionRatherThanInvalidToken() {
    stubIdentityClaims(ACCOUNT_UUID.toString(), ACCOUNT_UUID.toString());
    when(accountRepository.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(account()));
    DataAccessResourceFailureException unavailable =
        new DataAccessResourceFailureException("account session unavailable");
    when(sessionService.isAccountSessionActive(ACCOUNT_ID, TOKEN)).thenThrow(unavailable);

    assertSame(
        unavailable,
        assertThrows(
            DataAccessResourceFailureException.class, () -> verifier.requireCaller(TOKEN)));
  }

  private AuthenticationException requireInvalidCaller() {
    AuthenticationException failure =
        assertThrows(AuthenticationException.class, () -> verifier.requireCaller(TOKEN));
    assertEquals("CONNECT_CONTEXT_INVALID", failure.getCode());
    return failure;
  }

  private AuthenticationException requireUnavailableCaller() {
    AuthenticationException failure =
        assertThrows(AuthenticationException.class, () -> verifier.requireCaller(TOKEN));
    assertEquals("AUTH_UNAVAILABLE", failure.getCode());
    return failure;
  }

  private void stubSignedClaims() {
    when(jwtUtil.parseToken(TOKEN)).thenReturn(signedToken);
    when(signedToken.getPayload()).thenReturn(claims);
  }

  private void stubIdentityClaims(Object subject, Object accountUuid) {
    stubSignedClaims();
    when(claims.get("aud")).thenReturn("player-bootstrap");
    when(claims.get("sub")).thenReturn(subject);
    when(claims.get("accountId")).thenReturn(accountUuid);
  }

  private static Account account() {
    Account account = new Account();
    account.setId(ACCOUNT_ID);
    account.setAccountUuid(ACCOUNT_UUID);
    account.setAccountUuidSourceNumericId(ACCOUNT_ID);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    return account;
  }
}
