"""Provision a local, database-scoped application account after verified migration.

Reads the MySQL administrator password from MIGRATION_MYSQL_PASSWORD. Generates
an application password in the git-ignored .env file without printing credentials.
Existing .env files and existing database accounts are never overwritten.
"""
import json
import os
from pathlib import Path
import secrets
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'artifacts/migration-deps'))
import pymysql


def main():
    local = ROOT / '.env'
    if local.exists():
        raise RuntimeError('.env already exists; refusing to overwrite local credentials.')
    password = os.environ.get('MIGRATION_MYSQL_PASSWORD')
    if password is None:
        raise RuntimeError('Set MIGRATION_MYSQL_PASSWORD for the current process.')
    result_path = ROOT / 'artifacts/mysql-migration-result.json'
    report = json.loads(result_path.read_text(encoding='utf-8-sig'))
    if not report.get('success') or report.get('target') != 'susheguanli':
        raise RuntimeError('A successful verified migration is required before configuring the application.')
    app_user = 'dormitory_app'
    app_password = secrets.token_urlsafe(36)
    admin = pymysql.connect(host='127.0.0.1', port=3306, user='root', password=password,
                            charset='utf8mb4', autocommit=True)
    try:
        with admin.cursor() as cursor:
            cursor.execute('SELECT COUNT(*) FROM mysql.user WHERE User=%s', (app_user,))
            if cursor.fetchone()[0]:
                raise RuntimeError('Application username already exists; refusing to change an existing account.')
            # Persist generated credentials before provisioning so an interrupted run is recoverable.
            with local.open('x', encoding='utf-8') as config:
                config.write('# Local MySQL credentials. Do not commit or publish this file.\n')
                config.write('DB_USERNAME=' + app_user + '\nDB_PASSWORD=' + app_password + '\n')
            for host in ('localhost', '127.0.0.1'):
                cursor.execute('CREATE USER %s@%s IDENTIFIED BY %s', (app_user, host, app_password))
                cursor.execute('GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, INDEX ON `susheguanli`.* TO %s@%s', (app_user, host))
        app = pymysql.connect(host='127.0.0.1', port=3306, user=app_user, password=app_password,
                              database='susheguanli', charset='utf8mb4')
        try:
            with app.cursor() as cursor:
                cursor.execute('SELECT COUNT(*) FROM student')
                count = cursor.fetchone()[0]
        finally:
            app.close()
        print(json.dumps({'configured': True, 'database': 'susheguanli', 'appUser': app_user,
                          'credentials': '.env (git-ignored)', 'verifiedStudents': count,
                          'permissions': 'SELECT, INSERT, UPDATE, DELETE, CREATE, INDEX on susheguanli only'}))
    finally:
        admin.close()


if __name__ == '__main__':
    main()
