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
            for operation in (lambda worker=worker: self.store.send(worker, "Message"),
                              lambda worker=worker: self.store.send("General", "Message", author=worker),
                              lambda worker=worker: self.store.list(worker), lambda worker=worker: self.store.unread_count(worker)):
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
        timestamps = iter((
            "2026-10-01T00:00:00Z",
            "2026-10-01T00:01:00Z",
            "2026-10-01T00:02:00Z",
            "2026-10-01T00:03:00Z",
        ))
        with patch("fire_controller.inbox._now", side_effect=lambda: next(timestamps)):
            root = self.store.send("General", "Original request", author="Overseer", job="merge-train", pr=2898)
            first_reply = self.store.send("Overseer", "First response", author="General", reply_to=root["id"])
            sibling_reply = self.store.send("Gameplay", "Parallel response", author="Review", reply_to=root["id"])
            nested_reply = self.store.send("General", "Follow-up", author="Overseer", reply_to=first_reply["id"])

        thread = self.store.thread(nested_reply["id"])
        self.assertEqual([message["id"] for message in thread], [
            root["id"], first_reply["id"], sibling_reply["id"], nested_reply["id"],
        ])
        self.assertEqual([message["recipient"] for message in thread], ["General", "Overseer", "Gameplay", "General"])
        self.assertEqual((thread[0]["job"], thread[0]["pr"], thread[0]["body"]), ("merge-train", 2898, "Original request"))
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
        self.assertEqual([message["id"] for message in self.store.thread(sibling["id"])], [
            root["id"], reply["id"], sibling["id"],
        ])

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
            self.assertEqual(connection.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall(), [("unrelated",)])
        absent = self.root / "absent.sqlite3"
        with self.assertRaises(InboxNotBootstrapped):
            InboxStore(absent).send("Worker Alpha", "Must not create a DB")
        self.assertFalse(absent.exists())


if __name__ == "__main__":
    unittest.main()
