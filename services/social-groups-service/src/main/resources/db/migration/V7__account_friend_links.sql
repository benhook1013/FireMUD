CREATE TABLE account_friend_links (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    friend_account_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_account_friend_links_tenant_id ON account_friend_links(tenant_id);
CREATE INDEX idx_account_friend_links_account_id ON account_friend_links(account_id);
CREATE INDEX idx_account_friend_links_friend_id ON account_friend_links(friend_account_id);
CREATE UNIQUE INDEX uq_account_friend_links_tenant_account_friend
    ON account_friend_links(tenant_id, account_id, friend_account_id);
