#!/usr/bin/env python3
"""Temporary synthetic identity on an already running local Grocy; never starts services."""
import json
import secrets
import sys
from pathlib import Path
import importlib.util
spec = importlib.util.spec_from_file_location('fixture_helpers', Path(__file__).with_name('prepare-stage3-fixtures.py'))
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
mode, path = sys.argv[1:]
assert mode in ('prepare', 'cleanup')
if mode == 'cleanup':
    helper.cleanup(json.loads(Path(path).read_text()))
    Path(path).unlink()
    print('Removed temporary Stage 8 identities and credentials.')
else:
    base = 'http://127.0.0.1:9283'
    token = secrets.token_hex(6)
    key_id, key = helper.mint_key(base, 'admin', 'admin', 'Stillroom Stage8 temporary ' + token)
    f = dict(base_url=base+'/api', bootstrap_key=key, bootstrap_key_id=key_id, key_ids=[], user_ids=[], parent_key=key, parent_user_id=1)
    doc = {'fixtures':[f]}
    helper.write_private(path,doc)
    def call(endpoint, method='GET', body=None):
        status, value = helper.request(base,endpoint,key,method,body)
        assert status in (200,204), (endpoint,status)
        return value
    f['version'] = call('/system/info')['grocy_version']['Version']
    username = 'stillroom_stage8_child_' + token
    password = secrets.token_urlsafe(24)
    call('/users','POST',dict(username=username,first_name='Stage8',last_name='child',password=password,picture_file_name=None))
    user_id = int(next(u['id'] for u in call('/users') if u['username']==username))
    f['user_ids'].append(user_id); f['child_user_id']=user_id
    helper.write_private(path,doc)
    child_key_id, child_key = helper.mint_key(base,username,password,'Stillroom Stage8 child '+token)
    f['key_ids'].append(child_key_id); f['child_key']=child_key
    helper.write_private(path,doc)
    ids={r['name']:int(r['id']) for r in call('/objects/permission_hierarchy')}
    call(f'/users/{user_id}/permissions','PUT',{'permissions':[ids['CHORES'],ids['CHORE_TRACK_EXECUTION']]})
    status,user=helper.request(base,'/user',child_key)
    assert status==200 and int(user[0]['id'])==user_id
    helper.write_private(path,doc)
    print('Verified existing local Grocy '+f['version']+' and temporary separate child identity.')
