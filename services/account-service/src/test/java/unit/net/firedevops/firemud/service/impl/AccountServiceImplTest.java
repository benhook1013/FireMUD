package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.client.LoggingAdminClient;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.dto.AccountDto;
import net.firedevops.firemud.dto.CreateAccountRequest;
import net.firedevops.firemud.dto.PasswordResetRequest;
import net.firedevops.firemud.entity.Account;
import net.firedevops.firemud.entity.Profile;
import net.firedevops.firemud.mapper.AccountMapper;
import net.firedevops.firemud.mapper.ProfileMapper;
import net.firedevops.firemud.repository.AccountRepository;
import net.firedevops.firemud.repository.ExternalAccountRepository;
import net.firedevops.firemud.repository.PaymentTransactionRepository;
import net.firedevops.firemud.repository.ProfileRepository;
import net.firedevops.firemud.repository.SubscriptionRepository;
import net.firedevops.firemud.service.NotificationService;
import net.firedevops.firemud.service.session.SessionService;
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
  @Mock private LoggingAdminClient loggingAdminClient;
  @Mock private SessionService sessionService;
  @Mock private PaymentTransactionRepository paymentTransactionRepository;
  @Mock private SubscriptionRepository subscriptionRepository;
  @Mock private ExternalAccountRepository externalAccountRepository;

  @Mock
  private net.firedevops.firemud.repository.PasswordResetTokenRepository
      passwordResetTokenRepository;

  private AccountServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    AccountMapper mapper = Mappers.getMapper(AccountMapper.class);
    JwtUtil jwtUtil = new JwtUtil("mysecretkey123456789012345678901", 3600000L);
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
            notificationService,
            loggingAdminClient,
            jwtUtil,
            sessionService);
  }

  @Test
  void createAccountPersistsEntity() {
    CreateAccountRequest request =
        new CreateAccountRequest(1L, "demo", "demo@example.com", "password");
    Account saved = new Account();
    saved.setId(1L);
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(Account.class))).thenReturn(saved);

    AccountDto dto = service.createAccount(request);

    assertEquals(1L, dto.id());
  }

  @Test
  void authenticateReturnsTokenWhenPasswordMatches() {
    Account account = new Account();
    account.setId(1L);
    account.setTenantId(1L);
    account.setUsername("demo");
    account.setPasswordHash(hash("password"));
    when(accountRepository.findByTenantIdAndUsername(1L, "demo")).thenReturn(Optional.of(account));

    String token = service.authenticate(1L, "demo", "password", null);

    assertNotNull(token);
    org.mockito.Mockito.verify(sessionService).storeSession(1L, 1L, token);
  }

  @Test
  void authenticateThrowsWhenInvalid() {
    when(accountRepository.findByTenantIdAndUsername(1L, "demo")).thenReturn(Optional.empty());
    assertThrows(
        IllegalArgumentException.class, () -> service.authenticate(1L, "demo", "bad", null));
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
        .thenReturn(new net.firedevops.firemud.dto.ProfileDto(1L, 1L, 2L, "demo", null));

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
        .thenReturn(new net.firedevops.firemud.dto.ProfileDto(1L, 1L, 2L, "demo", "bio"));

    var dto =
        service.updateProfile(
            new net.firedevops.firemud.dto.UpdateProfileRequest(1L, 2L, "demo", "bio"));

    assertEquals("demo", dto.displayName());
    org.mockito.Mockito.verify(notificationService).sendNotification(1L, 2L, "Profile updated");
  }

  @Test
  void requestPasswordResetCreatesToken() {
    Account account = new Account();
    account.setId(1L);
    when(accountRepository.findByTenantIdAndEmail(1L, "demo@example.com"))
        .thenReturn(Optional.of(account));

    service.requestPasswordReset(new PasswordResetRequest(1L, "demo@example.com"));

    org.mockito.Mockito.verify(passwordResetTokenRepository)
        .save(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(notificationService)
        .sendNotification(1L, 1L, "Password reset requested");
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
        new net.firedevops.firemud.dto.LinkExternalAccountRequest(1L, 5L, "google", "abc"));

    org.mockito.ArgumentCaptor<net.firedevops.firemud.entity.ExternalAccount> captor =
        org.mockito.ArgumentCaptor.forClass(net.firedevops.firemud.entity.ExternalAccount.class);
    org.mockito.Mockito.verify(externalAccountRepository).save(captor.capture());
    assertEquals("abc", captor.getValue().getExternalId());
  }

  private static String hash(String password) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
