package net.firedevops.firemud.accountservice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Optional;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.config.MailProperties;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.AuthenticationResult;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.EmailVerificationToken;
import net.firedevops.firemud.accountservice.entity.Profile;
import net.firedevops.firemud.accountservice.entity.Subscription;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.mapper.ProfileMapper;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.EmailVerificationTokenRepository;
import net.firedevops.firemud.accountservice.repository.ExternalAccountRepository;
import net.firedevops.firemud.accountservice.repository.PaymentTransactionRepository;
import net.firedevops.firemud.accountservice.repository.ProfileRepository;
import net.firedevops.firemud.accountservice.repository.SubscriptionRepository;
import net.firedevops.firemud.accountservice.service.EmailService;
import net.firedevops.firemud.accountservice.service.NotificationService;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.SessionService;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AccountServiceImplTest {
  @Mock private AccountRepository accountRepository;
  @Mock private ProfileRepository profileRepository;
  @Mock private ProfileMapper profileMapper;
  @Mock private NotificationService notificationService;
  @Mock private EmailService emailService;
  @Mock private MailProperties mailProperties;
  @Mock private LoggingAdminClient loggingAdminClient;
  @Mock private SessionService sessionService;
  @Mock private SagaRunner sagaRunner;
  @Mock private PaymentTransactionRepository paymentTransactionRepository;
  @Mock private SubscriptionRepository subscriptionRepository;
  @Mock private ExternalAccountRepository externalAccountRepository;

  @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;

  @Mock
  private net.firedevops.firemud.accountservice.repository.PasswordResetTokenRepository
      passwordResetTokenRepository;

  private AccountServiceImpl service;

  @BeforeEach
  void setup() throws net.firedevops.firemud.common.saga.SagaException {
    MockitoAnnotations.openMocks(this);
    AccountMapper mapper = Mappers.getMapper(AccountMapper.class);
    JwtUtil jwtUtil = new JwtUtil("mysecretkey123456789012345678901", 3600000L);
    when(mailProperties.getResetUrl()).thenReturn("http://reset/%s");
    when(mailProperties.getVerificationUrl()).thenReturn("http://verify/%s");
    service =
        new AccountServiceImpl(
            accountRepository,
            mapper,
            profileRepository,
            profileMapper,
            paymentTransactionRepository,
            subscriptionRepository,
            externalAccountRepository,
            passwordResetTokenRepository,
            emailVerificationTokenRepository,
            notificationService,
            emailService,
            mailProperties,
            loggingAdminClient,
            jwtUtil,
            sessionService,
            sagaRunner);
    org.mockito.Mockito.doAnswer(
            inv -> {
              ((net.firedevops.firemud.common.saga.Saga) inv.getArgument(0)).run();
              return null;
            })
        .when(sagaRunner)
        .run(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void createAccountPersistsEntity() throws net.firedevops.firemud.common.saga.SagaException {
    CreateAccountRequest request = new CreateAccountRequest("demo", "demo@example.com", "password");
    Account saved = new Account();
    saved.setId(1L);
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class))).thenReturn(saved);

    AccountDto dto = service.createAccount(request);

    assertEquals(1L, dto.id());
    org.mockito.Mockito.verify(sagaRunner).run(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void createAccountContinuesWhenAuditLoggingFails()
      throws net.firedevops.firemud.common.saga.SagaException {
    CreateAccountRequest request = new CreateAccountRequest("demo", "demo@example.com", "password");
    Account saved = new Account();
    saved.setId(1L);
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class))).thenReturn(saved);
    org.mockito.Mockito.doThrow(new StatusRuntimeException(Status.UNAVAILABLE))
        .when(loggingAdminClient)
        .logAccountCreation(0L, 1L);

    AccountDto dto = service.createAccount(request);

    assertEquals(1L, dto.id());
    org.mockito.Mockito.verify(sagaRunner).run(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void authenticateReturnsTokenWhenPasswordMatches() {
    Account account = new Account();
    account.setId(1L);
    account.setTenantId(1L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByTenantIdAndUsername(1L, "demo")).thenReturn(Optional.of(account));

    AuthenticationResult result = service.authenticate(1L, "demo", "password", null);

    assertNotNull(result.authToken());
    assertEquals(1L, result.accountId());
    org.mockito.Mockito.verify(sessionService).storeSession(1L, 1L, result.authToken());
  }

  @Test
  void authenticateFallsBackToTenantEmailLookup() {
    Account account = new Account();
    account.setId(1L);
    account.setTenantId(1L);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByTenantIdAndUsername(1L, "demo@example.com"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByTenantIdAndEmail(1L, "demo@example.com"))
        .thenReturn(Optional.of(account));

    AuthenticationResult result = service.authenticate(1L, "demo@example.com", "password", null);

    assertNotNull(result.authToken());
    assertEquals(1L, result.accountId());
    org.mockito.Mockito.verify(sessionService).storeSession(1L, 1L, result.authToken());
  }

  @Test
  void authenticateFallsBackToGlobalAccountLookup() {
    Account account = new Account();
    account.setId(1L);
    account.setTenantId(0L);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByTenantIdAndUsername(1L, "demo@example.com"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByTenantIdAndEmail(1L, "demo@example.com"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByTenantIdAndUsername(0L, "demo@example.com"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByTenantIdAndEmail(0L, "demo@example.com"))
        .thenReturn(Optional.of(account));

    AuthenticationResult result = service.authenticate(1L, "demo@example.com", "password", null);

    assertNotNull(result.authToken());
    assertEquals(1L, result.accountId());
    org.mockito.Mockito.verify(sessionService).storeSession(1L, 1L, result.authToken());
  }

  @Test
  void authenticateThrowsWhenInvalid() {
    when(accountRepository.findByTenantIdAndUsername(1L, "demo")).thenReturn(Optional.empty());
    when(accountRepository.findByTenantIdAndEmail(1L, "demo")).thenReturn(Optional.empty());
    when(accountRepository.findByTenantIdAndUsername(0L, "demo")).thenReturn(Optional.empty());
    when(accountRepository.findByTenantIdAndEmail(0L, "demo")).thenReturn(Optional.empty());
    AuthenticationException exception =
        assertThrows(
            AuthenticationException.class, () -> service.authenticate(1L, "demo", "bad", null));
    assertEquals(AuthenticationErrorCodes.INVALID_CREDENTIALS, exception.getCode());
  }

  @Test
  void getTenantMembershipForRuntimeReturnsAdmissionAllowedForExistingAccount() {
    Account account = new Account();
    account.setId(11L);
    account.setTenantId(7L);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findById(11L)).thenReturn(Optional.of(account));

    var dto = service.getTenantMembershipForRuntime(11L, 7L, "req-1");

    assertEquals(11L, dto.accountId());
    assertEquals(7L, dto.tenantId());
    assertTrue(dto.gameplayAdmissionAllowed());
    assertEquals(11L, dto.membershipVersion());
    assertNotNull(dto.evaluatedAt());
  }

  @Test
  void getTenantEntitlementsForRuntimeUsesCurrentSubscriptions() {
    Subscription active = new Subscription();
    active.setId(31L);
    active.setTenantId(7L);
    active.setStatus("active");
    when(subscriptionRepository.findByTenantId(7L)).thenReturn(java.util.List.of(active));

    var dto = service.getTenantEntitlementsForRuntime(7L, "req-2");

    assertEquals(7L, dto.tenantId());
    assertTrue(dto.gameplayAvailable());
    assertEquals(31L, dto.entitlementVersion());
    assertEquals(31L, dto.tenantBillingSequence());
    assertNotNull(dto.evaluatedAt());
  }

  @Test
  void getProfileReturnsDto() {
    Account account = new Account();
    account.setId(2L);
    Profile profile = new Profile();
    profile.setAccount(account);
    profile.setTenantId(1L);
    profile.setDisplayName("demo");
    when(profileRepository.findByAccountIdAndTenantId(2L, 1L)).thenReturn(Optional.of(profile));
    when(profileMapper.toDto(profile))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.ProfileDto(1L, 1L, 2L, "demo", null));

    var dto = service.getProfile(1L, 2L);

    assertEquals("demo", dto.displayName());
  }

  @Test
  void updateProfileStoresChanges() {
    Profile profile = new Profile();
    profile.setAccount(new Account());
    profile.setTenantId(1L);
    when(profileRepository.findByAccountIdAndTenantId(2L, 1L)).thenReturn(Optional.of(profile));
    when(profileRepository.save(profile)).thenReturn(profile);
    when(profileMapper.toDto(profile))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.ProfileDto(1L, 1L, 2L, "demo", "bio"));

    var dto =
        service.updateProfile(
            new net.firedevops.firemud.accountservice.dto.UpdateProfileRequest(
                1L, 2L, "demo", "bio"));

    assertEquals("demo", dto.displayName());
    org.mockito.Mockito.verify(notificationService).sendNotification(1L, 2L, "Profile updated");
  }

  @Test
  void requestPasswordResetCreatesToken() {
    Account account = new Account();
    account.setId(1L);
    account.setEmail("demo@example.com");
    when(accountRepository.findByTenantIdAndEmail(1L, "demo@example.com"))
        .thenReturn(Optional.of(account));

    service.requestPasswordReset(new PasswordResetRequest(1L, "demo@example.com"));

    org.mockito.Mockito.verify(passwordResetTokenRepository)
        .save(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.eq("demo@example.com"),
            org.mockito.ArgumentMatchers.eq("Password Reset"),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(notificationService)
        .sendNotification(1L, 1L, "Password reset requested");
  }

  @Test
  void sendUsernameReminderEmailsUsername() {
    Account account = new Account();
    account.setId(1L);
    account.setTenantId(1L);
    account.setUsername("demo");
    account.setEmail("demo@example.com");
    when(accountRepository.findByTenantIdAndEmail(1L, "demo@example.com"))
        .thenReturn(Optional.of(account));

    service.sendUsernameReminder(
        new net.firedevops.firemud.accountservice.dto.UsernameRecoveryRequest(
            1L, "demo@example.com"));

    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.eq("demo@example.com"),
            org.mockito.ArgumentMatchers.eq("Username Reminder"),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(notificationService)
        .sendNotification(1L, 1L, "Username reminder requested");
  }

  @Test
  void linkExternalAccountSavesEntity() {
    Account account = new Account();
    account.setId(5L);
    account.setTenantId(1L);
    when(accountRepository.findById(5L)).thenReturn(Optional.of(account));
    when(externalAccountRepository.existsByTenantIdAndAccountIdAndProvider(1L, 5L, "google"))
        .thenReturn(false);

    service.linkExternalAccount(
        new net.firedevops.firemud.accountservice.dto.LinkExternalAccountRequest(
            1L, 5L, "google", "abc"));

    org.mockito.ArgumentCaptor<net.firedevops.firemud.accountservice.entity.ExternalAccount>
        captor =
            org.mockito.ArgumentCaptor.forClass(
                net.firedevops.firemud.accountservice.entity.ExternalAccount.class);
    org.mockito.Mockito.verify(externalAccountRepository).save(captor.capture());
    assertEquals("abc", captor.getValue().getExternalId());
  }

  @Test
  void requestEmailVerificationCreatesToken() {
    Account account = new Account();
    account.setId(6L);
    account.setTenantId(1L);
    account.setEmail("demo@example.com");
    when(accountRepository.findById(6L)).thenReturn(Optional.of(account));

    service.requestEmailVerification(1L, 6L);

    org.mockito.Mockito.verify(emailVerificationTokenRepository)
        .save(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(emailService)
        .sendEmail(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("Email Verification"),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.verify(notificationService)
        .sendNotification(1L, 6L, "Email verification requested");
  }

  @Test
  void verifyEmailSetsFlag() {
    Account account = new Account();
    account.setTenantId(1L);
    EmailVerificationToken token = new EmailVerificationToken();
    token.setAccount(account);
    token.setTenantId(1L);
    token.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
    when(emailVerificationTokenRepository.findByTokenAndTenantId("tok", 1L))
        .thenReturn(Optional.of(token));

    service.verifyEmail(
        new net.firedevops.firemud.accountservice.dto.VerifyEmailRequest(1L, "tok"));

    assertTrue(account.isEmailVerified());
    org.mockito.Mockito.verify(emailVerificationTokenRepository).delete(token);
  }

  private static String hash(String password) {
    Argon2 argon2 = Argon2Factory.create();
    char[] chars = password.toCharArray();
    try {
      return argon2.hash(2, 65536, 1, chars);
    } finally {
      argon2.wipeArray(chars);
    }
  }
}
