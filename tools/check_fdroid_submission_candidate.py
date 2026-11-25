#!/usr/bin/env python3
"""Static checks for this project's reviewed F-Droid recipe; NOT a build or scanner."""

import argparse
import json
from pathlib import Path
import re
import subprocess


APP_ID = 'io.github.volmir.wordscombination'
CERT_SHA256 = 'a10cdd4ff4000673f14db9fe4a814f8bbd63b6e2007f5f38b1fe5cfb7310f353'
REPO_URL = f'https://github.com/{APP_ID.split(".")[2]}/words-combination'
QA_ARCHIVES = (
    'docs/qa/pre04-17/gradle-report.zip',
    'docs/qa/pre04-17/manual-witness.zip',
    'docs/qa/pre04-17/remote-manual-witness.zip',
    'docs/qa/pre04-17/verification-logs.zip',
)


def scalar(contents, key):
    # ruamel.yaml may wrap a long URL onto the next indented line.
    lines = contents.splitlines()
    matches = [i for i, line in enumerate(lines) if line.startswith(key + ':')]
    if len(matches) != 1:
        raise ValueError(f'Expected exactly one {key} metadata field')
    index = matches[0]
    value = lines[index].partition(':')[2].strip()
    if not value and index + 1 < len(lines) and lines[index + 1].startswith('  '):
        value = lines[index + 1].strip()
    if not value:
        raise ValueError(f'Empty {key} metadata field')
    return value


def verify_recipe(contents):
    expected = {
        'License': 'MIT',
        'AuthorName': APP_ID.split('.')[2].capitalize(),
        'SourceCode': REPO_URL,
        'IssueTracker': REPO_URL + '/issues',
        'RepoType': 'git',
        'Repo': REPO_URL + '.git',
        'Binaries': REPO_URL + '/releases/download/%v/WordsCombination-v%v.apk',
        'AllowedAPKSigningKeys': CERT_SHA256,
        'AutoUpdateMode': 'Version',
        'UpdateCheckMode': 'Tags',
    }
    for field, value in expected.items():
        if scalar(contents, field) != value:
            raise ValueError(f'Unexpected {field} metadata value')
    if not re.search(r'^Categories:\n  - Word Game$', contents, re.MULTILINE):
        raise ValueError('F-Droid game category is missing')
    version = scalar(contents, 'CurrentVersion')
    code = scalar(contents, 'CurrentVersionCode')
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        raise ValueError('Invalid CurrentVersion')
    if not re.fullmatch(r'[1-9][0-9]*', code):
        raise ValueError('Invalid CurrentVersionCode')
    if len(re.findall(r'^  - versionName:', contents, re.MULTILINE)) != 1:
        raise ValueError('Expected exactly one candidate build')
    build = re.search(r'^Builds:\n(?P<body>(?:  .*\n|    .*\n|      .*\n)+)',
                      contents, re.MULTILINE)
    if not build:
        raise ValueError('Builds block missing')
    body = build.group('body')
    if not re.search(rf'^  - versionName: {re.escape(version)}$', body, re.MULTILINE):
        raise ValueError('Unexpected Builds.versionName value')
    for field, value in {
        'versionCode': code,
        'subdir': 'app',
    }.items():
        if not re.search(rf'^    {field}: {re.escape(value)}$', body, re.MULTILINE):
            raise ValueError(f'Unexpected Builds.{field} value')
    if not re.search(r'^    gradle:\n      - yes$', body, re.MULTILINE):
        raise ValueError('Build must use the normal Gradle release task')
    commits = re.findall(r'^    commit: ([0-9a-f]{40})$', body, re.MULTILINE)
    if len(commits) != 1:
        raise ValueError('Unexpected Builds.commit value: expected one full Git SHA')
    ignores = re.findall(r'^    scanignore:\n((?:      - .*\n)+)', body, re.MULTILINE)
    if (len(ignores) != 1 or ignores[0].splitlines() != ['      - data/word_sources.jsonl.gz']
            or re.search(r'^scanignore:|^\s*disable:', contents, re.MULTILINE)):
        raise ValueError('Scanner bypass / disabled build is not allowed')
    deletes = re.findall(r'^    scandelete:\n((?:      - .*\n)+)', body, re.MULTILINE)
    removed = [line.removeprefix('      - ') for block in deletes for line in block.splitlines()]
    if any(file not in QA_ARCHIVES for file in removed):
        raise ValueError('Unexpected scandelete path; provenance must be retained')
    for file in QA_ARCHIVES:
        if file not in removed:
            raise ValueError('Missing explicitly removable QA archive: ' + file)
    for field in ('output', 'WebSite', 'UpdateCheckData'):
        if re.search(rf'^\s*{field}:', contents, re.MULTILINE):
            raise ValueError('Obsolete metadata field: ' + field)
    # Project agreement from MR !58000, not a restriction of the F-Droid schema.
    for field in ('MaintainerNotes', 'sudo', 'prebuild', 'Name', 'Summary', 'Description'):
        if re.search(rf'^\s*{field}:', contents, re.MULTILINE):
            raise ValueError('Field contradicts the reviewed project recipe: ' + field)
    return {'version': version, 'code': int(code), 'commit': commits[0]}


def verify_metadata(metadata):
    recipe = verify_recipe(metadata.read_text(encoding='utf-8'))
    for summary in sorted(metadata.with_suffix('').glob('*/summary.txt')):
        raise ValueError('Localized summary must come from upstream Fastlane: ' + str(summary))
    return recipe


def git_at_tag(repository, version, path):
    return subprocess.check_output(
        ['git', 'show', f'{version}:{path}'], cwd=repository, text=True,
    )


def verify_tag(repository, recipe):
    version, code, expected_commit = (recipe[key] for key in ('version', 'code', 'commit'))
    commit = subprocess.check_output(
        ['git', 'rev-parse', f'refs/tags/{version}^{{commit}}'],
        cwd=repository, text=True,
    ).strip()
    if commit != expected_commit:
        raise ValueError('Release tag resolves to a different commit')
    build = git_at_tag(repository, version, 'app/build.gradle.kts')
    for field, expected in (
        ('applicationId', APP_ID),
        ('versionName', version),
        ('versionCode', code),
    ):
        literal = f'"{expected}"' if isinstance(expected, str) else str(expected)
        if not re.search(rf'\b{field}\s*=\s*{re.escape(literal)}\b' if isinstance(expected, int)
                         else rf'\b{field}\s*=\s*{re.escape(literal)}', build):
            raise ValueError(f'Tag {version} has unexpected {field}')
    if not git_at_tag(repository, version, 'LICENSE').startswith('MIT License'):
        raise ValueError('Tag does not contain the declared MIT project license')
    wrapper = git_at_tag(repository, version, 'gradle/wrapper/gradle-wrapper.properties')
    catalog = git_at_tag(repository, version, 'gradle/libs.versions.toml')
    if 'gradle-9.3.1-bin.zip' not in wrapper:
        raise ValueError('Tag uses an unexpected Gradle distribution')
    if 'agp = "9.1.1"' not in catalog or 'kotlin = "2.2.10"' not in catalog:
        raise ValueError('Tag uses an unexpected AGP/Kotlin toolchain')
    for file in QA_ARCHIVES:
        subprocess.run(['git', 'cat-file', '-e', f'{version}:{file}'],
                       cwd=repository, check=True)
    for locale in ('en-US', 'ru-RU'):
        for name in ('title.txt', 'short_description.txt', 'full_description.txt'):
            git_at_tag(repository, version, f'fastlane/metadata/android/{locale}/{name}')
    return commit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--metadata', type=Path)
    args = parser.parse_args()
    metadata = args.metadata or (args.repo / 'docs/fdroiddata/metadata' / f'{APP_ID}.yml')
    try:
        recipe = verify_metadata(metadata)
        commit = verify_tag(args.repo, recipe)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.error(str(error))
    print(json.dumps({
        'status': 'FDROID_STATIC_CANDIDATE_PASS_EXTERNAL_BUILD_NOT_VERIFIED',
        'app_id': APP_ID, 'tag': recipe['version'], 'version_code': recipe['code'],
        'commit': commit, 'signer_certificate_sha256': CERT_SHA256,
        'fdroid_lint_verified': False, 'fdroid_buildserver_verified': False,
        'inclusion_policy_eligible': 'NOT_CONFIRMED',
    }, indent=2))


if __name__ == '__main__':
    main()
