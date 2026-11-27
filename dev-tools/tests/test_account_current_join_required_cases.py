#!/usr/bin/env python3
"""Synthetic strict-proof contracts, not physical execution evidence."""
import importlib.util
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).parents[1]
SPEC = importlib.util.spec_from_file_location('proof', ROOT / 'validation/account_donor_required_cases.py')
proof = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(proof)
CURRENT = json.loads((ROOT / 'validation/account_current_join_required_cases.json').read_text())['required_cases']


class CurrentJoinRequiredCasesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def report(self, name, cases):
        suite = ET.Element('testsuite', name=name, tests=str(len(cases)), failures='0', errors='0', skipped='0')
        for case in cases:
            ET.SubElement(suite, 'testcase', classname=name, name=case)
        ET.ElementTree(suite).write(self.root / ('TEST-' + name + '.xml'))

    def test_scope_is_distinct_and_contains_all_55_unique_cases(self):
        original = json.loads(proof.CONTRACT.read_text())['required_cases']
        self.assertFalse(original.keys() & CURRENT.keys())
        self.assertEqual(sorted(map(len, CURRENT.values())), [5, 10, 11, 29])
        for cases in CURRENT.values():
            self.assertEqual(len(cases), len(set(cases)))
        self.assertEqual(proof.required_cases().keys(), original.keys() | CURRENT.keys())

    def test_every_named_case_is_required_without_duplicate_substitution(self):
        for name, cases in CURRENT.items():
            self.report(name, cases)
            self.assertEqual(proof.verify_partition(self.root, {name}), {name})
            for index, case in enumerate(cases):
                with self.subTest(suite=name, missing=case):
                    missing = cases[:index] + cases[index + 1:]
                    self.report(name, missing)
                    with self.assertRaisesRegex(ValueError, 'named cases missing'):
                        proof.verify_partition(self.root, {name})
                    self.report(name, missing + [missing[0]])
                    with self.assertRaisesRegex(ValueError, 'duplicate'):
                        proof.verify_partition(self.root, {name})
            (self.root / ('TEST-' + name + '.xml')).unlink()

    def test_old_ambiguous_parameter_labels_cannot_prove_scope(self):
        name = next(name for name in CURRENT if 'JoinScopePersistence' in name)
        cases = [case for case in CURRENT[name] if ' bound=' not in case]
        self.report(name, cases + ['[1] bound = false', '[2] bound = true'] * 2)
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            proof.verify_partition(self.root, {name})

    def test_original_contract_alone_cannot_satisfy_complete_union(self):
        original = set(json.loads(proof.CONTRACT.read_text())['required_cases'])
        with self.assertRaisesRegex(ValueError, 'missing from partition union'):
            proof.verify_complete_union([original])
        proof.verify_complete_union([original, set(CURRENT)])


if __name__ == '__main__':
    unittest.main()
