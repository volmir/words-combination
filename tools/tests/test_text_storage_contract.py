import tempfile
import unittest
from pathlib import Path
from tools.check_text_storage import check

class TextStorageContractTest(unittest.TestCase):
    def test_repository_sources(self):
        check(Path(__file__).resolve().parents[2])

    def test_hidden_binary_extensions_headers_and_imports_fail(self):
        mutations=[('data/hidden.db',b'anything'),('app/src/main/assets/payload.bin',b'SQLite format 3\x00hidden'),
                   ('tools/optional.py',b'import sqlite3\n'),('tools/old.py',b'from sqlite3 import connect\n'),
                   ('app/src/main/kotlin/Old.kt',b'import android.database.sqlite.SQLiteDatabase\n'),
                   ('app/build.gradle.kts',b'val old = "libsqlite3.so.0"')]
        for name,content in mutations:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                root=Path(temp);path=root/name;path.parent.mkdir(parents=True);path.write_bytes(content)
                with self.assertRaises(ValueError):check(root)

    def test_history_and_ignored_host_cache_are_not_build_inputs(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            for name in ('docs/archive.md','.local/cache/old.db','tools/cache/old.db'):
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('Historical fixture')
            check(root)
