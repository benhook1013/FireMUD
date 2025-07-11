INSERT INTO guilds (id, tenant_id, name, owner_account_id)
VALUES (1, 1, 'Adventurers', 100);

INSERT INTO guild_members (guild_id, account_id, role)
VALUES (1, 100, 'OWNER');

INSERT INTO friend_links (tenant_id, account_id, friend_account_id, status)
VALUES (1, 100, 200, 'accepted');

INSERT INTO chat_messages (tenant_id, sender_account_id, content)
VALUES (1, 100, 'Welcome to FireMUD!');
