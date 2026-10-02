#!/usr/bin/env python3
"""隔離Paperで、月替わりの抽選・保存による固定・受け取り・カレンダーGUI・保存ファイル破損時の保留を確認する。"""
import os
import pathlib
import shutil
import subprocess
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
adminshop = root.parent / 'mc-adminshop'
source = root / 'server-data'
work = root / 'target/monthly-rewards-paper-smoke'
java = os.environ.get('JAVA_BIN', '/opt/homebrew/opt/openjdk/bin/java')
port = os.environ.get('MONTHLY_TEST_PORT', '25586')
subprocess.run(['mvn', '-B', 'package'], cwd=adminshop, check=True)
subprocess.run(['mvn', '-B', 'clean', 'package'], cwd=root, check=True)
if work.exists():
    shutil.rmtree(work)
(work / 'plugins/AdminShop').mkdir(parents=True)
for name in ('libraries', 'cache', 'versions'):
    if (source / name).exists():
        shutil.copytree(source / name, work / name)
shutil.copy2(source / 'paper-26.1.2-74.jar', work / 'paper.jar')
shutil.copy2(source / 'eula.txt', work / 'eula.txt')
for name in ('Vault.jar', 'EssentialsX-2.22.0.jar'):
    shutil.copy2(source / 'plugins' / name, work / 'plugins' / name)
shutil.copy2(adminshop / 'target/adminshop-1.0.0.jar', work / 'plugins/AdminShop.jar')
shutil.copy2(root / 'target/ecolifeassist-1.0.0.jar', work / 'plugins/EcoLifeAssist.jar')
shutil.copy2(root.parent / 'spsmc-infra/plugins/AdminShop/config.yml', work / 'plugins/AdminShop/config.yml')
(work / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\n')
with zipfile.ZipFile(work / 'plugins/MonthlyRewardsProbe.jar', 'w') as jar:
    jar.writestr('plugin.yml', 'name: MonthlyRewardsProbe\nversion: 1\nmain: dev.spa.ecolife.PaperMonthlyRewardsProbe\napi-version: "26.1.2"\ndepend: [EcoLifeAssist, AdminShop]\n')
    for file in (root / 'target/test-classes/dev/spa/ecolife').glob('PaperMonthlyRewardsProbe*.class'):
        jar.write(file, 'dev/spa/ecolife/' + file.name)
with (work / 'server.log').open('w') as log:
    result = subprocess.run([java, '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms256M', '-Xmx1G',
                             '-jar', 'paper.jar', '--nogui'], cwd=work, stdout=log,
                            stderr=subprocess.STDOUT, timeout=180)
output = (work / 'server.log').read_text()
for line in output.splitlines():
    if any(word in line for word in ('MONTHLY_REWARDS_PROBE', 'マス目:', 'Exception', 'AssertionError', 'Caused by:')):
        print(line, flush=True)
if result.returncode or 'MONTHLY_REWARDS_PROBE_PASS' not in output or 'MONTHLY_REWARDS_PROBE_FAIL' in output:
    raise SystemExit('FAILED: ' + str(work / 'server.log'))
print('PASS: monthly draw saved and fixed, claim and calendar GUI follow it, broken file holds claims. Logs: ' + str(work))
