package net.firedevops.firemud.accountservice.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class AccountRealmAccessGrant {
  private Long id;
  private Account account;
  private Long tenantId;
  private String worldSlug;
  private String realmSlug;
  private Long grantVersion;
  private String grantedBy;
  private String grantReason;
  private Instant createdAt;
  private Instant updatedAt;
}
