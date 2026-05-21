package net.firedevops.firemud.socialgroups.entity;

import java.time.Instant;
import lombok.Data;

/** Entity representing an account-level friendship. */
@Data
public class AccountFriendLink {
  private Long id;
  private Long tenantId;
  private Long accountId;
  private Long friendAccountId;
  private String status;
  private Instant createdAt;
}
