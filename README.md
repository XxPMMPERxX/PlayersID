# PlayersID

プレイヤーの整数ID、XUID・ゲーマータグ、Discord IDの対応を管理する。ログイン時にIDと名前を保存し、成功後にプレイヤーのNBTへIDを設定する。成長・職業・HPなどのゲームルールは各機能が管理する。

## 依存・導入

Java 21、DBConnectorN 1.6.0以降、Exposed 1.0.0を使用する。`plugin.yml`の`depend`へPlayersIDを指定する。

```kotlin
compileOnly("jp.asteria:players-id:1.4.0")
```

`./gradlew shadowJar`で生成した`build/libs/`の`-all.jar`を配置し、依存プラグインと一緒に再起動する。

## IDの取得

`jp.asteria.player.primaryId`をimportすると、`IPlayer.primaryId`で整数ID（未設定時はnull）を取得できる。`discordId`は未設定なら空文字を返す。ログイン処理はサーバー主スレッドかつDBトランザクションの外で行う。

## プレイヤーデータの保存境界

`jp.asteria.player.infrastructure.PlayerTransactions.write(playerId, protectedTables) { ... }`は、現在のExposedトランザクションの同じ接続で、既存の`players`行をロックする。外側のトランザクションがなければ作成し、外側があれば参加する。未知のIDを拒否する。ロックは最外の保存または取消まで保持する。MySQL・SQLiteに対応する。

複数IDは、最初に`write(playerIds, protectedTables) { ... }`でまとめて渡す。重複を除いて昇順に取得する。別々の呼び出しでIDを追加した場合の取得順は保証しない。同じ外側トランザクションで再度呼んでも、未保存の自分のSQL更新は残る。SQL競合の例外は外側まで伝え、トランザクション全体を再試行する。内側だけを再試行しない。

ブロック内は各機能のSQL保存サービスを呼び、ロック取得後の現在値から計算する。取得済みのDAO・古いスナップショットから更新値を作らない。`protectedTables`にはSQL保存へ移行した担当テーブルを渡す。同じトランザクション・親・後から作る子で、対象テーブルへのORMの挿入・更新・削除を拒否する。SQL保存とDAO更新を混在させず、直接SQLでの変更も担当サービスへ集約する。

ブロックはSQL再試行で再実行され得るため、実体の変更、メッセージ、インベントリ、外部通信を含めない。結果にはIDと不変の値を返し、DAO・接続・ResultSetを保持しない。エラーは外側まで伝えて全体を取り消す。禁止したORM書き込み、root取得SQLまたは更新ブロックの失敗が発生した後は、例外を捕捉しても保存を拒否する。子のセーブポイントを取り消しても、その更新の失敗後は親全体を取り消す。SQL再試行では最外トランザクション全体を新しい試行として実行する。DBConnectorNのrawトランザクションには参加せず、呼び出しを拒否する。

この境界を使用しない保存処理や別DBへの変更は直列化しない。各機能は自身の更新・初期行作成・保存・現在値取得を同じ境界へ揃える必要がある。

このAPIはPlayersIDの有効化後に使う。停止後の新規呼び出しは拒否し、既に進行中のトランザクションの保護は維持する。ORM監査は依存側のDB処理開始前に登録するため、導入・更新はサーバー全体の再起動で行う。稼働中のプラグイン単独再ロードには対応しない。

## 保存テーブル

`players`の`id`が整数の主キー。`xuid`とnullableな`discord_id`は一意。`gamertag`、`created_at`、`updated_at`も保存する。既存のテーブル名・列・データを保持し、ロック専用のテーブルを追加しない。

## 新規IDの作成記録

新しく作るIDは、`player_identity_creations_v1`とDBConnectorNの操作結果をIDのINSERTと同じSQLトランザクションで保存する。`PlayerCreations.findInCurrentTransaction(playerId)`で、作成元・要求hash・確定結果を照合した不変値を取得できる。外側のExposed保存境界から呼ぶ。既存IDには記録を補完しない。nullは新規扱いや移行許可を意味せず、各機能の初期化・移行の判断には別途検証が必要。破損記録は例外として保留する。
