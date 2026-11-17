"""Pinned SQLite writer and byte-exact recovery. Network is opt-in setup only."""
import hashlib
from contextlib import closing
import json
import os
from pathlib import Path
import platform
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
# The existing generator also supports script entry points with local imports.
sys.path.insert(0, str(ROOT / 'tools'))
LOCK = ROOT / 'tools/owned_hints_toolchain.json'
LIBRARY = ROOT / '.local/owned-hints-toolchain/lib/libsqlite3.so.0'
PREPARE = 'python3 tools/prepare_owned_hints.py --download'


def load_lock():
    value = json.loads(LOCK.read_text())
    if value['schema'] != 'owned-hints-toolchain-v1':
        raise ValueError('Unknown owned hints toolchain lock')
    return value


def checked(content, digest, label):
    if hashlib.sha256(content).hexdigest() != digest:
        raise ValueError('SHA-256 mismatch: ' + label)
    return content


def runtime_profile():
    with closing(sqlite3.connect(':memory:')) as db:
        options = '\n'.join(row[0] for row in db.execute('PRAGMA compile_options'))
        return dict(version=sqlite3.sqlite_version,
                    source_id=db.execute('SELECT sqlite_source_id()').fetchone()[0],
                    compile_options_sha256=hashlib.sha256(options.encode()).hexdigest())


def download(artifact):
    # Called only by prepare_owned_hints.py with explicit --download.
    with urlopen(artifact['url'], timeout=60) as response:
        content = response.read()
    return checked(content, artifact['sha256'], artifact['url'])


def git_database(data, lock):
    try:
        return subprocess.check_output(
            ['git', '-C', str(data.parent), 'show',
             lock['database']['git_revision'] + ':data/definitions.db'],
            stderr=subprocess.DEVNULL, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return None


def restore_database(data, lock=None, allow_download=False):
    """Recover a missing approved artifact, never replace an existing file."""
    lock = lock or load_lock()
    artifact = lock['database']
    path = data / 'definitions.db'
    if path.exists():
        checked(path.read_bytes(), artifact['sha256'], str(path))
        return
    manifest = json.loads((data / 'manifest.json').read_text())
    if manifest.get('definitions', {}).get('sha256') != artifact['sha256']:
        raise ValueError('Recovery artifact does not match manifest; no files changed')
    content = git_database(data, lock)
    if content is None:
        if not allow_download:
            raise ValueError('Approved definitions.db is missing and unavailable in Git. Run: ' + PREPARE)
        content = download(artifact)
    checked(content, artifact['sha256'], 'approved definitions.db')
    # Publish complete bytes atomically, without overwriting even if another
    # process creates the destination after our initial existence check.
    with tempfile.NamedTemporaryFile(dir=data, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
            os.link(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)
    print('Restored approved definitions.db (SHA-256 verified)', file=sys.stderr)


def check_library(path, lock):
    if not path.is_file():
        raise ValueError('Pinned SQLite library is missing. Run: ' + PREPARE)
    checked(path.read_bytes(), lock['package']['library_sha256'], str(path))


def worker_environment(lock):
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        raise ValueError('Pinned SQLite worker requires Linux x86_64 with glibc >= 2.34')
    check_library(LIBRARY, lock)
    environment = os.environ.copy()
    environment['LD_LIBRARY_PATH'] = str(LIBRARY.parent)
    return environment


def prepare_library(lock, allow_download=False):
    if LIBRARY.exists():
        check_library(LIBRARY, lock)
    elif allow_download:
        # Verify the entire .deb before extraction; do not install system packages.
        worker_platform = (platform.system(), platform.machine())
        if worker_platform != ('Linux', 'x86_64'):
            raise ValueError('Pinned SQLite package requires Linux x86_64')
        if not shutil.which('dpkg-deb'):
            raise ValueError('Preparing pinned SQLite requires dpkg-deb')
        package = download(lock['package'])
        LIBRARY.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=LIBRARY.parent) as temporary:
            temporary = Path(temporary)
            archive = temporary / 'sqlite.deb'
            archive.write_bytes(package)
            subprocess.run(['dpkg-deb', '-x', str(archive), str(temporary / 'package')], check=True)
            source = temporary / 'package' / lock['package']['library']
            check_library(source, lock)
            os.replace(source, LIBRARY)
    elif runtime_profile() == lock['sqlite']:
        return
    else:
        raise ValueError('SQLite runtime differs from the pinned writer. Run: ' + PREPARE)
    subprocess.run([sys.executable, str(Path(__file__).resolve()), '--probe'],
                   env=worker_environment(lock), check=True, timeout=30)


def worker(output, payload):
    lock = load_lock()
    if runtime_profile() != lock['sqlite']:
        raise ValueError('SQLite worker does not match pinned source id / compile options')
    from tools.build_game_data import build_database as original
    original(output, payload['rows'], payload['categories'], payload['metadata'], payload['version'])


def build_database(path, rows, categories, metadata, version):
    lock = load_lock()
    if runtime_profile() == lock['sqlite']:
        from tools.build_game_data import build_database as original
        return original(path, rows, categories, metadata, version)
    # Only the writer changes environment. The caller, Gradle and system Python
    # retain their normal libraries. All original byte-level verification stays.
    payload = dict(rows=rows, categories=categories, metadata=metadata, version=version)
    subprocess.run([sys.executable, str(Path(__file__).resolve()), '--worker', str(path.resolve())],
                   input=json.dumps(payload, ensure_ascii=False), text=True,
                   env=worker_environment(lock), check=True, timeout=120)


def main():
    sys.path.insert(0, str(ROOT))
    if sys.argv[1:] == ['--probe']:
        if runtime_profile() != load_lock()['sqlite']:
            raise ValueError('SQLite worker does not match the pinned runtime')
        print('Pinned SQLite runtime: PASS')
    elif len(sys.argv) == 3 and sys.argv[1] == '--worker':
        worker(Path(sys.argv[2]), json.load(sys.stdin))
    else:
        raise ValueError('Internal worker: expected --probe or --worker OUTPUT')


if __name__ == '__main__':
    main()
