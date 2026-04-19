package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.entity.RecordedParticipantDigest;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.repository.RecordedParticipantDigestRepository;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class RecordedParticipantDigestServiceImplTest {
  @Mock private RecordedParticipantDigestRepository repository;

  private RecordedParticipantDigestServiceImpl service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new RecordedParticipantDigestServiceImpl(repository);
  }

  @Test
  void assertMatchesRecordedDigestsFailsClosedOnDigestMismatch() {
    RecordedParticipantDigest recorded = new RecordedParticipantDigest();
    recorded.setTenantId("tenant-1");
    recorded.setPublishType(PublishType.FULL_VERSION);
    recorded.setParticipantKey(PublishParticipantKey.GAME_DESIGN_CONTROL_PLANE);
    recorded.setScopeValue("7");
    recorded.setAppliedCommitId("version:7");
    recorded.setContentDigest("recorded");
    recorded.setDigestSchemaVersion(1);
    when(repository.findByTenantIdAndPublishTypeAndParticipantKeyAndAppliedCommitId(
            "tenant-1",
            PublishType.FULL_VERSION,
            PublishParticipantKey.GAME_DESIGN_CONTROL_PLANE,
            "version:7"))
        .thenReturn(Optional.of(recorded));

    PublishGateFailureException thrown =
        assertThrows(
            PublishGateFailureException.class,
            () ->
                service.assertMatchesRecordedDigests(
                    "tenant-1",
                    PublishType.FULL_VERSION,
                    List.of(
                        new PublishParticipantDigestDto(
                            "GAME_DESIGN_CONTROL_PLANE",
                            "7",
                            "version:7",
                            "current",
                            1,
                            null,
                            null))));

    assertEquals(PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH, thrown.failureCode());
  }

  @Test
  void recordVerifiedDigestsStoresBaselineAndVerificationWorkflow() {
    when(repository.findByTenantIdAndPublishTypeAndParticipantKeyAndAppliedCommitId(
            "tenant-1",
            PublishType.FULL_VERSION,
            PublishParticipantKey.GAME_DESIGN_CONTROL_PLANE,
            "version:7"))
        .thenReturn(Optional.empty());
    when(repository.save(any(RecordedParticipantDigest.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.recordVerifiedDigests(
        "tenant-1",
        PublishType.FULL_VERSION,
        "workflow-1",
        List.of(
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE", "7", "version:7", "digest-1", 1, null, null)));

    org.mockito.Mockito.verify(repository).save(any(RecordedParticipantDigest.class));
  }
}
