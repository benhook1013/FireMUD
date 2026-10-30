package net.firedevops.firemud.socialgroups.entity;

import java.time.Instant;
import lombok.Data;

/** Entity representing an account-level friendship. */
@Data
public class AccountFriendLink {
  private Long id;
  private Long tenantId;
  private String accountId;
  private String friendAccountId;
  private String status;
  private Instant createdAt;
}
