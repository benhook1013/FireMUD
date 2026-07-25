package net.firedevops.firemud.accountservice.service;

import net.firedevops.firemud.accountservice.dto.AccountDataExportDto;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.AccountLoginAuthModesDto;
import net.firedevops.firemud.accountservice.dto.BootstrapCharacterDto;
import net.firedevops.firemud.accountservice.dto.BootstrapRealmDto;
import net.firedevops.firemud.accountservice.dto.BootstrapWorldDto;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenResult;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.PlayerBootstrapResult;
import net.firedevops.firemud.accountservice.dto.ProfileDto;
import net.firedevops.firemud.accountservice.dto.PublicProductionMembershipResult;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantRequest;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantResult;
import net.firedevops.firemud.accountservice.dto.RuntimeEntitlementsDto;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipDto;
import net.firedevops.firemud.accountservice.dto.TenantDataExportDto;
import net.firedevops.firemud.accountservice.dto.UpdateAccountLoginAuthModesRequest;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRequest;
import net.firedevops.firemud.accountservice.entity.ProfilePresenceVisibilityPolicy;

public interface AccountService {
  AccountDto createAccount(CreateAccountRequest request);

  net.firedevops.firemud.accountservice.dto.AuthenticationResult authenticate(
      Long tenantId, String username, String password);

  void requestEmailLoginOtp(Long tenantId, String email);

  net.firedevops.firemud.accountservice.dto.AuthenticationResult verifyEmailLoginOtp(
      Long tenantId, String email, String code);

  PlayerBootstrapResult issuePlayerBootstrap(String accountIdentifier, String secret);

  java.util.List<BootstrapWorldDto> listBootstrapWorlds(String bootstrapToken);

  java.util.List<BootstrapRealmDto> listBootstrapRealms(String bootstrapToken, String worldSlug);

  java.util.List<BootstrapCharacterDto> listBootstrapCharacters(
      String bootstrapToken, String worldSlug, String realmSlug, String connectScopeId);

  ConnectTokenResult issueConnectToken(String bootstrapToken, ConnectTokenRequest request);

  PublicProductionMembershipResult ensurePublicProductionPlayerMembership(
      Long accountId, Long tenantId, String worldSlug, String realmSlug, String requestId);

  RuntimeMembershipDto getTenantMembershipForRuntime(
      Long accountId, Long tenantId, String requestId);

  RealmAccessGrantResult getRealmAccessGrantForRuntime(
      Long accountId, Long tenantId, String worldSlug, String realmSlug, String requestId);

  RealmAccessGrantResult grantRealmAccess(RealmAccessGrantRequest request);

  void revokeRealmAccess(Long accountId, Long tenantId, String worldSlug, String realmSlug);

  RuntimeEntitlementsDto getTenantEntitlementsForRuntime(Long tenantId, String requestId);

  ProfileDto getProfile(Long tenantId, Long accountId);

  java.util.Map<Long, ProfilePresenceVisibilityPolicy> listPresenceVisibilityPolicies(
      Long tenantId, java.util.List<Long> accountIds);

  ProfileDto updateProfile(UpdateProfileRequest request);

  AccountLoginAuthModesDto getLoginAuthModes(Long accountId);

  AccountLoginAuthModesDto updateLoginAuthModes(
      Long accountId, UpdateAccountLoginAuthModesRequest request);

  AccountDataExportDto exportAccountData(Long accountId);

  TenantDataExportDto exportTenantData(Long tenantId, Long accountId);

  void deleteAccount(Long accountId);

  void requestPasswordReset(PasswordResetRequest request);

  void completePasswordReset(CompletePasswordResetRequest request);

  void linkExternalAccount(
      net.firedevops.firemud.accountservice.dto.LinkExternalAccountRequest request);

  void requestEmailVerification(Long accountId);

  void verifyEmail(net.firedevops.firemud.accountservice.dto.VerifyEmailRequest request);

  /** Send the username associated with an email address. */
  void sendUsernameReminder(
      net.firedevops.firemud.accountservice.dto.UsernameRecoveryRequest request);
}
