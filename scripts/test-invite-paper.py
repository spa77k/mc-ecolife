#!/usr/bin/env python3
"""Real isolated Paper/Vault/Essentials integration; deterministic Player adapters, no external connections."""
import os
import pathlib
import shutil
import subprocess
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
work = root / 'target' / 'invite-paper-smoke'
java = os.environ.get('JAVA_BIN', '/opt/homebrew/opt/openjdk@25/bin/java')
source = root / 'server-data'
subprocess.run(['mvn', '-B', '-q', 'package'], cwd=root, check=True)
if work.exists():
    shutil.rmtree(work)
(work / 'plugins').mkdir(parents=True)
for directory in ('libraries', 'cache', 'versions'):
    if (source / directory).exists():
        shutil.copytree(source / directory, work / directory)
shutil.copy2(source / 'paper-26.1.2-74.jar', work / 'paper.jar')
shutil.copy2(source / 'eula.txt', work / 'eula.txt')
for name in ('Vault.jar', 'EssentialsX-2.22.0.jar'):
    shutil.copy2(source / 'plugins' / name, work / 'plugins' / name)
shutil.copy2(root / 'target/ecolifeassist-1.1.0.jar', work / 'plugins/EcoLifeAssist.jar')
(work / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25579\nonline-mode=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\n')
with zipfile.ZipFile(work / 'plugins/InviteProbe.jar', 'w') as jar:
    jar.writestr('plugin.yml', 'name: InviteProbe\nversion: 1\nmain: dev.spa.ecolife.invite.PaperInviteProbe\napi-version: "26.1.2"\ndepend: [EcoLifeAssist, Vault, Essentials]\n')
    for file in (root / 'target/test-classes/dev/spa/ecolife/invite').glob('PaperInviteProbe*.class'):
        jar.write(file, 'dev/spa/ecolife/invite/' + file.name)
for phase in ('flow', 'restart', 'no-vault'):
    if phase == 'no-vault':
        for name in ('Vault.jar', 'EssentialsX-2.22.0.jar', 'InviteProbe.jar'):
            (work / 'plugins' / name).unlink()
        with zipfile.ZipFile(work / 'plugins/NoVaultProbe.jar', 'w') as jar:
            jar.writestr('plugin.yml', 'name: NoVaultProbe\nversion: 1\nmain: dev.spa.ecolife.invite.PaperNoVaultProbe\napi-version: "26.1.2"\ndepend: [EcoLifeAssist]\n')
            jar.write(root / 'target/test-classes/dev/spa/ecolife/invite/PaperNoVaultProbe.class', 'dev/spa/ecolife/invite/PaperNoVaultProbe.class')
    with (work / (phase + '.log')).open('w') as log:
        result = subprocess.run([java, '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms512M', '-Xmx1G', '-jar', 'paper.jar', '--nogui'], cwd=work, stdout=log, stderr=subprocess.STDOUT, timeout=180)
    output = (work / (phase + '.log')).read_text()
    for line in output.splitlines():
        if any(word in line for word in ('INVITE_PROBE', 'EcoLifeAssist', 'AssertionError', 'Exception', 'Caused by:')):
            print(line, flush=True)
    if result.returncode or 'INVITE_PROBE_PASS' not in output or 'INVITE_PROBE_FAIL' in output:
        raise SystemExit('FAILED: ' + str(work / (phase + '.log')))
print('PASS: command/payment/IP/GUI/event flow and server restart. Logs: ' + str(work))
