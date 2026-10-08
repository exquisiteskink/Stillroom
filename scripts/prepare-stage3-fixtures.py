#!/usr/bin/env python3
"""Official Grocy API/web setup for disposable synthetic accounts. Never print keys."""
import http.cookiejar
import json
import os
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from pathlib import Path


class KeyParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.keys = {}

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if "data-apikey-id" in values and "data-apikey-key" in values:
            self.keys[int(values["data-apikey-id"])] = values["data-apikey-key"]


def request(base, path, key, method="GET", body=None):
    headers = {"GROCY-API-KEY": key, "Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(base + "/api" + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            payload = response.read()
            return response.status, json.loads(payload) if payload else None
    except urllib.error.HTTPError as error:
        payload = error.read()
        return error.code, json.loads(payload) if payload else None


def mint_key(base, username, password, description):
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    login = urllib.parse.urlencode({"username": username, "password": password}).encode()
    with client.open(urllib.request.Request(base + "/login", data=login), timeout=15) as response:
        response.read()
    with client.open(base + "/manageapikeys/new?" + urllib.parse.urlencode({"description": description}), timeout=15) as response:
        key_id = int(urllib.parse.parse_qs(urllib.parse.urlsplit(response.url).query)["key"][0])
        parser = KeyParser()
        parser.feed(response.read().decode())
        api_key = parser.keys[key_id]
    with client.open(base + "/logout", timeout=15) as response:
        response.read()
    return key_id, api_key


def write_private(path, document):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as output:
        os.chmod(path, 0o600)
        json.dump(document, output)


def cleanup(document):
    failures = 0
    for fixture in document.get("fixtures", []):
        base, bootstrap = fixture["base_url"].removesuffix("/api"), fixture["bootstrap_key"]
        for key_id in fixture.get("key_ids", []):
            status, _ = request(base, f"/objects/api_keys/{key_id}", bootstrap, "DELETE")
            failures += status not in (200, 204)
        for user_id in fixture.get("user_ids", []):
            status, _ = request(base, f"/users/{user_id}", bootstrap, "DELETE")
            failures += status not in (200, 204)
        status, _ = request(base, f"/objects/api_keys/{fixture['bootstrap_key_id']}", bootstrap, "DELETE")
        failures += status not in (200, 204)
    if failures:
        raise RuntimeError("Could not revoke every disposable fixture credential.")


def prepare(path):
    token = secrets.token_hex(6)
    document = {"fixtures": []}
    write_private(path, document)
    summaries = []
    for port, expected_version in [(9283, "4.7.1"), (9284, "4.6.0")]:
        base = f"http://127.0.0.1:{port}"
        bootstrap_id, bootstrap = mint_key(base, "admin", "admin", "Stillroom Stage3 temporary setup " + token)
        fixture = {"base_url": base + "/api", "bootstrap_key": bootstrap, "bootstrap_key_id": bootstrap_id, "key_ids": [], "user_ids": []}
        document["fixtures"].append(fixture)
        write_private(path, document)
        status, info = request(base, "/system/info", bootstrap)
        assert status == 200 and info["grocy_version"]["Version"] == expected_version
        fixture["version"] = expected_version
        status, hierarchy = request(base, "/objects/permission_hierarchy", bootstrap)
        assert status == 200
        ids = {row["name"]: int(row["id"]) for row in hierarchy}
        for role, permissions in [("parent", ["ADMIN"]), ("child", ["CHORES", "CHORE_TRACK_EXECUTION"])]:
            username = f"stillroom_stage3_{role}_{token}"
            password = secrets.token_urlsafe(24)
            status, _ = request(base, "/users", bootstrap, "POST", {"username": username, "first_name": "Stage3", "last_name": role, "password": password, "picture_file_name": None})
            assert status in (200, 204)
            status, users = request(base, "/users", bootstrap)
            assert status == 200
            user_id = int(next(user["id"] for user in users if user["username"] == username))
            fixture["user_ids"].append(user_id)
            fixture[role + "_user_id"] = user_id
            fixture[role + "_username"] = username
            write_private(path, document)
            # Mint in this user's own web session, then replace default ADMIN before any test uses the key.
            key_id, key = mint_key(base, username, password, "Stillroom Stage3 temporary " + role + " " + token)
            fixture["key_ids"].append(key_id)
            fixture[role + "_key"] = key
            write_private(path, document)
            status, _ = request(base, f"/users/{user_id}/permissions", bootstrap, "PUT", {"permissions": [ids[name] for name in permissions]})
            assert status == 204
            status, grants = request(base, f"/users/{user_id}/permissions", bootstrap)
            assert status == 200 and {int(row["permission_id"]) for row in grants} == {ids[name] for name in permissions}
            status, current = request(base, "/user", key)
            assert status == 200 and len(current) == 1 and int(current[0]["id"]) == user_id
        status, forbidden = request(base, "/users", fixture["child_key"])
        assert status == 403 and forbidden["error_message"] == "Permission missing: USERS_READ"
        summaries.append({"version": expected_version, "parent_user_id": fixture["parent_user_id"], "child_user_id": fixture["child_user_id"],
                          "child_explicit_grants": ["CHORES", "CHORE_TRACK_EXECUTION"], "child_forbidden_status": status,
                          "child_forbidden_error": forbidden["error_message"]})
        write_private(path, document)
    Path("app/build/reports/stage3").mkdir(parents=True, exist_ok=True)
    Path("app/build/reports/stage3/fixture-checks.json").write_text(json.dumps(summaries, indent=2) + "\n")
    print("Prepared separate synthetic parent/child users on Grocy 4.7.1 and 4.6.0; grants and Stage 1 denial verified. Keys are private and temporary.")


if __name__ == "__main__":
    try:
        mode, path = sys.argv[1:]
        if mode == "prepare":
            prepare(path)
        elif mode == "cleanup":
            cleanup(json.loads(Path(path).read_text()))
            print("Revoked temporary fixture keys and removed temporary fixture users.")
        else:
            raise ValueError("Unknown mode")
    except Exception as error:
        print("Fixture operation failed (" + type(error).__name__ + "). No credentials are included in this message.", file=sys.stderr)
        sys.exit(1)
