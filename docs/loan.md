# 借金

`/loan` でゲーム内通貨を借りられる。利息は1週間を1年として数える複利で、返済期限を過ぎると収入の差し押さえと、買い物・送金の禁止がかかる。スマホのトップにある `Spa Loan` からも同じ操作ができる。

## コマンド

| コマンド | 権限 | 内容 |
| --- | --- | --- |
| `/loan` | `ecolife.loan` | 借金残高、次に利息が付く日時、返済期限、借入上限を表示する。`/debt` でも同じ |
| `/loan borrow <額>` | `ecolife.loan` | 整数の額を借りる。所持金へすぐ入る |
| `/loan repay <額\|all>` | `ecolife.loan` | 所持金から返す。借金残高と所持金のうち小さいほうまで |
| `/loan list` | `ecolife.loan.admin` | 全員の借金残高と期限を表示する。コンソールでも使える |
| `/loan forgive <名前>` | `ecolife.loan.admin` | 借金を帳消しにする。所持金は変えない |

## しくみ

- **借入上限**: `limits` にLuckPermsのグループ名ごとの上限を書く。プレイヤーが入っているグループ（権限 `group.<グループ名>`）のうち最大の値を使う。利息込みの残高と借りる額の合計が上限を超える借入は断る。利息で残高が上限を超えることはある。
- **利息**: 最初に借りた時刻から `interest-period-hours`（既定168時間）ごとに、残高へ `interest-rate`（既定10%）を複利で付ける。小数第2位より下は切り上げる。サーバーが止まっていた間やオフラインの間のぶんも、次の見回り（1分ごと）でまとめて付く。
- **返済期限**: 最初に借りた時刻から `due-days`（既定14日）。追加で借りても延びない。全額返すと借金の記録が消え、次に借りたときに新しい期限が始まる。
- **期限切れの間のペナルティ**:
  - 所持金が増えるたびに、増えた額の `garnish-rate`（既定50%）を借金の返済へ回す。Jobs・ログインボーナス・売上・受け取った `/pay` など、EssentialsXの残高が増える操作すべてが対象。`/eco` による付与は対象外。
  - `blocked-commands` のコマンド（送金、土地保護ブロックの購入、依頼の作成）を実行できない。
  - `blocked-inventory-holders` の画面（AdminShop、オークションの出品詳細と即決確認、依頼の作成確認）を開けない。出品や保管庫は使える。
  - QuickShopの販売ショップから買えない。ショップへ売ることはできる。
  - 新しく借りられない。
- 期限切れでも `/loan repay` は使える。全額返すとペナルティはすぐ外れる。

## 設定と保存データ

設定は `plugins/EcoLifeAssist/loan.yml`。`/ecolife reload` で読み直す。`enabled: false` にすると借入・返済・利息・ペナルティをすべて止め、記録は残す。

借金の記録は `plugins/EcoLifeAssist/loans.yml` に、プレイヤーのUUIDごとの残高・最初に借りた時刻・最後に利息を付けた時刻として保存する。借入・返済・帳消しのたびに書き、利息と差し押さえのぶんは1分ごとの見回りと停止時に書く。このファイルが壊れて読めないときは、空の記録で上書きしないようプラグインを起動しない。

操作はサーバーログに `LOAN_BORROW`・`LOAN_REPAY`・`LOAN_INTEREST`・`LOAN_GARNISH`・`LOAN_FORGIVE` として残る。

## 連携

- お金の出し入れはVaultを使う。Vaultがないと借入・返済はできない。
- 差し押さえはEssentialsXの `UserBalanceUpdateEvent`、QuickShopの購入禁止はQuickShop-Hikariの `ShopPurchaseEvent` を使う。どちらもコンパイル時の依存は持たず、プラグインがなければその部分だけ働かない（起動時に警告を1行出す）。
- 購入画面の判定は相手プラグインのクラス名に頼る。AdminShop・AuctionHouse・ContractBoardで画面のクラス名を変えたら `blocked-inventory-holders` も揃える。

## 検証

`python3 scripts/test-loan-paper.py` は、隔離Paperに本物のEssentialsXとVaultを入れ、上限、借入、返済、2週ぶんの複利、期限切れの差し押さえ、コマンドと画面の禁止、完済後の解除、再起動後の保持を確かめる。隔離環境は `target/loan-paper-smoke`、待受は既定で `127.0.0.1:25588`（`LOAN_TEST_PORT` で変更）。

Playerはテスト用で、時間の経過は記録を15日前に書き換えて再現している。QuickShopの購入禁止、他プラグインの実際の購入画面、実クライアントでの表示はこのテストに含まれない。
