#!/usr/bin/env python3
"""Verify and restore the source release into a new directory (Python 3.12+)."""
import argparse
import copy
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import sys
import tarfile
import zipfile


def checked_path(root, relative):
    name = PurePosixPath(relative)
    if name.is_absolute() or ".." in name.parts or "\\" in relative or ":" in relative:
        raise ValueError(f"Unsafe source path: {relative}")
    path = (root / str(name)).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError(f"Source path leaves destination: {relative}")
    return path


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def restore(bundle, destination, verify_only=False):
    manifest = json.loads((bundle / "native-source-manifest.json").read_text(encoding="utf-8"))
    entries = [manifest["wrapper"], *manifest["dependencies"], *manifest.get("data_files", [])]
    # Verify the complete closure before writing any source files.
    for entry in entries:
        archive = checked_path(bundle / "source-archives", entry["archive_name"])
        if sha256(archive) != entry["sha256"]:
            raise ValueError(f"Checksum mismatch: {entry['archive_name']}")
    if verify_only:
        print(f"Verified {len(entries)} source inputs.")
        return
    if destination.exists():
        raise FileExistsError("Choose a new destination directory; existing files are never overwritten")
    destination.mkdir(parents=True)
    wrapper = checked_path(bundle / "source-archives", manifest["wrapper"]["archive_name"])
    with zipfile.ZipFile(wrapper) as archive:
        for entry in archive.infolist():
            target = checked_path(destination, entry.filename)
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Unexpected symlink in the wrapper archive")
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(entry) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)
            mode = (entry.external_attr >> 16) & 0o777
            if mode:
                target.chmod(mode)
    for dependency in manifest["dependencies"]:
        target = checked_path(destination, dependency["extract_to"])
        target.mkdir(parents=True, exist_ok=True)
        source = checked_path(bundle / "source-archives", dependency["archive_name"])
        strip = dependency.get("strip_components", 1)
        with tarfile.open(source, "r:*") as archive:
            members = []
            for entry in archive:
                original = PurePosixPath(entry.name)
                if original.is_absolute() or ".." in original.parts or "\\" in entry.name:
                    raise ValueError(f"Unsafe archive member: {entry.name}")
                parts = original.parts[strip:]
                if not parts:
                    continue
                entry = copy.copy(entry)
                entry.name = str(PurePosixPath(*parts))
                checked_path(target, entry.name)
                if entry.islnk():
                    entry.linkname = str(PurePosixPath(*PurePosixPath(entry.linkname).parts[strip:]))
                members.append(entry)
            archive.extractall(target, members=members, filter="data")
    for entry in manifest.get("data_files", []):
        target = checked_path(destination, entry["copy_to"])
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(checked_path(bundle / "source-archives", entry["archive_name"]), target)
    print(f"Restored wrapper and {len(manifest['dependencies'])} dependency sources to {destination}")
    print("Use the matching NDK and build instructions; no native build is performed by this script.")


if __name__ == "__main__":
    if sys.version_info < (3, 12):
        raise SystemExit("Python 3.12 or newer is required for safe archive extraction")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    restore(args.bundle.resolve(), args.destination.resolve(), args.verify_only)
