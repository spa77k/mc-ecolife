#!/usr/bin/env python3
"""隔離Paperと本物のEssentialsX・Vaultで、借金の借入・返済・利息・差し押さえ・禁止・再起動を検証する。"""
import os
import pathlib
import shutil
import subprocess
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
source = root / 'server-data'
work = root / 'target/loan-paper-smoke'
java = os.environ.get('JAVA_BIN', '/opt/homebrew/opt/openjdk@25/bin/java')
port = int(os.environ.get('LOAN_TEST_PORT', '25588'))
subprocess.run(['mvn', '-B', 'package'], cwd=root, check=True)
if work.exists():
    shutil.rmtree(work)
(work / 'plugins/EcoLifeAssist').mkdir(parents=True)
for name in ('libraries', 'cache', 'versions'):
    if (source / name).exists():
        shutil.copytree(source / name, work / name)
shutil.copy2(source / 'paper-26.2-129.jar', work / 'paper.jar')
shutil.copy2(source / 'eula.txt', work / 'eula.txt')
shutil.copy2(root / 'target/ecolifeassist-1.0.0.jar', work / 'plugins/EcoLifeAssist.jar')
for name in ('Vault.jar', 'EssentialsX-2.22.0.jar'):
    shutil.copy2(source / 'plugins' / name, work / 'plugins' / name)
config = (root / 'src/main/resources/loan.yml').read_text()
config = config.replace('blocked-inventory-holders:\n',
                        'blocked-inventory-holders:\n  - dev.spa.ecolife.loan.PaperLoanProbe$ShopHolder\n')
(work / 'plugins/EcoLifeAssist/loan.yml').write_text(config)
(work / 'server.properties').write_text(
    f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n'
    'view-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\n'
    'generate-structures=false\n')
with zipfile.ZipFile(work / 'plugins/LoanProbe.jar', 'w') as jar:
    jar.writestr('plugin.yml', 'name: LoanProbe\nversion: 1\n'
                'main: dev.spa.ecolife.loan.PaperLoanProbe\napi-version: "26.2"\n'
                'depend: [EcoLifeAssist, Vault, Essentials]\n')
    for file in (root / 'target/test-classes/dev/spa/ecolife/loan').glob('PaperLoanProbe*.class'):
        jar.write(file, 'dev/spa/ecolife/loan/' + file.name)
for phase in ('initial', 'restart'):
    with (work / (phase + '.log')).open('w') as log:
        result = subprocess.run([java, '-Dprobe.restart=' + str(phase == 'restart').lower(),
                                 '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms256M',
                                 '-Xmx1G', '-jar', 'paper.jar', '--nogui'], cwd=work,
                                stdout=log, stderr=subprocess.STDOUT, timeout=180)
    output = (work / (phase + '.log')).read_text()
    for line in output.splitlines():
        if any(word in line for word in ('LOAN_', 'Exception', 'AssertionError', 'Caused by:')):
            print(line, flush=True)
    if result.returncode or 'LOAN_PROBE_PASS' not in output or 'LOAN_PROBE_FAIL' in output:
        raise SystemExit('FAILED: ' + str(work / (phase + '.log')))
print('PASS: borrow limit, repayment, compound interest, garnishing with real EssentialsX, '
      'blocked commands and screens while overdue, and restart persistence. Logs: ' + str(work))
