#!/usr/bin/env python3
"""Static checks for the fdroiddata proposal; NOT an fdroid build or scanner."""

import argparse
import json
from pathlib import Path
import re
import subprocess


APP_ID = 'io.github.volmir.wordscombination'
VERSION = '1.0.2'
CODE = 3
COMMIT = '2ce9687b2468eded6dc32f5841c755a256e6e9dd'
CERT_SHA256 = 'a10cdd4ff4000673f14db9fe4a814f8bbd63b6e2007f5f38b1fe5cfb7310f353'
REPO_URL = f'https://github.com/{APP_ID.split(".")[2]}/words-combination'
QA_ARCHIVES = (
    'docs/qa/pre04-17/gradle-report.zip',
    'docs/qa/pre04-17/manual-witness.zip',
    'docs/qa/pre04-17/remote-manual-witness.zip',
    'docs/qa/pre04-17/verification-logs.zip',
)


def scalar(contents, key):
    matches = re.findall(rf'^{re.escape(key)}: (.+)$', contents, re.MULTILINE)
    if len(matches) != 1:
        raise ValueError(f'Expected exactly one {key} metadata field')
    return matches[0].strip()


def verify_recipe(contents):
    expected = {
        'License': 'MIT',
        'SourceCode': REPO_URL,
        'IssueTracker': REPO_URL + '/issues',
        'RepoType': 'git',
        'Repo': REPO_URL + '.git',
        'Binaries': REPO_URL + '/releases/download/%v/WordsCombination-v%v.apk',
        'AllowedAPKSigningKeys': CERT_SHA256,
        'AutoUpdateMode': 'None',
        'UpdateCheckMode': 'Tags',
        'CurrentVersion': VERSION,
        'CurrentVersionCode': str(CODE),
    }
    for field, value in expected.items():
        if scalar(contents, field) != value:
            raise ValueError(f'Unexpected {field} metadata value')
    if not re.search(r'^Categories:\n  - Games$', contents, re.MULTILINE):
        raise ValueError('F-Droid game category is missing')
    if len(re.findall(r'^  - versionName:', contents, re.MULTILINE)) != 1:
        raise ValueError('Expected exactly one candidate build')
    build = re.search(r'^Builds:\n(?P<body>(?:  .*\n|    .*\n|      .*\n)+)',
                      contents, re.MULTILINE)
    if not build:
        raise ValueError('Builds block missing')
    body = build.group('body')
    if not re.search(rf'^  - versionName: {re.escape(VERSION)}$', body, re.MULTILINE):
        raise ValueError('Unexpected Builds.versionName value')
    for field, value in {
        'versionCode': str(CODE),
        'commit': COMMIT,
        'subdir': 'app',
        'output': 'build/outputs/apk/release/app-release-unsigned.apk',
    }.items():
        if not re.search(rf'^    {field}: {re.escape(value)}$', body, re.MULTILINE):
            raise ValueError(f'Unexpected Builds.{field} value')
    if not re.search(r'^    gradle:\n      - yes$', body, re.MULTILINE):
        raise ValueError('Build must use the normal Gradle release task')
    if 'scanignore:' in contents or 'disable:' in contents:
        raise ValueError('Scanner bypass / disabled build is not allowed')
    for file in QA_ARCHIVES:
        if f'      - {file}\n' not in contents:
            raise ValueError('Missing explicitly removable QA archive: ' + file)
    if "UpdateCheckData: 'app/build.gradle.kts|versionCode *= *([0-9]+)|.|versionName *= *\"([0-9.]+)\"'" not in contents:
        raise ValueError('UpdateCheckData must inspect Gradle version fields')


def git_at_tag(repository, path):
    return subprocess.check_output(
        ['git', 'show', f'{VERSION}:{path}'], cwd=repository, text=True,
    )


def verify_tag(repository):
    commit = subprocess.check_output(
        ['git', 'rev-parse', f'refs/tags/{VERSION}^{{commit}}'],
        cwd=repository, text=True,
    ).strip()
    if commit != COMMIT:
        raise ValueError('Release tag resolves to a different commit')
    build = git_at_tag(repository, 'app/build.gradle.kts')
    for field, expected in (
        ('applicationId', APP_ID),
        ('versionName', VERSION),
        ('versionCode', CODE),
    ):
        literal = f'"{expected}"' if isinstance(expected, str) else str(expected)
        if not re.search(rf'\b{field}\s*=\s*{re.escape(literal)}\b' if isinstance(expected, int)
                         else rf'\b{field}\s*=\s*{re.escape(literal)}', build):
            raise ValueError(f'Tag {VERSION} has unexpected {field}')
    if not git_at_tag(repository, 'LICENSE').startswith('MIT License'):
        raise ValueError('Tag does not contain the declared MIT project license')
    wrapper = git_at_tag(repository, 'gradle/wrapper/gradle-wrapper.properties')
    catalog = git_at_tag(repository, 'gradle/libs.versions.toml')
    if 'gradle-9.3.1-bin.zip' not in wrapper:
        raise ValueError('Tag uses an unexpected Gradle distribution')
    if 'agp = "9.1.1"' not in catalog or 'kotlin = "2.2.10"' not in catalog:
        raise ValueError('Tag uses an unexpected AGP/Kotlin toolchain')
    for file in QA_ARCHIVES:
        subprocess.run(['git', 'cat-file', '-e', f'{VERSION}:{file}'],
                       cwd=repository, check=True)
    for locale in ('en-US', 'ru-RU'):
        for name in ('title.txt', 'short_description.txt', 'full_description.txt'):
            git_at_tag(repository, f'fastlane/metadata/android/{locale}/{name}')
    return commit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--metadata', type=Path)
    args = parser.parse_args()
    metadata = args.metadata or (args.repo / 'docs/fdroiddata/metadata' / f'{APP_ID}.yml')
    try:
        verify_recipe(metadata.read_text(encoding='utf-8'))
        commit = verify_tag(args.repo)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.error(str(error))
    print(json.dumps({
        'status': 'FDROID_STATIC_CANDIDATE_PASS_EXTERNAL_BUILD_NOT_VERIFIED',
        'app_id': APP_ID, 'tag': VERSION, 'version_code': CODE,
        'commit': commit, 'signer_certificate_sha256': CERT_SHA256,
        'fdroid_lint_verified': False, 'fdroid_buildserver_verified': False,
        'inclusion_policy_eligible': 'NOT_CONFIRMED',
    }, indent=2))


if __name__ == '__main__':
    main()
