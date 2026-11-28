-- An effect with retained attempt evidence cannot be treated as unclaimed.
-- [jooq ignore start]
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM recovery_control.initial_gameplay_release_outbox o
        WHERE o.state = 'pending' AND (
            EXISTS (SELECT 1 FROM recovery_control.initial_gameplay_release_attempt_closure c
                    WHERE c.outbox_id = o.outbox_id)
            OR EXISTS (SELECT 1 FROM recovery_control.initial_gameplay_release_attempt_rejection r
                       WHERE r.outbox_id = o.outbox_id)
            OR EXISTS (SELECT 1 FROM recovery_control.initial_gameplay_release_attempt_local_no_send n
                       WHERE n.outbox_id = o.outbox_id)
            OR EXISTS (SELECT 1 FROM recovery_control.initial_gameplay_release_preparation_receipt p
                       WHERE p.outbox_id = o.outbox_id)
        )
    ) THEN
        RAISE EXCEPTION 'Pending Recovery effect has retained attempt evidence' USING ERRCODE = '23514';
    END IF;
END
$$;
-- [jooq ignore stop]
