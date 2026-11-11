"""Synthetic integrity tests; never execute downloaded native code."""
import io
import pathlib
import tarfile
import tempfile
import unittest
from unittest import mock
import inspect_sources as subject


class IntegrityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        subject.CACHE.mkdir(parents=True, exist_ok=True)

    def test_changed_payload_rejected_by_tracked_pin(self):
        original = b'reviewed source'
        record = {'name': 'source.dat', 'size': len(original), 'sha256': subject.sha(original)}
        self.assertEqual(subject.verify(original, record), original)
        with self.assertRaisesRegex(ValueError, 'locked input identity mismatch'):
            subject.verify(b'changed source', record)

    def test_missing_offline_input_is_failure(self):
        with tempfile.TemporaryDirectory(dir=subject.CACHE) as folder:
            with mock.patch.object(subject, 'CACHE', pathlib.Path(folder)):
                with self.assertRaisesRegex(FileNotFoundError, 'offline input missing'):
                    subject.load({'name': 'absent'}, None, False)

    def test_missing_report_requires_explicit_generation(self):
        with tempfile.TemporaryDirectory(dir=subject.CACHE) as folder:
            with mock.patch.object(subject, 'OUT', pathlib.Path(folder)), mock.patch('sys.argv', ['inspect_sources.py']), mock.patch.object(subject, 'load') as loader:
                with self.assertRaisesRegex(FileNotFoundError, 'expected evidence.json missing'):
                    subject.main()
                loader.assert_not_called()
            self.assertFalse((pathlib.Path(folder)/'evidence.json').exists())

    def test_changed_cache_cannot_be_accepted_by_adjacent_receipt(self):
        with tempfile.TemporaryDirectory(dir=subject.CACHE) as folder:
            path = pathlib.Path(folder)
            (path/'sample').write_bytes(b'changed')
            (path/'sample.json').write_text('{"sha256":"untrusted adjacent receipt"}')
            record = {'name': 'sample', 'size': 8, 'sha256': subject.sha(b'original')}
            with mock.patch.object(subject, 'CACHE', path):
                with self.assertRaisesRegex(ValueError, 'locked input identity mismatch'):
                    subject.load(record, None, False)

    def test_unsafe_archive_name_rejected_without_extraction(self):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w:gz') as archive:
            info = tarfile.TarInfo('../outside.c')
            info.size = 1
            archive.addfile(info, io.BytesIO(b'x'))
        with self.assertRaisesRegex(ValueError, 'duplicate/unsafe archive name'):
            list(subject.archive_files(data.getvalue()))


if __name__ == '__main__':
    unittest.main()
