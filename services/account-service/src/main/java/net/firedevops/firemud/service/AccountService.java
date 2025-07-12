package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.AccountDataExportDto;
import net.firedevops.firemud.dto.AccountDto;
import net.firedevops.firemud.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.dto.CreateAccountRequest;
import net.firedevops.firemud.dto.PasswordResetRequest;
import net.firedevops.firemud.dto.ProfileDto;
import net.firedevops.firemud.dto.UpdateProfileRequest;

public interface AccountService {
  AccountDto createAccount(CreateAccountRequest request);

  String authenticate(Long tenantId, String username, String password, String otp);

  ProfileDto getProfile(Long tenantId, Long accountId);

  ProfileDto updateProfile(UpdateProfileRequest request);

  AccountDataExportDto exportAccountData(Long tenantId, Long accountId);

  void deleteAccount(Long tenantId, Long accountId);

  void requestPasswordReset(PasswordResetRequest request);

  void completePasswordReset(CompletePasswordResetRequest request);

  void linkExternalAccount(net.firedevops.firemud.dto.LinkExternalAccountRequest request);
}
