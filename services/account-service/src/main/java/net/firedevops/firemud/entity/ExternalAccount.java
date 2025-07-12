package net.firedevops.firemud.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(
    name = "external_account",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"provider", "external_id"})})
public class ExternalAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "account_id", nullable = false)
  private Account account;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(nullable = false, length = 20)
  private String provider;

  @Column(name = "external_id", nullable = false, length = 100)
  private String externalId;
}
