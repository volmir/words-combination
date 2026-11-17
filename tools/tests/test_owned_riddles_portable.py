"""Clean source-tree rebuild without native storage modules, caches, Git or network."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
class OwnedRiddlesPortableTest(unittest.TestCase):
    def test_clean_offline_source_tree_regenerates_exact_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            shutil.copytree(ROOT/'tools',root/'tools',ignore=shutil.ignore_patterns('cache','tests','__pycache__'))
            (root/'docs').mkdir();(root/'data').mkdir()
            for name in ('owned_hints.json','OWN_HINTS_OWNER_APPROVAL.json','OWN_HINTS_MANUAL_AUDIT.json'):
                shutil.copyfile(ROOT/'docs'/name,root/'docs'/name)
            for name in ('campaign.json','words.txt','manifest.json'):
                shutil.copyfile(ROOT/'data'/name,root/'data'/name)
            script='''import sys, runpy, socket
sys.modules['sqlite3']=None
sys.modules['_sqlite3']=None
def denied(event,args):
    if event.startswith('socket.'): raise AssertionError('Network forbidden in portable generator')
sys.addaudithook(denied)
sys.argv=['tools/build_owned_hints.py']
runpy.run_path('tools/build_owned_hints.py',run_name='__main__')
'''
            result=subprocess.run([sys.executable,'-c',script],cwd=root,capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stdout+result.stderr)
            self.assertEqual((ROOT/'data/owned_riddles.json').read_bytes(),(root/'data/owned_riddles.json').read_bytes())
            self.assertEqual((ROOT/'data/manifest.json').read_bytes(),(root/'data/manifest.json').read_bytes())
            self.assertFalse((root/'.local').exists())
