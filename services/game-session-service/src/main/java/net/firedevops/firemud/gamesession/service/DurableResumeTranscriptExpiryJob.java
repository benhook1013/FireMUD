package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import net.firedevops.firemud.gamesession.repository.ResumeTranscriptEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Bounds durable reconnect storage even when a player never reconnects after expiry. */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repository is an internal shared Spring collaborator.")
public class DurableResumeTranscriptExpiryJob {
  private static final Logger log = LoggerFactory.getLogger(DurableResumeTranscriptExpiryJob.class);
  private static final int BATCH_SIZE = 500;

  private final ResumeTranscriptEntryRepository repository;

  public DurableResumeTranscriptExpiryJob(ResumeTranscriptEntryRepository repository) {
    this.repository = repository;
  }

  @Scheduled(fixedDelayString = "${firemud.reconnection.buffer.expiry-sweep-ms:60000}")
  @Transactional
  public void purgeExpiredTranscripts() {
    int deleted = repository.deleteExpiredBefore(Instant.now(), BATCH_SIZE);
    if (deleted > 0) {
      log.debug("Purged {} expired reconnect transcript entries", deleted);
    }
  }
}
