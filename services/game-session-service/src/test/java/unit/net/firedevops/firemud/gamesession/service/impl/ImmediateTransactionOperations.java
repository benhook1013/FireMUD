package net.firedevops.firemud.gamesession.service.impl;

import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** Executes transaction callbacks directly for unit tests that do not use a database. */
final class ImmediateTransactionOperations implements TransactionOperations {
  @Override
  public <T> T execute(TransactionCallback<T> action) {
    return action.doInTransaction(new SimpleTransactionStatus());
  }
}
