package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;

@Data
public class VersionTemplateRemapSet {
  private Long id;
  private String remapSetId;
  private String tenantId;
  private Long sourceVersionId;
  private Long targetVersionId;
  private TemplateRemapSetStatus status = TemplateRemapSetStatus.DRAFT;
  private String createdReason;
  private String approvalReason;
  private LocalDateTime createdAt;

  private LocalDateTime approvedAt;
  private LocalDateTime updatedAt;
  private List<VersionTemplateRemapEntry> remapEntries = new ArrayList<>();

  public void addRemapEntry(VersionTemplateRemapEntry remapEntry) {
    remapEntries.add(remapEntry);
    remapEntry.setRemapSet(this);
  }

  void prePersist() {
    LocalDateTime now = LocalDateTime.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
  }

  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
