# EcoLifeAssist（エコライフアシスト）

PaperMC サーバー向けの、毎日ログインして遊ぶ理由を作るプラグインです。
第一弾の機能として**ログインボーナス（カレンダー式）**を入れています。
デイリー任務や実績など、毎日の動機づけになる機能は今後ここに足していきます。

## 動作環境

- Minecraft サーバー: PaperMC 26.1.2
- 対象 API: Paper API `1.21-R0.1-SNAPSHOT`
- Java: 21
- ビルドツール: Maven
- メインクラス: `dev.spa.ecolife.EcoLifeAssistPlugin`
- 依存プラグイン: なし（報酬はアイテムのみで、お金を扱わないため Vault は要りません）

## ログインボーナス

参加すると3秒後に、その日のぶんが**自動で**手に入ります。コマンドを打つ必要はありません。

### カレンダーの進み方

報酬表は1マス目〜31マス目まであり、良いものほど後ろのマスに置いてあります。

- 受け取ると、**その月に受け取った回数**が1つ進みます。1回目なら1マス目、2回目なら2マス目です
- **休んでもマスは戻りません。**ただし進みもしないので、休んだ日数ぶんだけ月の後ろのマスに届かなくなります
- 月が変わると1マス目に戻ります。前月の進みは持ち越しません
- その月の日数ぶん毎日受け取った人だけが、最後のマスに到達します（皆勤）

2月は最大28マス目まで、31日ある月だけ31マス目に届きます。

### 報酬の中身

前半は「その日の作業がはかどる消耗品」（パン・松明・石炭・鉄・矢など）です。
ダイヤなど値の張るものは、毎日欠かさず入った人だけが届く21マス目以降に置いています。
これは Jobs Reborn の時給（駆け出しでおよそ 1000S）と QuickShop の相場を壊さないための配分です。

中身はすべて `config.yml` の `rewards` に出してあり、マスごとに差し替えられます。

### 持ち物がいっぱいのとき

入り切らなかったぶんは**足元に落とします**。参加した瞬間に自動で渡すため、
持ち物が満杯の状態で入ってくる場面は必ず起きます。落とした場合はその旨をチャットで伝えます。

### 1日の区切り

日付は**日本時間の朝4時**に変わる扱いです（`config.yml` の `day.timezone` と `day.reset-hour`）。
月の切り替わりも同じ区切りで判定します。深夜0時ではなく朝4時にしているのは、日付をまたいで
遊んでいる人の受け取りがプレイ中に切り替わらないようにするためです。

日付をまたいで遊び続けている人には、1分ごとの見回りでその日のぶんを渡します。

## Discord通知

依頼板（ContractBoard）やオークション（AuctionHouse）など、他のプラグインで起きた出来事を
Discord Webhookへ知らせます。発生元プラグインをコンパイル時依存にしていないため、
EcoLifeAssist 単体でも、発生元プラグインが入っていない環境でも問題なく起動します。

### 何を通知するか

`config.yml` の `notify.sources` に登録した種別だけを通知します（既定は下の2種類）。

- `contract-created`: 依頼板に新しい依頼が出た
- `auction-listed`: オークションに新しい出品があった

発生元プラグイン側は Bukkit のカスタムイベントに `getNotifyKind()` と
`getNotifyPlaceholders()` を持たせるだけでよく、EcoLifeAssist は
`Class.forName` + 動的なイベント購読でそれを拾います。新しい発生元プラグインを足すときも、
EcoLifeAssist のコード変更は不要で、`notify.sources` への追記だけで済みます。

### 設定のしかた

`config.yml` の `notify:` セクションで設定します。

1. `enabled: true` にする
2. `webhook-url` にDiscordのWebhook URLを入れる（`${CFG_ECOLIFE_DISCORD_WEBHOOK}` の形は
   spsmc-infra が起動時に環境変数へ置き換える前提。直書きも可）
3. `/ecolife reload` で読み込み直す

`min-interval-seconds`（種別ごとの最短間隔）、`per-player-cooldown-seconds`（同一プレイヤーの
連投抑制）、`max-per-minute`（全体の毎分上限）で通知の頻度を間引けます。あふれたぶんは捨て、
捨てた件数だけ1分に1回以下の頻度でログに残します。

依頼タイトルやアイテム名などのプレイヤー入力は、Discordへ送る前に必ず無害化します。
色コードの除去、`@everyone` / `@here` の無効化（`allowed_mentions`）、Markdown記号の
エスケープ、改行の除去、`text-max-length`（既定80文字）での切り詰めを行います。

### 通知が飛ばないとき

上から順に確認してください。

1. `notify.enabled` が `true` になっているか
2. `notify.webhook-url` が実際のURLに置き換わっているか（空や `${...}` のままだと送信しません）
3. `/ecolife notify status` で、対象の発生元が「購読中」になっているか
   - 「未導入」なら、その発生元プラグイン（ContractBoard / AuctionHouse など）が
     このサーバーに入っていません
   - 「型が不正」「型が不正のため停止」なら、イベントクラスが通知規約を満たしていません
4. 間引き（`min-interval-seconds` / `per-player-cooldown-seconds` / `max-per-minute`）で
   捨てられていないか。`/ecolife notify status` の破棄件数と、サーバーログを確認してください

`/ecolife notify test` で、間引きを無視した1件をその場で送れます。

## コマンド

| コマンド | 権限 | 既定 | 説明 |
| --- | --- | --- | --- |
| `/daily`（`/bonus`, `/loginbonus`） | `ecolife.daily` | 全員 | 今月の進み具合と次のマスの中身を見る。未受け取りが残っていればその場で渡す |
| `/ecolife reload` | `ecolife.admin` | OP | `config.yml` を読み込み直す |
| `/ecolife info <名前>` | `ecolife.admin` | OP | 今月のマス数・最終受け取り日・通算・皆勤月数を見る |
| `/ecolife reset <名前>` | `ecolife.admin` | OP | 受け取り記録を消す（次回は今月の1マス目から） |
| `/ecolife notify status` | `ecolife.admin` | OP | Discord通知の有効/無効・購読状況・送信数と破棄数を見る |
| `/ecolife notify test` | `ecolife.admin` | OP | 間引きを無視して、テスト通知を1件送る |

`/ecolife info` と `/ecolife reset` は、オンラインのプレイヤーと、`data.yml` に記録が
残っているプレイヤーを名前で引けます。

## 保存されるデータ

`plugins/EcoLifeAssist/data.yml` に、プレイヤーごとの「いま数えている月」「その月に受け取った回数」
「最終受け取り日」「通算回数」「皆勤した月数」を保存します。受け取りのたびに非同期で書き込み、
サーバー停止時にも書き切ります。

## ビルドとローカル確認

```sh
mvn -B package
./scripts/test-plugin.sh
```

`scripts/test-plugin.sh` はローカルのPaperサーバーを起動し、プラグインが有効になることと
`/ecolife reload` が動くことを確認します。実行には `server-data/` に Paper の JAR が必要です
（`server-data/` は Git 管理外）。

アイテムの受け取り・満杯時のドロップ・演出は、起動後にクライアントから `localhost:25568` へ
接続して確かめてください。

## 設計の記録

実装前の合意内容は [AGREEMENT.md](AGREEMENT.md) にあります。
