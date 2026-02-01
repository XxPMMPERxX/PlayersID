# PlayersID
## 概要
DB上のプレイヤーのプライマリーキーをintにするプラグインです  
XUID/GUIDでは遅いため  
ついでにdiscordユーザーとの紐づけ用のカラムも用意

## テーブル定義
### players
| 物理名 | 論理名 | INDEX/制約 | NULL | 型 | 説明 |
| :--- | :--- | :---: | :---: | :---: |:--- |
| id | プレイヤーID | PK | | UNSIGNED INT | プレイヤー毎のユニークなID |
| xuid | プレイヤーXUID | UNIQUE | | VARCHAR(20) | マインクラフト側のユニークなID |
| discord_id | ディスコードID | UNIQUE | 〇 | VARCHAR(255) | ディスコードのユーザーID |
| gamertag | ゲーマータグ |  | | VARCHAR(255) | ユーザー名 |
| created_at | 作成日 | |  | DATETIME | |
| updated_at | 更新日 | |  | DATETIME | |

## 使い方
### build.gradle.kts
```gradle
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/xxpmmperxx/PlayersID")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")

        }
    }
}

dependencies {
    compileOnly("jp.asteria:players-id:1.0.1")
}
```

### plugin.yml
dependを書いてください
```
name: SamplePlugin
version: 1.0.0
～～～～（略）～～～～
depend:
  - PlayersID
```

### ソースコード
```kotlin
@EventHandler
fun onJoin(event: PlayerJoinEvent) {
    val player = event.player
    player.primaryId // プロパティで拾えます

    val offlinePlayer = server.getOfflinePlayer("deceitya")
    offlinePlayer.primaryId
}
```
