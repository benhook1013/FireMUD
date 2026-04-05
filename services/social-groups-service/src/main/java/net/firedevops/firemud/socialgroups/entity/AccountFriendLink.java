package net.firedevops.firemud.socialgroups.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

/** Entity representing an account-level friendship. */
@Data
@Entity
@Table(name = "account_friend_links")
public class AccountFriendLink {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long accountId;

  @Column(nullable = false)
  private Long friendAccountId;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(nullable = false)
  private Instant createdAt;
}
