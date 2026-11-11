"""Start the persistent synthetic ciphertext receiver on literal loopback only.

Requires an explicit storage directory and SIDECAR_TRANSFER_TOKEN. The fixed
development Binding is not a production identity or device attestation. No
capture, decryption, external model or public deployment is enabled here.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path

import uvicorn

from .transfer_contract import Binding
from .transfer_http import _token, create_receiver_app
from .transfer_receiver import Receiver


HOST = "127.0.0.1"
DEFAULT_PORT = 8767
TOKEN_ENV = "SIDECAR_TRANSFER_TOKEN"
SYNTHETIC_BINDING = Binding("alice", "local", "synthetic-device", 1,
                            "development", "opaque-receiver", "local-key")


def _port(value):
    try:
        number = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("port must be an integer in 1024..65535") from exc
    if not 1024 <= number <= 65535:
        raise argparse.ArgumentTypeError("port must be 1024..65535")
    return number


def _root(value):
    if not isinstance(value, str) or not value.strip():
        raise argparse.ArgumentTypeError("an explicit persistent directory is required")
    return Path(value)


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--synthetic-only", action="store_true", required=True,
                        help="acknowledge that only synthetic material may be used")
    result.add_argument("--root", type=_root, required=True,
                        help="explicit persistent receiver directory")
    result.add_argument("--port", type=_port, default=DEFAULT_PORT)
    return result


def create_server(root, token):
    """Return the real Receiver and application; caller owns Receiver.close().

    Validate with the existing HTTP token validator before touching storage.
    The token is never printed, returned in status or persisted in the database.
    """
    checked_token = _token(token)
    directory = Path(root)
    if directory.exists() and not directory.is_dir():
        raise ValueError("root must be a persistent directory")
    receiver = Receiver(directory / "receiver.sqlite")
    try:
        application = create_receiver_app(receiver, {checked_token: SYNTHETIC_BINDING})
    except BaseException:
        receiver.close()
        raise
    return receiver, application


def main(argv=None):
    arguments = parser()
    args = arguments.parse_args(argv)
    token = os.environ.get(TOKEN_ENV, "")
    try:
        receiver, application = create_server(args.root, token)
    except ValueError as exc:
        # HTTP validation messages describe the requirement, never the token.
        arguments.error(str(exc))
    try:
        uvicorn.run(application, host=HOST, port=args.port, access_log=False,
                    ws="none", proxy_headers=False, workers=1)
    finally:
        receiver.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
