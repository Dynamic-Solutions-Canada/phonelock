#!/usr/bin/env python3
"""Check that the parent app reads and writes preferences only through Prefs.kt (CONF-8).

Prefs.kt owns every preferences file, key name and default. A key string typed anywhere else drifts from it, the
same way browser storage keys do, so this fails when parent-app code outside Prefs.kt opens a preferences file or
passes a quoted key to a SharedPreferences getter or editor. JSON objects use the same method names with one
argument (get) or none of the typed put names, so they don't match.

The lock app is not checked yet: its preferences move behind a typed wrapper in a later change.
Exits non-zero with every match listed.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCES = [ROOT / "android/companion/src/main", ROOT / "android/companion/src/github", ROOT / "android/companion/src/play"]
OWNER = "Prefs.kt"

PATTERNS = [
    # Opening a preferences file.
    (re.compile(r"\bgetSharedPreferences\s*\("), "opens a preferences file"),
    # SharedPreferences.Editor's typed puts, with a key string.
    (re.compile(r'\.put(String|Int|Long|Boolean|Float|StringSet)\s*\(\s*"'), "writes a preference by key"),
    # SharedPreferences' getters take a key and a default; JSONObject's take only the key.
    (re.compile(r'\.get(String|Int|Long|Boolean|Float|StringSet)\s*\(\s*"[^"]*"\s*,'), "reads a preference by key"),
]


def main():
    problems = []
    for base in SOURCES:
        for path in sorted(base.rglob("*.kt")):
            if path.name == OWNER:
                continue
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                for pattern, what in PATTERNS:
                    if pattern.search(line):
                        problems.append(f"{path.relative_to(ROOT)}:{number}: {what}; add it to {OWNER} instead")
    if problems:
        print("\n".join(problems), file=sys.stderr)
        print(f"check:prefs: {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print(f"check:prefs: parent-app preferences go only through {OWNER}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
