# サーバー内イベントのDiscord通知 — 規約 v1

## 全体像
発生元プラグイン（ContractBoard / AuctionHouse / 将来のQuickShop等）が Bukkit のカスタムイベントを投げ、
EcoLifeAssist がそれを拾って Discord Webhook へ送る。通知先・文面・間引きの設定は EcoLifeAssist の config.yml に一元化する。

EcoLifeAssist は発生元プラグインを**コンパイル時依存にしない**。
`Class.forName` + `PluginManager#registerEvent` で動的に購読する。
これにより ContractBoard / AuctionHouse は EcoLifeAssist が無くても単体でビルド・起動できる。

## イベント規約（発生元プラグインが守る）
イベントクラスは以下を満たすこと。

1. `org.bukkit.event.Event` を継承し、静的 `HandlerList` と `getHandlers()` / `getHandlerList()` を持つ
2. `public String getNotifyKind()` — 通知種別の識別子。例 `"contract.created"` / `"auction.listed"`
3. `public Map<String, String> getNotifyPlaceholders()` — 文面差し込み用。少なくとも `player` を含める
4. **メインスレッド同期**で発火する（`new EventClass(...)` は非同期イベントにしない）
5. 発火位置は「DBへの保存と金銭の移動がすべて成功した後」。失敗経路では投げない
6. キャンセル可能にしない（Cancellable を実装しない）。通知は事後報告であって拒否の余地がない

プレースホルダの値は生の文字列でよい。整形・エスケープ・長さ制限は受け手（EcoLifeAssist）が行う。

## 対象イベント
| 発生元 | クラス | kind | プレースホルダ |
| --- | --- | --- | --- |
| mc-irai | `net.mcirai.contractboard.event.ContractCreatedEvent` | `contract.created` | `player` `title` `reward` `expire_hours` `min_stars` `item_delivery` |
| mc-auction | `net.mcauction.auctionhouse.event.AuctionListedEvent` | `auction.listed` | `player` `item` `amount` `start_price` `buyout_price` `duration_hours` |

`reward` / `start_price` / `buyout_price` は EconomyService#format 済みの表示文字列。
`buyout_price` は即決なしのとき空文字。`item_delivery` は `"あり"` / `"なし"`。

## EcoLifeAssist 側の要件
- 既定は `enabled: false`。Webhook URL 未設定なら黙って何もしない
- 送信は非同期。メインスレッドを絶対にブロックしない
- 送信キューは上限つき。あふれたぶんは捨て、捨てた件数をログに残す
- 間引き: 種別ごとの最短送信間隔、同一プレイヤーの連投抑制、全体の毎分上限
- **プレイヤー入力（依頼タイトル・アイテム表示名）がそのままDiscordへ出るため、必ず無害化する**
  - `§` `&` の色コードを除去
  - `@everyone` `@here` を無効化するため payload に `"allowed_mentions": {"parse": []}` を必ず入れる
  - Markdown記号（`*` `_` `` ` `` `~` `|` `>`）をエスケープ
  - 1項目あたりの長さ上限（既定80文字、超過は末尾に `…`）
- Webhook URL はログにもエラーメッセージにも出さない
- `${CFG_...}` 形式のまま残っていたら未設定として扱う（spsmc-infra の起動時置換に対応）
