import json
import os
import subprocess
import time
import urllib.error
import urllib.request


def compose_postgres_container_name():
    compose_project_name = os.environ.get("COMPOSE_PROJECT_NAME", "docker")
    return f"{compose_project_name}-postgres-1"


def verify_smoke_account(account_api_base, tenant_id, username, password, timeout_seconds):
    payload = json.dumps(
        {
            "tenantId": int(tenant_id),
            "username": username,
            "password": password,
            "otp": "",
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        f"{account_api_base}/auth/login",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                body = response.read().decode("utf-8", errors="ignore").strip()
                print("=== Account validation response ===")
                print(body or "<empty>")
                if response.status >= 500:
                    raise RuntimeError(
                        f"Smoke account validation returned unexpected status {response.status}"
                    )
                return body
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="ignore").strip()
            print("=== Account validation response ===")
            print(body or "<empty>")
            raise RuntimeError(
                f"Smoke account validation failed with status {exc.code}: {body or '<empty>'}"
            ) from exc
        except OSError as exc:
            if attempt < 3:
                time.sleep(1)
                continue
            raise RuntimeError(f"Smoke account validation failed: {exc}") from exc


def wait_for_account_schema(startup_wait_seconds, timeout_seconds):
    deadline = time.time() + startup_wait_seconds
    query = "select to_regclass('account_service.accounts');"
    postgres_container = compose_postgres_container_name()
    while time.time() < deadline:
        try:
            table_name = subprocess.check_output(
                [
                    "docker",
                    "exec",
                    postgres_container,
                    "psql",
                    "-U",
                    "firemud",
                    "-d",
                    "firemud",
                    "-tAc",
                    query,
                ],
                text=True,
                timeout=timeout_seconds,
            ).strip()
            if table_name == "account_service.accounts":
                print("Confirmed account schema is ready.")
                return
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
        time.sleep(2)
    raise RuntimeError("Account schema readiness did not converge before smoke execution")


def http_readiness_up(readiness_url, timeout_seconds):
    try:
        with urllib.request.urlopen(readiness_url, timeout=timeout_seconds) as response:
            body = response.read().decode("utf-8", errors="ignore")
            return response.status < 500 and "\"status\":\"UP\"" in body.replace(" ", "")
    except (urllib.error.URLError, OSError):
        return False


def wait_for_http_readiness(name, base_url, startup_wait_seconds, timeout_seconds):
    deadline = time.time() + startup_wait_seconds
    readiness_url = f"{base_url}/actuator/health/readiness"
    while time.time() < deadline:
        if http_readiness_up(readiness_url, timeout_seconds):
            print(f"Confirmed {name} readiness via {readiness_url}.")
            return
        time.sleep(2)
    raise RuntimeError(f"{name} readiness did not report UP at {readiness_url}")


def run_command_plan(steps, executor):
    for step in steps:
        if len(step) == 3:
            line, expected_substrings, label = step
            timeout = None
        elif len(step) == 4:
            line, expected_substrings, label, timeout = step
        else:
            raise ValueError(f"Unsupported command-plan step: {step!r}")
        executor(line, expected_substrings, label, timeout)


def gameplay_item_container_equipment_steps(
    username,
    password,
    worlds_expect,
    login_expect,
    play_expect,
    look_expect,
    look_timeout=None,
):
    steps = [
        ("WORLDS", [worlds_expect], "WORLDS"),
        (f"LOGIN {username} {password}", [login_expect], "LOGIN"),
        ("PLAY demo", [play_expect], "PLAY"),
        ("LOOK", [look_expect], "LOOK"),
        ("INV HERE", ["Room Inventory:", "Torch", "Backpack"], "INV HERE"),
        ("GET Torch", ["You pick up Torch.", "Inventory:", "Torch"], "GET"),
        (
            "CONTAINER Backpack",
            ["Container: Backpack [backpack#1]", "Ration"],
            "CONTAINER",
        ),
        (
            "PUT Torch INTO Backpack",
            ["You put Torch into Backpack.", "Container: Backpack [backpack#1]", "Torch"],
            "PUT",
        ),
        (
            "TAKE Torch FROM Backpack",
            ["You take Torch from Backpack.", "Container: Backpack [backpack#1]", "Ration"],
            "TAKE",
        ),
        ("DROP Torch", ["You drop Torch."], "DROP"),
        ("INV HERE", ["Room Inventory:", "Torch", "Backpack"], "INV HERE after DROP"),
        ("EQUIPMENT", ["You have nothing equipped."], "EQUIPMENT empty"),
        ("WEAR Leather Cap", ["You wear Leather Cap."], "WEAR"),
        ("EQUIPMENT", ["Equipment:", "HEAD", "Leather Cap"], "EQUIPMENT worn"),
        ("REMOVE HEAD", ["You remove Leather Cap."], "REMOVE"),
        ("EQUIPMENT", ["You have nothing equipped."], "EQUIPMENT empty again"),
        (
            "WEAR Iron Boots",
            ["ERROR SLOT_INCOMPATIBLE", "Iron Boots cannot be worn by this body layout"],
            "WEAR incompatible",
        ),
    ]
    if look_timeout is None:
        return steps
    return [
        (line, expected, label, look_timeout)
        if label in {"LOOK", "REMOVE", "WEAR incompatible"}
        else (line, expected, label)
        for (line, expected, label) in steps
    ]
