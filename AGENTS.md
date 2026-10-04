# Repository Guidelines

## 1. この文書の目的

この `AGENTS.md` は、Codexその他のAI開発支援ツールが「アラートくん（AlertKun）」を実装・修正する際に守るべきリポジトリ規約、設計規約、信頼性・セキュリティ上の原則を定める。

本プロジェクトでは、単にビルドが通ることよりも、メールの取りこぼし防止、重複警報防止、Android上での長期安定動作、障害からの復旧、安全な認証情報管理を優先する。

実装前に必ず `architecture.md` を確認すること。

---

## 2. プロジェクト概要

本プロジェクトは、単一 `app/` モジュールで構成するAndroidアプリケーションである。

- アプリ名: アラートくん
- プロジェクト名: AlertKun
- 言語: Kotlin
- UI: Jetpack Compose
- namespace / Application ID: `direct.kabu.alertkun`
- 主目的: メールをトリガーとしたAndroid用システム監視・警報

本アプリは一般的なメールクライアントではない。POP3(S) / IMAP4(S)でメールを定期確認し、設定したルールに一致したメールを検出して、端末上で音・バイブレーション等による警報を行うことを主目的とする。

---

## 3. 設計文書の優先順位

内部実装の基準は `architecture.md` とする。

実装または変更を行う前に、該当する `architecture.md` の項目を読むこと。

依頼された実装が `architecture.md` と矛盾するように見える場合、AIの判断だけで設計を変更してはならない。

その場合は、

1. どの記述と矛盾するか
2. なぜ矛盾すると考えるか
3. 変更が必要なら何を変更する必要があるか

をユーザーへ示し、確認を得てから設計変更を行うこと。

実装を簡単にするためだけに、仕様やアーキテクチャを勝手に変更してはならない。

---

## 4. プロジェクト構成

本番コードは以下に置く。

```text
app/src/main/java/direct/kabu/alertkun
```

基本パッケージ構成:

```text
direct.kabu.alertkun

├── ui
│   ├── main
│   ├── account
│   ├── rule
│   ├── mail
│   ├── settings
│   └── theme
│
├── data
│   ├── database
│   ├── entity
│   ├── dao
│   ├── repository
│   └── preferences
│
├── mail
│   ├── common
│   ├── pop3
│   ├── imap
│   └── parser
│
├── rules
├── monitoring
├── alarm
├── security
├── receiver
└── model
```

AndroidリソースとManifest:

```text
app/src/main/res/
app/src/main/AndroidManifest.xml
```

ローカルJVM単体テスト:

```text
app/src/test/
```

端末・エミュレータ用テスト:

```text
app/src/androidTest/
```

テストコードは可能な限り本番コードのパッケージ構成に対応させる。

ビルド設定:

```text
build.gradle.kts
app/build.gradle.kts
gradle/libs.versions.toml
```

---

## 5. 責務分離

`architecture.md` に従い、以下の責務を分離する。

### UI

Compose UIは以下を直接行わない。

- POP3 / IMAP通信
- Roomへの直接アクセス
- Android Keystoreへの直接アクセス
- ルール判定本体
- Foreground Service内部処理
- 警報制御本体

### ViewModel

ViewModelは画面状態とユーザー操作を調整し、Repository等を呼び出す。

ViewModelへメール通信、永続化、警報処理などのビジネスロジックを集中させない。

### mail/

POP3 / IMAP通信およびメール解析を担当する。

### rules/

ルール判定を担当する。

ルール判定本体は `RuleEngine` に置く。

### monitoring/

WorkManagerによる監視スケジューリングと一回分の受信処理の統括等を担当する。

### alarm/

通知音、バイブレーション、繰り返し警報、停止処理等を担当する。

### security/

認証情報および自己署名証明書の信頼情報を扱う。

### data/

Room、DataStore、Repository等による永続化を担当する。

---

## 6. データ保存方式

用途ごとに保存方式を分離する。

```text
Room
    構造化された永続データ

DataStore
    軽量なアプリ設定

Android Keystore
    パスワード等の秘密情報
```

パスワードをRoom、DataStore、JSON設定ファイル等へ平文保存してはならない。

Room Entityとアプリ内部のdomain modelは、可能な限り分離する。

---

## 7. メール受信と処理の絶対条件

メールの「受信済み」と「処理済み」は別の状態として扱う。

初期実装では以下の2状態を使用する。

```text
RECEIVED
PROCESSED
```

### RECEIVED

サーバーから必要なデータを取得し、後続処理に必要なローカルデータの永続保存まで正常に完了した状態。

### PROCESSED

ルール判定、および必要な警報処理への引き渡しまで完了した状態。

次のような情報を別フィールドで保持できる設計とする。

```text
processingState
alertTriggered
matchedRuleId
```

これにより、

```text
受信済み・未処理
処理済み・警報対象外
処理済み・警報発生
```

を区別できるようにする。

---

## 8. UIDL / UIDの扱い

POP3では、永続的なリモートメッセージ識別子として `UIDL` を使用する。

IMAPでは `UID` を使用する。

POP3のメッセージ番号や、セッション内だけで意味を持つ番号を永続的なメッセージ識別子として使用してはならない。

UIDL / UIDは端末内へ永続保存し、アプリ再起動、Service再起動、端末再起動後も受信済みメールを判別できるようにする。

---

## 9. UIDL / UIDとローカルメール保存の原子性

UIDL / UIDだけを先に保存してはならない。

禁止例:

```text
UIDL保存
↓
プロセス停止
↓
本文等は未保存
```

この状態になると、次回起動時にUIDLだけを見て「受信済み」と判断し、必要なメールデータを永久に取得できなくなる危険がある。

したがって、

```text
UIDL / UID
受信日時
From
必要なヘッダー
Subject
必要な本文
processingState = RECEIVED
```

など、そのメールを後からローカル処理するために必要なデータは、同一のRoomトランザクション内で確定させる。

「UIDL / UIDが保存されている」という状態は、「そのメールについて必要なローカル受信データの保存が完了している」ことを意味しなければならない。

---

## 10. 必要最小限のメール取得

メールデータを無条件ですべてダウンロードしない。

基本方針:

```text
新着識別
↓
ヘッダー / Subject取得
↓
有効ルールを確認
↓
件名だけで判定可能なら本文取得を省略可能
↓
本文判定が必要なら本文取得
↓
必要なローカルデータとUIDL / UIDを保存
```

有効ルールに本文判定が必要なものが存在する場合は、後続のルール判定に必要な本文を取得してから `RECEIVED` とする。

本文を取得しなかった場合は、その事実を表現できるデータ設計とする。

添付ファイルは仕様変更がない限り、

- ダウンロードしない
- 保存しない
- 表示しない
- ルール判定対象にしない

---

## 11. クラッシュ・プロセス停止からの復旧

アプリ起動時または監視Service再開時に、

```text
processingState = RECEIVED
```

のメールが残っていないか確認する。

存在する場合、そのメールについては原則としてサーバーから再ダウンロードせず、保存済みローカルデータを使用してルール判定以降を再開する。

例えば、

```text
サーバーからメール取得
↓
Room保存完了
↓
RECEIVED
↓
ルール判定中にプロセス停止
```

となっても、次回起動時にローカル処理を再開できなければならない。

UIDL / UIDがDBに存在する場合、そのサーバーメールはすでに受信済みである。

ただし、

```text
processingState = RECEIVED
```

ならローカル処理は未完了なので、処理を継続する。

つまり、

```text
受信済み != 処理済み
```

である。

---

## 12. サーバー側メール削除

「受信後サーバーから削除」が有効でも、受信直後に削除してはならない。

最低でも以下が完了してから削除する。

```text
ローカル保存
↓
ルール判定
↓
必要な警報処理への引き渡し
↓
processingState = PROCESSED
↓
サーバー側削除
```

途中でエラーになった場合はサーバー側メールを削除しない。

サーバー側削除に失敗したことを理由として、すでにローカルで `PROCESSED` になったメールを再警報してはならない。

---

## 13. RuleEngineの規約

ルール判定は `rules/RuleEngine` に集約する。

RuleEngineの入力は概念的に、

```text
Subject
本文
有効なルール一覧
```

とし、出力は、

```text
一致した最高優先ルール
または
一致なし
```

とする。

優先順位は数値で表し、

```text
数値が小さいほど優先順位が高い
```

とする。

同一メールが複数の有効ルールに一致した場合、最も優先順位の高い1ルールだけを採用する。

RuleEngineでは以下を行わない。

- Android UI操作
- POP3 / IMAP通信
- Roomアクセス
- 通知発行
- 音・バイブレーション制御
- サーバー側メール削除

RuleEngineはAndroid frameworkから可能な限り独立させ、ローカルJVM単体テスト可能な純粋ロジックとする。

---

## 14. 監視処理とバックグラウンド実行ルール

Phase 6の監視は`WorkManager -> MailCheckWorker -> MailCheckCoordinator -> MailReceiver -> Room -> RuleEngine -> AlarmController`で実装する。常駐Foreground Service、`foregroundServiceType="dataSync"`、常駐通知、`while (true)` / 長時間`delay()`のpolling loop、アプリ独自の長時間WakeLockは使用しない。

- 1 Workerは1回のboundedなmail checkだけを実行する。WorkerへRoom・RuleEngine・Alarm処理の詳細を詰め込まず、`MailCheckCoordinator`へ委譲する。
- `pollIntervalMinutes`は1～1500分、default 5分の「check開始時刻間の目標周期」であり、正確な実行時刻を保証しない。
- 1～14分はunique `OneTimeWorkRequest`の自己再登録、15分以上はunique `PeriodicWorkRequest`を使用する。14 / 15分が境界である。AlarmManager、Expedited Work、Foreground InfoはPhase 6で使用しない。
- `scheduleGeneration`は、ON/OFF、interval変更、起動時または不整合scheduleのrebase、大きな時計変更で更新する。scheduled Workerは自身のgenerationが現在値と一致するときだけ次work登録とnext target更新を行う。runNow、正常scheduleを維持する起動時OFF、timezone変更、60秒未満の時計補正はgenerationを更新しない。
- mail checkはprocess-local `executionMutex`で直列化する。scheduled WorkerはMutex取得後にDataStoreを再読込し、直前のcheckから計算したtargetより前ならskipする。runNowはskipしない。skipしても有効なshort scheduleを失わせない。
- targetは実check開始時刻（rebase時は現在時刻）をlocal timeの分境界に丸め、intervalを加算する。秒0～30.000は現在分、30.001以降は次分を基準とし、実測の`lastCheckStartedAtEpochMillis`自体は丸めない。interval変更では変更時刻を基準にする。
- `checkImmediatelyOnAppStart`はdefault ON。`StartupReconcileGate`により同一processで一度だけcold-start相当の処理を行う。ONはstartup checkとschedule rebase、OFFは正常なfuture scheduleを維持し、期限切れ・欠落・不整合のみcheckなしでrebaseする。
- `ACTION_TIME_CHANGED`はwall clockと`elapsedRealtime`から補正量を求め、絶対値60秒以上だけrebaseする。`ACTION_TIMEZONE_CHANGED`はscheduleやgenerationを変えず、日時表示だけ再評価する。
- 通常workへ`NetworkType.CONNECTED` constraintを付けない。Worker内で`DEVICE_OFFLINE`と`SERVER_UNAVAILABLE`を区別し、どちらも永続的な連続失敗countへ含める。成功（新着0通を含む）でcountとfailure notification状態をresetする。
- `MonitoringStatusRepository`はDataStoreへ監視有効状態、failure状態、last successful / started / completed check、next target、schedule generationを永続化する。UIのnext targetは端末local timezoneで`YYYY/MM/DD HH:mm頃`と表示し、保証時刻として扱わない。

`MailCheckCoordinator`はまず`RECEIVED`をresumeし、new mailはRoom UNIQUE制約で重複防止して`RECEIVED`保存する。RuleEngine評価とAlarmControllerへのhandoff完了後に`PROCESSED`へ進める。この意味論を変更してはならない。Phase 6は`DummyMailReceiver`のみであり、POP3S / IMAPS実通信は未実装である。

---

## 15. 受信間隔と接続失敗

受信間隔:

```text
1～1500分
デフォルト 5分
```

接続タイムアウト:

```text
30秒
```

連続失敗回数設定:

```text
1～99回
デフォルト 3回
```

正常なメールサーバー確認が1回成功したら、連続失敗回数を0へ戻す。

以下は成功である。

```text
サーバー接続成功
受信処理正常終了
新着メール0通
```

「新着0通」を監視失敗として扱ってはならない。

失敗例:

```text
DNS解決失敗
TCP接続失敗
接続タイムアウト
TLS接続失敗
証明書不一致
認証失敗
POP3 / IMAPプロトコルエラー
その他、受信処理を正常完了できないエラー
```

設定された連続失敗回数へ達した場合、ユーザーが監視異常を認識できる通知を行う。

---

## 16. 警報処理

警報処理は `alarm/` に分離する。

`AlarmController` 等は、ルール判定済みの `AlarmEvent` を受け取って、

- 通知
- 音
- バイブレーション
- 一回のみ
- 明示的停止まで繰り返し

等を処理する。

AlarmControllerへPOP3 / IMAP処理を持ち込まない。

繰り返し警報は通知上の明示的な「停止」操作で停止可能にする。

---

## 17. 自己署名証明書

証明書検証を全面的に無効化してはならない。

禁止:

```text
TrustAll
acceptAllCertificates
HostnameVerifier { _, _ -> true }
```

など、すべての証明書を無条件に受け入れる実装。

自己署名証明書を利用する場合は、

```text
初回接続
↓
証明書情報取得
↓
フィンガープリント表示
↓
ユーザーによる明示的な信頼
↓
信頼情報保存
↓
次回以降は証明書比較
```

という流れを使用する。

保存済み証明書と現在の証明書が異なる場合、自動的に新しい証明書を信頼してはならない。

---

## 18. 認証情報とログ

絶対にログへ出してはならないもの:

- パスワード
- 認証トークン
- 秘密鍵
- 完全なメール本文
- その他の認証秘密情報

パスワードを平文で永続保存してはならない。

デバッグを容易にするためという理由で、秘密情報の保護を弱めてはならない。

---

## 19. Kotlinコーディング規約

KotlinはAndroid Studio標準のフォーマットに従う。

インデントは4スペース。

基本命名:

```text
Class / data class / enum / Composable
    PascalCase

function / property
    camelCase

Android resource
    lowercase_with_underscores
```

新しい本番コードのpackageは `direct.kabu.alertkun` 配下とする。

既存のスターターテスト等に古いpackage名が残っている場合、そのテストを変更する機会に正しいpackageへ移行する。

Composableは必要以上に巨大化させず、状態と責務を明確にする。

### Localization

- ユーザー向け文字列をKotlin / Composeコードへ直接ハードコードしない。画面、通知、ダイアログ、validation、ボタン、ラベル、placeholder、accessibility用文言も対象とする。
- 基準言語はEnglishの `res/values/strings.xml` とし、日本語は `res/values-ja/strings.xml` に定義する。新しい表示文言は両方へ同時に追加し、Englishをfallback言語として扱う。
- format引数を含む文言は可能な限りformat string resourceを使う。
- `enum.name` や内部識別子を表示名に使わない。enumのユーザー向け表示名はstring resourceから取得する。
- class名、package名、DB識別子、notification channel id、Intent action、route、UUID、testTag、ログ、内部例外メッセージは原則翻訳しない。
- UI操作テストでは、翻訳確認が目的でない限り表示文字列をセレクタに使わない。必要に応じて `rule_edit_<ruleId>` や `alarm_test_once` のような安定した言語非依存testTagを使う。

---

## 20. テスト規約

ローカル単体テストにはJUnit 4を使用する。

Android framework、resource、Compose UI等が必要なテストにはAndroidX JUnit、Espresso、Compose test API等を使用する。

テストファイル名は対象クラスに対応させる。

例:

```text
RuleEngine.kt
RuleEngineTest.kt
```

Android依存のないロジックは、可能な限り `app/src/test/` で高速にテストできる設計とする。

特に以下は重点的にテストする。

- UIDL / UIDによる重複防止
- `RECEIVED`からの処理再開
- ルール優先順位
- 複数ルール一致時の単一選択
- 警報対象外メール
- 新着0通を成功として扱うこと
- 連続失敗カウンタのリセット
- サーバー削除前の処理条件

---

## 21. ビルド・テストコマンド

Windows上で、リポジトリルートから実行する。

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat connectedAndroidTest
.\gradlew.bat lint
```

用途:

```text
assembleDebug
    Debug APKをビルド

test
    ローカルJVM単体テスト

connectedAndroidTest
    接続端末またはエミュレータ上のテスト

lint
    Android Lint
```

エミュレータ実行、実機実行、Compose Preview等はAndroid Studioを使用してよい。

---

## 22. 生成物とGit管理

以下の生成物やローカル環境固有ファイルをコミットしない。

```text
build/
.gradle/
local.properties
```

既存の `.gitignore` を尊重する。

Git管理対象へ新しい秘密情報を追加してはならない。

---

## 23. Commit規約

Commitは小さく、レビュー可能な単位にする。

短く内容の分かるメッセージを使用する。

例:

```text
Initial Android project setup
Add initial architecture document
Add Codex repository guidelines
Implement initial domain models
```

無関係な変更を1つのCommitへ混在させない。

大規模な自動リファクタリングを、機能変更と同一Commitに混ぜない。

---

## 24. Pull Request規約

Pull Requestを使用する場合は、少なくとも以下を説明する。

- 何を変更したか
- ユーザーから見える影響
- アーキテクチャ上の影響
- 実行したテスト
- 関連Issue等があればその参照

UI変更の場合は、必要に応じてスクリーンショットまたは画面記録を添付する。

以下を変更した場合は明示する。

- Android permission
- Foreground / background service
- 永続ストレージ
- 認証情報
- 証明書処理
- メール削除動作
- UIDL / UID管理
- 警報動作

---

## 25. AIによる変更時の作業手順

Codexその他のAI開発支援ツールは、実装前に次の順序で作業する。

1. ユーザーの依頼内容を確認する
2. `architecture.md` の関連箇所を読む
3. 既存コードを確認する
4. 変更範囲を必要最小限にする
5. 実装する
6. 適切なテストを追加または更新する
7. 関連するGradle build / test / lintを実行する
8. 変更内容と検証結果をユーザーへ報告する

報告には可能な限り、

```text
変更したファイル
実装した内容
実行したテスト
テスト結果
未解決事項
```

を含める。

---

## 26. AIが勝手に行ってはならないこと

明示的な依頼または設計変更なしに、以下を行ってはならない。

- 仕様変更
- `architecture.md` の基本方針変更
- 常駐 `dataSync` Foreground Service の導入・維持
- OS制限やFGS時間制限を無視した24/365常駐ポーリングループの作成
- Exact Alarm（`SCHEDULE_EXACT_ALARM`）の安易な利用
- メール受信意味論の変更
- UIDL / UID管理方式の変更
- `RECEIVED` / `PROCESSED`状態モデルの削除・変更
- 認証情報保護の弱体化
- TLS証明書検証の無効化
- サーバー側メール削除条件の緩和
- 添付ファイル対応の追加
- 不要な外部ライブラリの追加
- 将来使うかもしれないという理由だけの抽象化
- 要求されていない大規模リファクタリング
- package構造の全面変更
- ユーザー確認なしの破壊的DB migration

仕様が曖昧で、その選択によって、

- メール取りこぼし
- 重複警報
- セキュリティ
- 永続データ
- サーバー側メール削除
- Android上での監視継続性

へ影響する可能性がある場合は、推測で決定せずユーザーへ確認する。

---

## 27. 不要な依存関係を増やさない

新しい外部ライブラリを追加する前に、

1. Android / Kotlin標準機能で実現できないか
2. 既存依存関係で実現できないか
3. その依存が本当に現在の実装に必要か

を確認する。

「将来使うかもしれない」という理由だけで依存関係を追加しない。

依存を追加する場合は、その目的を変更報告に含める。

---

## 28. 初期MVPの信頼性優先順位

設計・実装上の優先順位は以下とする。

1. メールを取りこぼさない
2. 同じメールで重複警報しない
3. Android OSによる停止から復帰しやすくする
4. 監視機能自体の異常をユーザーが認識できる
5. 認証情報を安全に扱う
6. 長期間安定して動作する

コード量の少なさ、実装速度、見た目の簡潔さ等とこれらが衝突する場合は、原則として上記の信頼性要件を優先する。

---

## 29. 初期開発フェーズ

`architecture.md` に定義されたフェーズに従って段階的に実装する。

現在の基本方針:

```text
Phase 0
    Androidプロジェクト初期化・Git管理

Phase 1
    パッケージ構成・基本モデル

Phase 2
    Compose画面骨格

Phase 3
    Room / DataStore / Repository

Phase 4
    ルールUI / RuleEngine / 単体テスト

Phase 5
    通知 / 音 / バイブレーション / 繰り返し警報

Phase 6
    WorkManager / MailCheckWorker / DummyMailReceiver による監視骨格

Phase 7
    POP3S

Phase 8
    UIDL / 受信状態 / 処理状態 / サーバー削除

Phase 9
    IMAPS / UID

Phase 10
    自己署名証明書

Phase 11
    BOOT_COMPLETED / Doze / バッテリー関連

Phase 12
    JSON Export / Import

Phase 13
    異常系・長時間動作試験
```

後のPhaseの都合を理由に、現在のPhaseへ大量の未使用コードを先行投入しない。

---

## 30. 最終原則

アラートくんは「メールが来たら鳴るだけ」のアプリではなく、長期間動作する監視・警報システムである。

したがって、実装判断では常に、

```text
この変更でメールを取りこぼさないか
この変更で同じメールを二重警報しないか
途中で停止しても復旧できるか
監視が壊れたことをユーザーが認識できるか
秘密情報を安全に扱えているか
長期間動かして問題ないか
```

を確認すること。

不明な場合は、動くコードを推測で追加するより、設計上の確認を優先する。
