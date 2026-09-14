#!/usr/bin/env python3
"""Freeze public library/consumer inputs and reject both path and byte drift.

Discovery is independent of the previous manifest. sbt's verifySourceIdentities
additionally checks its actual Compile / sources and records each profile's
selected input set. Generated targets and editor-only metals.sbt are excluded.
"""

import argparse
import hashlib
from pathlib import Path
import re


MAIN_TREES = tuple(
    f"modules/{module}/{platform}/src/main"
    for module in ("core", "node-common")
    for platform in ("shared", "jvm", "js")
) + (
    "modules/node-jvm/src/main",
    "release-conformance/shared",
    "release-conformance/jvm/src/main",
    "release-conformance/js/src/main",
    "release-conformance/export-jvm",
    "release-conformance/tools",
    "release-conformance/fixtures",
)
CONFIGURATION = (
    ".scalafmt.conf",
    ".scalafix.conf",
    "modules/core/js/yarn.lock",
    "modules/node-common/js/yarn.lock",
    "release-conformance/package.json",
    "release-conformance/package-lock.json",
    "release-conformance/run-conformance.sh",
    "project/build.properties",
    "release-conformance/project/build.properties",
)
GENERATED_DIRECTORIES = {"target", ".scala-build", ".bsp", ".bloop", ".metals", "__pycache__"}


def discover(root):
    selected = {root / name for name in CONFIGURATION}
    for directory in MAIN_TREES:
        selected.update(
            path for path in (root / directory).rglob("*")
            if path.is_file() and GENERATED_DIRECTORIES.isdisjoint(path.relative_to(root).parts)
            and path.name != ".DS_Store"
        )
    for directory in (".", "project", "release-conformance", "release-conformance/project"):
        for pattern in ("*.sbt", "*.scala"):
            selected.update(
                path for path in (root / directory).glob(pattern)
                if path.name != "metals.sbt"
            )
    return sorted(path.relative_to(root).as_posix() for path in selected)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(root, manifest):
    rows = manifest.read_text().splitlines()
    if any(not re.fullmatch(r"[0-9a-f]{64}  .+", row) for row in rows):
        raise ValueError("malformed source inventory row")
    paths = [row.split("  ", 1)[1] for row in rows]
    expected = discover(root)
    if paths != sorted(set(paths)):
        raise ValueError("source inventory paths must be sorted and unique")
    if paths != expected:
        raise ValueError(
            f"source inventory path mismatch: missing={sorted(set(expected) - set(paths))}; "
            f"unexpected={sorted(set(paths) - set(expected))}"
        )
    changed = [path for row, path in zip(rows, paths)
               if not (root / path).is_file() or sha256(root / path) != row[:64]]
    if changed:
        raise ValueError(f"source inventory content mismatch: {changed}")
    print(f"PASS: {len(paths)} discovered public source/configuration/fixture identities")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--manifest", default="docs/conformance/public-conformance-inputs.sha256")
    parser.add_argument("--write", action="store_true", help="explicitly freeze the current input set")
    args = parser.parse_args()
    root = args.root.resolve()
    manifest = root / args.manifest
    try:
        if args.write:
            manifest.write_text("".join(f"{sha256(root / name)}  {name}\n" for name in discover(root)))
        verify(root, manifest)
    except (OSError, ValueError) as error:
        parser.exit(1, f"FAIL: {error}\n")


if __name__ == "__main__":
    main()
