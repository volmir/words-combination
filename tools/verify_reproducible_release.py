#!/usr/bin/env python3
"""Compare an upstream signed APK to an independent unsigned release build.

Uses F-Droid's apksigcopier. A successful result is local reproducibility
evidence, not an F-Droid buildserver or publication approval.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


# Public certificate fingerprint approved for Words Combination (not an APK hash).
OWNER_CERT_SHA256 = 'a10cdd4ff4000673f14db9fe4a814f8bbd63b6e2007f5f38b1fe5cfb7310f353'


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def check_release_checksum(signed, sums):
    """Accept one exact SHA256SUMS entry naming this published artifact."""
    lines = sums.read_text(encoding='utf-8').splitlines()
    if len(lines) != 1:
        raise ValueError('SHA256SUMS.txt must contain exactly one APK entry')
    match = re.fullmatch(r'([0-9a-fA-F]{64}) [ *]([^/\\]+\.apk)', lines[0])
    if not match or match.group(2) != signed.name:
        raise ValueError('SHA256SUMS.txt does not name the selected APK')
    actual = sha256_file(signed)
    if match.group(1).lower() != actual:
        raise ValueError('Published APK SHA-256 does not match SHA256SUMS.txt')
    return actual


def find_apksigner():
    installed = shutil.which('apksigner')
    if installed:
        return installed
    for name in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        sdk = os.environ.get(name)
        if not sdk:
            continue
        build_tools = Path(sdk) / 'build-tools'
        if build_tools.is_dir():
            for path in sorted(build_tools.glob('*/apksigner'), reverse=True):
                if path.is_file() and os.access(path, os.X_OK):
                    return str(path)
    raise RuntimeError('apksigner not found: set ANDROID_HOME or install Android SDK Build Tools')


def verify_release(signed, unsigned, sums, apksigner, apksigcopier):
    signed, unsigned, sums = (Path(value) for value in (signed, unsigned, sums))
    for path in (signed, unsigned, sums):
        if not path.is_file():
            raise ValueError('Required file is missing: ' + str(path))
    if signed.resolve() == unsigned.resolve():
        raise ValueError('Signed and independently built APK must be different files')

    published_sha = check_release_checksum(signed, sums)
    env = dict(os.environ)
    env['PATH'] = str(Path(apksigner).parent) + os.pathsep + env.get('PATH', '')

    signature = subprocess.run(
        [apksigner, 'verify', '--verbose', '--print-certs', str(signed)],
        capture_output=True, text=True, check=False, env=env, timeout=60,
    )
    if signature.returncode != 0:
        raise ValueError('Published APK signature does not verify')
    output = signature.stdout
    certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$',
                       output, flags=re.MULTILINE)
    if ('Number of signers: 1' not in output or len(certs) != 1 or
            certs[0].lower() != OWNER_CERT_SHA256):
        raise ValueError('Published APK is not signed by the approved owner certificate')
    if 'Verified using v2 scheme (APK Signature Scheme v2): true' not in output:
        raise ValueError('Published APK has no verified v2 signature')

    unsigned_check = subprocess.run(
        [apksigner, 'verify', '--verbose', str(unsigned)],
        capture_output=True, text=True, check=False, env=env, timeout=60,
    )
    if unsigned_check.returncode == 0:
        raise ValueError('Second APK is signed: expected an independent unsigned build')

    comparison = subprocess.run(
        [apksigcopier, 'compare', '--unsigned', str(signed), str(unsigned)],
        capture_output=True, text=True, check=False, env=env, timeout=180,
    )
    if comparison.returncode != 0:
        raise ValueError('APK contents do not reproduce (apksigcopier compare failed): ' +
                         (comparison.stderr or comparison.stdout)[-800:])

    return {
        'status': 'LOCAL_SOURCE_TO_SIGNED_APK_VERIFIED',
        'signed_apk': signed.name,
        'signed_sha256': published_sha,
        'unsigned_apk': unsigned.name,
        'unsigned_sha256': sha256_file(unsigned),
        'signer_certificate_sha256': OWNER_CERT_SHA256,
        'method': 'apksigcopier compare --unsigned',
        'fdroid_buildserver_verified': False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--signed', type=Path, required=True)
    parser.add_argument('--unsigned', type=Path, required=True)
    parser.add_argument('--sha256sums', type=Path, required=True)
    parser.add_argument('--output', type=Path, help='Optional JSON evidence report')
    args = parser.parse_args()
    apksigcopier = shutil.which('apksigcopier')
    if not apksigcopier:
        parser.error('apksigcopier is required; install the F-Droid signature comparison tool')
    try:
        result = verify_release(args.signed, args.unsigned, args.sha256sums,
                                find_apksigner(), apksigcopier)
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        parser.error(str(error))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
