#!/usr/bin/env python3
"""隔離Paperでポスターの配布と地図ID・描画復元を検証。実クライアントは使用しない。"""
import os
import pathlib
import shutil
import subprocess
import zipfile
import struct
import zlib

root = pathlib.Path(__file__).resolve().parents[1]
work = root / 'target/poster-paper-smoke'
source = root / 'server-data'
java = os.environ.get('JAVA_BIN', '/opt/homebrew/opt/openjdk/bin/java')
subprocess.run(['mvn', '-B', 'package'], cwd=root, check=True)
if work.exists():
    shutil.rmtree(work)
(work / 'plugins/EcoLifeAssist/posters/images').mkdir(parents=True)
for name in ('libraries', 'cache', 'versions'):
    if (source / name).exists():
        shutil.copytree(source / name, work / name)
shutil.copy2(source / 'paper-26.1.2-74.jar', work / 'paper.jar')
shutil.copy2(source / 'eula.txt', work / 'eula.txt')
shutil.copy2(root / 'target/ecolifeassist-1.0.0.jar', work / 'plugins/EcoLifeAssist.jar')
(work / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25580\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\n')
def chunk(kind, data):
    return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 256, 256, 8, 2, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress((b'\x00' + b'\xff\xff\x00' * 256) * 256)) + chunk(b'IEND', b'')
(work / 'plugins/EcoLifeAssist/posters/images/test.png').write_bytes(png)
(work / 'plugins/EcoLifeAssist/posters/catalog.yml').write_text('posters:\n' + ''.join(f'  sample{i}:\n    title: テスト{i}\n    file: test.png\n    width: 2\n    height: 2\n' for i in range(47)))
with zipfile.ZipFile(work / 'plugins/PosterProbe.jar', 'w') as jar:
    jar.writestr('plugin.yml', 'name: PosterProbe\nversion: 1\nmain: dev.spa.ecolife.poster.PaperPosterProbe\napi-version: "26.1.2"\ndepend: [EcoLifeAssist]\n')
    for file in (root / 'target/test-classes/dev/spa/ecolife/poster').glob('PaperPosterProbe*.class'):
        jar.write(file, 'dev/spa/ecolife/poster/' + file.name)
for phase in ('initial', 'restart', 'withdrawn'):
    if phase == 'withdrawn':
        (work / 'plugins/EcoLifeAssist/posters/catalog.yml').write_text('posters: {}\n')
        (work / 'plugins/EcoLifeAssist/posters/images/test.png').unlink()
    with (work / (phase + '.log')).open('w') as log:
        result = subprocess.run([java, '-Dprobe.withdrawn=' + str(phase == 'withdrawn').lower(), '-Dprobe.restart=' + str(phase == 'restart').lower(), '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms256M', '-Xmx1G', '-jar', 'paper.jar', '--nogui'], cwd=work, stdout=log, stderr=subprocess.STDOUT, timeout=180)
    output = (work / (phase + '.log')).read_text()
    for line in output.splitlines():
        if any(word in line for word in ('POSTER_PROBE', 'Exception', 'AssertionError', 'Caused by:')):
            print(line, flush=True)
    if result.returncode or 'POSTER_PROBE_PASS' not in output or 'POSTER_PROBE_FAIL' in output:
        raise SystemExit('FAILED: ' + str(work / (phase + '.log')))
print('PASS: catalog pagination, permissions, full inventory, tile receipt, ID reuse, restart renderer restoration and withdrawn image retention. Logs: ' + str(work))
