package net.firedevops.firemud.accountservice.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
public class AccountRealmAccessGrant {
  private Long id;
  private Account account;
  private Long tenantId;
  private String worldSlug;
  private String realmSlug;
  private Long grantVersion;
  private boolean granted = true;
  private UUID grantAuthorityGeneration;
  private String grantedBy;
  private String grantReason;
  private Instant createdAt;
  private Instant updatedAt;
}
