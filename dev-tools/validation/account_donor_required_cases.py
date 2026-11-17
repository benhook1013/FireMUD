#!/usr/bin/env python3
"""Exact-case check after canonical partition provenance validation.

This helper does not establish freshness, execution, artifact provenance, or
whole-donor consumption. The canonical caller must establish those separately.
No scheduling, selectors, or current required-CI decisions are changed here.
"""
from __future__ import annotations

import json
from pathlib import Path
import xml.etree.ElementTree as ET

CONTRACT = Path(__file__).with_suffix('.json')


def required_cases() -> dict[str, set[str]]:
    data = json.loads(CONTRACT.read_text())
    if data.get('schema_version') != 1:
        raise ValueError('unsupported Account proof contract')
    return {name: set(cases) for name, cases in data['required_cases'].items()}


def verify_partition(reports: Path, selected_classes: set[str]) -> set[str]:
    """Require each selected obligated class's named cases exactly once and green.

    The caller must supply the already-verified integrationTest selector, not a
    caller-invented partial universe. The aggregate must additionally require
    every contract class to occur in the complete partition union.
    """
    required = required_cases()
    selected = selected_classes & required.keys()
    seen: dict[str, set[str]] = {name: set() for name in selected}
    suites_seen: set[str] = set()
    for path in sorted(reports.glob('TEST-*.xml')):
        root = ET.parse(path).getroot()
        # Gradle emits namespace-free JUnit. Unsupported structures cannot
        # establish a successful required outcome, even with zero totals.
        if any(not isinstance(node.tag, str) or '{' in node.tag or ':' in node.tag
               for node in root.iter()):
            raise ValueError('unsupported namespaced JUnit structure')
        name = root.get('name', '')
        if name not in required:
            continue
        if name not in selected:
            raise ValueError('required Account suite reported in wrong partition')
        if root.tag != 'testsuite' or name in suites_seen:
            raise ValueError('duplicate or invalid required Account suite')
        suites_seen.add(name)
        for attribute in ('failures', 'errors', 'skipped'):
            if attribute not in root.attrib or int(root.attrib[attribute]) != 0:
                raise ValueError('required Account suite is not wholly successful')
        cases = root.findall('testcase')
        if int(root.attrib.get('tests', '-1')) != len(cases):
            raise ValueError('required Account suite count disagrees with cases')
        for case in cases:
            case_name = case.get('name', '')
            if case.get('classname') != name or not case_name or case_name in seen[name]:
                raise ValueError('duplicate or mismatched Account case identity')
            if any(case.find(tag) is not None for tag in ('skipped', 'failure', 'error')):
                raise ValueError('required Account case did not pass')
            seen[name].add(case_name)
        if not required[name] <= seen[name]:
            raise ValueError('required Account named cases missing')
    if suites_seen != selected:
        raise ValueError('required Account suite missing')
    return suites_seen


def verify_complete_union(verified_partitions: list[set[str]]) -> None:
    seen: set[str] = set()
    for partition in verified_partitions:
        if seen & partition:
            raise ValueError('required Account suite appears in multiple partitions')
        seen |= partition
    if seen != required_cases().keys():
        raise ValueError('required Account proof missing from partition union')
