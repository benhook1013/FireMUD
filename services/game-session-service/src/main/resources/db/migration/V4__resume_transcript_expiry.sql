ALTER TABLE resume_transcript_entry
    ADD COLUMN expires_at timestamp without time zone;

CREATE INDEX idx_resume_transcript_entry_expiry
    ON resume_transcript_entry USING btree (expires_at)
    WHERE expires_at IS NOT NULL;
