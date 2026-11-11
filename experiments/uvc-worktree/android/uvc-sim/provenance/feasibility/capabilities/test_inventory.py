"""Synthetic read-only inventory guards; no upstream execution."""
import io
import pathlib
import tarfile
import tempfile
import unittest
from unittest import mock
import inventory


class InventoryChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cache = inventory.SLICE / 'build/feasibility/capabilities'
        cls.cache.mkdir(parents=True, exist_ok=True)

    def test_changed_input_fails_against_tracked_hash(self):
        record = {'id': 'sample', 'bytes': 4, 'sha256': inventory.sha(b'good')}
        self.assertEqual(inventory.checked(b'good', record), b'good')
        with self.assertRaisesRegex(ValueError, 'pinned input mismatch'):
            inventory.checked(b'evil', record)

    def test_missing_reports_cannot_be_success(self):
        with tempfile.TemporaryDirectory(dir=self.cache) as folder:
            with mock.patch.object(inventory, 'OUT', pathlib.Path(folder)), mock.patch('sys.argv', ['inventory.py', '--source-cache', folder]):
                with self.assertRaisesRegex(FileNotFoundError, 'derived reports missing'):
                    inventory.main()
            self.assertEqual(list(pathlib.Path(folder).iterdir()), [])

    def test_comments_and_strings_do_not_pull_unrelated_java(self):
        prefix = 'libuvccamera/src/main/java/g/'
        files = {prefix+'A.java': b'package g;\nimport androidx.annotation.NonNull;\nclass A { B next; String s="C"; /* C unused; */ }',
                 prefix+'B.java': b'package g; class B {}', prefix+'C.java': b'package g; class C {}'}
        with mock.patch.object(inventory, 'JAVA_SEEDS', ['g.A']):
            result = inventory.java_candidates(files)
        self.assertEqual({r['class'] for r in result['manifest']}, {'g.A', 'g.B'})
        self.assertEqual(result['external_imports'][0]['kind'], 'third_party')

    def test_archive_traversal_fails_without_extraction(self):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w:gz') as archive:
            member = tarfile.TarInfo('../outside')
            member.size = 1
            archive.addfile(member, io.BytesIO(b'x'))
        with self.assertRaisesRegex(ValueError, 'unsafe or duplicate'):
            inventory.read_tar(data.getvalue())


if __name__ == '__main__':
    unittest.main()
