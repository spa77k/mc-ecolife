# Paper 26.2 ローカル互換性確認（2026-09-24）

この記録は、当日の未コミット変更を含む作業ツリーの EcoLifeAssist 1.0.0 JAR を対象とする。公開済みの `v1.0.0` 成果物や本番環境の確認結果ではない。

## 検証環境

- Docker: `itzg/minecraft-server:java25`
- Paper: `26.2` build `128`、Java 25
- 常駐確認用コンテナ: `ecolife-paper-262-smoke`、Java 版接続先 `localhost:25582`（ホスト側は `127.0.0.1` に限定）
- 隔離データ: `target/paper-26.2-docker/`、各機能テストは `target/paper-26.2-*-probe/`
- 依存: AdminShop 1.0.0、EssentialsX 2.22.0、LuckPerms 5.5.71、McLevel 1.0.0、Vault 1.7.3-b131
- 機能テストで使った EcoLifeAssist JAR の SHA-256: `448788397ea555c2f2b05906ede5bd51e6833c06c0389357af5e2e8d00012859`。後続の作業で `target/ecolifeassist-1.0.0.jar` が再生成されても、この値で検証対象を識別できる。

## 結果

- 既存の Paper 26.1.2 API 向け JAR は Paper 26.2 で有効化できた。`/ecolife reload` とプラグイン一覧も確認した。
- `JAVA_HOME=/opt/homebrew/opt/openjdk@25 mvn -B -Dpaper-api.version=26.2.build.124-stable package` は成功し、単体テスト 5 件も成功した。この再ビルド JAR も Paper 26.2 で有効化した。
- `PaperDailyAdminShopProbe`: 14日目の AdminShop 製アイテム受け取り、二重受け取り防止、商品欠落時の保留が成功した。
- `PaperInviteProbe`: 7199秒と7200秒の境界、入金、二重払い防止、IP 非制限、McLevel 停止時の保留と復旧、再起動後の記録復元が成功した。
- `PaperPosterProbe`: 一覧のページ切替、権限、地図配布、ID 再利用、再起動後の描画復元、画像取り下げ後の表示保持が成功した。

以上はテスト用 Player アダプターによる確認であり、実クライアントによる表示・操作は未確認。EssentialsX 2.22.0 は有効化されたが、Paper 26.2 に対して `You are running an unsupported server version!` と記録した。EcoLifeAssist の `pom.xml` と `plugin.yml` の対象指定は、引き続き 26.1.2 のまま。

確認用コンテナと隔離データは削除せず残している。状態は `docker ps -a --filter name=ecolife-paper-262`、ログは `docker logs ecolife-paper-262-smoke` で確認できる。
