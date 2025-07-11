package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.AccountDto;
import net.firedevops.firemud.dto.CreateAccountRequest;

public interface AccountService {
  AccountDto createAccount(CreateAccountRequest request);

  String authenticate(Long tenantId, String username, String password, String otp);
}
