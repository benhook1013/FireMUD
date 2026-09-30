package net.firedevops.firemud.automationscripting.service.impl;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps replay authority RPCs outside JDBC transactions and isolates its durable mutation phase.
 */
@Service
public class ScriptDeadLetterReplayTransactionBoundary {
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public <T> T withoutTransaction(Supplier<T> work) {
    return work.get();
  }

  @Transactional
  public <T> T inTransaction(Supplier<T> work) {
    return work.get();
  }
}
