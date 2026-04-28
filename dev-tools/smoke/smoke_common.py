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
