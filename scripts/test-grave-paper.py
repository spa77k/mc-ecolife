#!/usr/bin/env python3
"""隔離Paperでお墓の作成・取り出し・期限切れ・保存をテストする。実クライアントは使わない。"""
import os
import pathlib
import shutil
import subprocess
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
work = root / 'target/grave-paper-smoke'
source = root / 'server-data'
java = os.environ.get('JAVA_BIN', '/opt/homebrew/opt/openjdk@25/bin/java')
port = int(os.environ.get('GRAVE_TEST_PORT', '25585'))
subprocess.run(['mvn', '-B', 'clean', 'package', '-Dmaven.compiler.fork=true'], cwd=root, check=True)
if work.exists():
    shutil.rmtree(work)
(work / 'plugins').mkdir(parents=True)
for name in ('libraries', 'cache', 'versions'):
    if (source / name).exists():
        shutil.copytree(source / name, work / name)
shutil.copy2(source / 'paper-26.2-129.jar', work / 'paper.jar')
shutil.copy2(source / 'eula.txt', work / 'eula.txt')
shutil.copy2(root / 'target/ecolifeassist-1.0.0.jar', work / 'plugins/EcoLifeAssist.jar')
(work / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\n')
with zipfile.ZipFile(work / 'plugins/GraveProbe.jar', 'w') as jar:
    jar.writestr('plugin.yml', 'name: GraveProbe\nversion: 1\nmain: dev.spa.ecolife.grave.PaperGraveProbe\napi-version: "26.2"\ndepend: [EcoLifeAssist]\n')
    for file in (root / 'target/test-classes/dev/spa/ecolife/grave').glob('PaperGraveProbe*.class'):
        jar.write(file, 'dev/spa/ecolife/grave/' + file.name)
with (work / 'smoke.log').open('w') as log:
    result = subprocess.run([java, '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms256M', '-Xmx1G', '-jar', 'paper.jar', '--nogui'], cwd=work, stdout=log, stderr=subprocess.STDOUT, timeout=180)
output = (work / 'smoke.log').read_text()
for line in output.splitlines():
    if any(word in line for word in ('GRAVE_PROBE', 'お墓', 'Exception', 'AssertionError', 'Caused by:')):
        print(line, flush=True)
if result.returncode or 'GRAVE_PROBE_PASS' not in output or 'GRAVE_PROBE_FAIL' in output:
    raise SystemExit('FAILED: ' + str(work / 'smoke.log'))
print('PASS: grave creation, slot restore, owner-only access, expiry drop, save/reload, keepInventory skip, void placement and marker cleanup. Logs: ' + str(work))
