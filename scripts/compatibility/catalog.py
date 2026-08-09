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
SYMBOL_PATTERN = re.compile(r"[A-Za-z_$][A-Za-z0-9_.$]*$")
DEX_RULE_FIELDS = {
    "result", "searchPackages", "methodNames", "paramTypes", "returnType", "paramCount",
    "usingStrings", "invokes", "fieldNames", "fieldType",
    "minCandidates", "maxCandidates",
}


class CatalogError(ValueError):
    pass


def load_catalog(path: Path) -> dict[str, Any]:
    try:
        catalog = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CatalogError(f"cannot read catalog {path}: {error}") from error
    validate_catalog(catalog)
    return catalog


def _validate_string_list(value: Any, label: str, minimum: int, maximum: int,
                          symbols_only: bool) -> None:
    if not isinstance(value, list) or not minimum <= len(value) <= maximum:
        raise CatalogError(f"{label} must contain {minimum}..{maximum} items")
    for item in value:
        if (not isinstance(item, str) or not item or len(item) > 200
                or (symbols_only and not SYMBOL_PATTERN.fullmatch(item))):
            raise CatalogError(f"invalid value in {label}")


def _validate_scope(scope: Any, label: str, symbols_required: bool) -> None:
    if not isinstance(scope, dict):
        raise CatalogError(f"{label} must be an object")
    symbols = scope.get("symbols")
    if symbols_required and not isinstance(symbols, dict):
        raise CatalogError(f"symbols must be an object in {label}")
    if symbols is not None:
        if not isinstance(symbols, dict) or len(symbols) > 256:
            raise CatalogError(f"invalid symbols in {label}")
        for key, values in symbols.items():
            if not isinstance(key, str) or not SYMBOL_PATTERN.fullmatch(key):
                raise CatalogError(f"invalid symbol key in {label}")
            _validate_string_list(values, f"{label}.{key}", 0, 100, True)

    features = scope.get("features")
    if features is not None:
        if not isinstance(features, dict) or len(features) > 100:
            raise CatalogError(f"invalid features in {label}")
        for key, enabled in features.items():
            if (not isinstance(key, str) or not SYMBOL_PATTERN.fullmatch(key)
                    or not isinstance(enabled, bool)):
                raise CatalogError(f"invalid feature flag in {label}")

    rules = scope.get("dexRules")
    if rules is None:
        return
    if not isinstance(rules, dict) or len(rules) > 100:
        raise CatalogError(f"invalid dexRules in {label}")
    for key, rule in rules.items():
        if (not isinstance(key, str) or not SYMBOL_PATTERN.fullmatch(key)
                or not isinstance(rule, dict)
                or set(rule) - DEX_RULE_FIELDS):
            raise CatalogError(f"invalid DexKit rule in {label}")
        result_type = rule.get("result", "method")
        if result_type not in {"method", "ownerClass", "field", "fieldOwnerClass"}:
            raise CatalogError(f"invalid result in {label}.{key}")
        _validate_string_list(
            rule.get("searchPackages"), f"{label}.{key}.searchPackages", 1, 8, True)
        _validate_string_list(
            rule.get("methodNames", []), f"{label}.{key}.methodNames", 0, 16, True)
        _validate_string_list(
            rule.get("paramTypes", []), f"{label}.{key}.paramTypes", 0, 32, True)
        _validate_string_list(
            rule.get("usingStrings", []), f"{label}.{key}.usingStrings", 0, 16, False)
        _validate_string_list(
            rule.get("invokes", []), f"{label}.{key}.invokes", 0, 16, False)
        _validate_string_list(
            rule.get("fieldNames", []), f"{label}.{key}.fieldNames", 0, 16, True)
        return_type = rule.get("returnType", "")
        field_type = rule.get("fieldType", "")
        if (not isinstance(return_type, str)
                or (return_type and not SYMBOL_PATTERN.fullmatch(return_type))
                or not isinstance(field_type, str)
                or (field_type and not SYMBOL_PATTERN.fullmatch(field_type))):
            raise CatalogError(f"invalid type in {label}.{key}")
        param_count = rule.get("paramCount", -1)
        minimum = rule.get("minCandidates", 1)
        maximum = rule.get("maxCandidates", 1)
        method_rule = result_type in {"method", "ownerClass"}
        field_rule = result_type in {"field", "fieldOwnerClass"}
        has_param_types = "paramTypes" in rule
        if (not isinstance(param_count, int) or isinstance(param_count, bool)
                or not -1 <= param_count <= 32
                or not isinstance(minimum, int) or isinstance(minimum, bool)
                or not isinstance(maximum, int) or isinstance(maximum, bool)
                or minimum < 1 or maximum < minimum or maximum > 16
                or (has_param_types and param_count >= 0
                    and len(rule["paramTypes"]) != param_count)
                or (method_rule and (rule.get("fieldNames") or field_type))
                or (field_rule and (rule.get("methodNames") or has_param_types
                    or rule.get("usingStrings") or rule.get("invokes")
                    or return_type or param_count >= 0))):
            raise CatalogError(f"invalid bounds in {label}.{key}")
        if (method_rule and not rule.get("methodNames") and not has_param_types
                and not rule.get("usingStrings") and not rule.get("invokes")
                and not return_type and param_count < 0):
            raise CatalogError(f"unconstrained DexKit rule in {label}.{key}")
        if field_rule and not rule.get("fieldNames") and not field_type:
            raise CatalogError(f"unconstrained DexKit field rule in {label}.{key}")

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
    _validate_scope(catalog.get("defaults", {}), "defaults", False)
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
        _validate_scope(profile, profile_id, True)


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
