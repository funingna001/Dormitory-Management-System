"""Copy existing data for local smoke tests. Never modifies the source database."""
from pathlib import Path
import sqlite3
import json

root = Path(__file__).resolve().parents[1]
artifact = root / 'artifacts'
artifact.mkdir(exist_ok=True)
target = artifact / 'smoke.db'
if target.exists():
    raise SystemExit('Smoke database already exists; refusing to overwrite it.')
with sqlite3.connect('file:' + (root / 'susheguanli.db').as_posix() + '?mode=ro', uri=True) as source:
    with sqlite3.connect(target) as dest:
        source.backup(dest)
        # Only the disposable smoke-test copy uses this test credential.
        dest.execute("UPDATE user SET password=? WHERE username='admin'", ('SmokeAdmin123!',))
        print(json.dumps({t:dest.execute('SELECT COUNT(*) FROM ' + t).fetchone()[0] for t in ['student','dormitory','building','repair_request']}))
