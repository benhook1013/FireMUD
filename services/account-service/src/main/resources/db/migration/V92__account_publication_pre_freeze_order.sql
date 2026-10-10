-- The Account publication order is acquired from the authenticated immutable Draft selection
-- before World capture. Existing World evidence remains retained and immutable; new orders
-- leave the optional later-correlation evidence absent.
ALTER TABLE account_selected_publication_authorizations
    ALTER COLUMN world_evidence DROP NOT NULL;
