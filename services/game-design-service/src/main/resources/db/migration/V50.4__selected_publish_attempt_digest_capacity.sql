-- Full-version attempts retain the exact authored-Draft selection digest,
-- including its algorithm prefix. Widen only; preserve every existing attempt
-- and its original request digest, including absent historical evidence.
ALTER TABLE publish_attempt
    ALTER COLUMN request_digest TYPE VARCHAR(71);
