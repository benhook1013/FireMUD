package net.firedevops.firemud.gamedesign.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Data
@Entity
@Table(name = "game_templates")
public class GameTemplate {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 36)
  private String tenantId;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(length = 255)
  private String description;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String config;

  @Column(name = "default_version_id")
  private Long defaultVersionId;

  @Column(name = "default_script_patch_version", length = 100)
  private String defaultScriptPatchVersion;

  @Column(name = "default_runtime_flags_json", nullable = false, columnDefinition = "text")
  private String defaultRuntimeFlagsJson = "{}";

  @Enumerated(EnumType.STRING)
  @Column(name = "template_reference_phase", nullable = false, length = 32)
  private TemplateReferencePhase templateReferencePhase = TemplateReferencePhase.ENFORCED;

  @Column(nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();
}
