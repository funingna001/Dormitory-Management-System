"""Read-only verification against the pre-refactor SQLite snapshot."""
from pathlib import Path
import sqlite3
import hashlib
import json

root=Path(__file__).resolve().parents[1]
tables=['user','student','dormitory','building','repair_request']
with sqlite3.connect('file:'+(root/'susheguanli.db').as_posix()+'?mode=ro',uri=True) as current:
    with sqlite3.connect('file:'+(root/'backups/susheguanli-before-separation.db').as_posix()+'?mode=ro',uri=True) as backup:
        for table in tables:
            before=backup.execute('SELECT * FROM '+table+' ORDER BY id').fetchall()
            after=current.execute('SELECT * FROM '+table+' ORDER BY id').fetchall()
            assert before==after, table+' differs from backup'
            print(table,len(after),'rows: unchanged')
