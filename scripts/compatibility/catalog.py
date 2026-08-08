#!/usr/bin/env python3
"""Small, dependency-free helpers for the Zhihu compatibility sentinel."""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


CHANNEL_NAMES = {
    "domestic": "国内版",
    "play": "Google Play 版",
}
PLAY_VERSION_PATTERN = re.compile(
    r'\[\[\["([^"\\]+)"\]\],\[\[\[36\]\]'
)
VERSION_NAME_PATTERN = re.compile(r"\d+(?:\.\d+){1,3}(?:[-+][0-9A-Za-z._-]+)?")


class CatalogError(ValueError):
    pass


def load_catalog(path: Path) -> dict[str, Any]:
    try:
        catalog = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CatalogError(f"cannot read catalog {path}: {error}") from error
    validate_catalog(catalog)
    return catalog


def validate_catalog(catalog: dict[str, Any]) -> None:
    if not isinstance(catalog, dict):
        raise CatalogError("catalog must be an object")
    if catalog.get("schemaVersion") != 1:
        raise CatalogError("schemaVersion must be 1")
    if catalog.get("targetPackage") != "com.zhihu.android":
        raise CatalogError("targetPackage must be com.zhihu.android")
    revision = catalog.get("revision")
    if not isinstance(revision, int) or isinstance(revision, bool) or revision <= 0:
        raise CatalogError("revision must be a positive integer")
    profiles = catalog.get("profiles")
    if not isinstance(profiles, list):
        raise CatalogError("profiles must be an array")

    ids: set[str] = set()
    for profile in profiles:
        if not isinstance(profile, dict):
            raise CatalogError("every profile must be an object")
        profile_id = profile.get("id")
        if not isinstance(profile_id, str) or not profile_id:
            raise CatalogError("profile id must be a non-empty string")
        if profile_id in ids:
            raise CatalogError(f"duplicate profile id: {profile_id}")
        ids.add(profile_id)
        if profile.get("channel") not in CHANNEL_NAMES:
            raise CatalogError(f"invalid channel in {profile_id}")
        if not isinstance(profile.get("versionName"), str):
            raise CatalogError(f"invalid versionName in {profile_id}")
        minimum = profile.get("minVersionCode")
        maximum = profile.get("maxVersionCode")
        if (not isinstance(minimum, int) or isinstance(minimum, bool)
                or not isinstance(maximum, int) or isinstance(maximum, bool)
                or minimum < 0 or maximum < minimum):
            raise CatalogError(f"invalid version range in {profile_id}")
        if profile.get("status") != "adapted":
            raise CatalogError(f"unsupported profile status in {profile_id}")
        if not isinstance(profile.get("symbols"), dict):
            raise CatalogError(f"symbols must be an object in {profile_id}")


def is_adapted(catalog: dict[str, Any], channel: str,
               version_name: str | None = None,
               version_code: int | None = None) -> bool:
    if channel not in CHANNEL_NAMES:
        raise CatalogError(f"unsupported channel: {channel}")
    if version_name is None and version_code is None:
        raise CatalogError("version name or version code is required")
    for profile in catalog["profiles"]:
        if profile["channel"] != channel or profile["status"] != "adapted":
            continue
        if version_name is not None and profile["versionName"] != version_name:
            continue
        if version_code is not None and not (
                profile["minVersionCode"] <= version_code <= profile["maxVersionCode"]):
            continue
        return True
    return False


def parse_play_version(html: str) -> str:
    versions = {
        match.group(1)
        for match in PLAY_VERSION_PATTERN.finditer(html)
        if VERSION_NAME_PATTERN.fullmatch(match.group(1))
    }
    if not versions:
        raise CatalogError("Google Play version field was not found")
    if len(versions) != 1:
        raise CatalogError(
            "Google Play page contains ambiguous version fields: "
            + ", ".join(sorted(versions))
        )
    return versions.pop()


def next_revision(current: int, requested: int | None = None) -> int:
    candidate = requested
    if candidate is None:
        candidate = int(datetime.now(timezone.utc).strftime("%Y%m%d%H"))
    return max(current + 1, candidate)


def add_profile(catalog: dict[str, Any], channel: str, version_name: str,
                version_code: int, requested_revision: int | None = None) -> dict[str, Any]:
    validate_catalog(catalog)
    if channel not in CHANNEL_NAMES:
        raise CatalogError(f"unsupported channel: {channel}")
    if not VERSION_NAME_PATTERN.fullmatch(version_name):
        raise CatalogError(f"invalid version name: {version_name}")
    if version_code <= 0:
        raise CatalogError("version code must be positive")
    if is_adapted(catalog, channel, version_name, version_code):
        raise CatalogError(f"{channel} {version_name} ({version_code}) is already adapted")

    profile_id = f"{channel}-{version_name}-{version_code}"
    catalog["profiles"].append({
        "id": profile_id,
        "channel": channel,
        "displayName": CHANNEL_NAMES[channel],
        "versionName": version_name,
        "minVersionCode": version_code,
        "maxVersionCode": version_code,
        "status": "adapted",
        # Passing the full hook suite with an unknown version means the shared
        # defaults/structural resolvers were sufficient. Never invent symbols.
        "symbols": {},
    })
    catalog["revision"] = next_revision(catalog["revision"], requested_revision)
    validate_catalog(catalog)
    return catalog


def write_catalog(path: Path, catalog: dict[str, Any]) -> None:
    path.write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    check = subparsers.add_parser("is-adapted")
    check.add_argument("--catalog", type=Path, required=True)
    check.add_argument("--channel", choices=CHANNEL_NAMES, required=True)
    check.add_argument("--version-name")
    check.add_argument("--version-code", type=int)

    play = subparsers.add_parser("parse-play-version")
    play.add_argument("--html", type=Path, required=True)

    add = subparsers.add_parser("add-profile")
    add.add_argument("--catalog", type=Path, required=True)
    add.add_argument("--channel", choices=CHANNEL_NAMES, required=True)
    add.add_argument("--version-name", required=True)
    add.add_argument("--version-code", type=int, required=True)
    add.add_argument("--revision", type=int)

    validate = subparsers.add_parser("validate")
    validate.add_argument("--catalog", type=Path, required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "parse-play-version":
            html = args.html.read_text(encoding="utf-8")
            print(parse_play_version(html))
            return 0

        catalog = load_catalog(args.catalog)
        if args.command == "is-adapted":
            print("true" if is_adapted(
                catalog, args.channel, args.version_name, args.version_code
            ) else "false")
        elif args.command == "add-profile":
            add_profile(
                catalog, args.channel, args.version_name,
                args.version_code, args.revision,
            )
            write_catalog(args.catalog, catalog)
        elif args.command == "validate":
            print("valid")
        return 0
    except (CatalogError, OSError, UnicodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
