-- A previous writer cannot establish whether an ambiguous retained dispatch was sent.
ALTER TABLE recovery_control.initial_gameplay_release_outbox
    ADD COLUMN send_outcome_uncertain boolean;
