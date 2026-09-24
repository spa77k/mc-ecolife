#!/usr/bin/env python3
"""隔離Paperでスマホ配布・GUI生成をテストする。実クライアントは使わない。"""
import os
import pathlib
import shutil
import subprocess
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
work = root / 'target/phone-paper-smoke'
source = root / 'server-data'
java = os.environ.get('JAVA_BIN', '/opt/homebrew/opt/openjdk@25/bin/java')
subprocess.run(['mvn', '-B', 'package'], cwd=root, check=True)
if work.exists():
    shutil.rmtree(work)
(work / 'plugins').mkdir(parents=True)
for name in ('libraries', 'cache', 'versions'):
    if (source / name).exists():
        shutil.copytree(source / name, work / name)
shutil.copy2(source / 'paper-26.1.2-74.jar', work / 'paper.jar')
shutil.copy2(source / 'eula.txt', work / 'eula.txt')
shutil.copy2(root / 'target/ecolifeassist-1.0.0.jar', work / 'plugins/EcoLifeAssist.jar')
(work / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25583\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\n')
with zipfile.ZipFile(work / 'plugins/PhoneProbe.jar', 'w') as jar:
    jar.writestr('plugin.yml', 'name: PhoneProbe\nversion: 1\nmain: dev.spa.ecolife.PaperPhoneProbe\napi-version: "26.1.2"\ndepend: [EcoLifeAssist]\n')
    for file in (root / 'target/test-classes/dev/spa/ecolife').glob('PaperPhoneProbe*.class'):
        jar.write(file, 'dev/spa/ecolife/' + file.name)
with (work / 'smoke.log').open('w') as log:
    result = subprocess.run([java, '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms256M', '-Xmx1G', '-jar', 'paper.jar', '--nogui'], cwd=work, stdout=log, stderr=subprocess.STDOUT, timeout=180)
output = (work / 'smoke.log').read_text()
for line in output.splitlines():
    if any(word in line for word in ('PHONE_PROBE', 'Exception', 'AssertionError', 'Caused by:')):
        print(line, flush=True)
if result.returncode or 'PHONE_PROBE_PASS' not in output or 'PHONE_PROBE_FAIL' in output:
    raise SystemExit('FAILED: ' + str(work / 'smoke.log'))
print('PASS: smartphone distribution, duplicate prevention, item model, main GUI and trade category. Logs: ' + str(work))
