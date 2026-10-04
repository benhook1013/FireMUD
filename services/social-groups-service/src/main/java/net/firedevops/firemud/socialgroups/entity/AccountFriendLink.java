package net.firedevops.firemud.socialgroups.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.Data;

/** Entity representing an account-level friendship. */
@Data
public class AccountFriendLink {
  private Long id;
  private Long tenantId;
  private UUID accountId;
  private UUID friendAccountId;
  private String status;
  private Instant createdAt;
}
