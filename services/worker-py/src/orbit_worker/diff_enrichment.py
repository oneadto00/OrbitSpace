from __future__ import annotations

import ast
import json
import os
import re
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

HUNK_RE = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$")

JS_PATTERNS: List[Tuple[str, re.Pattern[str]]] = [
    ("function", re.compile(r"^\s*(?:export\s+(?:default\s+)?)?(?:async\s+)?function\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*\(")),
    ("class", re.compile(r"^\s*(?:export\s+(?:default\s+)?)?(?:abstract\s+)?class\s+([A-Za-z_$][A-Za-z0-9_$]*)\b")),
    ("interface", re.compile(r"^\s*(?:export\s+)?(?:interface|type)\s+([A-Za-z_$][A-Za-z0-9_$]*)\b")),
    (
        "function",
        re.compile(
            r"^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][A-Za-z0-9_$]*)\s*=>"
        ),
    ),
]

JAVA_CLASS_RE = re.compile(
    r"^\s*(?:public|protected|private|abstract|final|sealed|non-sealed|static|\s)*\b(class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)\b"
)
JAVA_METHOD_RE = re.compile(
    r"^\s*(?:public|protected|private|static|final|synchronized|abstract|default|native|\s)+[A-Za-z0-9_<>\[\],.? ]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;]*\)\s*(?:throws\s+[A-Za-z0-9_.,\s]+)?\{"
)


@dataclass(frozen=True)
class SymbolSpan:
    name: str
    kind: str
    start_line: int
    end_line: int


def _parse_hunks(patch_text: str) -> Tuple[List[Tuple[int, int]], List[Tuple[int, int]]]:
    old_ranges: List[Tuple[int, int]] = []
    new_ranges: List[Tuple[int, int]] = []
    for line in patch_text.splitlines():
        match = HUNK_RE.match(line)
        if not match:
            continue
        old_start = int(match.group(1))
        old_count = int(match.group(2)) if match.group(2) is not None else 1
        new_start = int(match.group(3))
        new_count = int(match.group(4)) if match.group(4) is not None else 1
        old_end = old_start + max(1, old_count) - 1
        new_end = new_start + max(1, new_count) - 1
        old_ranges.append((old_start, old_end))
        new_ranges.append((new_start, new_end))
    return old_ranges, new_ranges


def _overlaps(span: SymbolSpan, ranges: List[Tuple[int, int]]) -> bool:
    for r_start, r_end in ranges:
        if span.start_line <= r_end and span.end_line >= r_start:
            return True
    return False


def _extract_python_symbols(source_text: str) -> List[SymbolSpan]:
    tree = ast.parse(source_text)
    spans: List[SymbolSpan] = []

    def visit(node: ast.AST, prefix: str = "") -> None:
        for child in ast.iter_child_nodes(node):
            if isinstance(child, ast.ClassDef):
                qualified = "{}.{}".format(prefix, child.name) if prefix else child.name
                spans.append(
                    SymbolSpan(
                        name=qualified,
                        kind="class",
                        start_line=int(child.lineno),
                        end_line=int(getattr(child, "end_lineno", child.lineno)),
                    )
                )
                visit(child, qualified)
            elif isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
                qualified = "{}.{}".format(prefix, child.name) if prefix else child.name
                spans.append(
                    SymbolSpan(
                        name=qualified,
                        kind="method" if prefix else "function",
                        start_line=int(child.lineno),
                        end_line=int(getattr(child, "end_lineno", child.lineno)),
                    )
                )

    visit(tree)
    return spans


def _extract_regex_symbols(source_text: str, lang: str) -> List[SymbolSpan]:
    lines = source_text.splitlines()
    spans: List[SymbolSpan] = []
    if lang == "js":
        for idx, line in enumerate(lines, start=1):
            for kind, pat in JS_PATTERNS:
                m = pat.match(line)
                if m:
                    spans.append(SymbolSpan(name=m.group(1), kind=kind, start_line=idx, end_line=idx + 1))
                    break
    elif lang == "java":
        for idx, line in enumerate(lines, start=1):
            cm = JAVA_CLASS_RE.match(line)
            if cm:
                spans.append(SymbolSpan(name=cm.group(2), kind=cm.group(1), start_line=idx, end_line=idx + 1))
                continue
            mm = JAVA_METHOD_RE.match(line)
            if mm and mm.group(1) not in {"if", "for", "while", "switch", "catch"}:
                spans.append(SymbolSpan(name=mm.group(1), kind="method", start_line=idx, end_line=idx + 1))
    return spans


def _extract_symbols_for_text(path: str, source_text: str) -> Tuple[List[SymbolSpan], bool]:
    ext = os.path.splitext(path)[1].lower()
    if ext == ".py":
        return _extract_python_symbols(source_text), False
    if ext in {".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs"}:
        return _extract_regex_symbols(source_text, "js"), False
    if ext == ".java":
        return _extract_regex_symbols(source_text, "java"), False
    return [], True


def _match_symbols_for_file(file_entry: Dict[str, Any]) -> Dict[str, Any]:
    path = str(file_entry.get("path", ""))
    patch_text = str(file_entry.get("patchText") or "")
    old_text = file_entry.get("oldText")
    new_text = file_entry.get("newText")
    old_ranges, new_ranges = _parse_hunks(patch_text)

    symbols_out: List[Dict[str, Any]] = []
    fallback_used = False

    try:
        old_spans: List[SymbolSpan] = []
        new_spans: List[SymbolSpan] = []
        if isinstance(old_text, str) and old_text:
            old_spans, fb_old = _extract_symbols_for_text(path, old_text)
            fallback_used = fallback_used or fb_old
        if isinstance(new_text, str) and new_text:
            new_spans, fb_new = _extract_symbols_for_text(path, new_text)
            fallback_used = fallback_used or fb_new

        if not old_spans and not new_spans and patch_text:
            # Fallback: extract hunk header context hints if full source wasn't provided.
            for line in patch_text.splitlines():
                m = HUNK_RE.match(line)
                if m and m.group(5).strip():
                    symbols_out.append(
                        {
                            "name": m.group(5).strip()[:120],
                            "kind": "hunk_context",
                            "oldStartLine": int(m.group(1)),
                            "newStartLine": int(m.group(3)),
                        }
                    )
            return {
                "path": path,
                "symbols": symbols_out,
                "fallbackUsed": True,
                "hunkCount": len(new_ranges),
            }

        old_by_key: Dict[Tuple[str, str], SymbolSpan] = {(s.kind, s.name): s for s in old_spans}
        new_by_key: Dict[Tuple[str, str], SymbolSpan] = {(s.kind, s.name): s for s in new_spans}
        seen_keys: List[Tuple[str, str]] = []
        for s in new_spans:
            k = (s.kind, s.name)
            if k not in seen_keys:
                seen_keys.append(k)
        for s in old_spans:
            k = (s.kind, s.name)
            if k not in seen_keys:
                seen_keys.append(k)

        for key in seen_keys:
            old_s = old_by_key.get(key)
            new_s = new_by_key.get(key)
            touched = False
            if new_s is not None and (not new_ranges or _overlaps(new_s, new_ranges)):
                touched = True
            if old_s is not None and (not old_ranges or _overlaps(old_s, old_ranges)):
                touched = True
            if touched:
                symbols_out.append(
                    {
                        "name": key[1],
                        "kind": key[0],
                        "oldStartLine": old_s.start_line if old_s is not None else None,
                        "newStartLine": new_s.start_line if new_s is not None else None,
                    }
                )
    except Exception:
        # Safe degradation to plain diff markers on parse errors; never fail the diff job.
        fallback_used = True
        symbols_out = []

    return {
        "path": path,
        "symbols": symbols_out,
        "fallbackUsed": fallback_used,
        "hunkCount": len(new_ranges),
    }


def enrich_diff_payload(raw_bytes: bytes, max_output_bytes: int) -> bytes:
    try:
        payload = json.loads(raw_bytes.decode("utf-8"))
    except Exception as exc:
        fallback = {"files": [], "fallbackUsed": True}
        encoded = json.dumps(fallback).encode("utf-8")
        if len(encoded) > max_output_bytes:
            raise ValueError("DIFF_OUTPUT_TOO_LARGE") from exc
        return encoded

    files_in = payload.get("files") if isinstance(payload, dict) else []
    if not isinstance(files_in, list):
        files_in = []

    enriched_files = [
        _match_symbols_for_file(item) for item in files_in if isinstance(item, dict)
    ]
    result = {
        "diffId": payload.get("diffId") if isinstance(payload, dict) else None,
        "files": enriched_files,
        "fallbackUsed": any(bool(f.get("fallbackUsed")) for f in enriched_files),
    }
    encoded = json.dumps(result, ensure_ascii=False).encode("utf-8")
    if len(encoded) > max_output_bytes:
        # Trim symbols safely rather than overflowing max_output_bytes.
        compact = {
            "diffId": result["diffId"],
            "files": [
                {
                    "path": f["path"],
                    "symbols": [],
                    "fallbackUsed": True,
                    "hunkCount": f["hunkCount"],
                }
                for f in enriched_files
            ],
            "fallbackUsed": True,
        }
        encoded = json.dumps(compact, ensure_ascii=False).encode("utf-8")
    return encoded
