#!/usr/bin/env python3
"""Codex Stop hook that runs spec-self-eval for touched feature specs."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPORT_RE = re.compile(r"eval-report-\d{4}-\d{2}-\d{2}(?:-\d+)?\.md$", re.IGNORECASE)
BRACKET_FAIL_RE = re.compile(r"\[FAIL\]", re.IGNORECASE)


@dataclass(frozen=True)
class Failure:
    feature: str
    item: str
    evidence: str
    report: Path


def emit(payload: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(payload, ensure_ascii=False))


def run_git(repo: Path, *args: str) -> list[str]:
    result = subprocess.run(
        ["git", *args],
        cwd=repo,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if result.returncode != 0:
        return []
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def repo_root(cwd: Path) -> Path:
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        cwd=cwd,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if result.returncode == 0 and result.stdout.strip():
        return Path(result.stdout.strip()).resolve()
    return cwd.resolve()


def normalize_repo_path(path: str) -> str:
    return path.replace("\\", "/").lstrip("/")


def feature_from_repo_path(path: str) -> str | None:
    normalized = normalize_repo_path(path)
    parts = normalized.split("/")
    if len(parts) < 3 or parts[0] != ".specs":
        return None
    if parts[1].startswith("_"):
        return None
    if REPORT_RE.match(parts[-1]):
        return None
    return parts[1]


def dirty_spec_features(repo: Path) -> set[str]:
    features: set[str] = set()
    status_lines = run_git(repo, "status", "--porcelain=v1", "-uall", "--", ".specs")
    for line in status_lines:
        # Porcelain v1 format is "XY path" or "XY old -> new".
        path_part = line[3:] if len(line) > 3 else line
        for candidate in path_part.split(" -> "):
            feature = feature_from_repo_path(candidate.strip().strip('"'))
            if feature:
                features.add(feature)
    return features


def transcript_spec_features(payload: dict[str, Any], repo: Path) -> set[str]:
    transcript = payload.get("transcript_path")
    if not transcript:
        return set()

    path = Path(transcript)
    if not path.exists():
        return set()

    turn_id = payload.get("turn_id")
    features: set[str] = set()

    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return set()

    for raw in lines:
        if turn_id and turn_id not in raw:
            continue
        try:
            obj: Any = json.loads(raw)
            text = json.dumps(obj, ensure_ascii=False)
        except json.JSONDecodeError:
            text = raw

        for match in re.finditer(r"(?:^|[\"'\s])(\.specs[\\/][^\"'\s]+)", text):
            feature = feature_from_repo_path(match.group(1))
            if feature:
                features.add(feature)

        repo_text = str(repo).replace("\\", r"\\")
        abs_pattern = re.escape(repo_text) + r"[\\/]\.specs[\\/][^\"'\s]+"
        for match in re.finditer(abs_pattern, text):
            rel = Path(match.group(0)).resolve()
            try:
                feature = feature_from_repo_path(str(rel.relative_to(repo)))
            except ValueError:
                feature = None
            if feature:
                features.add(feature)

    return features


def touched_features(payload: dict[str, Any], repo: Path) -> set[str]:
    features = transcript_spec_features(payload, repo)
    features.update(dirty_spec_features(repo))
    return features


def latest_report(feature_dir: Path) -> Path | None:
    reports = [p for p in feature_dir.glob("eval-report-*.md") if REPORT_RE.match(p.name)]
    if not reports:
        return None
    return max(reports, key=lambda p: (p.stat().st_mtime_ns, p.name))


def run_spec_self_eval(repo: Path, feature: str, timeout: int) -> tuple[bool, str]:
    prompt = (
        "Use the spec-self-eval skill. "
        f"Evaluate feature `{feature}` under `.specs/{feature}/`. "
        "Follow the skill output rules exactly."
    )
    command = [
        "codex",
        "exec",
        "--cd",
        str(repo),
        "--disable",
        "hooks",
        "--disable",
        "codex_hooks",
        prompt,
    ]
    env = os.environ.copy()
    env["CODEX_SPEC_SELF_EVAL_HOOK_CHILD"] = "1"

    result = subprocess.run(
        command,
        cwd=repo,
        env=env,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )
    if result.returncode == 0:
        return True, result.stdout.strip()

    detail = (result.stderr or result.stdout).strip()
    return False, detail or f"codex exec exited with status {result.returncode}"


def split_markdown_row(line: str) -> list[str]:
    stripped = line.strip()
    if not stripped.startswith("|") or not stripped.endswith("|"):
        return []
    return [part.strip() for part in stripped.strip("|").split("|")]


def parse_failures(report: Path, feature: str) -> list[Failure]:
    text = report.read_text(encoding="utf-8", errors="replace")
    failures: list[Failure] = []

    for line in text.splitlines():
        cells = split_markdown_row(line)
        if len(cells) >= 4 and cells[2].strip().upper() == "FAIL":
            failures.append(Failure(feature, cells[1], cells[3], report))
            continue

        if BRACKET_FAIL_RE.search(line):
            failures.append(Failure(feature, line.strip("- "), "", report))

    return failures


def block_reason(failures: list[Failure]) -> str:
    by_report = sorted(failures, key=lambda failure: (failure.feature, str(failure.report), failure.item))
    lines = [
        "spec-self-eval found FAIL items. Fix the spec before ending the turn.",
        "",
    ]
    for failure in by_report[:20]:
        rel_report = failure.report.as_posix()
        item = failure.item or "FAIL item"
        evidence = f" Evidence: {failure.evidence}" if failure.evidence else ""
        lines.append(f"- .specs/{failure.feature}/: {item}.{evidence} Report: {rel_report}")

    if len(by_report) > 20:
        lines.append(f"- ...and {len(by_report) - 20} more FAIL item(s).")

    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--print-touched", action="store_true")
    args = parser.parse_args()

    try:
        payload = json.loads(sys.stdin.read() or "{}")
    except json.JSONDecodeError as exc:
        emit({"decision": "block", "reason": f"spec-self-eval hook received invalid JSON: {exc}"})
        return 0

    repo = repo_root(Path(payload.get("cwd") or os.getcwd()))
    features = sorted(touched_features(payload, repo))

    if args.print_touched:
        for feature in features:
            print(feature)
        return 0

    if not features:
        emit({"continue": True})
        return 0

    all_failures: list[Failure] = []
    run_errors: list[str] = []

    for feature in features:
        feature_dir = repo / ".specs" / feature
        if not feature_dir.is_dir():
            continue

        ok, detail = run_spec_self_eval(repo, feature, args.timeout)
        if not ok:
            run_errors.append(f".specs/{feature}/: {detail}")
            continue

        report = latest_report(feature_dir)
        if not report:
            run_errors.append(f".specs/{feature}/: spec-self-eval did not create an eval report")
            continue

        all_failures.extend(parse_failures(report, feature))

    if run_errors:
        emit(
            {
                "decision": "block",
                "reason": "spec-self-eval hook could not complete:\n- " + "\n- ".join(run_errors),
            }
        )
        return 0

    if all_failures:
        emit({"decision": "block", "reason": block_reason(all_failures)})
        return 0

    emit({"continue": True})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
