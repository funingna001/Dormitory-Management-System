"""Inspect or migrate SQLite to local MySQL. Password comes from an environment variable.

The script never overwrites a nonempty target schema. It backs up the SQLite
snapshot, loads into a separate staging schema, verifies every row, and only then
renames the verified tables into the target schema. Original SQLite is read-only.
Migration-only dependencies: python -m pip install --target artifacts/migration-deps 'PyMySQL[rsa]' bcrypt
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import sqlite3
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'artifacts/migration-deps'))
import bcrypt
import pymysql

TABLES = ['user', 'building', 'dormitory', 'student', 'repair_request']
NAME = re.compile(r'^[a-zA-Z][a-zA-Z0-9_]{0,63}$')
BCRYPT = re.compile(r'^\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}$')


def identifier(value):
    if not NAME.fullmatch(value):
        raise ValueError('Invalid database identifier')
    return '`' + value + '`'


def normalize_time(value):
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(value / 1000)
    return datetime.fromisoformat(str(value).strip().replace('T', ' '))


def safe_json(value):
    if isinstance(value, datetime):
        return value.isoformat(sep=' ')
    if isinstance(value, Decimal):
        return str(value)
    raise TypeError(type(value).__name__)


def connect(args):
    password = os.environ.get(args.password_env)
    if password is None:
        raise RuntimeError('Set ' + args.password_env + ' in the current process before running.')
    return pymysql.connect(host=args.host, port=args.port, user=args.user, password=password,
                           charset='utf8mb4', autocommit=False, connect_timeout=10,
                           read_timeout=60, write_timeout=60,
                           init_command="SET time_zone = '+08:00'")


def inspect(connection, target):
    with connection.cursor() as cursor:
        cursor.execute('SELECT VERSION(), @@port, @@character_set_server, @@time_zone')
        version, port, charset, zone = cursor.fetchone()
        cursor.execute('SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s', (target,))
        exists = cursor.fetchone() is not None
        cursor.execute('SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s', (target,))
        tables = [row[0] for row in cursor.fetchall()]
        counts = {}
        for table in tables:
            cursor.execute('SELECT COUNT(*) FROM ' + identifier(target) + '.' + identifier(table))
            counts[table] = cursor.fetchone()[0]
    return dict(version=version, port=port, charset=charset, timezone=zone,
                target=target, targetExists=exists, targetTables=counts)


def snapshot_source(source_path, backup_path):
    source = sqlite3.connect('file:' + source_path.as_posix() + '?mode=ro', uri=True)
    try:
        backup = sqlite3.connect(backup_path)
        try:
            source.backup(backup)
        finally:
            backup.close()
    finally:
        source.close()
    return sqlite3.connect('file:' + backup_path.as_posix() + '?mode=ro', uri=True)


def source_rows(source, table):
    columns = [row[1] for row in source.execute('PRAGMA table_info(' + identifier(table) + ')')]
    records = source.execute('SELECT * FROM ' + identifier(table) + ' ORDER BY id').fetchall()
    normalized = []
    for record in records:
        row = list(record)
        for i, column in enumerate(columns):
            if table == 'repair_request' and column in ('create_time', 'update_time'):
                row[i] = normalize_time(row[i])
        normalized.append(row)
    return columns, normalized


def data_fingerprint(columns, rows):
    return hashlib.sha256(json.dumps([columns, rows], ensure_ascii=False, default=safe_json,
                                    separators=(',', ':')).encode()).hexdigest()


def check_source(source):
    checks = {
        'orphanStudentDormitories': "SELECT COUNT(*) FROM student s LEFT JOIN dormitory d ON s.dormitory_number=d.dormitory_number WHERE s.dormitory_number IS NOT NULL AND s.dormitory_number<>'' AND d.id IS NULL",
        'orphanDormitoryBuildings': 'SELECT COUNT(*) FROM dormitory d LEFT JOIN building b ON d.building=b.name WHERE b.id IS NULL',
        'occupancyMismatches': 'SELECT COUNT(*) FROM dormitory d WHERE current_count<>(SELECT COUNT(*) FROM student s WHERE s.dormitory_number=d.dormitory_number)',
        'overCapacity': 'SELECT COUNT(*) FROM dormitory WHERE current_count>capacity OR current_count<0 OR capacity<=0',
        'genderMismatches': 'SELECT COUNT(*) FROM student s JOIN dormitory d ON s.dormitory_number=d.dormitory_number WHERE s.gender<>d.gender',
    }
    return {name: source.execute(sql).fetchone()[0] for name, sql in checks.items()}


def verify(connection, database, originals):
    result = {}
    with connection.cursor() as cursor:
        for table, (columns, expected) in originals.items():
            cursor.execute('SELECT ' + ','.join(identifier(c) for c in columns) + ' FROM '
                           + identifier(database) + '.' + identifier(table) + ' ORDER BY id')
            actual = [list(row) for row in cursor.fetchall()]
            if actual != expected:
                raise RuntimeError('Row verification failed for ' + table)
            result[table] = dict(rows=len(actual), sha256=data_fingerprint(columns, actual), verified=True)
    return result


def migrate(connection, args):
    target = args.database
    initial = inspect(connection, target)
    # Reject even empty preexisting tables: CREATE/RENAME must never replace a user's schema.
    if initial['targetTables']:
        raise RuntimeError('Target contains tables; refusing to overwrite existing MySQL data.')
    stamp = datetime.now().strftime('%Y%m%d-%H%M%S')
    backup_dir = ROOT / 'backups' / ('mysql-migration-' + stamp)
    backup_dir.mkdir(parents=True, exist_ok=False)
    source = snapshot_source(args.source.resolve(), backup_dir / 'source-sqlite.db')
    staging = target[:32] + '_stage_' + datetime.now().strftime('%Y%m%d_%H%M%S') + '_' + secrets.token_hex(3)
    originals = {}
    report = dict(startedAt=datetime.now().isoformat(), source=str(args.source.resolve()), target=target,
                  backup=str(backup_dir), staging=staging, before=initial,
                  relationships=check_source(source), timestampPolicy='Preserve local Asia/Shanghai wall time and first 6 fractional digits; original nanoseconds retained in SQLite backup')
    report['success'] = False
    (backup_dir / 'migration-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    if (ROOT / 'uploads').exists():
        shutil.copytree(ROOT / 'uploads', backup_dir / 'uploads')
    if any(value for key, value in report['relationships'].items() if key != 'overCapacity'):
        raise RuntimeError('Source has inconsistent relationships; inspect before migrating: ' + json.dumps(report['relationships']))
    legacy_rooms = source.execute('SELECT id,dormitory_number,room_type,capacity,current_count FROM dormitory WHERE current_count>capacity').fetchall()
    if report['relationships']['overCapacity'] and (args.overcapacity_policy == 'reject' or not legacy_rooms):
        raise RuntimeError('Source contains an over-capacity room. Choose an explicit migration policy after reviewing the data.')
    report['overcapacityPolicy'] = args.overcapacity_policy
    report['sourceDataAdjustments'] = []
    for table in TABLES:
        columns, records = source_rows(source, table)
        if not columns:
            raise RuntimeError('Missing source table: ' + table)
        if table == 'user':
            index = columns.index('password')
            for row in records:
                if not BCRYPT.fullmatch(row[index]):
                    original = row[index].encode('utf-8')
                    row[index] = bcrypt.hashpw(original, bcrypt.gensalt(rounds=12)).decode('ascii')
                    if not bcrypt.checkpw(original, row[index].encode('ascii')):
                        raise RuntimeError('Password migration verification failed')
        if table == 'dormitory' and legacy_rooms and args.overcapacity_policy == 'expand-to-six':
            for row in records:
                if row[columns.index('current_count')] > row[columns.index('capacity')]:
                    if row[columns.index('current_count')] > 6:
                        raise RuntimeError('Cannot automatically convert occupancy above 6 to a six-person room.')
                    report['sourceDataAdjustments'].append(dict(id=row[columns.index('id')], room=row[columns.index('dormitory_number')],
                        originalCapacity=row[columns.index('capacity')], originalRoomType=row[columns.index('room_type')],
                        migratedCapacity=6,migratedRoomType='六人间'))
                    row[columns.index('capacity')] = 6
                    row[columns.index('room_type')] = '六人间'
        originals[table] = (columns, records)
    sequences = dict(source.execute('SELECT name,seq FROM sqlite_sequence').fetchall())
    report['sourceSequences'] = {table: int(sequences.get(table, 0)) for table in TABLES}
    # First create a complete portable, hashed-password SQL snapshot as a restore artifact.
    schema = (ROOT / 'src/main/resources/db/schema-mysql.sql').read_text(encoding='utf-8')
    if legacy_rooms and args.overcapacity_policy == 'preserve':
        exceptions = ' OR '.join('(dormitory_number='+connection.escape(row[1])+' AND capacity='+str(row[3])+' AND current_count<='+str(row[4])+')' for row in legacy_rooms)
        schema = schema.replace('current_count <= capacity)', '(current_count <= capacity OR '+exceptions+'))')
        report['legacyCapacityExceptions'] = [dict(id=row[0],room=row[1],capacity=row[3],existingOccupants=row[4]) for row in legacy_rooms]
    dump = ["SET NAMES utf8mb4;", "SET time_zone='+08:00';", schema, 'START TRANSACTION;']
    for table, (columns, records) in originals.items():
        prefix = 'INSERT INTO ' + identifier(table) + ' (' + ','.join(identifier(c) for c in columns) + ') VALUES '
        for row in records:
            values = []
            for value in row:
                if value is None:
                    values.append('NULL')
                elif isinstance(value, (int, float)):
                    values.append(str(value))
                else:
                    text = safe_json(value) if isinstance(value, datetime) else str(value)
                    values.append("CONVERT(X'" + text.encode('utf-8').hex() + "' USING utf8mb4)")
            dump.append(prefix + '(' + ','.join(values) + ');')
    dump.append('COMMIT;')
    (backup_dir / 'restore-mysql.sql').write_text('\n'.join(dump), encoding='utf-8')
    with connection.cursor() as cursor:
        cursor.execute('CREATE DATABASE ' + identifier(staging) + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
        cursor.execute('USE ' + identifier(staging))
        for statement in schema.split(';'):
            if statement.strip():
                cursor.execute(statement)
    connection.commit()
    try:
        with connection.cursor() as cursor:
            for table, (columns, records) in originals.items():
                sql = 'INSERT INTO ' + identifier(staging) + '.' + identifier(table) + ' (' + ','.join(identifier(c) for c in columns)
                sql += ') VALUES (' + ','.join(['%s'] * len(columns)) + ')'
                if records:
                    cursor.executemany(sql, records)
        report['tables'] = verify(connection, staging, originals)
        connection.commit()
        with connection.cursor() as cursor:
            for table in TABLES:
                next_id = report['sourceSequences'][table] + 1
                cursor.execute('ALTER TABLE ' + identifier(staging) + '.' + identifier(table)
                               + ' AUTO_INCREMENT = ' + str(next_id))
        # MySQL DDL is auto-committing. Publishing uses one atomic multi-table RENAME.
        with connection.cursor() as cursor:
            cursor.execute('CREATE DATABASE IF NOT EXISTS ' + identifier(target) + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
            if inspect(connection, target)['targetTables']:
                raise RuntimeError('Target changed during migration; refusing to publish.')
            renames = ','.join(identifier(staging) + '.' + identifier(t) + ' TO '
                               + identifier(target) + '.' + identifier(t) for t in TABLES)
            cursor.execute('RENAME TABLE ' + renames)
        report['tables'] = verify(connection, target, originals)
        with connection.cursor() as cursor:
            cursor.execute('SELECT TABLE_NAME,AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s', (target,))
            report['autoIncrement'] = dict(cursor.fetchall())
            cursor.execute('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s', (staging,))
            if cursor.fetchone()[0] == 0:
                cursor.execute('DROP DATABASE ' + identifier(staging))
                report['emptyStagingRemoved'] = True
        report['finishedAt'] = datetime.now().isoformat()
        report['success'] = True
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        source.close()
        (backup_dir / 'migration-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2, default=safe_json), encoding='utf-8')
    if target == 'susheguanli':
        (ROOT / 'artifacts/mysql-migration-result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2, default=safe_json),encoding='utf-8')
    print(json.dumps(report, ensure_ascii=True, indent=2, default=safe_json))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=3306)
    parser.add_argument('--user', default='root')
    parser.add_argument('--password-env', default='MIGRATION_MYSQL_PASSWORD')
    parser.add_argument('--database', default='susheguanli')
    parser.add_argument('--source', type=Path, default=ROOT / 'susheguanli.db')
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--overcapacity-policy', choices=['reject','preserve','expand-to-six'],default='reject',
                        help='Explicit policy for historical over-capacity records; never silently repairs source data.')
    args = parser.parse_args()
    identifier(args.database)
    connection = connect(args)
    try:
        if args.apply:
            migrate(connection, args)
        else:
            print(json.dumps(inspect(connection, args.database), indent=2, ensure_ascii=True))
    finally:
        connection.close()


if __name__ == '__main__':
    main()
