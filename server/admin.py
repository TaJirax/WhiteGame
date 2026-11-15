#!/usr/bin/env python3
"""List or revoke managed peers without putting the admin token in shell arguments."""
from __future__ import annotations

import argparse
import base64
import getpass
import ipaddress
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


class AdminError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


def normalize_server(value: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(value.strip())
        host, port = parsed.hostname, parsed.port
        if (parsed.scheme != "https" or not host or parsed.username is not None
                or parsed.password is not None or "?" in value or "#" in value
                or port is not None and not 1 <= port <= 65535
                or any(char.isspace() for char in value.strip())
                or "%" in parsed.path or any(part in (".", "..") for part in parsed.path.split("/"))):
            raise ValueError()
        try:
            address = ipaddress.ip_address(host)
            shown = f"[{address}]" if address.version == 6 else str(address)
        except ValueError:
            if len(host) > 253 or not all(re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label)
                                          for label in host.rstrip(".").split(".")):
                raise ValueError()
            shown = host.lower()
        authority = shown + (f":{port}" if port not in (None, 443) else "")
        return urllib.parse.urlunsplit(("https", authority, parsed.path.rstrip("/"), "", ""))
    except ValueError as error:
        raise AdminError("Use an HTTPS base URL without credentials, query parameters or a fragment") from error


def validate_public_key(value: str) -> str:
    try:
        raw = base64.b64decode(value, validate=True)
        if len(raw) != 32 or not any(raw) or base64.b64encode(raw).decode("ascii") != value:
            raise ValueError()
    except (ValueError, TypeError) as error:
        raise AdminError("Enter a canonical 32-byte base64 public key") from error
    return value


def request(server: str, token: str, key: str | None = None, open_url=None):
    base = normalize_server(server)
    if len(token) < 32 or not token.isascii() or any(not 33 <= ord(char) <= 126 for char in token):
        raise AdminError("Enter the server's admin token")
    path = "/v1/peers"
    if key is not None:
        path += "/" + urllib.parse.quote(validate_public_key(key), safe="")
    operation = urllib.request.Request(base + path, method="DELETE" if key is not None else "GET")
    operation.add_header("Authorization", "Bearer " + token)
    operation.add_header("Accept", "application/json")
    if open_url is None:
        open_url = urllib.request.build_opener(NoRedirect).open
    try:
        with open_url(operation, timeout=30) as response:
            raw = response.read(4 * 1024 * 1024 + 1)
            if len(raw) > 4 * 1024 * 1024:
                raise AdminError("Admin response is too large")
            result = json.loads(raw)
            if (not isinstance(result, dict) or
                    (key is None and not isinstance(result.get("peers"), list)) or
                    (key is not None and result.get("revoked") is not True)):
                raise AdminError("Server returned an invalid admin response")
            return result
    except urllib.error.HTTPError as error:
        # Never echo an error body; it may contain server diagnostics or credentials.
        if 300 <= error.code <= 399:
            raise AdminError("Server redirected the request; use its final HTTPS base URL") from error
        raise AdminError(f"Admin request failed (HTTP {error.code})") from error
    except (urllib.error.URLError, OSError, ValueError) as error:
        raise AdminError("Admin request failed; check the server URL, certificate and connection") from error


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True, help="HTTPS provisioning base URL")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("list", help="List managed peers")
    revoke = commands.add_parser("revoke", help="Remove one managed peer")
    revoke.add_argument("public_key", help="WireGuard public key to remove")
    arguments = parser.parse_args(argv)
    try:
        server = normalize_server(arguments.server)
        key = validate_public_key(arguments.public_key) if arguments.command == "revoke" else None
        token = getpass.getpass("Admin token: ")
        result = request(server, token, key)
        print(json.dumps(result, indent=2, ensure_ascii=True))
        return 0
    except (AdminError, EOFError, KeyboardInterrupt) as error:
        print(str(error) if isinstance(error, AdminError) else "Admin request cancelled", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
