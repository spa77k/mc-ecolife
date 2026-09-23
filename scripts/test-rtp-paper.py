#!/usr/bin/env python3
"""隔離PaperでRTPの安全地点・待機・クールダウン・再起動を検証する。"""
import os
import pathlib
import shutil
import subprocess
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
source = root / 'server-data'
work = root / 'target/rtp-paper-smoke'
java = os.environ.get('JAVA_BIN', '/opt/homebrew/opt/openjdk/bin/java')
subprocess.run(['mvn', '-B', 'package'], cwd=root, check=True)
if work.exists():
    shutil.rmtree(work)
(work / 'plugins/EcoLifeAssist').mkdir(parents=True)
for name in ('libraries', 'cache', 'versions'):
    if (source / name).exists():
        shutil.copytree(source / name, work / name)
shutil.copy2(source / 'paper-26.1.2-74.jar', work / 'paper.jar')
shutil.copy2(source / 'eula.txt', work / 'eula.txt')
shutil.copy2(root / 'target/ecolifeassist-1.0.0.jar', work / 'plugins/EcoLifeAssist.jar')
(work / 'plugins/EcoLifeAssist/rtp.yml').write_text(
    'enabled: true\ncenter-x: 0\ncenter-z: 0\nmin-radius: 2\nmax-radius: 24\n'
    'min-y: -64\nmax-y: 320\nnether-max-y: 120\nmax-attempts: 32\n'
    'cooldown-seconds: 600\ndelay-seconds: 5\ndisabled-worlds: []\n')
(work / 'server.properties').write_text(
    'server-ip=127.0.0.1\nserver-port=25583\nonline-mode=false\n'
    'view-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\n'
    'generate-structures=false\n')
with zipfile.ZipFile(work / 'plugins/RtpProbe.jar', 'w') as jar:
    jar.writestr('plugin.yml', 'name: RtpProbe\nversion: 1\n'
                'main: dev.spa.ecolife.rtp.PaperRtpProbe\napi-version: "26.1.2"\n'
                'depend: [EcoLifeAssist]\n')
    for file in (root / 'target/test-classes/dev/spa/ecolife/rtp').glob('PaperRtpProbe*.class'):
        jar.write(file, 'dev/spa/ecolife/rtp/' + file.name)
for phase in ('initial', 'restart'):
    with (work / (phase + '.log')).open('w') as log:
        result = subprocess.run([java, '-Dprobe.restart=' + str(phase == 'restart').lower(),
                                 '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms256M',
                                 '-Xmx1G', '-jar', 'paper.jar', '--nogui'], cwd=work,
                                stdout=log, stderr=subprocess.STDOUT, timeout=180)
    output = (work / (phase + '.log')).read_text()
    for line in output.splitlines():
        if any(word in line for word in ('RTP_PROBE', 'Exception', 'AssertionError', 'Caused by:')):
            print(line, flush=True)
    if result.returncode or 'RTP_PROBE_PASS' not in output or 'RTP_PROBE_FAIL' in output:
        raise SystemExit('FAILED: ' + str(work / (phase + '.log')))
print('PASS: RTP command, safe location, delay, cooldown, admin bypass and restart persistence.')
