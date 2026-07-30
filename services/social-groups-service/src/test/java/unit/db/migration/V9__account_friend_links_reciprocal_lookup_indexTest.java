package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class V9__account_friend_links_reciprocal_lookup_indexTest {

  @Test
  void createsIndexDirectlyForEmptyFreshBootstrapTable() {
    assertThat(V9__account_friend_links_reciprocal_lookup_index.indexCreationStatement(false))
        .startsWith("CREATE INDEX idx_account_friend_links_reciprocal_lookup");
  }

  @Test
  void createsIndexConcurrentlyWhenExistingRowsNeedOnlineMigration() {
    assertThat(V9__account_friend_links_reciprocal_lookup_index.indexCreationStatement(true))
        .startsWith("CREATE INDEX CONCURRENTLY idx_account_friend_links_reciprocal_lookup");
  }
}
