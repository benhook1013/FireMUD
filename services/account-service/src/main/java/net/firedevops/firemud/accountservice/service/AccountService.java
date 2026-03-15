package net.firedevops.firemud.accountservice.service;

import net.firedevops.firemud.accountservice.dto.AccountDataExportDto;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.ProfileDto;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRequest;

public interface AccountService {
  AccountDto createAccount(CreateAccountRequest request);

  net.firedevops.firemud.accountservice.dto.AuthenticationResult authenticate(
      Long tenantId, String username, String password, String otp);

  ProfileDto getProfile(Long tenantId, Long accountId);

  ProfileDto updateProfile(UpdateProfileRequest request);

  AccountDataExportDto exportAccountData(Long tenantId, Long accountId);

  void deleteAccount(Long tenantId, Long accountId);

  void requestPasswordReset(PasswordResetRequest request);

  void completePasswordReset(CompletePasswordResetRequest request);

  void linkExternalAccount(
      net.firedevops.firemud.accountservice.dto.LinkExternalAccountRequest request);

  void requestEmailVerification(Long tenantId, Long accountId);

  void verifyEmail(net.firedevops.firemud.accountservice.dto.VerifyEmailRequest request);

  /** Send the username associated with an email address. */
  void sendUsernameReminder(
      net.firedevops.firemud.accountservice.dto.UsernameRecoveryRequest request);
}
