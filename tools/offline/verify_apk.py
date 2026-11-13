"""Check that a built APK is self-contained and cannot request Android network access."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

p = argparse.ArgumentParser()
p.add_argument('apk', type=Path)
p.add_argument('--build-tools', type=Path, required=True)
p.add_argument('--readelf', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[2]

def run(*command):
    return subprocess.check_output([str(x) for x in command], text=True, stderr=subprocess.STDOUT)

permissions = run(a.build_tools/'aapt2', 'dump', 'permissions', a.apk)
assert 'android.permission.INTERNET' not in permissions
assert 'android.permission.RECORD_AUDIO' in permissions
badging = run(a.build_tools/'aapt2', 'dump', 'badging', a.apk)
assert "package: name='org.fcitx.fcitx5.android.offline" in badging
signature = run(a.build_tools/'apksigner', 'verify', '--verbose', '--print-certs', a.apk)
with zipfile.ZipFile(a.apk) as z:
    names = set(z.namelist())
    for required in ('assets/bundled-rime/rime_ice.schema.yaml',
                     'assets/bundled-rime/default.custom.yaml',
                     'assets/bundled-rime/LICENSE',
                     'assets/usr/share/fcitx5/addon/rime.conf',
                     'assets/usr/share/fcitx5/inputmethod/rime.conf',
                     'lib/arm64-v8a/librime.so',
                     'lib/arm64-v8a/libsherpa-onnx-jni.so'):
        assert required in names, required
    assert not any('.parts/' in name for name in names)
    expected = json.loads((root/'.local-build/bundled-models.json').read_text())
    for name, data in expected.items():
        entry = name.replace('app/src/main/', '')
        assert z.getinfo(entry).file_size == data['bytes']
        with z.open(entry) as f:
            assert hashlib.file_digest(f, 'sha256').hexdigest() == data['sha256']
    system = {'libc.so','libm.so','libdl.so','liblog.so','libandroid.so','libz.so',
              'libjnigraphics.so','libEGL.so','libGLESv2.so','libOpenSLES.so','libvulkan.so'}
    libraries = {Path(n).name for n in names if n.startswith('lib/arm64-v8a/') and n.endswith('.so')}
    native = {}
    with tempfile.TemporaryDirectory() as temp:
        for name in names:
            if name.startswith('lib/arm64-v8a/') and name.endswith('.so'):
                path = Path(temp)/Path(name).name
                path.write_bytes(z.read(name))
                headers = run(a.readelf, '-h', path)
                assert 'AArch64' in headers
                needed = re.findall(r'Shared library: \[(.*?)\]', run(a.readelf, '-d', path))
                assert set(needed) <= libraries | system, (name, set(needed)-libraries-system)
                native[path.name] = needed
report = {'apk': a.apk.name, 'bytes': a.apk.stat().st_size,
    'sha256': hashlib.file_digest(a.apk.open('rb'), 'sha256').hexdigest(),
    'permissions': permissions, 'signature': signature, 'native_dependencies': native,
    'model_hashes_verified': True, 'bundled_rime_verified': True,
    'device_tested': False}
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k != 'native_dependencies'}, ensure_ascii=False, indent=2))
