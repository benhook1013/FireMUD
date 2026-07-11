package net.firedevops.firemud.accountservice.entity;

import lombok.Data;

@Data
public class Account {
  private Long id;
  private String username;
  private String email;
  private String passwordHash;
  private String role = "player";
  private String twoFactorSecret;
  private boolean emailVerified = false;
  private String loginAuthModes = AccountLoginAuthModes.DEFAULT_SERIALIZED;
}
