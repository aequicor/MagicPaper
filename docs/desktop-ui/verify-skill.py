#!/usr/bin/env python3
"""Deterministic source/package checks for the MagicPaper desktop UI skill."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[2]
SKILL = ROOT / "skills" / "magicpaper-desktop-ui"
MANIFEST = SKILL / "skill-package.json"


def fail(message: str) -> None:
    raise SystemExit(f"FAIL: {message}")


def main() -> None:
    skill_text = (SKILL / "SKILL.md").read_text(encoding="utf-8")
    frontmatter = re.match(r"\A---\n(.*?)\n---\n", skill_text, re.DOTALL)
    if not frontmatter:
        fail("SKILL.md frontmatter is missing")
    header = frontmatter.group(1)
    required_header = {
        "name: magicpaper-desktop-ui",
        '  version: "1.0.0"',
        '  package-id: "magicpaper.desktop-ui"',
        "license: MIT",
    }
    missing_header = sorted(required_header - set(header.splitlines()))
    if missing_header:
        fail(f"frontmatter fields missing: {missing_header}")
    if "description:" not in header or "macOS and Windows" not in header:
        fail("frontmatter description does not route desktop UI tasks")
    if "TODO" in skill_text:
        fail("unfinished TODO in SKILL.md")

    manifest_bytes = MANIFEST.read_bytes()
    manifest = json.loads(manifest_bytes)
    if manifest["schemaVersion"] != 1 or manifest["id"] != "magicpaper.desktop-ui" or manifest["version"] != "1.0.0":
        fail("unexpected package identity")
    if manifest["license"] != "MIT" or manifest["compatibility"] != {
        "minHost": "1.0.0", "maxHostExclusive": "2.0.0", "platforms": ["desktop"]
    }:
        fail("unexpected license or host contract")

    declared = {item["path"]: item for item in manifest["files"]}
    actual = {
        str(path.relative_to(SKILL))
        for path in SKILL.rglob("*")
        if path.is_file() and path != MANIFEST
    }
    if set(declared) != actual:
        fail(f"manifest file set differs: declared={sorted(declared)}, actual={sorted(actual)}")
    for relative, item in declared.items():
        data = (SKILL / relative).read_bytes()
        if item["size"] != len(data) or item["sha256"] != hashlib.sha256(data).hexdigest():
            fail(f"manifest digest/size mismatch: {relative}")

    markdown_link = re.compile(r"\[[^]]+\]\(([^)]+)\)")
    for path in sorted(SKILL.rglob("*.md")):
        for target in markdown_link.findall(path.read_text(encoding="utf-8")):
            parsed = urlparse(target)
            if parsed.scheme:
                if parsed.scheme != "https" or not parsed.netloc:
                    fail(f"unsafe external link in {path.relative_to(SKILL)}: {target}")
            elif not (path.parent / target).resolve().is_file():
                fail(f"broken internal link in {path.relative_to(SKILL)}: {target}")

    required_rules = (
        "Feature and app modules use only the public `Paper*` design-system API",
        "Preserve product vocabulary, content, user data",
        "Do not impose WebView, React, Node, Rust, MVI, Hilt, Navigation 3",
        "If existing public API cannot express the required behavior, extend it narrowly",
    )
    for rule in required_rules:
        if rule not in skill_text:
            fail(f"required instruction missing: {rule}")
    agent = (SKILL / "agents" / "openai.yaml").read_text(encoding="utf-8")
    if "allow_implicit_invocation: true" not in agent or "$magicpaper-desktop-ui" not in agent:
        fail("implicit invocation metadata is missing")

    print(f"PASS: magicpaper.desktop-ui@1.0.0; files={len(actual)}; manifestSha256={hashlib.sha256(manifest_bytes).hexdigest()}")


if __name__ == "__main__":
    main()
