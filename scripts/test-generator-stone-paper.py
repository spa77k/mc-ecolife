#!/usr/bin/env python3
"""隔離Paperで、石製造機で生まれた石の Jobs 報酬だけが取り消されることを確認する。"""
import os
import pathlib
import shutil
import subprocess
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
source = root / 'server-data'
work = root / 'target/generator-stone-paper-smoke'
java = os.environ.get('JAVA_BIN', '/opt/homebrew/opt/openjdk/bin/java')
# 本番と同じ Jobs と CMILib のJARを使う。既定は隣の spsmc-infra のローカル環境のもの
infra_plugins = root.parent / 'spsmc-infra/data/plugins'
jobs = pathlib.Path(os.environ.get('JOBS_JAR', infra_plugins / 'Jobs5.2.6.6.jar'))
cmilib = pathlib.Path(os.environ.get('CMILIB_JAR', infra_plugins / 'CMILib1.6.0.1.jar'))
for jar in (jobs, cmilib):
    if not jar.is_file():
        raise SystemExit(f'FAILED: {jar} がありません。JOBS_JAR / CMILIB_JAR で指定してください。')

subprocess.run(['mvn', '-B', 'clean', 'package'], cwd=root, check=True)
if work.exists():
    shutil.rmtree(work)
(work / 'plugins/EcoLifeAssist').mkdir(parents=True)
for name in ('libraries', 'cache', 'versions'):
    if (source / name).exists():
        shutil.copytree(source / name, work / name)
shutil.copy2(source / 'paper-26.2-129.jar', work / 'paper.jar')
shutil.copy2(source / 'eula.txt', work / 'eula.txt')
shutil.copy2(root / 'target/ecolifeassist-1.0.0.jar', work / 'plugins/EcoLifeAssist.jar')
shutil.copy2(jobs, work / 'plugins/Jobs.jar')
shutil.copy2(cmilib, work / 'plugins/CMILib.jar')
port = os.environ.get('GENERATOR_TEST_PORT', '25587')
(work / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\nspawn-monsters=false\n')
with zipfile.ZipFile(work / 'plugins/GeneratorStoneProbe.jar', 'w') as jar:
    jar.writestr('plugin.yml', 'name: GeneratorStoneProbe\nversion: 1\nmain: dev.spa.ecolife.PaperGeneratorStoneProbe\napi-version: "26.2"\ndepend: [EcoLifeAssist, Jobs]\n')
    for file in (root / 'target/test-classes/dev/spa/ecolife').glob('PaperGeneratorStoneProbe*.class'):
        jar.write(file, 'dev/spa/ecolife/' + file.name)


def run(label, *props):
    with (work / f'run-{label}.log').open('w') as log:
        result = subprocess.run([java, *props, '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms256M', '-Xmx1G', '-jar', 'paper.jar', '--nogui'],
                                cwd=work, stdout=log, stderr=subprocess.STDOUT, timeout=300)
    output = (work / f'run-{label}.log').read_text()
    for line in output.splitlines():
        if any(word in line for word in ('GENERATOR_PROBE', '石製造機', 'Exception', 'Caused by:')):
            print(f'[{label}] {line}', flush=True)
    if result.returncode or 'GENERATOR_PROBE_DONE' not in output:
        raise SystemExit('FAILED: ' + str(work / f'run-{label}.log'))
    return {line.split('GENERATOR_PROBE_RESULT ')[1].split()[0]: line.split()[-1] == 'true'
            for line in output.splitlines() if 'GENERATOR_PROBE_RESULT ' in line}


def check(ok, message):
    if not ok:
        raise SystemExit('FAILED: ' + message)


first = run('first')
log = (work / 'run-first.log').read_text()
check('GENERATOR_PROBE_FORMED STONE STONE' in log or 'GENERATOR_PROBE_FORMED COBBLESTONE STONE' in log, '溶岩と水で石が生まれる')
check(first.get('generator_break') is True, '製造機の石を掘る報酬は取り消す')
check(first.get('generator_tnt') is True, '製造機の石をTNTで壊す報酬も取り消す')
check(first.get('generator_place') is False, '設置など掘る以外の報酬は取り消さない')
check(first.get('natural_break') is False, '自然の石を掘る報酬は取り消さない')
check(first.get('generator_reformed_break') is True, '掘ったあと生まれ直した石も取り消す')
check('GENERATOR_PROBE_PUSHED STONE' in log, 'ピストンで石が動く')
check(first.get('pushed_break') is True, 'ピストンで押した先の石も取り消す')
check(first.get('pushed_from_break') is False, '押す前の場所の記録は消える')

second = run('second', '-Dprobe.second=true')
check(second.get('restart_pushed_break') is True, '再起動後も記録が残る')
check('GENERATOR_PROBE_EXPLODED AIR' in (work / 'run-second.log').read_text(), '爆発で石が壊れる')
check(second.get('exploded_then_placed_break') is False, '爆発で壊れた場所の記録は消える')
print('PASS: 石製造機で生まれた石の Jobs 報酬だけを取り消しました。 Logs: ' + str(work))
