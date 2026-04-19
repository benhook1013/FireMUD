package net.firedevops.firemud.accountservice.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "accounts")
public class Account {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 50)
  private String username;

  @Column(nullable = false, unique = true, length = 100)
  private String email;

  @Column(nullable = false, length = 255)
  private String passwordHash;

  @Column(nullable = false, length = 20)
  private String role = "player";

  @Column(name = "two_factor_secret", length = 64)
  private String twoFactorSecret;

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified = false;
}
