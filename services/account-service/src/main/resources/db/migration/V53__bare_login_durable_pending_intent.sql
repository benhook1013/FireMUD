-- V37 made PENDING bare-LOGIN exchange claims transaction-local, which prevents durable
-- pre-sign intent recovery. Keep the operation non-authorizing while PENDING; terminal success
-- still requires its exact response envelope in the same Account transaction.
-- [jooq ignore start]
DROP TRIGGER account_bare_login_pending_commit_check_trigger
    ON account_bare_login_exchange_operations;
DROP FUNCTION account_bare_login_pending_commit_check();
-- [jooq ignore end]
