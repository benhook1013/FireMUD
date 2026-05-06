package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.PublishAttemptParticipantDigest;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptParticipantDigestRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.service.PublishAttemptService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators remain internal service dependencies")
public class PublishAttemptServiceImpl implements PublishAttemptService {
  private final PublishAttemptRepository publishAttemptRepository;
  private final PublishAttemptParticipantDigestRepository participantDigestRepository;

  public PublishAttemptServiceImpl(
      PublishAttemptRepository publishAttemptRepository,
      PublishAttemptParticipantDigestRepository participantDigestRepository) {
    this.publishAttemptRepository = publishAttemptRepository;
    this.participantDigestRepository = participantDigestRepository;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void createAttempt(VersionDto version, PublishType publishType, String publishWorkflowId) {
    Objects.requireNonNull(version, "version must not be null");
    PublishAttempt attempt = new PublishAttempt();
    attempt.setTenantId(version.tenantId());
    attempt.setPublishWorkflowId(publishWorkflowId);
    attempt.setPublishType(publishType);
    attempt.setVersionId(version.id());
    attempt.setVersionNumber(version.versionNumber());
    attempt.setScriptPatchVersion(version.scriptPatchVersion());
    publishAttemptRepository.save(attempt);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordParticipantDigests(
      String publishWorkflowId, List<PublishParticipantDigestDto> participantDigests) {
    PublishAttempt attempt = requireAttempt(publishWorkflowId);
    participantDigestRepository.deleteByPublishAttemptId(attempt.getId());
    participantDigests.forEach(
        digest -> participantDigestRepository.save(toEntity(attempt.getId(), digest)));
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markSucceeded(String publishWorkflowId) {
    PublishAttempt attempt = requireAttempt(publishWorkflowId);
    attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
    attempt.setFailureCode(null);
    attempt.setFailureMessage(null);
    attempt.setCompletedAt(LocalDateTime.now());
    publishAttemptRepository.save(attempt);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markFailed(String publishWorkflowId, String failureCode, String failureMessage) {
    PublishAttempt attempt = requireAttempt(publishWorkflowId);
    attempt.setStatus(PublishAttemptStatus.FAILED);
    attempt.setFailureCode(failureCode);
    attempt.setFailureMessage(failureMessage);
    attempt.setCompletedAt(LocalDateTime.now());
    publishAttemptRepository.save(attempt);
  }

  private PublishAttempt requireAttempt(String publishWorkflowId) {
    return publishAttemptRepository
        .findByPublishWorkflowId(publishWorkflowId)
        .orElseThrow(() -> new IllegalArgumentException("publish attempt not found"));
  }

  private PublishAttemptParticipantDigest toEntity(
      Long publishAttemptId, PublishParticipantDigestDto digest) {
    PublishAttemptParticipantDigest entity = new PublishAttemptParticipantDigest();
    entity.setPublishAttemptId(publishAttemptId);
    entity.setParticipantKey(PublishParticipantKey.valueOf(digest.participantKey()));
    entity.setScopeValue(digest.scopeValue());
    entity.setAppliedCommitId(digest.appliedCommitId());
    entity.setContentDigest(digest.contentDigest());
    entity.setDigestSchemaVersion(digest.digestSchemaVersion());
    entity.setErrorCode(digest.errorCode());
    entity.setErrorMessage(digest.errorMessage());
    return entity;
  }
}
