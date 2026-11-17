"""Reject project binary storage/imports; historical evidence and host caches are separate."""
import ast
from pathlib import Path
import re
import os

FORBIDDEN_MODULES = {'sqlite3', '_sqlite3', 'pysqlite3', 'apsw'}
LEGACY_TOOLCHAIN_REFERENCE = re.compile(
    r'prepare_owned_hints[.]py|owned-hints-toolchain[/\\]|libsqlite3[.]so', re.I,
)


def check(root):
    root = Path(root)
    failures = []
    # Explicit project source roots work in source archives without Git. Generated
    # builds and ignored research caches are never inputs to this contract.
    for base in ('data', 'tools', 'app/src', 'core/src', '.github', 'fdroid'):
        directory = root / base
        if not directory.exists():
            continue
        paths=[]
        for parent, dirs, files in os.walk(directory):
            dirs[:]=[d for d in dirs if d not in {'cache','__pycache__','build'}]
            paths.extend(Path(parent)/name for name in files)
        for path in paths:
            if not path.is_file():
                continue
            name = path.relative_to(root).as_posix()
            if re.search(r'\.(?:db(?:-.*)?|sqlite[^/]*|deb)$|libsqlite', path.name, re.I):
                failures.append(name)
                continue
            with path.open('rb') as source:
                if source.read(16) == b'SQLite format 3\x00':
                    failures.append(name)
            if path.suffix == '.py':
                for node in ast.walk(ast.parse(path.read_text(encoding='utf8'))):
                    if (isinstance(node, ast.Constant) and isinstance(node.value, str) and
                            LEGACY_TOOLCHAIN_REFERENCE.search(node.value)):
                        failures.append(name)
                    modules = ([a.name for a in node.names] if isinstance(node,ast.Import) else
                               [node.module or ''] if isinstance(node,ast.ImportFrom) else [])
                    if any(m.split('.')[0] in FORBIDDEN_MODULES for m in modules):
                        failures.append(name)
            elif path.suffix in {'.kt', '.kts', '.toml', '.yml', '.yaml'}:
                content = path.read_text()
                if (LEGACY_TOOLCHAIN_REFERENCE.search(content) or
                        re.search(r'android\.database\.sqlite|SQLiteDatabase|org\.sqlite|sqlite-jdbc|libsqlite|security\.ubuntu\.com/.+\.deb',content,re.I)):
                    failures.append(name)
    # Root and module build files are not under source roots.
    for path in [*root.glob('*.gradle.kts'), root/'app/build.gradle.kts', root/'core/build.gradle.kts']:
        if not path.is_file():
            continue
        if any(p in {'.local','.gradle','build','cache'} for p in path.relative_to(root).parts):
            continue
        content = path.read_text()
        if (LEGACY_TOOLCHAIN_REFERENCE.search(content) or
                re.search(r'libsqlite|sqlite-jdbc|definitions\.db|3\.37\.2|\.deb\b',content,re.I)):
            failures.append(path.relative_to(root).as_posix())
    if failures:
        raise ValueError('Forbidden project storage dependency: ' + ', '.join(sorted(set(failures))))
