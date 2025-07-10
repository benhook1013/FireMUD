CREATE TABLE mail_messages (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    sender_account_id BIGINT NOT NULL,
    recipient_account_id BIGINT NOT NULL,
    subject VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP
);
