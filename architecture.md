# アラートくん 内部設計書

## 1. 文書情報

- アプリ名: アラートくん
- 英名 / プロジェクト名: AlertKun
- Application ID / namespace: `direct.kabu.alertkun`
- 対象: Android
- 言語: Kotlin
- UI: Jetpack Compose
- 目的: メールをトリガーとしたAndroid用システム監視・警報アプリ

本書は「アラートくん 仕様書」に基づき、初期実装およびMVP開発で使用する内部アーキテクチャを定義する。

---

## 2. 設計上の最優先事項

内部設計では、以下を最優先する。

1. メールを取りこぼさないこと
2. 同じメールを誤って何度も警報しないこと
3. Android OS環境下で監視継続性を最優先し、24時間365日復帰可能であること
4. 監視機能自体の異常をユーザーが認識できること
5. 認証情報を安全に扱うこと
6. 長期間安定して動作すること

UIの簡便さよりも、受信処理の安定性、重複防止、障害時の復帰、警報の確実性を優先する。

---

## 3. 全体方針

以下の責務を分離する。

- UI
- 永続データ
- メール受信
- ルール判定
- 監視制御
- 警報
- セキュリティ
- OSイベント受信

`MainActivity` やWorkManagerのWorkerへ処理を集中させない。

特に、メール通信、ルール判定、通知、Room、Keystore、OSイベント処理は独立した責務として実装する。

---

## 4. パッケージ構成

ルートパッケージ:

```text
direct.kabu.alertkun
```

初期構成:

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

必要以上の細分化は避けるが、責務の混在は避ける。

---

## 5. UI層

### 5.1 方針

UIはJetpack Composeで実装する。

UI層では以下を行わない。

- POP3 / IMAP通信
- WorkManager Worker内部処理
- Roomへの直接アクセス
- Keystoreへの直接アクセス
- ルール判定本体
- 警報制御本体

UIはViewModelを介してアプリ状態を表示・更新する。

### 5.2 主な画面

初期MVPでは最低限、以下の画面を想定する。

- メイン画面
- アカウント設定
- ルール一覧
- ルール編集
- 受信メール一覧
- 受信メール詳細
- その他設定

### 5.3 メイン画面

表示候補:

```text
アラートくん
監視中（5分毎）

最終確認: 2026/10/04 19:40
次回確認予定: 2026/10/04 19:45頃

[受信メール]
[ルール設定]
[アカウント設定]
[その他設定]
```

監視状態はUI独自に保持せず、監視状態モデルから取得する。監視ON時は現在の`pollIntervalMinutes`を表示し、監視OFF時は周期を表示しない。日時は端末local timezoneの`YYYY/MM/DD HH:mm`で表示し、epoch millis・UNIX time・秒を直接表示しない。`nextScheduledCheckAtEpochMillis` は保証実行時刻ではなく目標予定時刻として「頃」を付けて表示する。

---

## 6. ViewModel

画面ごとに必要に応じてViewModelを用意する。

例:

```text
MainViewModel
AccountViewModel
RuleListViewModel
RuleEditViewModel
MailListViewModel
SettingsViewModel
```

ViewModelの責務:

- 画面状態の保持
- ユーザー操作の受け付け
- Repository呼び出し
- UI向け状態への変換

ViewModelへビジネスロジックを集中させない。

---

## 7. model

アプリ内部で共通使用するデータ型を配置する。

候補:

```text
MailAccount
AlertRule
ReceivedMail
MonitoringState
MailCheckResult
AlarmEvent
```

Room Entityとアプリ内部モデルは可能な限り分離する。

目的:

- DB構造変更の影響を局所化する
- メール受信部やRuleEngineへのDB依存を避ける
- テストを容易にする

---

## 8. data

永続データを担当する。

### 8.1 保存方式

```text
DataStore
    軽量設定

Room
    構造化データ

Android Keystore
    秘密情報
```

の三分割とする。

### 8.2 DataStore

以下のような軽量設定を保存する。

- 受信間隔
- メール保存日数
- 連続失敗通知回数
- 監視ON / OFF
- その他アプリ設定

### 8.3 Room

最低限、以下を想定する。

```text
accounts
rules
received_mails
```

UIDL / UIDについては、`received_mails` 内に保持するか、将来必要に応じて専用テーブルへ分離する。

### 8.4 パスワード

パスワードはRoomへ平文保存しない。

アカウントデータにはKeystore側の認証情報を参照するためのキーのみ保持する。

---

## 9. MailAccount

概念モデル:

```text
id
name
protocol
host
port
username
credentialKey
deleteAfterReceive
trustedCertificateId
enabled
```

無料版では1アカウントに制限するが、内部設計では最初から一意な`id`を持たせる。

将来のマルチアカウント化でDB構造を大きく作り直さないことを目的とする。

---

## 10. AlertRule

概念モデル:

```text
id
name
searchText
target
priority
soundUri
vibrationPattern
alarmMode
enabled
```

検索対象:

```text
SUBJECT
BODY
SUBJECT_AND_BODY
```

警報方式:

```text
ONCE
UNTIL_STOPPED
```

優先順位は数値で保持し、数値が小さいほど高優先とする。

同一メールが複数ルールに一致した場合、最小値の優先順位を持つルール1件のみ発火させる。

---

## 11. rules

### 11.1 RuleEngine

ルール判定専門クラスとする。

入力:

```text
件名
本文
有効なルール一覧
```

出力:

```text
一致した最高優先ルール
または一致なし
```

RuleEngineでは以下を行わない。

- 通知
- DB保存
- メール削除
- メール通信

純粋な判定ロジックとして実装し、単体テスト可能にする。

例:

```text
Subject = "ALERT WARN"

ALERT priority=1
WARN  priority=10

=> ALERTのみ採用
```

---

## 12. mail

POP3 / IMAP通信を担当する。

上位層はプロトコル差異を極力意識しない構造とする。

共通インターフェース例:

```kotlin
interface MailReceiver {
    suspend fun checkNewMessages(
        account: MailAccount
    ): MailCheckResult
}
```

実装:

```text
Pop3MailReceiver
ImapMailReceiver
```

---

## 13. POP3 / IMAP新着識別

### 13.1 POP3

POP3では`UIDL`を使用する。

### 13.2 IMAP

IMAPでは`UID`を使用する。

メッセージ番号だけを新着識別には使用しない。

UIDL / UIDは端末内へ永続保存し、アプリ再起動、Service再起動、端末再起動後も既知メールを判別可能にする。

RAMだけでの管理は禁止する。

---

## 14. メール取得方針

新着確認時は必要以上に本文を取得しない。

基本方針:

```text
新着識別
↓
Subject取得
↓
有効ルールを確認
↓
件名のみで処理可能なら本文取得を省略
↓
本文判定が必要なら本文取得
```

有効ルールに`BODY`または`SUBJECT_AND_BODY`が存在する場合、必要に応じて本文を取得する。

添付ファイルは取得・保存・表示・判定しない。

---

## 15. mail/parser

### 15.1 MailBodyParser

以下のようなメール本文から判定用テキストを生成する。

```text
text/plain
text/html
multipart/*
```

HTMLは判定可能なプレーンテキストへ変換する。

添付ファイルは無視する。

---

## 14. 監視方針とバックグラウンド実行戦略

### 14.1 24/365監視方針と常駐FGSの完全廃止

- **24時間365日の監視継続性**を最優先とする。
- **常駐 Foreground Service（24時間365日常時起動・維持する FGS）は完全廃止・禁止**とする。Android 15以降の実行時間制限（最大6時間等）およびFGS制約により、常駐方式は長期運用と両立しないためである。
- スケジューリングの主軸には `WorkManager` を使用する。
- 1回のメール確認処理（mail check）は短時間タスクとして設計する。

### 14.2 pollIntervalMinutes（目標ポーリング間隔）の意味

- 設定範囲: 1～1500分（デフォルト 5分）
- **意味**: 「目標ポーリング間隔」であり、時間精度の高い厳密な実行（Exact Execution）を保証するものではない。
- Android OSの Doze モード、省電力機能、バックグラウンド実行制限により、実際の実行時刻が遅延することを許容する。
- 時間の厳密性よりも、OS環境下で確実に生き残る監視継続性を最優先する。

### 14.3 WorkManagerの役割と制約

- スケジューリングの主軸として `WorkManager` を使用する。
- 1～14分は、unique `OneTimeWorkRequest` を用いるshort scheduleで実装する。Workerは待機せず、実行の終了時に有効なgenerationでのみ次のshort workを登録する。
- 15分以上は、unique `PeriodicWorkRequest` を用いるperiodic scheduleで実装する。14 / 15分が方式の境界である。
- 1 Workerは1回のboundedなmail checkだけを実行する。Worker内で`while (true)`、長時間の`delay()`、次回周期までの待機を行わない。
- WorkManagerの実行時刻はOS都合で遅延し得る。`pollIntervalMinutes`はmail check開始時刻間の目標周期であり、Exact Executionを保証しない。

### 14.4 AlarmManager

Phase 6の現実装では`AlarmManager`を監視スケジュールに使用しない。WorkManagerだけでshort / periodic scheduleを構成する。Exact Alarmおよび`SCHEDULE_EXACT_ALARM`は使用しない。

### 14.5 Foreground処理

常駐 Foreground Service、`foregroundServiceType="dataSync"`、常駐監視notificationは使用しない。Phase 6のmail checkは通常の`CoroutineWorker`で実行し、Expedited Work、Foreground Info、短命Serviceも使用しない。

### 14.6 WakeLock

アプリ独自のWakeLockは使用しない。WorkManagerのwake管理に委ね、長時間または常時のWakeLock保持は使用しない。

---

## 15. メール取得方針と parser

### 15.1 メール取得方針

新着確認時は必要以上に本文を取得しない。

基本方針:

```text
新着識別
↓
Subject取得
↓
有効ルールを確認
↓
件名のみで処理可能なら本文取得を省略
↓
本文判定が必要なら本文取得
```

有効ルールに`BODY`または`SUBJECT_AND_BODY`が存在する場合、必要に応じて本文を取得する。

添付ファイルは取得・保存・表示・判定しない。

### 15.2 mail/parser

以下のようなメール本文から判定用テキストを生成する。

```text
text/plain
text/html
multipart/*
```

HTMLは判定可能なプレーンテキストへ変換する。

添付ファイルは無視する。

---

## 16. monitoring モジュール構造

監視処理の中心となるモジュール構成。詳細な実行戦略やOS制約については「14. 監視方針とバックグラウンド実行戦略」を参照すること。

### 16.1 概要

14章で定義した監視方針に従い、`monitoring` パッケージのクラス群を構成する。

### 16.2 監視スケジューラー（Worker / Scheduler）

実装経路は次のとおりである。

```text
WorkManager
↓
MailCheckWorker
↓
MailCheckCoordinator
↓
MailReceiver
↓
Room
↓
RuleEngine
↓
AlarmController
```

主な責務:

- `MonitoringScheduler`: start / stop / interval変更時の再登録、起動時reconcile、時刻変更rebase、runNowのenqueue、unique work名とschedule generationの管理
- `MailCheckWorker`: 1回だけ`MailCheckCoordinator`を呼び出し、結果・監視状態を更新する実行アダプタ
- `MailCheckWorker`間のmail checkはprocess-local `executionMutex`で直列化する。scheduled WorkerはMutex取得後にDataStoreを再読込し、直前のcheck開始時刻から計算した次targetより前ならCoordinatorをskipする。
- skipしたshort Workerも、有効なgenerationなら次のshort workを再登録する。

### 16.3 MailCheckCoordinator

一回分のポーリング処理全体を統括する。Android Frameworkから独立しており、JVM単体テストが可能。Phase 6では`DummyMailReceiver`を使用し、POP3S / IMAPS実通信は未実装である。

概念フロー:

```text
1. 既存`RECEIVED`をローカルデータからresumeする
2. `MailReceiver`から新規mailを取得する
3. 必要なら本文を取得する
4. `accountId + remoteMessageId`のRoom UNIQUE制約で重複を防ぎつつ`RECEIVED`として保存する
5. RuleEngineで評価する
6. 一致時はAlarmControllerへhandoffした後に、非一致時はそのまま、`PROCESSED`へfinalizeする
```

### 16.4 MonitoringState（監視状態モデル）

アプリ全体で共有する監視状態。

プロパティ意味:

- `isMonitoring`: 監視スケジュールが有効（アクティブ）か
- `lastSuccessfulCheckAtEpochMillis`: 最後に正常完了したメール確認日時
- `nextScheduledCheckAtEpochMillis`: 次回の目標予定時刻（※14.2で定義したとおり、OSの都合で遅延する可能性がある目標値）
- `consecutiveFailureCount`: 連続失敗回数

`MonitoringStatusRepository`は上記のUI状態に加え、DataStoreへ`monitoringEnabled`、failure notification済み状態、`lastCheckStartedAtEpochMillis`、`lastCheckCompletedAtEpochMillis`、`scheduleGeneration`、schedule種別を保持する。開始・完了は成功／失敗にかかわらない実測時刻であり、成功時刻とは混同しない。

### 16.5 次回target・scheduleGeneration・runNow

- `nextScheduledCheckAtEpochMillis`は予定であり、保証実行時刻ではない。UIは端末local timezoneの`YYYY/MM/DD HH:mm`で表示し、「頃」を付ける。epoch millisや秒を直接表示しない。
- targetは実際のcheck開始時刻（rebase時は基準となる現在時刻）をlocal timeの分境界へ丸めてからintervalを加算する。秒が0～30.000なら現在分、30.001以降なら次分を基準にする。`lastCheckStartedAtEpochMillis`自体は丸めない。
- interval変更は変更時刻を新しい周期の起点とし、過去のcheck開始時刻を流用しない。
- `scheduleGeneration`はWorkManagerの非同期cancel後に旧Workerがscheduleを復活・上書きすることを防ぐ。scheduled Workerはenqueue時のgenerationを保持し、次work登録およびnext target更新前に現在generationとの一致を確認する。
- generationは監視ON/OFF、interval変更、起動時rebase、期限切れ・欠落・不整合scheduleのrebase、大きなwall clock変更で更新する。runNow、正常scheduleを維持する起動時OFF、timezone変更、60秒未満の時計補正では更新しない。
- runNowは単発の明示checkでありgenerationを変更しない。近接skipの対象外だが、実行後にscheduled Workerが近接skipされることはある。

### 16.6 起動時reconcile・時計・ネットワーク・失敗状態

- `checkImmediatelyOnAppStart`はBoolean、default ONである。`StartupReconcileGate`により、Activity再生成やonResumeではなく同一processのcold start相当で一度だけ処理する。
- 起動時ONはgenerationを更新して旧scheduleをcancelし、startup Workerをenqueueする。その実check開始時刻を新周期の起点として通常scheduleを再登録する。
- 起動時OFFは、未来のnext target、現在intervalに一致するschedule種別、対応unique Workがすべて存在する場合はscheduleを維持する。期限切れ・欠落・不整合ならmail checkせず現在時刻からrebaseする。
- `ACTION_TIME_CHANGED`では`System.currentTimeMillis()`と`SystemClock.elapsedRealtime()`のbaselineから時計補正量を求め、絶対値60秒以上のときだけrebaseする。`ACTION_TIMEZONE_CHANGED`はepoch値・generation・scheduleを変えず、UI日時の再評価だけを行う。
- 通常workに`NetworkType.CONNECTED` constraintは付けない。Worker開始後に端末の接続状態を確認し、`DEVICE_OFFLINE`と、ネットワーク利用可能後の`SERVER_UNAVAILABLE`を区別する。両方とも連続失敗へ含める。
- failure countとthreshold notification済み状態はDataStoreに永続化する。成功（新着0通を含む）でcountとnotification状態をresetし、同じ失敗episodeで通知を繰り返さない。

---

## 17. メール受信済みと処理済みの分離

本設計では、

- 受信済み
- 処理済み

を明確に分離する。

### 17.1 受信完了の定義

次の情報をRoomへ正常に保存できた時点を「受信完了」と定義する。

```text
UIDL / UID
受信日時
From
必要なヘッダー
Subject
必要な本文
processingState = RECEIVED
```

UIDL / UIDだけを先に単独保存しない。

理由:

```text
UIDL保存
↓
本文保存前にプロセス停止
```

となった場合、再起動後に「既知UIDL」と誤判定して本文未取得メールをスキップする危険があるため。

UIDL / UIDと受信データは同一のRoomトランザクション内で確定する。

### 17.2 処理状態

初期MVPでは以下の2状態とする。

```kotlin
enum class MailProcessingState {
    RECEIVED,
    PROCESSED
}
```

意味:

```text
RECEIVED
    サーバーから受信し、端末への保存が完了
    ルール判定等は未完了の可能性がある

PROCESSED
    ルール判定および必要な警報処理への引き渡しが完了
```

警報発生有無は処理状態とは別に保持する。

候補:

```text
processingState
alertTriggered
matchedRuleId
```

これにより以下を区別できる。

```text
受信済み・未処理
処理済み・警報対象外
処理済み・警報発生
```

---

## 18. クラッシュ復帰

起動時または監視サービス再開時に、

```text
processingState = RECEIVED
```

のメールが残っていないか確認する。

存在する場合は、原則としてサーバーから再取得せず、端末に保存済みのデータを用いてルール判定以降を再開する。

これにより、

```text
メール受信
↓
Room保存
↓
RECEIVED
↓
ルール判定中にプロセス停止
```

となっても、次回起動時に未処理メールを復旧できる。

### 18.1 UIDL / UIDの役割

UIDL / UIDがDBに存在する場合:

```text
サーバーから同一メールを再取得しない
```

ただし、

```text
processingState = RECEIVED
```

であれば、

```text
ローカル処理は継続する
```

とする。

つまり、

```text
受信済み != 処理済み
```

である。

---

## 19. サーバー側メール削除

削除設定が有効な場合でも、以下が完了するまでは削除しない。

```text
受信データのローカル保存
ルール判定
警報処理への引き渡し
processingState = PROCESSED
```

途中でエラーになった場合はサーバー側メールを削除しない。

---

## 20. alarm

### 20.1 AlarmController

警報処理専門クラス。

入力候補:

```text
AlarmEvent
```

内容例:

```text
ruleName
soundUri
vibrationPattern
alarmMode
mailId
```

AlarmControllerはメール通信を知らない。

### 20.2 通常通知と監視異常通知

通常のメール警報と監視異常通知は区別する。

---

## 21. 永久警報

`UNTIL_STOPPED`の場合は、Androidの制約に従い、音・バイブレーションを一定間隔で繰り返す。

通知には明示的な、

```text
[停止]
```

アクションを付ける。

停止操作はロック画面中でも可能な通知設計とする。

停止アクションは`AlarmActionReceiver`等で受信する。

---

## 22. security

### 22.1 CredentialStore

Android Keystoreを使用して認証情報を保護する。

UIやRoomからKeystoreを直接操作せず、`CredentialStore`を経由する。

### 22.2 CertificateTrustStore

自己署名証明書の明示的信頼情報を管理する。

---

## 23. 自己署名証明書

基本フロー:

```text
初回接続
↓
証明書情報取得
↓
フィンガープリント表示
↓
ユーザーが明示的に信頼
↓
信頼情報保存
↓
次回以降は一致確認
```

保存済み証明書と現在の証明書が一致しない場合は接続エラーとし、自動で信頼し直さない。

証明書検証を完全に無効化する実装は禁止する。

---

## 24. receiver / 再起動復旧

Android BroadcastReceiver類およびシステム復旧メカニズムを配置する。

初期候補:

```text
AlarmActionReceiver
BootCompletedReceiver (必要時のみ補助利用)
```

### 24.1 再起動後の復帰 (Restart Recovery)

端末再起動後の監視復帰は、`WorkManager` 自身の持つ永続スケジュール機構を優先利用する（WorkManagerはOSの内部データベースにタスク情報を維持し、端末再起動後もシステムによって自動的にスケジュールを復元する）。

`BootCompletedReceiver` は、WorkManagerの自動復元で補いきれない環境や補助的な再リトライ・トリガーが必要な場合のみ、後Phaseで最小限実装する方針とする。

---

## 25. 連続受信失敗

`ConsecutiveFailureTracker`等の責務を設ける。

成功:

```text
failureCount = 0
```

失敗:

```text
failureCount++
```

設定された回数に達した時点で監視異常通知を発生させる。

設定範囲:

```text
1～99回
```

デフォルト:

```text
3回
```

### 25.1 正常と失敗の区別

以下は正常:

```text
メールサーバーへ接続成功
新着メール0通
```

以下は失敗例:

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

---

## 26. Repository

UIがRoomやDataStoreへ直接アクセスしないようRepositoryを設ける。

候補:

```text
AccountRepository
RuleRepository
MailRepository
SettingsRepository
```

Repositoryにより、上位層は実際の保存方式を意識しない。

---

## 27. 初期依存関係

```text
UI
 ↓
ViewModel
 ↓
Repository
 ↓
Room / DataStore / Keystore
```

監視側:

```text
WorkManager (Worker) / [補助: AlarmManager]
 ↓
MailCheckCoordinator
 ├─ MailReceiver
 ├─ RuleEngine
 ├─ Repository
 └─ AlarmController
```

逆向き依存は避ける。

禁止例:

```text
Pop3MailReceiver -> MainActivity
RuleEngine -> Compose UI
Room DAO -> AlarmController
```

---

## 28. 実装フェーズ

### Phase 0
- 現在のHello Android状態をGitへ初回Commit

### Phase 1
- パッケージ構成作成
- 基本モデル作成

### Phase 2
- トップ画面
- 各設定画面
- Compose画面骨格

### Phase 3
- Room
- DataStore
- Repository

### Phase 4
- ルール作成・編集
- RuleEngine
- RuleEngine単体テスト

### Phase 5
- 通常通知
- 通知音
- バイブレーション
- 永久警報
- 停止操作

### Phase 6
- WorkManagerによるスケジューリング骨格
- 目標ポーリング（pollIntervalMinutes）管理
- MailCheckCoordinatorとダミーメール受信処理による検証

### Phase 7
- POP3S
- UIDL管理
- 受信保存

### Phase 8
- 受信状態 / 処理状態管理
- サーバー側削除
- クラッシュ復帰

### Phase 9
- IMAPS
- UID管理

### Phase 10
- 自己署名証明書対応

### Phase 11
- BOOT_COMPLETED
- Doze
- バッテリー最適化関連
- 端末再起動後復帰

### Phase 12
- JSON Export / Import

### Phase 13
- 異常系テスト
- 長時間連続動作試験
- 重複通知試験
- 取りこぼし試験

---

## 29. AI支援開発時のルール

Codex、GitHub Copilot、Gemini等のAI支援ツールへ実装を依頼する場合、本書を設計上の基準とする。

AIへ依頼する際は以下を遵守させる。

1. 本書のパッケージ構成と責務分離を維持する
2. `MainActivity`やForeground Serviceへ処理を集中させない
3. Room、DataStore、Keystoreの役割分担を崩さない
4. UIDL / UIDと受信メール保存を安全に扱う
5. `RECEIVED`と`PROCESSED`を区別する
6. 未処理の`RECEIVED`メールを復旧可能にする
7. 証明書検証無効化を行わない
8. パスワードやメール本文をログへ出力しない
9. 仕様変更を行う場合は、実装だけでなく本書または仕様書も更新する
10. 大規模変更はGitでコミット可能な単位に分割する

---

## 30. 初期MVPで特に重視するテスト

最低限、以下を重点的に確認する。

- 同一UIDL / UIDを二重受信しない
- `RECEIVED`状態から再開できる
- 受信保存直後の強制終了から復帰できる
- ルール判定途中の強制終了から復帰できる
- 警報対象外メールが誤って鳴らない
- 複数ルール一致時に最高優先ルールのみ鳴る
- メール0通を受信失敗扱いしない
- 接続失敗回数が正常受信で0へ戻る
- サーバー削除設定時も未処理メールを削除しない
- 端末再起動後に監視を復帰できる
- 画面OFF / Doze環境で長時間監視できる
- 自己署名証明書変更時に自動信頼しない

---

## 31. 変更管理

本設計書は初期アーキテクチャの基準とする。

設計変更が必要になった場合は、

```text
仕様
↓
アーキテクチャ
↓
実装
```

の整合性を維持する。

実装だけを先行して設計書と乖離させない。

---

以上。
