#!/usr/bin/env python3
"""隔離PaperでAdminShopの本物の護符と欠落時の受け取り保留を確認する。"""
import os
import pathlib
import shutil
import subprocess
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
adminshop = root.parent / 'mc-adminshop'
source = root / 'server-data'
work = root / 'target/daily-adminshop-paper-smoke'
java = os.environ.get('JAVA_BIN', '/opt/homebrew/opt/openjdk/bin/java')
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
(work / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25582\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\n')
with zipfile.ZipFile(work / 'plugins/DailyAdminShopProbe.jar', 'w') as jar:
    jar.writestr('plugin.yml', 'name: DailyAdminShopProbe\nversion: 1\nmain: dev.spa.ecolife.PaperDailyAdminShopProbe\napi-version: "26.1.2"\ndepend: [EcoLifeAssist]\n')
    for file in (root / 'target/test-classes/dev/spa/ecolife').glob('PaperDailyAdminShopProbe*.class'):
        jar.write(file, 'dev/spa/ecolife/' + file.name)
for phase in ('with-adminshop', 'without-adminshop'):
    if phase == 'without-adminshop':
        (work / 'plugins/AdminShop.jar').unlink()
    with (work / (phase + '.log')).open('w') as log:
        result = subprocess.run([java, '-Dprobe.no-adminshop=' + str(phase == 'without-adminshop').lower(),
                                 '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms256M', '-Xmx1G',
                                 '-jar', 'paper.jar', '--nogui'], cwd=work, stdout=log,
                                stderr=subprocess.STDOUT, timeout=180)
    output = (work / (phase + '.log')).read_text()
    for line in output.splitlines():
        if any(word in line for word in ('DAILY_ADMINSHOP_PROBE', 'Exception', 'AssertionError', 'Caused by:')):
            print(line, flush=True)
    if result.returncode or 'DAILY_ADMINSHOP_PROBE_PASS' not in output or 'DAILY_ADMINSHOP_PROBE_FAIL' in output:
        raise SystemExit('FAILED: ' + str(work / (phase + '.log')))
print('PASS: genuine return charm, one claim per day, missing product/plugin holds slot 14. Logs: ' + str(work))
