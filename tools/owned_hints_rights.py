"""Read-only evidence validation for owned hints. Never authors human decisions.

The owner act and the separate, per-text human audit are trust inputs, not
outputs of a build. Hashes bind them to bytes; they do not prove authorship or
replace a person's assessment. See docs/OWN_HINTS_VERIFICATION.md.
"""
from datetime import date
import hashlib
import json
import re

from tools.owned_hints import sha, targets

PLACEHOLDER = '<ЛИЦЕНЗИЯ — решает владелец>'
CONFIRMATIONS = ('owner_reviewed_all_39', 'owner_confirms_rights',
                 'owner_approves_exact_texts', 'owner_approves_cc0_dedication')


def exact(actual, expected):
    # JSON equality must distinguish true from 1 and counts from floats.
    return json.dumps(actual, sort_keys=True) == json.dumps(expected, sort_keys=True)


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate evidence field: ' + key)
            result[key] = value
        return result
    try:
        return json.loads(path.read_bytes(), object_pairs_hook=unique)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ValueError('Invalid evidence JSON: ' + path.name) from error


def calendar_date(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}', value):
        raise ValueError('Expected calendar date YYYY-MM-DD')
    return date.fromisoformat(value)


def owner_decision(candidate_path, campaign_path):
    """None for absent/exact unsigned template; otherwise validate the whole act.

    There is deliberately no flag or CLI option substituting for this file.
    Only tests may supply a different candidate directory, inside a temp fixture.
    """
    path = candidate_path.parent / 'OWN_HINTS_OWNER_APPROVAL.json'
    if not path.exists():
        return None
    value = read_json(path)
    expected = dict(schema='owned-hints-owner-approval', version=1,
                    candidate_path='docs/owned_hints.json', candidate_sha256=sha(candidate_path),
                    campaign_sha256=sha(campaign_path), expected_unique_hints=39,
                    expected_role_bindings=40, license='CC0-1.0', approved_on=None, decision='pending',
                    **dict.fromkeys(CONFIRMATIONS, False))
    if exact(value, expected):
        return None
    if not isinstance(value, dict):
        raise ValueError('Invalid owner approval')
    calendar_date(value.get('approved_on'))
    expected.update(decision='approved', approved_on=value['approved_on'],
                    **dict.fromkeys(CONFIRMATIONS, True))
    if not exact(value, expected):
        raise ValueError('Owner approval does not match exact candidate/campaign/schema/confirmations')
    candidate = read_json(candidate_path)
    entries = candidate['entries']
    if (len(entries) != 39 or sum(len(e['roles']) for e in entries) != 40 or
            {e['word']: e['roles'] for e in entries} != targets(read_json(campaign_path))):
        raise ValueError('Owner approval requires all 39 hints / 40 bindings')
    return dict(approval_sha256=sha(path), approved_on=value['approved_on'],
                candidate_sha256=sha(candidate_path), campaign_sha256=sha(campaign_path))


def database_metadata(decision):
    """Only service metadata changes; hint text and candidate bytes never do."""
    if decision is None:
        return dict(own_license=PLACEHOLDER)
    return dict(own_license='CC0-1.0', owner_approval_sha256=decision['approval_sha256'])


def publication_fields(candidate_path, campaign_path, database_sha256, decision):
    """Derive flags from both independent evidence files, never manifest flags."""
    result = dict(audit=dict(passed=False),
                  rights_review=dict(passed=False, status='pending-owner-approval', replacement_required=False),
                  own_license=database_metadata(decision)['own_license'],
                  publication_status='draft-not-for-public-release')
    if decision is not None:
        result['rights_review'].update(status='pending-manual-audit', **decision)
    path = candidate_path.parent / 'OWN_HINTS_MANUAL_AUDIT.json'
    if not path.exists():
        return result
    if decision is None:
        raise ValueError('Human audit requires a valid owner approval first')
    value = read_json(path)
    if not isinstance(value, dict) or not isinstance(value.get('reviewer'), str) or not value['reviewer'].strip():
        raise ValueError('Human audit requires a named reviewer')
    reviewed = calendar_date(value.get('reviewed_on'))
    if reviewed < calendar_date(decision['approved_on']):
        raise ValueError('Human audit predates owner approval')
    entries = read_json(candidate_path)['entries']
    rows = [dict(word=e['word'], hint_sha256=hashlib.sha256(e['hint'].encode('utf-8')).hexdigest(),
                 quality='pass', originality='pass', correctness='pass', no_answer_leak='pass')
            for e in entries]
    expected = dict(schema='owned-hints-human-audit', version=1, reviewer=value['reviewer'],
                    reviewed_on=value['reviewed_on'], candidate_sha256=sha(candidate_path),
                    campaign_sha256=sha(campaign_path), database_sha256=database_sha256,
                    owner_approval_sha256=decision['approval_sha256'], checked=39, bad=0, entries=rows)
    if not exact(value, expected):
        raise ValueError('Human audit must cover exact package, 39/39 texts and zero problems')
    result['audit'] = dict(passed=True, checked=39, bad=0, reviewer=value['reviewer'],
                           reviewed_on=value['reviewed_on'], database_sha256=database_sha256,
                           candidate_sha256=sha(candidate_path), campaign_sha256=sha(campaign_path),
                           owner_approval_sha256=decision['approval_sha256'], report_sha256=sha(path))
    result['rights_review'].update(passed=True, status='approved')
    result['publication_status'] = 'approved'
    return result
