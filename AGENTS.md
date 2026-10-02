# 開発・リリースの進め方

2026-09-15のユーザー指定に基づく、このプロジェクトの開発方針。

## 基本方針

- 作業は `main` で進める。ユーザーから別途指定がない限り、作業ブランチやPRは作らない。
- 作業前に `git status --short` を確認し、既存の変更を上書き・削除したり、自分の変更と一緒にコミットしたりしない。
- バージョンは勝手に上げない。現在は `1.0.0` を維持し、既存の `v1.0.0` リリースの成果物を差し替える。
- 本番サーバーへの反映・再起動は、ユーザーから明示的に依頼された場合だけ行う。GitHubへのpushやリリース更新と、本番への反映は別の操作として扱う。
- メモやドキュメントだけの変更では、JARのビルド・リリース差し替えは不要。

## 実装からリリースまで

1. 既存の実装・設定・作業ツリーを確認し、`main` で変更する。
2. `mvn -B package` でビルド・テストする。招待機能の変更では `python3 scripts/test-invite-paper.py` で隔離Paperの動作も確認する。
   - 対象は Paper 26.1.2 / Java 25。
   - 隔離テストは `target/invite-paper-smoke`、待受は `127.0.0.1:25579`。
   - テスト用Playerによる検証と実クライアントでの確認を区別し、未確認の項目は報告する。
3. `git diff --check` と差分を確認し、今回の変更だけをコミットしてGitHubの `main` へpushする。
4. リリースを更新する場合は、検証した `target/ecolifeassist-1.0.0.jar`、対応するソース一式、`SHA256SUMS` を用意する。
5. 既存の `v1.0.0` リリースへ成果物を差し替える。タグも対応するソースコミットへ更新する。タグの更新は元の参照を確認し、対象タグだけを `--force-with-lease` で更新する。ブランチの強制pushはしない。
6. `../spsmc-infra/AGENTS.md` と作業ツリーを確認し、同リポジトリの `main` で `Dockerfile` の `ECOLIFE_SHA256` を新しいJARの値へ更新する。URLは既存の `v1.0.0/ecolifeassist-1.0.0.jar` を維持する。必要な設定変更もリポジトリ側へ反映する。
7. 公開リリースからJARをダウンロードし直し、Dockerfileに記載したSHA-256との一致を確認する。インフラの差分・設定を検証し、今回の変更だけをコミットしてGitHubの `main` へpushする。
   - Git remote名は実際に確認する。2026-09-22確認時は本リポジトリ・spsmc-infraともに `origin`。
   - push前に未送信コミットも確認し、今回と無関係なコミットを意図せず送信しない。
8. リリースURL、検証結果、インフラの更新内容、本番反映の有無を報告する。

## 本番操作の境界

リリースとインフラ更新までの依頼では、SSHによる本番操作、リモートのpull、コンテナ再作成、サーバー再起動は行わない。本番反映を明示的に依頼されたときは、spsmc-infraの運用ガイドに従う。

## 招待の時間判定

- 招待登録期限と報酬成立条件は、McLevelの公開API `getActiveSeconds(Player)` が返す累計アクティブ秒で統一する（既定7200秒）。
- McLevelが利用できない場合は登録・支払いを保留する。放置時間を含むBukkit統計へフォールバックしない。
- 連携変更時は隣接するmclevelもビルドし、隔離Paperで時間境界・旧版/未導入/停止時の保留・復旧・再起動を検証する。

## 招待のIP判定

- 2026-09-21のユーザー指定により、登録・報酬成立にIP制限を設けない。接続情報の欠落も拒否理由にしない。
- 旧版の `BLOCKED` はオンライン判定時に `WAITING` へ戻し、既存の支払い記録を維持して通常条件で再判定する。

## ポスター機能

- 2026-09-22のユーザー指定: EcoLifeAssistに追加する。運営登録の画像を一般ユーザーが一覧から選んで飾る。追加要望は既存のフィードバックを使い、投稿・要望機能を重複実装しない。
- 運営用の画像は `posters/images/`、登録内容は `posters/catalog.yml`。初期カタログは空にし、実際の画像は運営が用意する。
- 取得済み地図のIDと描画キャッシュを維持する。カタログ削除・画像変更で設置済みの表示を壊さない。
- 変更時は `mvn -B package` と `python3 scripts/test-poster-paper.py` を実行する。隔離環境は `target/poster-paper-smoke`、`127.0.0.1:25580`。テスト用Playerと実クライアント確認は区別する。
- 詳細は `docs/posters.md`。リリース更新だけでは本番反映しない。

## ホームコマンド

- 2026-09-23の指定により、EcoLifeAssist の `/home` と `/sethome`、EssentialsX とのホーム連携を削除した。再導入しない。
- EssentialsX 自体のホーム機能・設定・保存済みデータは `../spsmc-infra` の管理範囲であり、この削除では変更しない。
- コマンド所有者の確認には `python3 scripts/test-home-paper.py` を使う。隔離Paperは `target/home-paper-smoke`、待受は `127.0.0.1:25581`。

## ログインボーナスとAdminShop

- 2026-09-24の指定: 14マス目をAdminShop製の「帰還の護符」1個に差し替える。商品IDは `return_charm`。同梱設定と `../spsmc-infra/plugins/EcoLifeAssist/config.yml` を揃える。
- AdminShopが生成した効果データ付きの本物だけを渡す。未導入・停止・商品欠落・連携失敗時は14マス目の受け取りと記録を保留する。復旧後は `/daily` で再試行できる。
- 変更時はAdminShopもビルドし、`python3 scripts/test-daily-adminshop-paper.py` で隔離Paperを確認する。待受は `127.0.0.1:25582`。本番反映は別途明示依頼が必要。
- 2026-10-02の指定: 2026-11から、ログインボーナスを月替わりの抽選にする（`monthly-rewards`）。全員同じ月替わりカレンダーで、段階ごとの候補から抽選し、値の張るものは後ろの段階だけに置く。ネザライトは入れない。
- 抽選結果は月初めに `calendars.yml` へ保存して固定する。抽選済みの月は候補の書き換えや再起動で変えない。ファイルが読めないときは上書きせず、受け取りを保留する。
- `/daily` は今月のカレンダーGUIを開く（次にもらえるマスを光らせる）。変更時は `python3 scripts/test-monthly-rewards-paper.py` で確認する。待受は既定で `127.0.0.1:25586`（`MONTHLY_TEST_PORT` で変更）。GUIの見た目は実クライアントでの確認と区別して報告する。

## スマホ

- 2026-09-24の指定: 右クリックで開く配布アイテムに、一般プレイヤー向けの主要機能をまとめる。トップ画面は `Spa Job`、`Spazon`（オークションとAdminShop）、`Spa Mail`（フィードバック）、`SpaMap`（ロビー）のアプリ風にする。テクスチャもJava版・統合版用に用意する。
- 配布・GUI・操作は `docs/phone.md` を参照。既存機能のプレイヤー権限を維持し、管理者コマンドは載せない。
- 2026-09-24の追加指定: 自動配布は初回参加時のみ。2回目以降のログインと死亡後には再配布しない。捨てる操作を許可し、紛失時は `/phone get` で受け取れるようにする。
- 2026-09-26の指定: 案内所GUIを廃止し、一般プレイヤー向けの入口をスマホに統一する。資源・建築ワールドへの移動もスマホ内で完結させる。
- 変更時は `mvn -B package` と `python3 scripts/test-phone-paper.py` で検証する。隔離Paperは `target/phone-paper-smoke`、待受は既定で `127.0.0.1:25583`（使用中なら `PHONE_TEST_PORT` で変更）。実クライアント確認と区別する。

## 自動化装置の検出

- 2026-09-27の指定: ルール「全自動装置は禁止、半自動はOK」の違反候補を、運営専用のDiscord Webhookへ座標つきで通知する。通知だけにし、停止・撤去はしない。
- 判定基準は「最後の仕上げ（収穫・キル・回収）をプレイヤー本人がその場でやっているか」。ボタン式の半自動装置や、自分で倒す鉄トラップは対象外。近くのプレイヤーが全員放置中（または不在）なのに、搬送・回収・ピストン・ディスペンサー・プレイヤー以外によるモブの死亡が続くチャンクを数える。
- 一度通知した場所（ワールド＋チャンク）は `automation.db` に残し、二度と通知しない。
- 通知にはBlueMapのリンク（`bluemap-url`、地図IDはBlueMap APIから取得）と、CoreProtectで調べた装置の設置者・設置日時を載せる。CoreProtectの検索は送信用スレッドで行い、メインスレッドで引かない。
- `automation.db` は `../spsmc-insight` の `AutomationExport` が読み取り専用で開き、週次出力に含める。列を変えるときは両方を揃える。
- Webhookは一般向けの `notify.webhook-url` と分け、`${CFG_ECOLIFE_AUTOMATION_WEBHOOK}` を使う。座標が載るため一般チャンネルへ流さない。
- 変更時は `mvn -B package` と `python3 scripts/test-automation-paper.py` を実行する。隔離Paperは `target/automation-paper-smoke`、待受は既定で `127.0.0.1:25584`（`AUTOMATION_TEST_PORT` で変更）。設置者の検索まで確かめるときは `COREPROTECT_JAR` に本番と同じCoreProtectのJARを指定する。放置していないプレイヤーがいる場合に通知しないことは、テスト用Playerでは未検証。

## お墓

- 2026-10-02の指定: 死亡時の持ち物を預かるお墓をEcoLifeAssistに入れる（外部プラグインのAxGravesは使わない）。墓石のテクスチャはCodex CLIの画像生成で作る。
- 中身の正本は `graves.yml`。取り出し・期限切れでは、先に記録を消して保存してから中身を渡す（二重に渡さない）。
- 墓石は `ItemDisplay`。Geyserが送らないため、統合版では名前表示（`TextDisplay`）とクリック判定（`Interaction`）だけになる。
- テクスチャを作り直すときは `codex exec --enable image_generation` で32×32・単色マゼンタ背景の絵を作り、背景を透過して `assets/grave/grave.png` に置く。`scripts/build-phone-packs.py` がJava用パックへ入れる。
- 変更時は `mvn -B package` と `python3 scripts/test-grave-paper.py` を実行する。隔離Paperは `target/grave-paper-smoke`、待受は既定で `127.0.0.1:25585`（`GRAVE_TEST_PORT` で変更）。詳細は `docs/grave.md`。実クライアントでの見た目は別途確認が必要。
