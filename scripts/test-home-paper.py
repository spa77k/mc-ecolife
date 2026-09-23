#!/usr/bin/env python3
"""隔離Paper上でEssentialsXとの/home衝突解決と料金設定を確認する。"""
import os
import pathlib
import shutil
import subprocess
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
source = root / 'server-data'
work = root / 'target/home-paper-smoke'
java = os.environ.get('JAVA_BIN', '/opt/homebrew/opt/openjdk/bin/java')
subprocess.run(['mvn', '-B', 'clean', 'package'], cwd=root, check=True)
if work.exists():
    shutil.rmtree(work)
(work / 'plugins/Essentials').mkdir(parents=True)
for name in ('libraries', 'cache', 'versions'):
    if (source / name).exists():
        shutil.copytree(source / name, work / name)
shutil.copy2(source / 'paper-26.1.2-74.jar', work / 'paper.jar')
shutil.copy2(source / 'eula.txt', work / 'eula.txt')
shutil.copy2(source / 'plugins/EssentialsX-2.22.0.jar', work / 'plugins/EssentialsX.jar')
shutil.copy2(root / 'target/ecolifeassist-1.0.0.jar', work / 'plugins/EcoLifeAssist.jar')
shutil.copy2(root.parent / 'spsmc-infra/plugins/Essentials/config.yml', work / 'plugins/Essentials/config.yml')
with (work / 'plugins/Essentials/config.yml').open('a') as config:
    config.write('\n# テスト用Playerの権限をBukkitへ直接問い合わせる。\nuse-bukkit-permissions: true\n')
(work / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25581\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\n')
with zipfile.ZipFile(work / 'plugins/HomeProbe.jar', 'w') as jar:
    jar.writestr('plugin.yml', 'name: HomeProbe\nversion: 1\nmain: dev.spa.ecolife.PaperHomeProbe\napi-version: "26.1.2"\ndepend: [EcoLifeAssist, Essentials]\n')
    for file in (root / 'target/test-classes/dev/spa/ecolife').glob('PaperHomeProbe*.class'):
        jar.write(file, 'dev/spa/ecolife/' + file.name)
with (work / 'run.log').open('w') as log:
    result = subprocess.run([java, '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms256M', '-Xmx1G', '-jar', 'paper.jar', '--nogui'], cwd=work, stdout=log, stderr=subprocess.STDOUT, timeout=180)
output = (work / 'run.log').read_text()
for line in output.splitlines():
    if any(word in line for word in ('HOME_PROBE', 'Exception', 'AssertionError', 'Caused by:')):
        print(line, flush=True)
if result.returncode or 'HOME_PROBE_PASS' not in output or 'HOME_PROBE_FAIL' in output:
    raise SystemExit('FAILED: ' + str(work / 'run.log'))
print('PASS: command routing and configured price/limit on Paper + EssentialsX. Logs: ' + str(work))
