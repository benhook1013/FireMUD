package net.firedevops.firemud.gamedesign.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.entity.RecordedParticipantDigest;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.repository.RecordedParticipantDigestRepository;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import net.firedevops.firemud.gamedesign.service.RecordedParticipantDigestService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecordedParticipantDigestServiceImpl implements RecordedParticipantDigestService {
  private final RecordedParticipantDigestRepository repository;

  public RecordedParticipantDigestServiceImpl(RecordedParticipantDigestRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public void assertMatchesRecordedDigests(
      String tenantId,
      PublishType publishType,
      List<PublishParticipantDigestDto> participantDigests) {
    participantDigests.forEach(
        digest ->
            repository
                .findByTenantIdAndPublishTypeAndParticipantKeyAndAppliedCommitId(
                    tenantId,
                    publishType,
                    PublishParticipantKey.valueOf(digest.participantKey()),
                    digest.appliedCommitId())
                .ifPresent(recorded -> assertMatchesRecordedDigest(recorded, digest)));
  }

  @Override
  @Transactional
  public void recordVerifiedDigests(
      String tenantId,
      PublishType publishType,
      String publishWorkflowId,
      List<PublishParticipantDigestDto> participantDigests) {
    LocalDateTime now = LocalDateTime.now();
    participantDigests.forEach(
        digest -> {
          PublishParticipantKey participantKey =
              PublishParticipantKey.valueOf(digest.participantKey());
          RecordedParticipantDigest recorded =
              repository
                  .findByTenantIdAndPublishTypeAndParticipantKeyAndAppliedCommitId(
                      tenantId, publishType, participantKey, digest.appliedCommitId())
                  .orElseGet(RecordedParticipantDigest::new);
          recorded.setTenantId(tenantId);
          recorded.setPublishType(publishType);
          recorded.setParticipantKey(participantKey);
          recorded.setScopeValue(digest.scopeValue());
          recorded.setAppliedCommitId(digest.appliedCommitId());
          recorded.setContentDigest(digest.contentDigest());
          recorded.setDigestSchemaVersion(digest.digestSchemaVersion());
          if (recorded.getRecordedFromPublishWorkflowId() == null) {
            recorded.setRecordedFromPublishWorkflowId(publishWorkflowId);
            recorded.setRecordedAt(now);
          }
          recorded.setLastVerifiedPublishWorkflowId(publishWorkflowId);
          recorded.setLastVerifiedAt(now);
          repository.save(recorded);
        });
  }

  private void assertMatchesRecordedDigest(
      RecordedParticipantDigest recorded, PublishParticipantDigestDto current) {
    if (!Objects.equals(recorded.getScopeValue(), current.scopeValue())) {
      throw new PublishGateFailureException(
          PublishGateFailureCode.RECORDED_SCOPE_MISMATCH,
          "recorded scope mismatch for "
              + current.participantKey()
              + ": recorded="
              + recorded.getScopeValue()
              + " current="
              + current.scopeValue());
    }
    if (!Objects.equals(recorded.getDigestSchemaVersion(), current.digestSchemaVersion())) {
      throw new PublishGateFailureException(
          PublishGateFailureCode.RECORDED_DIGEST_SCHEMA_MISMATCH,
          "recorded digest schema mismatch for "
              + current.participantKey()
              + ": recorded="
              + recorded.getDigestSchemaVersion()
              + " current="
              + current.digestSchemaVersion());
    }
    if (!Objects.equals(recorded.getContentDigest(), current.contentDigest())) {
      throw new PublishGateFailureException(
          PublishGateFailureCode.RECORDED_CONTENT_DIGEST_MISMATCH,
          "recorded content digest mismatch for "
              + current.participantKey()
              + ": recorded="
              + recorded.getContentDigest()
              + " current="
              + current.contentDigest());
    }
  }
}
