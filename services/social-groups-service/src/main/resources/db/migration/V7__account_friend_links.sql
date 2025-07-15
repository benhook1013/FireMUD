CREATE TABLE account_friend_links (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    friend_account_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_account_friend_links_account_id ON account_friend_links(account_id);
CREATE INDEX idx_account_friend_links_friend_id ON account_friend_links(friend_account_id);
