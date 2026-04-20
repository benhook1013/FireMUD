package net.firedevops.firemud.gamesession.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "prepared_version_upgrade")
public class PreparedVersionUpgrade {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "preparation_id", nullable = false, unique = true, length = 64)
  private String preparationId;

  @Column(name = "control_plane_request_id", nullable = false, length = 128)
  private String controlPlaneRequestId;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "source_game_instance_id", nullable = false)
  private Long sourceGameInstanceId;

  @Column(name = "source_version_id", nullable = false)
  private Long sourceVersionId;

  @Column(name = "target_version_id", nullable = false)
  private Long targetVersionId;

  @Column(name = "target_launch_descriptor_id", nullable = false, length = 64)
  private String targetLaunchDescriptorId;

  @Column(name = "remap_set_id", length = 64)
  private String remapSetId;

  @Column(name = "result", nullable = false, length = 32)
  private String result;

  @Column(name = "reasons_json", nullable = false, columnDefinition = "TEXT")
  private String reasonsJson;

  @Column(name = "checked_participants_json", nullable = false, columnDefinition = "TEXT")
  private String checkedParticipantsJson;

  @Column(name = "participant_results_json", nullable = false, columnDefinition = "TEXT")
  private String participantResultsJson;

  @Column(name = "checked_at", nullable = false)
  private Instant checkedAt;
}
