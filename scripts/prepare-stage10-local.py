#!/usr/bin/env python3
"""Temporary key on the already-running synthetic local Grocy. No service startup."""
import importlib.util,json,secrets,sys
from pathlib import Path
spec=importlib.util.spec_from_file_location('fixture_helpers',Path(__file__).with_name('prepare-stage3-fixtures.py'))
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
mode,path=sys.argv[1:]
assert mode in ('prepare','cleanup')
if mode=='cleanup':
 helper.cleanup(json.loads(Path(path).read_text()));Path(path).unlink()
 print('Revoked the temporary Stage 10 local Grocy key.')
else:
 base='http://127.0.0.1:9283'
 key_id,key=helper.mint_key(base,'admin','admin','Stillroom Stage10 temporary '+secrets.token_hex(6))
 fixture=dict(base_url=base+'/api',bootstrap_key=key,bootstrap_key_id=key_id,key_ids=[],user_ids=[],parent_key=key)
 document={'fixtures':[fixture]};helper.write_private(path,document)
 status,info=helper.request(base,'/system/info',key)
 assert status==200
 fixture['version']=info['grocy_version']['Version'];helper.write_private(path,document)
 print('Verified existing local Grocy '+fixture['version']+'. No services started.')
