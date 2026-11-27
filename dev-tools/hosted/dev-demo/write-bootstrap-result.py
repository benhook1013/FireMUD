import json
import sys
import uuid
from pathlib import Path

RESULT_PREFIX = "DEV_DEMO_BOOTSTRAP_RESULT="
SESSION_IDS = ("id", "tenantId", "gameTemplateId", "ownerAccountId")


def main():
    evidence_dir, account_id_file, pod_log = map(Path, sys.argv[1:])
    try:
        intent = json.loads((evidence_dir / "intent.json").read_text(encoding="utf-8"))
        account_id = account_id_file.read_text(encoding="utf-8")
        parsed_account_id = uuid.UUID(account_id)
        if parsed_account_id.int == 0 or str(parsed_account_id) != account_id:
            raise ValueError
        lines = [
            line.removeprefix(RESULT_PREFIX)
            for line in pod_log.read_text(encoding="utf-8").splitlines()
            if line.startswith(RESULT_PREFIX)
        ]
        (encoded_result,) = lines
        result = json.loads(encoded_result)
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
