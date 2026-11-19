#!/usr/bin/env python3
"""Check that both apps' translations match English: same catalogs, keys, placeholders and plural forms.

Release lint enforces the same rules (the fatal checks in each build.gradle.kts); this check runs in seconds
without Gradle, so `validate` and CI catch a missing translation before a build. Adapted from SonarBridge's
check, extended to PhoneLock's split string files (strings_<area>.xml) and plurals.
Exits non-zero with every problem listed.
"""
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APPS = ["companion", "lock"]
# The seven wording catalogs of the nine-locale set: en-CA uses values/, fr-CA falls back to values-fr/.
FOLDERS = {"values", "values-es", "values-fr", "values-de", "values-b+zh+Hans", "values-pt", "values-ja"}
TOKEN = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")
# Plural quantities a language may need beyond "other"; each catalog must have "other" at least.
REQUIRED_PLURAL = "other"


def text_of(element):
    """The element's full text, including any inline markup such as <xliff:g>."""
    return "".join(element.itertext())


def read_catalog(path, problems):
    """Strings and plurals in one file, as {key: text} and {key: {quantity: text}}; untranslatable skipped."""
    root = ET.parse(path).getroot()
    strings, plurals = {}, {}
    for element in root:
        if element.get("translatable") == "false":
            continue
        name = element.get("name")
        if element.tag == "string":
            if name in strings:
                problems.append(f"{path}: duplicate key {name}")
            strings[name] = text_of(element)
        elif element.tag == "plurals":
            plurals[name] = {item.get("quantity"): text_of(item) for item in element.findall("item")}
    return strings, plurals


def placeholders(text):
    """Every printf argument and literal percent, independent of word order."""
    return Counter(TOKEN.findall(text))


def check_app(app, problems):
    """Compare every translated catalog of one app against its English catalog."""
    res = ROOT / "android" / app / "src/main/res"
    folders = {path.name for path in res.glob("values*") if any(path.glob("strings*.xml"))}
    if folders != FOLDERS:
        problems.append(f"{app}: unexpected or missing catalogs: {sorted(folders ^ FOLDERS)}")

    english_files = sorted(path.name for path in (res / "values").glob("strings*.xml"))
    for folder in sorted(FOLDERS - {"values"}):
        files = sorted(path.name for path in (res / folder).glob("strings*.xml"))
        if files != english_files:
            problems.append(f"{app}/{folder}: files differ from values/: {sorted(set(files) ^ set(english_files))}")

        for name in english_files:
            source, source_plurals = read_catalog(res / "values" / name, problems)
            target_path = res / folder / name
            if not target_path.exists():
                continue
            target, target_plurals = read_catalog(target_path, problems)
            where = f"{app}/{folder}/{name}"

            if target.keys() != source.keys():
                problems.append(f"{where}: keys differ: {sorted(target.keys() ^ source.keys())}")
            for key in target.keys() & source.keys():
                if placeholders(target[key]) != placeholders(source[key]):
                    problems.append(f"{where}: placeholders differ in {key}")
                if "—" in target[key]:
                    problems.append(f"{where}: em dash in {key}")

            if target_plurals.keys() != source_plurals.keys():
                problems.append(f"{where}: plurals differ: {sorted(target_plurals.keys() ^ source_plurals.keys())}")
            for key in target_plurals.keys() & source_plurals.keys():
                if REQUIRED_PLURAL not in target_plurals[key]:
                    problems.append(f"{where}: plural {key} has no '{REQUIRED_PLURAL}' form")
                wanted = placeholders(source_plurals[key].get(REQUIRED_PLURAL, ""))
                if placeholders(target_plurals[key].get(REQUIRED_PLURAL, "")) != wanted:
                    problems.append(f"{where}: placeholders differ in plural {key}")


def main():
    problems = []
    for app in APPS:
        check_app(app, problems)
    if problems:
        print("\n".join(problems), file=sys.stderr)
        print(f"check:l10n: {len(problems)} problem(s)", file=sys.stderr)
        return 1
    print("check:l10n: every catalog matches English")
    return 0


if __name__ == "__main__":
    sys.exit(main())
