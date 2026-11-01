"""Backup/restore regressions use generated data and separate temporary targets only."""
import argparse
from contextlib import closing
from datetime import datetime, timedelta, timezone
import importlib.util
import json
import os
from pathlib import Path
import secrets
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('backup_restore', ROOT / 'scripts/backup-restore.py')
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class BackupRestoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='backup-rehearsal-', dir=ROOT / 'artifacts')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.uploads = self.root / 'images'
        (self.uploads / 'nested').mkdir(parents=True)
        (self.uploads / 'nested/image.png').write_bytes(bytes(range(256)))
        (self.uploads / 'inspection.png').write_bytes(b'example inspection attachment')
        self.source = self.root / 'source.sqlite'
        with closing(sqlite3.connect(self.source)) as connection, connection:
            connection.execute('CREATE TABLE student(id INTEGER PRIMARY KEY, name TEXT, photo BLOB)')
            connection.executemany('INSERT INTO student VALUES(?,?,?)', [(1, '张同学\n"测试"', b'\x00\xff'), (2, None, None)])
            connection.execute('CREATE TABLE extra_business_table(id INTEGER PRIMARY KEY, amount INTEGER)')
            connection.execute('INSERT INTO extra_business_table VALUES(1,12345)')
        self.args = argparse.Namespace(offline=True, engine='sqlite', sqlite_path=str(self.source),
                                      output=str(self.root / 'backups'), upload_dir=str(self.uploads),
                                      keep_days=30, keep_last=7)

    def create(self):
        return Path(backup.create(self.args, {})['backup'])

    def restore_args(self, path):
        return argparse.Namespace(backup=str(path), upload_dir=str(self.root / 'restored-images'),
                                  sqlite_path=str(self.root / 'restored.sqlite'))

    def test_complete_roundtrip_verifies_every_table_and_attachment(self):
        path = self.create()
        manifest = backup.verify_backup(path)
        self.assertEqual(set(manifest['tables']), {'student', 'extra_business_table'})
        result = backup.restore(self.restore_args(path), {})
        self.assertTrue(result['verified'])
        self.assertEqual(backup.sqlite_signatures(self.source), backup.sqlite_signatures(self.root / 'restored.sqlite'))
        self.assertEqual((self.root / 'restored-images/nested/image.png').read_bytes(), bytes(range(256)))
        with self.assertRaises(ValueError):
            backup.restore(self.restore_args(path), {})

    def test_corrupt_attachment_blocks_restore_before_creating_targets(self):
        path = self.create()
        (path / 'uploads/inspection.png').write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            backup.restore(self.restore_args(path), {})
        self.assertFalse((self.root / 'restored.sqlite').exists())

    def test_existing_database_is_never_overwritten(self):
        path = self.create()
        args = self.restore_args(path)
        args.sqlite_path = str(self.source)
        before = backup.digest(self.source)
        with self.assertRaises(ValueError):
            backup.restore(args, {})
        self.assertEqual(before, backup.digest(self.source))

    def test_offline_confirmation_is_required(self):
        self.args.offline = False
        with self.assertRaisesRegex(ValueError, 'writers'):
            self.create()

    def test_manifest_path_traversal_and_missing_files_are_rejected(self):
        path = self.create()
        manifest_path = path / 'manifest.json'
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        manifest['files']['../source.sqlite'] = backup.digest(self.source)
        manifest_path.write_text(json.dumps(manifest), encoding='utf-8')
        with self.assertRaises(ValueError):
            backup.verify_backup(path)

    def test_retention_keeps_recent_backups_and_ignores_unrelated_directories(self):
        old = self.create()
        manifest_path = old / 'manifest.json'
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        manifest['createdAt'] = (datetime.now(timezone.utc) - timedelta(days=60)).isoformat()
        manifest_path.write_text(json.dumps(manifest), encoding='utf-8')
        newer = self.create()
        unrelated = self.root / 'backups/manual-copy'
        unrelated.mkdir()
        self.assertEqual(backup.prune(self.root / 'backups', 30, 1), [old.name])
        self.assertTrue(newer.exists())
        self.assertTrue(unrelated.exists())

    @unittest.skipUnless(os.environ.get('TEST_BACKUP_MYSQL_PORT'), 'Requires a disposable local MySQL server')
    def test_mysql_roundtrip_all_application_tables_indexes_and_images(self):
        from decimal import Decimal
        from datetime import datetime
        import pymysql
        port = int(os.environ['TEST_BACKUP_MYSQL_PORT'])
        password = os.environ.get('TEST_BACKUP_MYSQL_PASSWORD', '')
        source = 'susheguanli_test_backup_source_' + secrets.token_hex(4)
        target = 'susheguanli_test_backup_target_' + secrets.token_hex(4)
        with pymysql.connect(host='127.0.0.1', port=port, user='root', password=password, autocommit=True) as connection:
            with connection.cursor() as cursor:
                for name in (source, target):
                    cursor.execute('CREATE DATABASE ' + backup.identifier(name) + ' CHARACTER SET utf8mb4')
                try:
                    cursor.execute('USE ' + backup.identifier(source))
                    for script in ('schema-mysql.sql', 'business-mysql.sql', 'performance-indexes.sql'):
                        for statement in (ROOT / 'src/main/resources/db' / script).read_text(encoding='utf-8').split(';'):
                            if statement.strip():
                                cursor.execute(statement)
                    cursor.execute("INSERT INTO student(name,student_id,grade,phone) VALUES(%s,'backup-test','大一','13800000001')", ('备份同学\n测试',))
                    cursor.execute("INSERT INTO notification(recipient,title,content,created_at) VALUES('backup-test','通知','恢复验证','2026-10-31')")
                    cursor.execute('CREATE TABLE extra_types(id INT PRIMARY KEY, amount DECIMAL(10,2), moment DATETIME(6), payload BLOB, nullable_value TEXT)')
                    cursor.execute('INSERT INTO extra_types VALUES(1,%s,%s,%s,NULL)', (Decimal('123.45'), datetime(2026, 10, 31, 12, 34, 56, 123456), b'\x00\xfftest'))
                    self.args.engine = 'mysql'
                    self.args.host, self.args.port, self.args.user = '127.0.0.1', port, 'root'
                    self.args.database, self.args.password_env = source, 'REHEARSAL_PASSWORD'
                    values = {'REHEARSAL_PASSWORD': password}
                    path = Path(backup.create(self.args, values)['backup'])
                    args = self.restore_args(path)
                    args.host, args.port, args.user = '127.0.0.1', port, 'root'
                    args.database, args.password_env = target, 'REHEARSAL_PASSWORD'
                    result = backup.restore(args, values)
                    self.assertTrue(result['verified'])
                    self.assertEqual(result['tables'], 31)
                    cursor.execute('SHOW INDEX FROM ' + backup.identifier(target) + '.notification')
                    self.assertIn('idx_notification_recipient', [row[2] for row in cursor.fetchall()])
                    args.upload_dir = str(self.root / 'second-restore-images')
                    with self.assertRaisesRegex(ValueError, 'empty'):
                        backup.restore(args, values)
                finally:
                    # Only the two unique schemas created in this function can be removed.
                    for name in (source, target):
                        cursor.execute('DROP DATABASE ' + backup.identifier(name))


if __name__ == '__main__':
    unittest.main(verbosity=2)
