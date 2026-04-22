package net.firedevops.firemud.accountservice.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(
    name = "account_realm_access_grant",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_account_realm_access_grant_account_realm",
          columnNames = {"account_id", "tenant_id", "world_slug", "realm_slug"})
    })
public class AccountRealmAccessGrant {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "account_id", nullable = false)
  private Account account;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "world_slug", nullable = false, length = 120)
  private String worldSlug;

  @Column(name = "realm_slug", nullable = false, length = 120)
  private String realmSlug;

  @Column(name = "grant_version", nullable = false)
  private Long grantVersion;

  @Column(name = "granted_by", nullable = false, length = 200)
  private String grantedBy;

  @Column(name = "grant_reason", nullable = false, length = 500)
  private String grantReason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
