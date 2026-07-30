-- [jooq ignore start]
CREATE INDEX CONCURRENTLY idx_account_friend_links_reciprocal_lookup
    ON account_friend_links(tenant_id, friend_account_id, account_id, status);
-- [jooq ignore stop]
