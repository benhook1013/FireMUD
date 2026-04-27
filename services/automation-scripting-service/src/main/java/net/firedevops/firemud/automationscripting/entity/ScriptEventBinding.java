package net.firedevops.firemud.automationscripting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

@Data
@Entity
@Table(name = "script_event_bindings")
public class ScriptEventBinding {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false, length = 128)
  private String scriptPatchVersion;

  @Column(nullable = false, length = 128)
  private String eventType;

  @Column(nullable = false, length = 32)
  private String eventSchemaVersion;

  @Column(nullable = false, length = 128)
  private String scriptId;

  @Column(nullable = false, length = 32)
  private String targetScopeType;

  @Column(nullable = false, length = 128)
  private String targetScopeId;

  @Column(nullable = false)
  private int priority;

  @Column(nullable = false, length = 32)
  private String priorityTag = "normal";

  @Column(nullable = false)
  private boolean requiresExclusiveEvent;

  @Column(nullable = false)
  private boolean enabled = true;

  @Version
  @Column(name = "row_version")
  private int rowVersion;
}
