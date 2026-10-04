ALTER TABLE account_membership_transition_receipts
    DROP CONSTRAINT account_membership_transition_receipts_transition_check;

ALTER TABLE account_membership_transition_receipts
    DROP CONSTRAINT account_membership_transition_receipts_state_check;

ALTER TABLE account_membership_transition_receipts
    ADD CONSTRAINT account_membership_transition_receipts_transition_check
        CHECK (transition_type IN (
            'MEMBERSHIP_JOINED',
            'MEMBERSHIP_REACTIVATED',
            'MEMBERSHIP_LEFT'
        ));

ALTER TABLE account_membership_transition_receipts
    ADD CONSTRAINT account_membership_transition_receipts_state_check
        CHECK (
            authority_provenance = 'EXPLICIT_JOIN'
            AND (
                (transition_type IN ('MEMBERSHIP_JOINED', 'MEMBERSHIP_REACTIVATED')
                    AND membership_lifecycle_state = 'ACTIVE'
                    AND gameplay_admission_allowed = TRUE)
                OR
                (transition_type = 'MEMBERSHIP_LEFT'
                    AND membership_lifecycle_state = 'INACTIVE'
                    AND gameplay_admission_allowed = FALSE)
            )
        );
