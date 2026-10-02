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
3. Android OSに停止されにくいこと
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

`MainActivity` やForeground Serviceへ処理を集中させない。

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
- Foreground Service内部処理
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
監視中

最終受信成功: 10:42:13
次回受信予定: 約5分後

[受信メール]
[ルール設定]
[アカウント設定]
[その他設定]
```

監視状態はUI独自に保持せず、監視状態モデルから取得する。

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

## 16. monitoring

監視処理の中心。

### 16.1 MonitoringService

Foreground Serviceとして動作する。

主な責務:

- 監視開始
- 監視停止
- 次回ポーリングまでの待機
- 必要最小時間のWakeLock
- 一回分の受信処理呼び出し
- 常駐通知更新
- Service状態管理

Service自身にはメール受信やルール判定の詳細を埋め込まない。

### 16.2 MailCheckCoordinator

一回分のポーリング処理全体を統括する。

概念フロー:

```text
1. アカウント取得
2. メールサーバー接続
3. 新着識別
4. 未受信メール取得
5. ヘッダー / Subject取得
6. 必要なら本文取得
7. Roomへ受信メールを保存
8. ルール判定
9. 必要なら警報処理
10. 処理状態更新
11. 設定に応じてサーバー側メール削除
12. 正常終了
```

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

## 24. receiver

Android BroadcastReceiver類を配置する。

初期候補:

```text
BootCompletedReceiver
AlarmActionReceiver
```

### 24.1 BootCompletedReceiver

端末再起動後の監視復帰を担当する。

AndroidバージョンごとのForeground Service起動制限を考慮し、Receiver自身へ複雑な監視ロジックを持たせない。

必要な場合はユーザー操作を伴う復帰フローとする。

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
MonitoringService
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
- Foreground Service
- 一定間隔ポーリング骨格
- ダミーメール受信処理による検証

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
