"""Offline database + attachment backups. Restore only into empty, separate targets.

Uses the project's .env or process environment; never stores credentials in backups.
Run --help or see docs/BACKUP-RESTORE.md. PyMySQL is needed only for MySQL.
"""
from __future__ import annotations

import argparse
import base64
from contextlib import closing
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import shutil
import sqlite3
import sys
from urllib.parse import urlparse, parse_qs

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'artifacts/migration-deps'))
FORMAT = 1


def config():
    values = {}
    if (ROOT / '.env').exists():
        for line in (ROOT / '.env').read_text(encoding='utf-8').splitlines():
            if line.strip() and not line.lstrip().startswith('#'):
                key, value = line.split('=', 1)
                values[key.strip()] = value.strip()
    values.update(os.environ)
    return values


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def identifier(value):
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,63}', value):
        raise ValueError('Invalid database identifier')
    return '`' + value + '`'


def pack(value):
    if isinstance(value, bytes):
        return {'bytes': base64.b64encode(value).decode('ascii')}
    if isinstance(value, Decimal):
        return {'decimal': str(value)}
    if isinstance(value, (datetime, date)):
        return {'datetime': value.isoformat(sep=' ') if isinstance(value, datetime) else value.isoformat()}
    if isinstance(value, timedelta):
        return {'timedelta': value.total_seconds()}
    if value is None or isinstance(value, (str, int, float)):
        return value
    raise ValueError('Unsupported database value type: ' + type(value).__name__)


def unpack(value):
    if not isinstance(value, dict):
        return value
    if 'bytes' in value:
        return base64.b64decode(value['bytes'], validate=True)
    if 'decimal' in value:
        return Decimal(value['decimal'])
    if 'datetime' in value:
        return date.fromisoformat(value['datetime']) if len(value['datetime']) == 10 else datetime.fromisoformat(value['datetime'])
    if 'timedelta' in value:
        return timedelta(seconds=value['timedelta'])
    raise ValueError('Invalid serialized value')


def row_json(row):
    return json.dumps([pack(value) for value in row], ensure_ascii=False, separators=(',', ':'))


def fingerprint(rows):
    # Order independent, multiplicity preserving, constant memory fingerprint.
    count, aggregate = 0, 0
    for row in rows:
        count += 1
        aggregate = (aggregate + int.from_bytes(hashlib.sha256(row_json(row).encode()).digest(), 'big')) % (1 << 256)
    return {'count': count, 'sha256Sum': f'{aggregate:064x}'}


def mysql_settings(args, values):
    url = urlparse(values.get('DB_URL', 'jdbc:mysql://127.0.0.1:3306/susheguanli').removeprefix('jdbc:'))
    if url.scheme != 'mysql':
        raise ValueError('MySQL backup requires a MySQL DB_URL or explicit configuration')
    query = parse_qs(url.query)
    if query.get('sessionVariables') or query.get('connectionTimeZone') or query.get('serverTimezone', ['Asia/Shanghai'])[0] != 'Asia/Shanghai':
        raise ValueError('This backup format expects the application Asia/Shanghai (+08:00) session time zone')
    return dict(host=args.host or url.hostname, port=args.port or url.port or 3306,
                user=args.user or values.get('DB_USERNAME'), password=values.get(args.password_env, ''),
                database=args.database or url.path.strip('/'), charset='utf8mb4', autocommit=False,
                connect_timeout=10, read_timeout=120, write_timeout=120,
                init_command="SET time_zone='+08:00'")


def mysql_connect(settings):
    import pymysql
    return pymysql.connect(**settings)


def mysql_snapshot(connection, destination):
    import pymysql
    tables = {}
    with connection.cursor() as cursor:
        cursor.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
        cursor.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT')
        cursor.execute('SHOW FULL TABLES')
        listing = cursor.fetchall()
        if any(kind != 'BASE TABLE' for _, kind in listing):
            raise ValueError('Views are not supported by this application backup format')
        cursor.execute('SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()')
        if any(engine != 'InnoDB' for _, engine in cursor.fetchall()):
            raise ValueError('Consistent backup requires InnoDB tables')
        cursor.execute('SHOW TRIGGERS')
        if cursor.fetchone():
            raise ValueError('Triggers require a database-native backup tool')
    (destination / 'tables').mkdir()
    for table, _ in listing:
        with connection.cursor() as cursor:
            cursor.execute('SHOW CREATE TABLE ' + identifier(table))
            ddl = cursor.fetchone()[1]
        relative = 'tables/' + table + '.jsonl'
        with connection.cursor(pymysql.cursors.SSCursor) as cursor, (destination / relative).open('w', encoding='utf-8', newline='\n') as out:
            cursor.execute('SELECT * FROM ' + identifier(table))
            columns = [field[0] for field in cursor.description]

            def records():
                for row in cursor:
                    out.write(row_json(row) + '\n')
                    yield row

            signature = fingerprint(records())
        tables[table] = dict(ddl=ddl, columns=columns, file=relative, **signature)
    connection.rollback()
    return tables


def sqlite_connection(path):
    return sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True)


def sqlite_signatures(path):
    with closing(sqlite_connection(path)) as connection:
        if connection.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise ValueError('SQLite integrity check failed')
        tables = {}
        for (table,) in connection.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"):
            cursor = connection.execute('SELECT * FROM ' + identifier(table))
            tables[table] = dict(columns=[field[0] for field in cursor.description], **fingerprint(cursor))
        return tables


def safe_file(directory, relative):
    parts = PurePosixPath(relative)
    if parts.is_absolute() or not parts.parts or any(part in ('..', '.', '') or ':' in part or '\\' in part for part in parts.parts):
        raise ValueError('Unsafe backup path')
    path = directory.joinpath(*parts.parts)
    if not path.resolve().is_relative_to(directory.resolve()) or any(parent.is_symlink() for parent in [path, *path.parents] if parent != directory.parent):
        raise ValueError('Backup paths cannot contain symlinks')
    return path


def copy_uploads(source, destination):
    if not source.is_dir() or source.is_symlink():
        raise ValueError('Upload directory must exist and must not be a symlink')
    destination.mkdir()
    for item in source.rglob('*'):
        if item.is_symlink() or not item.resolve().is_relative_to(source.resolve()):
            raise ValueError('Upload directory contains a symlink')
        relative = item.relative_to(source)
        if item.is_dir():
            (destination / relative).mkdir(parents=True, exist_ok=True)
        elif item.is_file():
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(item, target)


def verify_backup(directory, *, pending=False):
    directory = directory.resolve()
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('format') != FORMAT or manifest.get('complete') is not (False if pending else True) or manifest.get('engine') not in ('mysql', 'sqlite'):
        raise ValueError('Unsupported or incomplete backup')
    expected = manifest['files']
    actual = {p.relative_to(directory).as_posix() for p in directory.rglob('*') if p.is_file() and p.relative_to(directory).as_posix() != 'manifest.json'}
    if actual != set(expected):
        raise ValueError('Backup file inventory differs from manifest')
    for relative, signature in expected.items():
        if digest(safe_file(directory, relative)) != signature:
            raise ValueError('Backup checksum mismatch: ' + relative)
    if manifest['engine'] == 'sqlite':
        if sqlite_signatures(directory / 'database.sqlite') != manifest['tables']:
            raise ValueError('Database fingerprints differ from manifest')
    else:
        for table in manifest['tables'].values():
            with safe_file(directory, table['file']).open(encoding='utf-8') as stream:
                signature = fingerprint([unpack(v) for v in json.loads(line)] for line in stream)
            if any(table[key] != value for key, value in signature.items()):
                raise ValueError('Database fingerprints differ from manifest')
    return manifest


def prune(directory, keep_days, keep_last):
    directory = directory.resolve()
    candidates = []
    for child in directory.iterdir():
        if child.is_symlink() or not child.is_dir() or not re.fullmatch(r'full-\d{8}T\d{6}Z-[a-f0-9]{8}', child.name):
            continue
        manifest = child / 'manifest.json'
        if manifest.is_file() and not manifest.is_symlink():
            try:
                info = json.loads(manifest.read_text(encoding='utf-8'))
                created = datetime.fromisoformat(info['createdAt'])
                if info.get('complete') is True and info.get('format') == FORMAT and created.tzinfo is not None:
                    candidates.append((created, child))
            except (ValueError, KeyError, TypeError):
                continue
    cutoff = datetime.now(timezone.utc) - timedelta(days=keep_days)
    removed = []
    for created, child in sorted(candidates, reverse=True)[keep_last:]:
        if created < cutoff:
            target = child.resolve()
            if target.parent != directory or not target.is_relative_to(directory):
                raise ValueError('Retention target escaped the backup directory')
            shutil.rmtree(target)
            removed.append(child.name)
    return removed


def create(args, values):
    if not args.offline:
        raise ValueError('Stop all application writers and supply --offline for a consistent database/attachment snapshot')
    uploads = Path(args.upload_dir or values.get('UPLOAD_DIR', str(ROOT / 'uploads/repair'))).resolve()
    output_root = Path(args.output).resolve()
    if output_root.is_relative_to(uploads) or uploads.is_relative_to(output_root):
        raise ValueError('Backup and upload directories must be separate')
    output_root.mkdir(parents=True, exist_ok=True)
    destination = output_root / ('full-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + secrets.token_hex(4))
    destination.mkdir()
    if args.engine == 'sqlite':
        source = Path(args.sqlite_path).resolve()
        with closing(sqlite_connection(source)) as connection, closing(sqlite3.connect(destination / 'database.sqlite')) as snapshot:
            connection.backup(snapshot)
        tables = sqlite_signatures(destination / 'database.sqlite')
        origin = {'path': str(source)}
    else:
        settings = mysql_settings(args, values)
        with mysql_connect(settings) as connection:
            tables = mysql_snapshot(connection, destination)
        origin = {key: settings[key] for key in ('host', 'port', 'database')}
    copy_uploads(uploads, destination / 'uploads')
    files = {p.relative_to(destination).as_posix(): digest(p) for p in destination.rglob('*') if p.is_file()}
    manifest = dict(format=FORMAT, complete=False, engine=args.engine, createdAt=datetime.now(timezone.utc).isoformat(),
                    origin=origin, sourceUploads=str(uploads), tables=tables, files=files)
    (destination / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    verify_backup(destination, pending=True)
    manifest['complete'] = True
    ready = destination / 'manifest.ready'
    ready.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    ready.replace(destination / 'manifest.json')
    removed = prune(output_root, args.keep_days, args.keep_last)
    return {'backup': str(destination), 'tables': len(tables), 'files': len(files), 'verified': True, 'pruned': removed}


def restore(args, values):
    directory = Path(args.backup).resolve()
    manifest = verify_backup(directory)
    uploads = Path(args.upload_dir).resolve()
    source_uploads = Path(manifest['sourceUploads']).resolve()
    if uploads.is_relative_to(source_uploads) or source_uploads.is_relative_to(uploads) or uploads.exists():
        raise ValueError('Restore uploads into a new, separate directory')
    if uploads.is_relative_to(directory) or directory.is_relative_to(uploads):
        raise ValueError('Restore target must be separate from backup')
    if manifest['engine'] == 'sqlite':
        if not args.sqlite_path:
            raise ValueError('Supply a new --sqlite-path for SQLite restore')
        target = Path(args.sqlite_path).resolve()
        if target.exists() or target == Path(manifest['origin']['path']).resolve():
            raise ValueError('SQLite restore target must be a new, separate file')
        if target.is_relative_to(directory) or target.is_relative_to(uploads) or uploads.is_relative_to(target):
            raise ValueError('Database restore target overlaps backup or uploads')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(directory / 'database.sqlite', target)
        if sqlite_signatures(target) != manifest['tables']:
            raise ValueError('Restored database fingerprint mismatch')
    else:
        if not args.database:
            raise ValueError('Supply an existing empty --database for MySQL restore')
        import pymysql
        settings = mysql_settings(args, values)
        # A different database name is required even when restoring on another host.
        if settings['database'] == manifest['origin']['database']:
            raise ValueError('Restore database must differ from source')
        with mysql_connect(settings) as connection, connection.cursor() as cursor:
            cursor.execute('SHOW FULL TABLES')
            if cursor.fetchone():
                raise ValueError('Restore database must be empty')
            cursor.execute('SET FOREIGN_KEY_CHECKS=0')
            for name, table in manifest['tables'].items():
                cursor.execute(table['ddl'])
                sql = 'INSERT INTO ' + identifier(name) + ' (' + ','.join(identifier(c) for c in table['columns']) + ') VALUES (' + ','.join(['%s'] * len(table['columns'])) + ')'
                with safe_file(directory, table['file']).open(encoding='utf-8') as stream:
                    batch = []
                    for line in stream:
                        batch.append([unpack(value) for value in json.loads(line)])
                        if len(batch) == 500:
                            cursor.executemany(sql, batch)
                            batch.clear()
                    if batch:
                        cursor.executemany(sql, batch)
                with connection.cursor(pymysql.cursors.SSCursor) as verification:
                    verification.execute('SELECT * FROM ' + identifier(name))
                    signature = fingerprint(verification)
                if any(table[key] != value for key, value in signature.items()):
                    raise ValueError('Restored database fingerprint mismatch: ' + name)
            cursor.execute('SET FOREIGN_KEY_CHECKS=1')
            connection.commit()
    copy_uploads(directory / 'uploads', uploads)
    for relative, checksum in manifest['files'].items():
        if relative.startswith('uploads/') and digest(safe_file(uploads, relative[len('uploads/'):])) != checksum:
            raise ValueError('Restored attachment checksum mismatch')
    return {'restored': True, 'tables': len(manifest['tables']), 'uploads': str(uploads), 'verified': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    backup = commands.add_parser('create')
    backup.add_argument('--engine', choices=['mysql', 'sqlite'], default='mysql')
    backup.add_argument('--offline', action='store_true', help='Confirm all application writers are stopped')
    backup.add_argument('--output', default=str(ROOT / 'backups/full'))
    backup.add_argument('--keep-days', type=int, default=30)
    backup.add_argument('--keep-last', type=int, default=7)
    restore_parser = commands.add_parser('restore')
    restore_parser.add_argument('--backup', required=True)
    restore_parser.add_argument('--upload-dir', required=True)
    verify = commands.add_parser('verify')
    verify.add_argument('--backup', required=True)
    for command in (backup, restore_parser):
        command.add_argument('--sqlite-path', default=str(ROOT / 'susheguanli.db') if command == backup else None)
        command.add_argument('--host')
        command.add_argument('--port', type=int)
        command.add_argument('--database')
        command.add_argument('--user')
        command.add_argument('--password-env', default='DB_PASSWORD')
    backup.add_argument('--upload-dir')
    args = parser.parse_args()
    if args.command == 'create' and (args.keep_days < 1 or args.keep_last < 1):
        parser.error('Retention must keep at least one day and one backup')
    if args.command == 'verify':
        manifest = verify_backup(Path(args.backup))
        report = {'verified': True, 'tables': len(manifest['tables']), 'files': len(manifest['files'])}
    else:
        report = (create if args.command == 'create' else restore)(args, config())
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Database exceptions may include data or connection details. Keep CLI errors private.
        if isinstance(error, (ValueError, FileNotFoundError)):
            print(str(error), file=sys.stderr)
        else:
            print('Backup/restore failed (' + type(error).__name__ + '); no existing target data was overwritten.', file=sys.stderr)
        sys.exit(1)
