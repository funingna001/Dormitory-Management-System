"""Run the integration suite in a disposable local MySQL schema, never the business database."""
import datetime
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'artifacts/migration-deps'))
import pymysql

password=os.environ.get('MIGRATION_MYSQL_PASSWORD')
if password is None:
    raise SystemExit('Set MIGRATION_MYSQL_PASSWORD for the current process.')
database='susheguanli_test_'+datetime.datetime.now().strftime('%Y%m%d_%H%M%S')+'_'+secrets.token_hex(3)
assert re.fullmatch(r'susheguanli_test_[a-zA-Z0-9_]+',database)
admin=pymysql.connect(host='127.0.0.1',user='root',password=password,charset='utf8mb4',autocommit=True)
try:
    with admin.cursor() as cursor:
        cursor.execute('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
    environment=os.environ.copy()
    environment.update(TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3306/'+database+'?serverTimezone=Asia/Shanghai',
                       TEST_MYSQL_USERNAME='root',TEST_MYSQL_PASSWORD=password)
    command=[shutil.which('mvn.cmd') or 'mvn','-B','-ntp','-Dmaven.repo.local='+str(ROOT/'.maven-repository'),'test']
    log=ROOT/'artifacts/mysql-integration-tests.log'
    with log.open('wb') as output:
        result=subprocess.run(command,cwd=ROOT,env=environment,stdout=output,stderr=subprocess.STDOUT)
    report=dict(database=database,exitCode=result.returncode,log=str(log),isolated=True)
    # Only this unique database created above can be dropped by this script.
    with admin.cursor() as cursor:
        cursor.execute('DROP DATABASE `'+database+'`')
    report['testDatabaseRemoved']=True
    (ROOT/'artifacts/mysql-tests-result.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report,indent=2))
    sys.exit(result.returncode)
finally:
    admin.close()
