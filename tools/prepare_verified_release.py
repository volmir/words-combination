#!/usr/bin/env python3
"""Build and verify a developer-signed release from two clean Git clones.

This command never creates tags, commits, pushes or publishes releases. The
private signing key stays on the owner's machine; only the signed build receives
signing variables. No artifacts are exported until verification succeeds.
"""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

if __package__:
    from .verify_reproducible_release import find_apksigner, sha256_file, verify_release
else:
    from verify_reproducible_release import find_apksigner, sha256_file, verify_release


SIGNING_VARIABLES = (
    'SOSTAVSLOVA_RELEASE_STORE_FILE',
    'SOSTAVSLOVA_RELEASE_STORE_PASSWORD',
    'SOSTAVSLOVA_RELEASE_KEY_ALIAS',
    'SOSTAVSLOVA_RELEASE_KEY_PASSWORD',
)


def require_signing_environment(environ):
    if any(not environ.get(name) for name in SIGNING_VARIABLES):
        raise ValueError('All four SOSTAVSLOVA_RELEASE_* variables must be exported')
    store = Path(environ[SIGNING_VARIABLES[0]])
    if not store.is_absolute() or not store.is_file():
        raise ValueError('Release keystore must be an existing absolute path (path hidden)')


def run(command, *, cwd=None, env=None):
    subprocess.run(command, cwd=cwd, env=env, check=True)


def get_tag_commit(repository, tag):
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', tag):
        raise ValueError('Expected a plain semantic-version tag, e.g. 1.0.2')
    kind = subprocess.check_output(
        ['git', 'cat-file', '-t', f'refs/tags/{tag}'], cwd=repository, text=True,
    ).strip()
    if kind != 'tag':
        raise ValueError('Release tag must be annotated')
    return subprocess.check_output(
        ['git', 'rev-parse', f'refs/tags/{tag}^{{commit}}'],
        cwd=repository, text=True,
    ).strip()


def clone_tag(repository, destination, tag, expected_commit):
    run(['git', 'clone', '--quiet', '--no-hardlinks', '--no-checkout',
         str(repository), str(destination)])
    run(['git', 'checkout', '--quiet', '--detach', tag], cwd=destination)
    actual = subprocess.check_output(
        ['git', 'rev-parse', 'HEAD'], cwd=destination, text=True,
    ).strip()
    if actual != expected_commit or not (destination / '.git').is_dir():
        raise ValueError('Clean full Git clone did not resolve to the release commit')
    if (destination / 'keystore.properties').exists():
        raise ValueError('A local keystore.properties unexpectedly entered the clone')


def check_build_metadata(clone, tag, expected_code):
    metadata_path = clone / 'app/build/outputs/apk/release/output-metadata.json'
    metadata = json.loads(metadata_path.read_text(encoding='utf-8'))
    versions = {(item['versionName'], item['versionCode'])
                for item in metadata['elements']}
    if len(versions) != 1:
        raise ValueError('Release APK output metadata must have one version')
    version, code = versions.pop()
    if version != tag or (expected_code is not None and code != expected_code):
        raise ValueError('APK versionName/versionCode does not match the release candidate')
    return code


def prepare_release(repository, tag, output_dir, expected_code,
                    *, environ=None, apksigner=None, apksigcopier=None):
    repository = Path(repository).resolve(strict=True)
    output_dir = Path(output_dir).resolve()
    if not (repository / '.git').is_dir():
        raise ValueError('Repository must be a normal Git clone, not a worktree')
    if output_dir.exists():
        raise ValueError('Output directory already exists; refusing to overwrite artifacts')
    variables = dict(os.environ if environ is None else environ)
    require_signing_environment(variables)
    commit = get_tag_commit(repository, tag)
    apksigner = apksigner or find_apksigner()
    apksigcopier = apksigcopier or shutil.which('apksigcopier')
    if not apksigcopier:
        raise ValueError('apksigcopier is required')

    with tempfile.TemporaryDirectory(prefix='words-repro-') as temporary:
        root = Path(temporary)
        # AGP embeds Git's local_root_path in version-control-info.textproto.
        # Independent clones in *different* paths therefore cannot reproduce.
        # Recreate the full clone at exactly the same absolute path instead.
        build_clone = root / 'source'
        clone_tag(repository, build_clone, tag, commit)

        unsigned_env = dict(variables)
        for name in SIGNING_VARIABLES:
            unsigned_env.pop(name, None)
        gradle_command = ['bash', './gradlew', ':app:assembleRelease',
                          '--no-build-cache', '--no-daemon', '--console=plain']
        run(gradle_command, cwd=build_clone, env=unsigned_env)
        unsigned_apk = (build_clone /
                        'app/build/outputs/apk/release/app-release-unsigned.apk')
        if not unsigned_apk.is_file():
            raise ValueError('Unsigned clone did not produce app-release-unsigned.apk')
        unsigned_code = check_build_metadata(build_clone, tag, expected_code)
        staged_unsigned = root / 'app-release-unsigned.apk'
        shutil.copyfile(unsigned_apk, staged_unsigned)

        # Remove only the disposable clone within this newly created temp dir.
        # The signed build starts from a second, entirely fresh full clone.
        shutil.rmtree(build_clone)
        clone_tag(repository, build_clone, tag, commit)

        run(gradle_command, cwd=build_clone, env=variables)
        signed_apk = build_clone / 'app/build/outputs/apk/release/app-release.apk'
        if not signed_apk.is_file():
            raise ValueError('Signed clone did not produce app-release.apk')
        signed_code = check_build_metadata(build_clone, tag, expected_code)
        if signed_code != unsigned_code:
            raise ValueError('Signed and unsigned versionCode differ')

        filename = f'WordsCombination-v{tag}.apk'
        staged_apk = root / filename
        shutil.copyfile(signed_apk, staged_apk)
        sums = root / 'SHA256SUMS.txt'
        sums.write_text(f'{sha256_file(staged_apk)}  {filename}\n', encoding='utf-8')
        result = verify_release(staged_apk, staged_unsigned, sums, apksigner, apksigcopier)
        result.update({
            'tag': tag,
            'commit': commit,
            'version_name': tag,
            'version_code': signed_code,
            'builds': 'two sequential independent full Git clones at the same absolute path',
            'github_release_published': False,
        })

        # Export only verified artifacts; never replace a previous candidate.
        output_dir.mkdir(parents=True, exist_ok=False)
        shutil.copyfile(staged_apk, output_dir / filename)
        shutil.copyfile(sums, output_dir / 'SHA256SUMS.txt')
        (output_dir / 'reproducibility-evidence.json').write_text(
            json.dumps(result, indent=2, ensure_ascii=False) + '\n', encoding='utf-8',
        )
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True, help='Existing local annotated tag')
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--expected-version-code', type=int, required=True)
    parser.add_argument('--output-dir', type=Path, help='Fresh directory for verified artifacts')
    args = parser.parse_args()
    destination = args.output_dir or (
        args.repo / 'app/build/outputs/verified-github-release' / args.tag
    )
    try:
        result = prepare_release(args.repo, args.tag, destination,
                                 expected_code=args.expected_version_code)
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError,
            subprocess.TimeoutExpired) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2, ensure_ascii=False))
    print(f'VERIFIED_RELEASE_DIR={Path(destination).resolve()}')


if __name__ == '__main__':
    main()
