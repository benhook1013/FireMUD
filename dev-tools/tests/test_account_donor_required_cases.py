#!/usr/bin/env python3
"""Synthetic negative contracts; these are not physical database evidence."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location('proof', Path(__file__).parents[1] / 'validation/account_donor_required_cases.py')
proof = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(proof)


class AccountRequiredCasesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.contract = proof.required_cases()
        self.name = sorted(self.contract)[0]
        self.path = self.root / 'TEST-obligated.xml'
        self.suite = ET.Element('testsuite', name=self.name, tests=str(len(self.contract[self.name])), failures='0', errors='0', skipped='0')
        for name in sorted(self.contract[self.name]):
            ET.SubElement(self.suite, 'testcase', name=name, classname=self.name)
        self.write()

    def write(self):
        ET.ElementTree(self.suite).write(self.path)

    def verify(self):
        self.write()
        return proof.verify_partition(self.root, {self.name})

    def test_complete_selected_suite(self):
        self.assertEqual(self.verify(), {self.name})

    def test_missing_named_case_even_with_consistent_count(self):
        self.suite.remove(self.suite[-1]); self.suite.set('tests', str(len(self.suite)))
        with self.assertRaises(ValueError): self.verify()

    def test_skipped_case_with_lying_suite_totals(self):
        ET.SubElement(self.suite[0], 'skipped')
        with self.assertRaises(ValueError): self.verify()

    def test_error_and_failure_cases_with_lying_totals(self):
        for tag in ('failure', 'error'):
            with self.subTest(tag=tag):
                child = ET.SubElement(self.suite[0], tag)
                with self.assertRaises(ValueError): self.verify()
                self.suite[0].remove(child)

    def test_duplicate_method_cannot_replace_obligation(self):
        self.suite[-1].set('name', self.suite[0].get('name'))
        with self.assertRaises(ValueError): self.verify()

    def test_wrong_case_class_refused(self):
        self.suite[0].set('classname', 'foreign.Class')
        with self.assertRaises(ValueError): self.verify()

    def test_duplicate_xml_refused(self):
        (self.root / 'TEST-duplicate.xml').write_bytes(self.path.read_bytes())
        with self.assertRaises(ValueError): self.verify()

    def test_missing_suite_refused(self):
        self.path.unlink()
        with self.assertRaises(ValueError): proof.verify_partition(self.root, {self.name})

    def test_wrong_partition_refused(self):
        with self.assertRaises(ValueError): proof.verify_partition(self.root, set())

    def test_missing_and_duplicate_union_refused(self):
        with self.assertRaises(ValueError): proof.verify_complete_union([{self.name}])
        with self.assertRaises(ValueError): proof.verify_complete_union([set(self.contract), {self.name}])
        proof.verify_complete_union([{name} for name in self.contract])

    def test_unobligated_empty_partition_allowed(self):
        self.path.unlink()
        self.assertEqual(proof.verify_partition(self.root, {'another.CurrentTest'}), set())

    def test_missing_or_nonzero_suite_totals_refused(self):
        for attribute in ('failures', 'errors', 'skipped'):
            with self.subTest(attribute=attribute):
                self.suite.set(attribute, '1')
                with self.assertRaises(ValueError): self.verify()
                del self.suite.attrib[attribute]
                with self.assertRaises(ValueError): self.verify()
                self.suite.set(attribute, '0')


if __name__ == '__main__':
    unittest.main()
