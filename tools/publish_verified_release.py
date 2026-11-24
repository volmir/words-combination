#!/usr/bin/env python3
"""Publish an explicit verified export, then verify the public download.

Network writes require --publish. Never selects APKs from Gradle outputs and
never replaces an existing release or asset. Git tags must already be pushed.
"""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request

if __package__:
    from .prepare_verified_release import check_apk_revision, get_tag_commit
    from .verify_reproducible_release import (OWNER_CERT_SHA256, find_apksigner,
                                              sha256_file, verify_release)
else:
    from prepare_verified_release import check_apk_revision, get_tag_commit
    from verify_reproducible_release import (OWNER_CERT_SHA256, find_apksigner,
                                             sha256_file, verify_release)


def validate_export(directory, repository, apksigner, apksigcopier):
    directory = Path(directory).resolve(strict=True)
    repository = Path(repository).resolve(strict=True)
    ordinary_output = repository / 'app/build/outputs/apk'
    if directory == ordinary_output or ordinary_output in directory.parents:
        raise ValueError('Ordinary Gradle APK outputs are not a verified export')
    evidence = json.loads((directory / 'reproducibility-evidence.json').read_text())
    tag = evidence.get('tag', '')
    commit = get_tag_commit(repository, tag)
    if evidence.get('commit') != commit:
        raise ValueError('Export Git commit does not match the release tag')
    if (evidence.get('status') != 'LOCAL_SOURCE_TO_SIGNED_APK_VERIFIED' or
            evidence.get('independent_paths') is not True or
            evidence.get('version_name') != tag or
            type(evidence.get('version_code')) is not int or
            evidence['version_code'] <= 0 or
            evidence.get('signer_certificate_sha256') != OWNER_CERT_SHA256):
        raise ValueError('Unconfirmed export or signing certificate')
    apk = directory / f'WordsCombination-v{tag}.apk'
    unsigned = [directory / f'unsigned-{index}.apk' for index in (1, 2)]
    hashes = [sha256_file(path) for path in unsigned]
    if hashes[0] != hashes[1] or hashes != evidence.get('unsigned_sha256s'):
        raise ValueError('Independent unsigned APK checksums do not match the evidence')
    if sha256_file(apk) != evidence.get('signed_sha256'):
        raise ValueError('Selected APK checksum does not match the verified signed APK')
    for candidate in [apk] + unsigned:
        check_apk_revision(candidate, commit)
    verify_release(apk, unsigned[1], directory / 'SHA256SUMS.txt',
                   apksigner, apksigcopier)
    return evidence


def verify_download(downloaded, directory, evidence, apksigner, apksigcopier):
    if sha256_file(downloaded) != evidence['signed_sha256']:
        raise ValueError('Downloaded public APK differs from the verified signed APK')
    result = verify_release(downloaded, directory / 'unsigned-2.apk',
                            directory / 'SHA256SUMS.txt', apksigner, apksigcopier)
    return dict(result, status='PUBLIC_DOWNLOAD_VERIFIED',
                commit=evidence['commit'], tag=evidence['tag'])


def publish_release(directory, repository, github_repo, *, publish=False):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', github_repo):
        raise ValueError('Expected GitHub owner/repository')
    directory = Path(directory).resolve(strict=True)
    ordinary_output = Path(repository).resolve() / 'app/build/outputs/apk'
    if directory == ordinary_output or ordinary_output in directory.parents:
        raise ValueError('Ordinary Gradle APK outputs are not a verified export')
    apksigner, apksigcopier = find_apksigner(), shutil.which('apksigcopier')
    if not apksigcopier:
        raise ValueError('apksigcopier is required')
    # Freeze the explicit bundle before validation and upload. No fallback/glob.
    with tempfile.TemporaryDirectory(prefix='verified-publication-') as temporary:
        stage = Path(temporary) / 'export'
        shutil.copytree(directory, stage)
        evidence = validate_export(stage, repository, apksigner, apksigcopier)
        if not publish:
            return dict(evidence, publication='NOT_RUN (requires --publish)')
        tag = evidence['tag']
        remote = subprocess.check_output(
            ['git', 'ls-remote', f'https://github.com/{github_repo}.git',
             f'refs/tags/{tag}^{{}}'], text=True).split()
        if not remote or remote[0] != evidence['commit']:
            raise ValueError('Public Git tag does not match the verified commit')
        exists = subprocess.run(['gh', 'api', f'repos/{github_repo}/releases/tags/{tag}'],
                                capture_output=True, text=True, check=False)
        if exists.returncode == 0:
            raise ValueError('Release already exists; refusing to replace public artifacts')
        if '404' not in exists.stderr:
            raise ValueError('Cannot establish that the release does not exist')
        notes = stage / 'release-notes.txt'
        notes.write_text(
            f'Technical update. Gameplay, interface and dictionary are unchanged.\n\n'
            f'- versionName: `{tag}`\n- versionCode: `{evidence["version_code"]}`\n'
            f'- Source commit: `{evidence["commit"]}`\n'
            f'- APK SHA-256: `{evidence["signed_sha256"]}`\n'
            f'- Signing certificate SHA-256: `{OWNER_CERT_SHA256}`\n\n'
            'Local reproducibility: PASS. Two independent clean Git clones at different '
            'absolute paths produced identical unsigned APKs. The developer signature '
            'was copied to the independent APK and verified using apksigcopier.\n\n'
            'Independent F-Droid infrastructure verification is pending.\n', encoding='utf-8')
        apk_name = f'WordsCombination-v{tag}.apk'
        subprocess.run(['gh', 'release', 'create', tag, '--repo', github_repo,
                        '--verify-tag', '--title', f'Words Combination {tag}',
                        '--notes-file', str(notes), str(stage / apk_name),
                        str(stage / 'SHA256SUMS.txt')], check=True)
        url = f'https://github.com/{github_repo}/releases/download/{tag}/{apk_name}'
        downloaded = Path(temporary) / apk_name
        record = directory / 'publication-verification.json'
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                with downloaded.open('wb') as target:
                    shutil.copyfileobj(response, target)
            result = verify_download(downloaded, stage, evidence, apksigner, apksigcopier)
        except Exception:
            record.write_text(json.dumps({'status': 'FAIL', 'tag': tag,
                                          'action': 'STOP; do not update F-Droid metadata'}))
            raise
        result['public_url'] = url
        record.write_text(json.dumps(result, indent=2) + '\n')
        shutil.copyfile(downloaded, directory / ('downloaded-' + apk_name))
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verified-dir', type=Path, required=True)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--github-repo', required=True)
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    try:
        result = publish_release(args.verified_dir, args.repo, args.github_repo,
                                 publish=args.publish)
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
