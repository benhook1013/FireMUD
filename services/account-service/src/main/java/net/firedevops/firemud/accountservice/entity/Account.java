package net.firedevops.firemud.accountservice.entity;

import java.util.UUID;
import lombok.Data;

@Data
public class Account {
  private Long id;
  private UUID accountUuid;
  private AccountIdentityProvenance accountUuidProvenance;
  private Long accountUuidSourceNumericId;
  private String username;
  private String email;
  private String passwordHash;
  private String role;
  private boolean emailVerified = false;
  private String loginAuthModes = AccountLoginAuthModes.DEFAULT_SERIALIZED;
  private AccountLifecycleState lifecycleState = AccountLifecycleState.ACTIVE;
}
