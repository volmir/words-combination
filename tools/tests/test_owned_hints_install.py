"""Installation and lexical provenance gates after the runtime subset conversion."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from tools import build_owned_hints as owned
from tools.owned_hints import sha
from tools.build_game_data import publish
ROOT=Path(__file__).resolve().parents[2]

class OwnedHintsInstallTest(unittest.TestCase):
    def test_categories_and_full_twenty_t6_pool_remain_independent_of_runtime_texts(self):
        candidate,index,metadata,_=owned.inputs(ROOT/'data')
        campaign=json.loads((ROOT/'data/campaign.json').read_text())
        task=next(t for l in campaign['levels'] for t in l['tasks'] if t['type']=='T6')
        self.assertEqual(4,task['n']);self.assertEqual(20,len(task['words']))
        self.assertTrue(all(metadata[w]['category']=='professions' and metadata[w]['category_source'].startswith('owner:SHORT15-05') for w in task['words']))
        self.assertEqual('CC-BY-SA-4.0',index['license']);self.assertEqual([],index['screening']['suspicious_matches'])
        self.assertEqual(39,len(candidate['entries']))

    def test_missing_leaking_or_changed_source_is_rejected(self):
        source=json.loads(owned.CANDIDATE.read_text())
        for mode in ('missing','leak','changed'):
            value=copy.deepcopy(source);entry=next(e for e in value['entries'] if any(r['type']=='T4' for r in e['roles']))
            if mode=='missing':value['entries'].remove(entry)
            else:entry['hint']=entry['word'] if mode=='leak' else 'Новый синтетический текст без разрешения.'
            with tempfile.TemporaryDirectory() as temp:
                p=Path(temp)/'owned_hints.json';p.write_text(json.dumps(value,ensure_ascii=False))
                with self.assertRaises(ValueError):owned.inputs(ROOT/'data',p)

    def test_lexical_index_cannot_carry_hidden_prose_or_changed_sources(self):
        index=json.loads(owned.INDEX.read_text())
        altered=copy.deepcopy(index);altered['hidden_prose']='Посторонний текст.'
        free=copy.deepcopy(index);free['word_metadata']['артист']['category_source']='Синтетическое чужое описание.'
        for value in (altered,free):
            with tempfile.TemporaryDirectory() as temp:
                p=Path(temp)/'index.json';p.write_text(json.dumps(value,ensure_ascii=False))
                with self.assertRaises(ValueError):owned.inputs(ROOT/'data',index_path=p)

    def test_reduced_campaign_cannot_reduce_required_coverage(self):
        c=json.loads(owned.CANDIDATE.read_text());campaign=json.loads((ROOT/'data/campaign.json').read_text())
        level=next(l for l in campaign['levels'] if any(t['type']=='T4' for t in l['tasks']))
        t=next(t for t in level['tasks'] if t['type']=='T4');level['tasks'].remove(t)
        for e in c['entries']:e['roles']=[r for r in e['roles'] if r['task_id']!=t['id']]
        c['entries']=[e for e in c['entries'] if e['roles']]
        with self.assertRaises(ValueError):owned.coverage(c,campaign)
