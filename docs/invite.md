# 友達招待（EcoLifeAssist 1.0.0）

## プレイヤーの使い方

1. 紹介者が自分のプレイヤー名を友達に伝える。紹介者は機能導入後に一度ログインしておく。
2. 初参加の友達が累計プレイ時間2時間未満で `/invite <紹介者の名前>` を実行する。
3. 累計2時間に達すると、オンライン中の定期確認（200 tick、通常約10秒）で紹介者へ2000S、新規へ1000Sを支払う。紹介者はオフラインでもよい。

`/invite` はチェストGUI。自分のコード、招待一覧と状況、自分が受けた招待、ランキングを表示する。45件ごとにページを切り替える。`/invite top` は成立件数の上位10人をチャットへ表示する。

プレイヤー名が `top` / `admin` / `code` の場合も `/invite code <名前>` なら登録できる。名前は大文字・小文字を区別せず、紐づけはUUIDで保存する。改名後にログインするとコードとランキング名が更新される。古い名前と別人の現在名が衝突する場合は誤登録を避けて拒否するため、両者にログインしてもらう。

## 条件と設定

- 機能導入後の初参加者だけが新規側になれる。Bukkitの `hasPlayedBefore()` で初参加を判定し、判定結果を初回に永続化する。導入時にオンラインだった人は既存扱い。
- Bukkit統計 `PLAY_ONE_MINUTE` はtick数として扱う。既定の境界は144000 tick。AFKもBukkit統計に計上された分は含む。
- 自己紹介、重複登録、相互紹介・循環する紐づけは禁止。紹介人数の上限はない。紹介者自身が誰かから紹介されていても、その上位者への追加報酬は発生しない。
- 同一IPを登録時と成立前に確認する。生IPはDB・イベント・プラグインのログへ出さず、ランダムなサーバー固有秘密値によるHMAC-SHA256だけを保存する。
- 導入後に観測した接続元ハッシュの履歴に共通項がある組み合わせを拒否する。接続元が不明なら支払いを保留する。同一IPで成立前に保留となった紐づけは、運営の救済まで成立しない。
- Bukkitが認識した接続元を使う。Playit/Geyserなどを通る本番接続で各人のIPを識別できるかは、公開前に確認する。全員が同じ中継IPに見える構成では通常の招待が拒否される。
- 一度成立した招待は取り消し不可。成立前に離脱した場合は紐づけを保持する。

```yaml
invite:
  enabled: true
  required-hours: 2.0
  rewards:
    inviter: 2000.0
    newcomer: 1000.0
  messages: # 配布config.yml参照。チャット・GUIの日本語文言を変更可能。
```

`/ecolife reload` で反映する。時間条件の変更は進行中の招待にも適用、報酬額の変更は新しい登録から適用。時間は正の有限値、金額は0以上の有限値。時間の上限はBukkit統計の整数範囲内。

Vaultは任意依存。Vaultと経済プロバイダーが揃うまで支払いを保留する。ログインボーナスや既存Discord通知はVaultなしで使える。招待成立の全体チャット・Discord通知は行わない。

## 運営コマンド

管理権限は `ecolife.admin`（既定OP）。一般機能は `ecolife.invite`（既定全員）。

| コマンド | 動作 |
| --- | --- |
| `/invite admin status` | 機能の有効状態とVault経済の利用可否 |
| `/invite admin info <新規>` | 新規対象か、紹介者、状態、双方の支払い状態、IP救済有無 |
| `/invite admin link <新規> <紹介者>` | 同一IP検査を免除して登録。新規はオンライン必須。初参加条件・時間制限・自己紹介禁止は維持 |
| `/invite admin allowip <新規>` | 登録済みの未成立招待のIP検査を免除。時間条件は維持 |
| `/invite admin cancel <新規>` | 支払い開始前の未成立招待だけ取り消す。入力期間内なら再登録可能 |
| `/invite admin resolve <新規> <inviter\|newcomer> <paid\|unpaid>` | 結果不明の支払いを、調査結果に基づき支払い済み／未払いへ確定 |

家族の救済例: 新規がログイン中に `/invite admin link Friend Inviter`。すでに登録済みで同一IP保留になった場合は `/invite admin allowip Friend`。

### 支払いエラー・停止からの復旧

Vaultには複数口座を一括確定するトランザクションがない。正常時は同じサーバーtick内に双方へ順番に入金するが、片方だけ成功する可能性がある。

入金直前に `SENDING` をSQLiteへ同期確定し、成功後に `PAID` を保存する。`PENDING` は未着手。例外・エラー応答・入金とDB確定の間のプロセス停止では `SENDING` のまま止め、自動再送しない。誤った二重払いを避けるため、エラー応答も結果不明として扱う。

1. `/invite admin info Friend` で対象と支払い状態を確認する。
2. 経済プラグインの残高・ログなどで、実際の入金有無を調査する。
3. 新規側が未払いと確認できたら `/invite admin resolve Friend newcomer unpaid`。入金済みなら最後を `paid` にする。
4. 新規の次回オンライン判定で残りの処理が進む。既に `PAID` の側には再送しない。

調査せず `unpaid` にすると二重払いになり得る。DB障害で招待機能全体が停止した場合は、原因を解消してサーバーを再起動する。

## 保存とSPSMCInsight連携

`plugins/EcoLifeAssist/invites.db` にプレイヤー、IPハッシュ履歴、招待・支払い状態、登録時報酬額、登録・成立時刻、管理操作の監査記録を保存する。ログインボーナスは引き続き `data.yml`。バックアップはサーバー停止中かSQLiteバックアップAPIを使う。稼働中にDBだけをコピーするとWALの確定済み内容を取りこぼす場合がある。DBを消すと支払い履歴とIP秘密値も失うため初期化しない。

`dev.spa.ecolife.invite.InviteEvent` をメインスレッドで、DB確定後に発火する。

- `getKind()`: `LINKED` / `BLOCKED` / `COMPLETED` / `CANCELLED`
- `getNewcomerId()` / `getInviterId()`: UUID
- `getNewcomerAmount()` / `getInviterAmount()`: 登録時の報酬額
- `isIpOverride()`: 運営によるIP救済の有無

受信側プラグインは `softdepend: [EcoLifeAssist]` とprovided依存を設定し、導入時だけListenerを登録する。SPSMCInsight本体の収集処理はこの変更に含めない。イベントは永続キューではなく、DB確定直後の強制終了などでは欠落し得る。厳密な集計は `invites` テーブルの `state='COMPLETE'` と `completed_at` を読み取り補完する。

## 検証

```sh
mvn -B package
python3 scripts/test-invite-paper.py
```

JUnitでSQLiteの再オープン、改名、初参加判定保持、IP履歴、取り消し、結果不明の送金確定を検証する。

Paper 26.1.2 build 74 / Java 25 / Vault / EssentialsX の隔離サーバーは `target/invite-paper-smoke`、待受は `127.0.0.1:25579`。本体の実コマンド・イベント・GUI生成を、テスト用Playerアダプターで駆動する。通常の報酬入金、2時間境界、同一IP拒否と再確認、改名、二重払い防止、片側失敗と手動復旧、再起動、Vaultなしでの起動を確認する。

実クライアントのログイン・実際の統計加算・GUIクリックの表示確認、およびPlayit/Geyser経由の接続元確認は、この自動検証には含まない。

API根拠: [Paper Statistic](https://jd.papermc.io/paper/26.1.2/org/bukkit/Statistic.html)、[Vault Economy](https://github.com/MilkBowl/VaultAPI/blob/master/src/main/java/net/milkbowl/vault/economy/Economy.java)。
