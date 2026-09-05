-- Keep the mutable admission projection aligned with immutable request history.
ALTER TABLE automation_admission_states
    ALTER COLUMN actor_principal TYPE VARCHAR(256);
