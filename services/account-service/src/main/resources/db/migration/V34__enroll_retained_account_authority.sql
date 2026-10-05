-- Retained Account rows receive the exact account-scoped state only when both
-- durable components are absent. The migration transaction keeps the pair
-- atomic with respect to concurrent Account writes.
-- [jooq ignore start]
LOCK TABLE accounts, account_authority_generations, account_authority_issuance_fences
    IN SHARE ROW EXCLUSIVE MODE;
-- [jooq ignore stop]

-- [jooq ignore start]
DO $migration$
DECLARE
    incomplete_account_uuid UUID;
BEGIN
    SELECT account.account_uuid
    INTO incomplete_account_uuid
    FROM accounts AS account
    LEFT JOIN account_authority_generations AS generation
        ON generation.scope_kind = 'ACCOUNT'
        AND generation.account_uuid = account.account_uuid
    LEFT JOIN account_authority_issuance_fences AS issuance_fence
        ON issuance_fence.account_uuid = account.account_uuid
    WHERE (generation.account_uuid IS NULL) <> (issuance_fence.account_uuid IS NULL)
    ORDER BY account.account_uuid
    LIMIT 1;

    IF incomplete_account_uuid IS NOT NULL THEN
        RAISE EXCEPTION
            'Retained Account % has incomplete authority generation and issuance-fence state',
            incomplete_account_uuid
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_authority_retained_pair_incomplete';
    END IF;
END;
$migration$;
-- [jooq ignore stop]

INSERT INTO account_authority_generations (
    scope_kind,
    account_uuid,
    generation,
    source_version
)
SELECT
    'ACCOUNT',
    account.account_uuid,
    1,
    1
FROM accounts AS account
WHERE NOT EXISTS (
    SELECT 1
    FROM account_authority_generations AS generation
    WHERE generation.scope_kind = 'ACCOUNT'
        AND generation.account_uuid = account.account_uuid
);

INSERT INTO account_authority_issuance_fences (
    account_uuid,
    issuance_fence,
    source_version
)
SELECT
    account.account_uuid,
    1,
    1
FROM accounts AS account
WHERE NOT EXISTS (
    SELECT 1
    FROM account_authority_issuance_fences AS issuance_fence
    WHERE issuance_fence.account_uuid = account.account_uuid
);
