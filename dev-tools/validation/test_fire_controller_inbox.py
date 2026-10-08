import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))

from fire_controller.inbox import (
    InboxError,
    InboxNotBootstrapped,
    InboxStore,
    MessageNotFound,
)


class InboxStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.database = self.root / "alpha.sqlite3"
        self.other_database = self.root / "beta.sqlite3"
        self.store = InboxStore(self.database)

    def test_worker_controls_match_job_identity_and_web_contract(self) -> None:
        self.store.bootstrap()
        for worker in ("Build\nTeam", "Build\rTeam", "Build\tTeam", " Build Team"):
            for operation in (
                lambda worker=worker: self.store.send(worker, "Message"),
                lambda worker=worker: self.store.send("General", "Message", author=worker),
                lambda worker=worker: self.store.list(worker),
                lambda worker=worker: self.store.unread_count(worker),
            ):
                with self.subTest(worker=worker), self.assertRaises(InboxError):
                    operation()
        self.assertEqual(self.store.list("General"), [])
        self.assertEqual(self.store.send("Build Team", "Valid")["recipient"], "Build Team")

    def test_credential_screening_matches_existing_backup_without_rewriting(self) -> None:
        from pr_review.sqlite_backup import create_snapshot

        self.store.bootstrap()
        ordinary = "#2840 [worker]\n\nThe password field is intentionally omitted.\n\n```text\nAuthentication uses environment configuration.\n```"
        message = self.store.send("General", ordinary, author="Overseer")
        self.assertEqual(message["body"], ordinary)
        for unsafe in ("Bearer example-token", "password=example", "api_key: example-value"):
            with self.subTest(unsafe=unsafe), self.assertRaisesRegex(InboxError, "credential"):
                self.store.send("General", unsafe)
        self.assertEqual([row["body"] for row in self.store.list("General")], [ordinary])
        snapshot = self.root / "combined.sqlite3"
        create_snapshot(self.database, snapshot)
        self.assertTrue(snapshot.is_file())

    def test_unread_count_is_read_only_when_inbox_schema_is_missing(self) -> None:
        self.assertEqual(self.store.unread_count("General"), 0)
        self.assertFalse(self.database.exists())
        with self.assertRaises(InboxNotBootstrapped):
            self.store.list("General")
        self.assertFalse(self.database.exists())

    def test_private_send_reply_read_ack_and_unread_lifecycle(self) -> None:
        self.store.bootstrap()
        first = self.store.send(
            "General", "Please inspect this isolated failure.", author="Overseer", job="job-42", pr=17
        )
        self.assertEqual(first["recipient"], "General")
        self.assertEqual(first["author"], "Overseer")
        self.assertEqual(first["job"], "job-42")
        self.assertEqual(first["pr"], 17)
        self.assertIsNone(first["seen_at"])
        self.assertEqual(self.store.unread_count("General"), 1)
        self.assertEqual(self.store.unread_count("Gameplay"), 0)
        self.assertEqual(self.store.list("Gameplay"), [])

        reply = self.store.send("Overseer", "I found the cause.", reply_to=first["id"])
        self.assertEqual(reply["reply_to"], first["id"])
        with self.assertRaises(MessageNotFound):
            self.store.send("General", "Bad reply", reply_to="missing-message")

        seen = self.store.read(first["id"], recipient="General")
        self.assertIsNotNone(seen["seen_at"])
        self.assertIsNone(seen["acknowledged_at"])
        self.assertEqual(self.store.unread_count("General"), 0)
        self.assertEqual(self.store.list("General", unread=True), [])
        with self.assertRaises(MessageNotFound):
            self.store.read(first["id"], recipient="Gameplay")

        acknowledged = self.store.ack(reply["id"])
        repeated = self.store.ack(reply["id"])
        self.assertEqual(acknowledged["seen_at"], acknowledged["acknowledged_at"])
        self.assertEqual(repeated["seen_at"], acknowledged["seen_at"])
        self.assertEqual(repeated["acknowledged_at"], acknowledged["acknowledged_at"])
        self.assertEqual(self.store.unread_count("Overseer"), 0)

    def test_unread_and_unacknowledged_filters_preserve_state_and_paginate_after_filtering(self) -> None:
        from unittest.mock import patch

        self.store.bootstrap()
        timestamps = iter(f"2026-10-01T00:0{minute}:00Z" for minute in range(5))
        with patch("fire_controller.inbox._now", side_effect=lambda: next(timestamps)):
            older_unread = self.store.send("General", "Older unread")
            older_acknowledged = self.store.send("General", "Older acknowledged")
            read_unacknowledged = self.store.send("General", "Read but pending")
            newer_acknowledged = self.store.send("General", "Newer acknowledged")
            newer_unread = self.store.send("General", "Newer unread")

        self.store.ack(older_acknowledged["id"])
        self.store.read(read_unacknowledged["id"], recipient="General")
        self.store.ack(newer_acknowledged["id"])

        self.assertEqual(
            [row["id"] for row in self.store.list("General", unacknowledged=True)],
            [newer_unread["id"], read_unacknowledged["id"], older_unread["id"]],
        )
        self.assertEqual(
            [row["id"] for row in self.store.list("General", unread=True)],
            [newer_unread["id"], older_unread["id"]],
        )
        self.assertEqual(
            [row["id"] for row in self.store.list("General", unread=True, unacknowledged=True)],
            [newer_unread["id"], older_unread["id"]],
        )
        self.assertEqual(
            [row["id"] for row in self.store.list("General", unacknowledged=True, limit=1, offset=1)],
            [read_unacknowledged["id"]],
        )
        self.assertNotIn(
            older_acknowledged["id"],
            [row["id"] for row in self.store.list("General", unacknowledged=True)],
        )
        self.assertNotIn(
            newer_acknowledged["id"],
            [row["id"] for row in self.store.list("General", unacknowledged=True)],
        )

    def test_message_pages_are_bounded_and_database_contexts_are_independent(self) -> None:
        self.store.bootstrap()
        for index in range(3):
            self.store.send("Gameplay", f"Message {index}")
        self.assertEqual(len(self.store.list("Gameplay", limit=2)), 2)
        self.assertEqual(len(self.store.list("Gameplay", limit=2, offset=2)), 1)

        other = InboxStore(self.other_database)
        other.bootstrap()
        self.assertEqual(other.unread_count("Gameplay"), 0)
        other.send("Gameplay", "Only in beta")
        self.assertEqual(self.store.unread_count("Gameplay"), 3)
        self.assertEqual(other.unread_count("Gameplay"), 1)

    def test_thread_follows_root_and_returns_cross_recipient_replies_read_only(self) -> None:
        from unittest.mock import patch

        self.store.bootstrap()
        timestamps = iter(
            (
                "2026-10-01T00:00:00Z",
                "2026-10-01T00:01:00Z",
                "2026-10-01T00:02:00Z",
                "2026-10-01T00:03:00Z",
            )
        )
        with patch("fire_controller.inbox._now", side_effect=lambda: next(timestamps)):
            root = self.store.send("General", "Original request", author="Overseer", job="merge-train", pr=2898)
            first_reply = self.store.send("Overseer", "First response", author="General", reply_to=root["id"])
            sibling_reply = self.store.send("Gameplay", "Parallel response", author="Review", reply_to=root["id"])
            nested_reply = self.store.send("General", "Follow-up", author="Overseer", reply_to=first_reply["id"])

        thread = self.store.thread(nested_reply["id"])
        self.assertEqual(
            [message["id"] for message in thread],
            [
                root["id"],
                first_reply["id"],
                sibling_reply["id"],
                nested_reply["id"],
            ],
        )
        self.assertEqual([message["recipient"] for message in thread], ["General", "Overseer", "Gameplay", "General"])
        self.assertEqual(
            (thread[0]["job"], thread[0]["pr"], thread[0]["body"]), ("merge-train", 2898, "Original request")
        )
        page = self.store.thread(root["id"], limit=2, offset=1)
        self.assertEqual([message["id"] for message in page], [first_reply["id"], sibling_reply["id"]])
        self.assertTrue(all(message["seen_at"] is None and message["acknowledged_at"] is None for message in thread))
        self.assertEqual(self.store.unread_count("General"), 2)
        with self.assertRaises(MessageNotFound):
            self.store.thread("missing-message")

    def test_thread_same_timestamp_preserves_inserted_parent_and_reply_order(self) -> None:
        from unittest.mock import patch
        from uuid import UUID

        self.store.bootstrap()
        ids = iter((UUID(int=3), UUID(int=2), UUID(int=1)))
        with (
            patch("fire_controller.inbox._now", return_value="2026-10-01T00:00:00Z"),
            patch("fire_controller.inbox.uuid.uuid4", side_effect=lambda: next(ids)),
        ):
            root = self.store.send("General", "Root")
            reply = self.store.send("Overseer", "Reply", reply_to=root["id"])
            sibling = self.store.send("Gameplay", "Sibling", reply_to=root["id"])
        self.assertEqual(
            [message["id"] for message in self.store.thread(sibling["id"])],
            [
                root["id"],
                reply["id"],
                sibling["id"],
            ],
        )

    def test_conversations_use_worker_activity_and_only_incoming_unread_counts(self) -> None:
        from unittest.mock import patch

        self.store.bootstrap()
        timestamps = iter(f"2026-10-01T00:{index:02d}:00Z" for index in range(20))
        with patch("fire_controller.inbox._now", side_effect=lambda: next(timestamps)):
            old = self.store.send("General", "Old request", author="Overseer")
            recent = self.store.send("General", "Recent request", author="Gameplay")
            outgoing = self.store.send("Review", "Outgoing-only request", author="General")
            reply = self.store.send("Overseer", "Old request response", author="General", reply_to=old["id"])
            self.store.send("Gameplay", "Other branch", author="Review", reply_to=recent["id"])
            self.store.send("Overseer", "Unrelated request", author="Gameplay")
        seen = self.store.read(recent["id"], recipient="General")
        page = self.store.conversations_page("General")
        self.assertEqual([row["root_id"] for row in page["conversations"]], [old["id"], outgoing["id"], recent["id"]])
        self.assertEqual(page["conversations"][0]["latest_message"]["id"], reply["id"])
        self.assertEqual(
            [(row["message_count"], row["unread_count"]) for row in page["conversations"]], [(2, 1), (1, 0), (2, 0)]
        )
        self.assertFalse(page["has_more"])
        self.assertIsNotNone(seen["seen_at"])
        self.assertIsNone(seen["acknowledged_at"])
        self.assertEqual(self.store.list("General", unread=True), [old])
        self.assertEqual(
            [row["id"] for row in self.store.messages_page("General")["messages"]],
            [reply["id"], outgoing["id"], recent["id"], old["id"]],
        )
        self.assertTrue(all(row["seen_at"] is None for row in self.store.thread(old["id"])))

    def test_conversation_grouping_precedes_pagination_and_full_final_pages_are_terminal(self) -> None:
        from unittest.mock import patch

        self.store.bootstrap()
        with patch("fire_controller.inbox._now", return_value="2026-10-01T00:00:00Z"):
            root = self.store.send("General", "Many incoming messages", author="Overseer")
            for index in range(54):
                self.store.send("General", f"Reply {index}", author="Overseer", reply_to=root["id"])
            other_roots = [self.store.send("General", f"Other root {index}", author="Gameplay") for index in range(3)]
        first = self.store.conversations_page("General", limit=2)
        final = self.store.conversations_page("General", limit=2, offset=2)
        self.assertEqual(
            [row["root_id"] for row in first["conversations"]], [other_roots[2]["id"], other_roots[1]["id"]]
        )
        self.assertEqual([row["root_id"] for row in final["conversations"]], [other_roots[0]["id"], root["id"]])
        self.assertTrue(first["has_more"])
        self.assertFalse(final["has_more"])
        self.assertEqual(
            (final["conversations"][1]["message_count"], final["conversations"][1]["unread_count"]), (55, 55)
        )
        self.assertTrue(self.store.messages_page("General", limit=29)["has_more"])
        self.assertFalse(self.store.messages_page("General", limit=29, offset=29)["has_more"])
        self.assertEqual(self.store.conversations_page("General", offset=4)["conversations"], [])

    def test_same_time_activity_uses_insertion_order_and_self_messages_are_not_duplicated(self) -> None:
        from unittest.mock import patch
        from uuid import UUID

        self.store.bootstrap()
        ids = iter(UUID(int=index) for index in (4, 3, 2, 1))
        with (
            patch("fire_controller.inbox._now", return_value="2026-10-01T00:00:00Z"),
            patch("fire_controller.inbox.uuid.uuid4", side_effect=lambda: next(ids)),
        ):
            first = self.store.send("General", "Unknown author")
            second = self.store.send("Review", "Sent request", author="General")
            reply = self.store.send("General", "Nested reply", author="Overseer", reply_to=first["id"])
            self_message = self.store.send("General", "Self note", author="General")
        self.assertEqual(
            [row["root_id"] for row in self.store.conversations_page("General")["conversations"]],
            [self_message["id"], first["id"], second["id"]],
        )
        self.assertEqual(
            [row["id"] for row in self.store.messages_page("General")["messages"]],
            [self_message["id"], reply["id"], second["id"], first["id"]],
        )
        self.assertEqual(self.store.conversations_page("General")["conversations"][0]["unread_count"], 1)

    def test_worker_thread_qualification_checks_full_tree_and_never_marks_messages(self) -> None:
        self.store.bootstrap()
        root = self.store.send("Gameplay", "Other participant root", author="Review")
        parent = root
        for index in range(49):
            parent = self.store.send("Gameplay", f"Nested {index}", author="Review", reply_to=parent["id"])
        participation = self.store.send(
            "Overseer", "Worker participates later", author="General", reply_to=parent["id"]
        )
        first = self.store.thread_page(participation["id"], worker="General", limit=50)
        final = self.store.thread_page(root["id"], worker="General", limit=50, offset=50)
        self.assertEqual(len(first["messages"]), 50)
        self.assertTrue(first["has_more"])
        self.assertEqual([row["id"] for row in final["messages"]], [participation["id"]])
        self.assertFalse(final["has_more"])
        self.assertFalse(
            self.store.thread_page(parent["id"], worker="General", limit=50, focus_id=participation["id"])["has_more"]
        )
        with self.assertRaises(MessageNotFound):
            self.store.thread_page(root["id"], worker="Unknown")
        # Agent lookup remains a full cross-recipient read without a worker selector.
        self.assertEqual(len(self.store.thread(root["id"], limit=100)), 51)
        self.assertTrue(
            all(
                row["seen_at"] is None and row["acknowledged_at"] is None
                for row in self.store.thread(root["id"], limit=100)
            )
        )

    def test_new_projections_validate_input_and_do_not_create_missing_schema(self) -> None:
        for operation in (self.store.messages_page, self.store.conversations_page):
            with self.subTest(operation=operation), self.assertRaises(InboxNotBootstrapped):
                operation("General")
            for args in (
                {"worker": " General"},
                {"worker": "General", "limit": True},
                {"worker": "General", "offset": -1},
            ):
                with self.subTest(operation=operation, args=args), self.assertRaises(InboxError):
                    operation(**args)
        self.assertFalse(self.database.exists())

    def test_schema_validation_and_input_bounds(self) -> None:
        self.store.bootstrap()
        with sqlite3.connect(self.database) as connection:
            InboxStore.validate(connection)
        with self.assertRaises(InboxError):
            self.store.send("General", "x" * 20_001)
        with self.assertRaises(InboxError):
            self.store.send("General", "body", pr=True)
        with self.assertRaises(InboxError):
            self.store.list("General", unread=1)
        with self.assertRaises(InboxError):
            self.store.list("General", unacknowledged=1)

    def test_explicit_bootstrap_requires_controller_context_and_roundtrips_backup(self) -> None:
        from pr_review.sqlite_backup import create_snapshot, restore_snapshot
        from pr_review.sqlite_store import SqliteStateStore

        self.store.bootstrap()
        before = SqliteStateStore(self.database).load().to_dict()
        message = self.store.send("Worker Alpha", "Retained private Markdown **body**")
        snapshot, restored = self.root / "snapshot.sqlite3", self.root / "restored.sqlite3"
        create_snapshot(self.database, snapshot)
        restore_snapshot(snapshot, restored)
        self.assertEqual(InboxStore(restored).list("Worker Alpha")[0]["id"], message["id"])
        self.assertEqual(SqliteStateStore(self.database).load().to_dict(), before)
        unrelated = self.root / "unrelated.sqlite3"
        with sqlite3.connect(unrelated) as connection:
            connection.execute("CREATE TABLE unrelated(value TEXT)")
        with self.assertRaises(InboxError):
            InboxStore(unrelated).bootstrap()
        with sqlite3.connect(unrelated) as connection:
            self.assertEqual(
                connection.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall(), [("unrelated",)]
            )
        absent = self.root / "absent.sqlite3"
        with self.assertRaises(InboxNotBootstrapped):
            InboxStore(absent).send("Worker Alpha", "Must not create a DB")
        self.assertFalse(absent.exists())


if __name__ == "__main__":
    unittest.main()
