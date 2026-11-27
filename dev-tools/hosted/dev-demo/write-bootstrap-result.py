import argparse
import json
import sys
import uuid
from pathlib import Path

RESULT_PREFIX = "DEV_DEMO_BOOTSTRAP_RESULT="
SESSION_IDS = ("id", "tenantId", "gameTemplateId", "ownerAccountId")


def validate_bootstrap_payload(intent, account_id, result):
    if not isinstance(account_id, str):
        raise TypeError
    parsed_account_id = uuid.UUID(account_id)
    if parsed_account_id.int == 0 or str(parsed_account_id) != account_id:
        raise ValueError
    if (
        result["accountUuid"] != account_id
        or result["controlPlaneRequestId"] != intent["controlPlaneRequestId"]
    ):
        raise ValueError
    session = {key: result["gameSession"][key] for key in SESSION_IDS}
    if any(type(value) is not int or value <= 0 for value in session.values()):
        raise ValueError
    if any(session[key] != intent[key] for key in ("tenantId", "gameTemplateId")):
        raise ValueError
    return session


def read_verified_receipt(intent_path, result_path):
    intent = json.loads(intent_path.read_text(encoding="utf-8"))
    receipt = json.loads(result_path.read_text(encoding="utf-8"))
    if receipt["schema"] != "dev-demo-bootstrap-result/v1" or receipt["intent"] != intent:
        raise ValueError
    if intent["schema"] != "dev-demo-bootstrap-intent/v1":
        raise ValueError
    account_id = receipt["accountUuid"]
    session = validate_bootstrap_payload(intent, account_id, {
        "accountUuid": account_id,
        "controlPlaneRequestId": intent["controlPlaneRequestId"],
        "gameSession": receipt["gameSession"],
    })
    if intent["controlPlaneRequestId"] != f"dev-demo-smoke-bootstrap-{intent['runId']}-{intent['attempt']}":
        raise ValueError
    return intent, account_id, session


def verify_receipt(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("intent", type=Path)
    parser.add_argument("result", type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--attempt", required=True)
    parser.add_argument("--target-sha", required=True)
    parser.add_argument("--namespace-uid", required=True)
    parser.add_argument("--helm-revision", required=True)
    parser.add_argument("--account-uuid", required=True)
    parser.add_argument("--game-session-id", required=True, type=int)
    args = parser.parse_args(argv)
    try:
        intent, account_id, session = read_verified_receipt(args.intent, args.result)
        if (
            intent["runId"] != args.run_id
            or intent["attempt"] != args.attempt
            or intent["headSha"] != args.target_sha
            or intent["namespace"] != "dev"
            or intent["namespaceUid"] != args.namespace_uid
            or intent["release"] != "dev"
            or intent["helmRevision"] != args.helm_revision
            or account_id != args.account_uuid
            or session["id"] != args.game_session_id
        ):
            raise ValueError
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError):
        print("bootstrap receipt refused")
        return 1
    print("bootstrap receipt verified")
    return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "verify":
        raise SystemExit(verify_receipt(sys.argv[2:]))
    evidence_dir, account_id_file, pod_log = map(Path, sys.argv[1:])
    try:
        intent = json.loads((evidence_dir / "intent.json").read_text(encoding="utf-8"))
        account_id = account_id_file.read_text(encoding="utf-8")
        lines = [
            line.removeprefix(RESULT_PREFIX)
            for line in pod_log.read_text(encoding="utf-8").splitlines()
            if line.startswith(RESULT_PREFIX)
        ]
        (encoded_result,) = lines
        result = json.loads(encoded_result)
        session = validate_bootstrap_payload(intent, account_id, result)
    except (OSError, KeyError, TypeError, ValueError):
        raise SystemExit("Cannot verify the exact successful dev-demo bootstrap result") from None

    receipt = {
        "schema": "dev-demo-bootstrap-result/v1",
        "intent": intent,
        "accountUuid": account_id,
        "gameSession": session,
    }
    with (evidence_dir / "result.json").open("x", encoding="utf-8") as output:
        json.dump(receipt, output, indent=2)
        output.write("\n")


if __name__ == "__main__":
    main()
