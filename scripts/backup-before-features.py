"""Back up current MySQL data and source before the approved business expansion."""
from pathlib import Path
from datetime import datetime
import sys
import zipfile
import json

root=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root/'artifacts/migration-deps'))
import pymysql
config={}
for line in (root/'.env').read_text(encoding='utf-8').splitlines():
    if line.strip() and not line.lstrip().startswith('#'):
        key,value=line.split('=',1);config[key]=value
backup=root/'backups'/('before-business-features-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
backup.mkdir(parents=True)
db=pymysql.connect(host='127.0.0.1',user=config['DB_USERNAME'],password=config['DB_PASSWORD'],database='susheguanli',charset='utf8mb4')
counts={}
try:
    with db.cursor() as cursor, (backup/'mysql-data.sql').open('w',encoding='utf-8') as output:
        cursor.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT')
        cursor.execute('SHOW TABLES');tables=[r[0] for r in cursor.fetchall()]
        output.write("SET NAMES utf8mb4;\nSET time_zone='+08:00';\n")
        for table in tables:
            assert table.replace('_','').isalnum()
            cursor.execute('SHOW CREATE TABLE `'+table+'`');output.write(cursor.fetchone()[1]+';\n')
            cursor.execute('SELECT * FROM `'+table+'`');rows=cursor.fetchall();counts[table]=len(rows)
            columns=[r[0] for r in cursor.description]
            for row in rows:
                values=','.join(db.escape(value) for value in row)
                output.write('INSERT INTO `'+table+'` ('+','.join('`'+c+'`' for c in columns)+') VALUES ('+values+');\n')
finally:
    db.close()
with zipfile.ZipFile(backup/'source.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for folder in ['src','frontend','deploy','docs','scripts']:
        for path in (root/folder).rglob('*'):
            if path.is_file() and not any(part in ['node_modules','dist','__pycache__'] for part in path.parts):
                archive.write(path,path.relative_to(root))
    archive.write(root/'pom.xml','pom.xml')
(backup/'summary.json').write_text(json.dumps(counts,indent=2),encoding='utf-8')
print(json.dumps({'backup':str(backup),'tables':counts}))
