#!/usr/bin/env python3
"""Export a reviewable source snapshot without machine state, Git history or credentials.

Standard library only; no network, Git commits or publishing. Existing output is
never overwritten. Binary media is stored without expensive recompression.
"""
import argparse
import hashlib
import json
import re
import shutil
import socket
import stat
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = (
    '.gitignore', 'README.md', 'README_ANDROID.md', 'PRIVACY.md',
    'RELEASE_REVIEW.md', 'RELEASE_NOTES.md', 'THIRD_PARTY_NOTICES.md', 'LICENSE',
    'DESIGN_REVIEW.md', 'build.gradle.kts', 'settings.gradle.kts',
    'gradle.properties', 'gradlew', 'gradlew.bat', 'app/build.gradle.kts', 'app/.gitignore',
    'android/package.json', 'android/package-lock.json', 'android/upstream-tree.json',
    'android/prepare-assets.mjs', 'android/export-source.py',
    'android/verify-scene.mjs', 'android/verify-control.mjs',
    'android/verify-chat.mjs', 'android/verify-todo.mjs',
)
SOURCE_DIRS = ('app/src', 'android/pet', 'gradle', 'docs/images')
DENIED_PARTS = {'.git', '.gradle', '.idea', '.kotlin', '.codex', '.agents', '.aws',
                'node_modules', '__pycache__', 'build', 'captures', 'dist', '.venv'}
DENIED_NAMES = {'local.properties', 'keystore.properties', 'signing.properties',
                'secrets.properties', '.DS_Store', '.gitmodules'}
DENIED_SUFFIXES = {'.jks', '.keystore', '.pem', '.key', '.p12', '.pfx', '.hprof', '.log', '.apk', '.aab', '.apks'}
BINARY = {'.webm', '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ttf', '.wav', '.ico', '.jar', '.pdf'}
SECRET = re.compile(rb'(?:sk-[A-Za-z0-9]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----)')

def allowed(path):
    return not (set(path.parts) & DENIED_PARTS or path.name in DENIED_NAMES or
                path.suffix.lower() in DENIED_SUFFIXES or path.name == '.env' or
                (path.name.startswith('.env.') and path.name != '.env.example'))

def digest(data):
    return hashlib.sha256(data).hexdigest()

def export(output):
    version = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT/'app/build.gradle.kts').read_text()).group(1)
    stage = output / 'dsh-pet-android'
    archive = output / ('dsh-pet-android-v'+version+'-source.zip')
    if stage.exists() or archive.exists():
        raise SystemExit('Output exists. Select a fresh --output directory; existing review files are preserved.')
    selected = {}
    for name in ROOT_FILES:
        source = ROOT/name
        if not source.is_file():
            raise SystemExit('Missing required source: '+name)
        selected[Path(name)] = source
    for name in SOURCE_DIRS:
        for source in (ROOT/name).rglob('*'):
            relative = source.relative_to(ROOT)
            if source.is_file() and allowed(relative):
                selected[relative] = source
    upstream = json.loads((ROOT/'android/upstream-tree.json').read_text())
    for item in upstream['tree']:
        relative = Path('dshgit')/item['path']
        if not allowed(relative):
            continue
        source = ROOT/relative
        if not source.is_file():
            if (item['path'].startswith(('dsh-pet/src/', 'dsh-pet/runtime/electron-helper/', 'dsh-pet/assets/'))
                    and '/preview/' not in item['path']):
                raise SystemExit('Required upstream file missing: '+relative.as_posix())
            continue
        if source.is_symlink():
            raise SystemExit('Symlinks are not exported: '+relative.as_posix())
        data = source.read_bytes()
        blob = hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
        if blob != item['sha']:
            raise SystemExit('Upstream content differs from pinned commit: '+relative.as_posix())
        selected[relative] = source
    private_markers = [str(ROOT).encode(), str(Path.home()).encode(), socket.gethostname().encode()]
    scanned_text = 0
    for relative, source in selected.items():
        if not allowed(relative) or source.is_symlink():
            raise SystemExit('Unsafe export path: '+relative.as_posix())
        data = source.read_bytes()
        if source.suffix.lower() not in BINARY:
            scanned_text += 1
            if SECRET.search(data):
                raise SystemExit('Potential secret found (value redacted): '+relative.as_posix())
            if any(marker and len(marker)>5 and marker in data for marker in private_markers):
                raise SystemExit('Machine identifier found (value redacted): '+relative.as_posix())
    stage.mkdir(parents=True)
    entries = []
    for relative, source in sorted(selected.items(), key=lambda pair: pair[0].as_posix()):
        destination = stage/relative; destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        destination.chmod(0o755 if relative.as_posix() == 'gradlew' else 0o644)
        entries.append({'path':relative.as_posix(), 'bytes':destination.stat().st_size,
                        'sha256':digest(destination.read_bytes())})
    provenance = {'repository':upstream['repository'], 'commit':upstream['commit'],
                  'layout':'vendored tracked-file snapshot; no nested Git metadata',
                  'omitted':'upstream demonstration media and development-assistant metadata; no runtime source omitted'}
    (stage/'UPSTREAM.json').write_text(json.dumps(provenance,ensure_ascii=False,indent=2)+'\n')
    entries.append({'path':'UPSTREAM.json','bytes':(stage/'UPSTREAM.json').stat().st_size,
                    'sha256':digest((stage/'UPSTREAM.json').read_bytes())})
    summary = {'version':version, 'files':entries, 'text_files_scanned':scanned_text,
               'secret_pattern_scan':'passed; no high-confidence credential pattern found',
               'machine_identifier_scan':'passed', 'git_history':'excluded',
               'private_and_build_artifacts':'excluded by explicit file selection and deny rules'}
    (stage/'SOURCE_MANIFEST.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
    with zipfile.ZipFile(archive,'w',allowZip64=True) as bundle:
        for source in sorted(stage.rglob('*')):
            if not source.is_file():
                continue
            relative = source.relative_to(stage)
            info = zipfile.ZipInfo('dsh-pet-android/'+relative.as_posix(), date_time=(1980,1,1,0,0,0))
            info.create_system=3; info.extra=b''
            mode = 0o755 if relative.as_posix() == 'gradlew' else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.compress_type = zipfile.ZIP_STORED if source.suffix.lower() in BINARY else zipfile.ZIP_DEFLATED
            bundle.writestr(info,source.read_bytes(),compresslevel=1)
    with zipfile.ZipFile(archive) as bundle:
        bad = bundle.testzip()
        if bad:
            raise SystemExit('Archive CRC failed: '+bad)
        for name in bundle.namelist():
            path = Path(name)
            if path.is_absolute() or '..' in path.parts or not allowed(Path(*path.parts[1:])):
                raise SystemExit('Unsafe archive entry: '+name)
    result = {'version':version,'source_files':len(entries)+1,'source_bytes':sum(x['bytes'] for x in entries),
              'archive_bytes':archive.stat().st_size,'archive_sha256':digest(archive.read_bytes()),
              'privacy_checks':'passed (pattern/identifier/selection/metadata checks; not a universal guarantee)',
              'upstream':provenance}
    (output/'PACKAGING_REPORT.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False))

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'dist/github-review')
    export(parser.parse_args().output.resolve())
