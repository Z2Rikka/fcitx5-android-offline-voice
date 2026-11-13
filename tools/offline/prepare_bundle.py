"""Prepare local inputs for the arm64 offline edition. Run before assembling :app."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import zipfile

root = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('--rime-apk', type=Path)
a = p.parse_args()
assets = root / 'app/src/main/assets'

ice = root / '.local-build/rime-ice.zip'
if ice.exists():
    target = assets / 'bundled-rime'
    with zipfile.ZipFile(ice) as z:
        for item in z.infolist():
            parts = Path(item.filename).parts[1:]
            if not parts or item.is_dir():
                continue
            if parts[0] in ('cn_dicts', 'en_dicts', 'lua', 'opencc') or \
                (len(parts) == 1 and (parts[0].endswith('.yaml') or parts[0] in ('custom_phrase.txt', 'LICENSE'))):
                dest = target.joinpath(*parts)
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(z.read(item))
    # Present only full pinyin by default; additional bundled schemas remain available.
    (target / 'default.custom.yaml').write_text('patch:\n  schema_list:\n    - schema: rime_ice\n', encoding='utf-8')

for folder in (assets / 'offline-voice').glob('*.parts'):
    shutil.rmtree(folder)

if a.rime_apk:
    with zipfile.ZipFile(a.rime_apk) as z:
        for item in z.infolist():
            if item.is_dir():
                continue
            name = item.filename
            if name.startswith('assets/usr/'):
                dest = assets / name.removeprefix('assets/')
            elif name.startswith('lib/arm64-v8a/'):
                dest = root / 'app/src/main/jniLibs/arm64-v8a' / Path(name).name
            else:
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(z.read(item))

files = [p for p in (assets / 'offline-voice').glob('*') if p.is_file()]
if len(files) != 4:
    raise RuntimeError('Expected encoder.int8.onnx, decoder.onnx, joiner.int8.onnx and tokens.txt')
record = {str(p.relative_to(root)): {'bytes': p.stat().st_size,
    'sha256': hashlib.file_digest(p.open('rb'), 'sha256').hexdigest()} for p in files}
(root / '.local-build/bundled-models.json').write_text(json.dumps(record, indent=2), encoding='utf-8')
print('Prepared', len(files), 'voice files and', sum(1 for _ in (assets/'bundled-rime').rglob('*')), 'Rime paths')
