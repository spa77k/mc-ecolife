#!/usr/bin/env python3
"""隔離Paperで自動化装置の検出・運営用Webhook通知・一度きりの通知を確認する。"""
import http.server
import json
import os
import pathlib
import shutil
import sqlite3
import subprocess
import threading
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
source = root / 'server-data'
work = root / 'target/automation-paper-smoke'
java = os.environ.get('JAVA_BIN', '/opt/homebrew/opt/openjdk/bin/java')
posts = []


class Hook(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        posts.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
        self.send_response(204)
        self.end_headers()

    def log_message(self, *args):
        pass


server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Hook)
threading.Thread(target=server.serve_forever, daemon=True).start()
hook = f'http://127.0.0.1:{server.server_address[1]}/hook'

subprocess.run(['mvn', '-B', 'clean', 'package'], cwd=root, check=True)
if work.exists():
    shutil.rmtree(work)
(work / 'plugins/EcoLifeAssist').mkdir(parents=True)
for name in ('libraries', 'cache', 'versions'):
    if (source / name).exists():
        shutil.copytree(source / name, work / name)
shutil.copy2(source / 'paper-26.1.2-74.jar', work / 'paper.jar')
shutil.copy2(source / 'eula.txt', work / 'eula.txt')
shutil.copy2(root / 'target/ecolifeassist-1.0.0.jar', work / 'plugins/EcoLifeAssist.jar')
# 設置者の検索まで確かめるときは、本番と同じ CoreProtect のJARを指定する
coreprotect = os.environ.get('COREPROTECT_JAR')
if coreprotect:
    shutil.copy2(coreprotect, work / 'plugins/CoreProtect.jar')
config = (root / 'src/main/resources/config.yml').read_text()
start = config.index('automation-watch:')
end = config.index('\n# 受け取ったときの演出。')
config = config[:start] + f'''automation-watch:
  enabled: true
  webhook-url: "{hook}"
  username: "test"
  bluemap-url: "https://map.example/"
  radius-blocks: 64
  afk-seconds: 60
  window-minutes: 3
  min-active-minutes: 2
  min-score: 5
  check-interval-seconds: 2
''' + config[end:]
(work / 'plugins/EcoLifeAssist/config.yml').write_text(config)
port = os.environ.get('AUTOMATION_TEST_PORT', '25584')
(work / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\nspawn-monsters=false\n')
with zipfile.ZipFile(work / 'plugins/AutomationProbe.jar', 'w') as jar:
    jar.writestr('plugin.yml', 'name: AutomationProbe\nversion: 1\nmain: dev.spa.ecolife.PaperAutomationProbe\napi-version: "26.1.2"\ndepend: [EcoLifeAssist]\nsoftdepend: [CoreProtect]\n')
    for file in (root / 'target/test-classes/dev/spa/ecolife').glob('PaperAutomationProbe*.class'):
        jar.write(file, 'dev/spa/ecolife/' + file.name)


def run(label):
    with (work / f'run-{label}.log').open('w') as log:
        result = subprocess.run([java, '-Dprobe.seconds=30', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms256M', '-Xmx1G', '-jar', 'paper.jar', '--nogui'],
                                cwd=work, stdout=log, stderr=subprocess.STDOUT, timeout=240)
    output = (work / f'run-{label}.log').read_text()
    for line in output.splitlines():
        if any(word in line for word in ('AUTOMATION_PROBE', '自動化装置', 'Exception', 'Caused by:')):
            print(f'[{label}] {line}', flush=True)
    if result.returncode or 'AUTOMATION_PROBE_DONE' not in output:
        raise SystemExit('FAILED: ' + str(work / f'run-{label}.log'))


def check(ok, message):
    if not ok:
        raise SystemExit('FAILED: ' + message)


run('first')
check('AUTOMATION_PROBE_ACTIVITY 9/9' in (work / 'run-first.log').read_text(), '放置判定の操作イベントをすべて購読している')
check(len(posts) == 2, f'1回目は2か所を1回ずつ通知する（実際 {len(posts)} 件）')
contents = [p['content'] for p in posts]
hopper = [p for p in contents if 'X 165 Y 69 Z 165' in p]
mob = [p for p in contents if 'モブの死亡' in p and '（チャンク -10, -10）' in p]
check(len(hopper) == 1 and 'ホッパー等の搬送' in hopper[0], 'ホッパーの座標と内訳が通知に入る')
check(len(mob) == 1, 'プレイヤー以外によるモブの死亡が通知に入る')
check('[地図で見る](<https://map.example/#world:165:69:165:60:0:0.9:0:0:perspective>)' in hopper[0], 'BlueMapのリンクが入る')
placer = '装置を置いた人: probe\\_user（' if coreprotect else '装置を置いた人: 不明（CoreProtect未導入）'
check(placer in hopper[0], f'設置者の欄が入る（{placer}）')
check('装置を置いた人: 不明（装置のブロックなし）' in mob[0], 'モブだけの場所は設置者を調べない')
check(all(p['allowed_mentions'] == {'parse': []} for p in posts), 'メンションを無効化している')
db = sqlite3.connect(work / 'plugins/EcoLifeAssist/automation.db')
rows = db.execute('SELECT world, chunk_x, chunk_z, sent, transfer, mob_death, map_url, placer, placed_at FROM detections ORDER BY chunk_x').fetchall()
db.close()
check(sorted((r[1], r[2], r[3]) for r in rows) == [(-10, -10, 1), (10, 10, 1)], f'DBに送信済みで記録される {rows}')
check(rows[0][5] > 0 and rows[1][4] > 0 and rows[1][6].startswith('https://map.example/#world:'), f'内訳と地図URLが列に入る {rows}')
check((rows[1][7], rows[1][8] > 0) == (('probe_user', True) if coreprotect else ('', False)), f'設置者は名前と時刻に分けて入る {rows}')

run('second')
check(len(posts) == 2, f'再起動後も記録済みの場所は通知しない（実際 {len(posts)} 件）')
server.shutdown()
print('PASS: 無人で動く装置を1か所1回だけ運営用Webhookへ通知しました。 Logs: ' + str(work))
