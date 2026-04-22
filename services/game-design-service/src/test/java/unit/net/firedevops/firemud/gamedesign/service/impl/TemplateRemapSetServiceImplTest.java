package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.dto.TemplateRemapEntryDto;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionTemplateRemapSet;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionTemplateRemapSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class TemplateRemapSetServiceImplTest {
  @Mock private VersionTemplateRemapSetRepository remapSetRepository;
  @Mock private VersionRepository versionRepository;

  private TemplateRemapSetServiceImpl service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new TemplateRemapSetServiceImpl(remapSetRepository, versionRepository);
    when(versionRepository.findById(7L)).thenReturn(Optional.of(version("tenant-1", 7L)));
    when(versionRepository.findById(8L)).thenReturn(Optional.of(version("tenant-1", 8L)));
  }

  @Test
  void createTemplateRemapSetPersistsDraftSet() {
    when(remapSetRepository.save(any(VersionTemplateRemapSet.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var created =
        service.createTemplateRemapSet(
            "tenant-1",
            7L,
            8L,
            "cutover prep",
            List.of(
                new TemplateRemapEntryDto(
                    "ENTITY", "CLASS_ASSIGNMENT", "class:warrior", "class:guardian")));

    assertEquals(TemplateRemapSetStatus.DRAFT, created.status());
    assertEquals(1, created.remapEntries().size());
    assertEquals("ENTITY", created.remapEntries().get(0).mappingDomain());
  }

  @Test
  void approveTemplateRemapSetMarksSetApproved() {
    VersionTemplateRemapSet existing = new VersionTemplateRemapSet();
    existing.setRemapSetId("remap-1");
    existing.setTenantId("tenant-1");
    existing.setSourceVersionId(7L);
    existing.setTargetVersionId(8L);
    existing.setStatus(TemplateRemapSetStatus.DRAFT);
    existing.setCreatedReason("cutover prep");
    when(remapSetRepository.findByTenantIdAndRemapSetId("tenant-1", "remap-1"))
        .thenReturn(Optional.of(existing));
    when(remapSetRepository.save(existing)).thenReturn(existing);

    var approved = service.approveTemplateRemapSet("tenant-1", "remap-1", "validated");

    assertEquals(TemplateRemapSetStatus.APPROVED, approved.status());
    assertEquals("validated", approved.approvalReason());
  }

  @Test
  void findApprovedTemplateRemapSetRejectsAmbiguousApprovedPair() {
    VersionTemplateRemapSet first = draftRemap("remap-1");
    first.setStatus(TemplateRemapSetStatus.APPROVED);
    VersionTemplateRemapSet second = draftRemap("remap-2");
    second.setStatus(TemplateRemapSetStatus.APPROVED);
    when(remapSetRepository
            .findAllByTenantIdAndSourceVersionIdAndTargetVersionIdAndStatusOrderByCreatedAtAsc(
                "tenant-1", 7L, 8L, TemplateRemapSetStatus.APPROVED))
        .thenReturn(List.of(first, second));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.findApprovedTemplateRemapSet("tenant-1", 7L, 8L));

    assertEquals(
        "INVALID_TEMPLATE_CONFIGURATION: multiple approved remap sets exist for the version pair",
        error.getMessage());
  }

  private Version version(String tenantId, long id) {
    Version version = new Version();
    version.setId(id);
    version.setTenantId(tenantId);
    return version;
  }

  private VersionTemplateRemapSet draftRemap(String remapSetId) {
    VersionTemplateRemapSet remapSet = new VersionTemplateRemapSet();
    remapSet.setRemapSetId(remapSetId);
    remapSet.setTenantId("tenant-1");
    remapSet.setSourceVersionId(7L);
    remapSet.setTargetVersionId(8L);
    remapSet.setStatus(TemplateRemapSetStatus.DRAFT);
    remapSet.setCreatedReason("cutover prep");
    return remapSet;
  }
}
