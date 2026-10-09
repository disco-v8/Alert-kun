# AlertKun

**アラートくん**は、メールアカウントを定期確認し、件名や本文がルールに一致したときに、Android端末の音・振動・通知で知らせるメール監視型アラートアプリです。システムの異常通知など、メールで届く情報を端末上で確認する用途を想定しています。

## Features

- POP3 / POP3S / IMAP4 / IMAPSによる受信と接続テスト
- 件名・本文の部分一致ルール、優先順位による1回の確認全体での警報選定
- 1回・3回・5回・停止まで継続の警報、アラーム・通知・着信カテゴリの音選択
- 振動パターンと設定時のプレビュー
- 受信メール一覧と、各メールに保存されるルールの背景色
- AccountごとのPOP3 / POP3Sサーバー削除ポリシー
- FCMによる主監視、WorkManagerによるバックアップ確認、連続失敗時の通知
- 回線切替時の短時間の復旧待ち、英語・日本語UI
- FCM主監視の「次回確認」の目安と、実メール確認が正常完了した「最終確認」の表示
- アカウントごとのTLS証明書検証4モード（標準は厳密検証）
- 過去24時間の監視が正常な場合に1日1回知らせる、静かな監視ステータス通知
- Android Keystoreを利用したパスワード保護

現在は開発中です。主要機能は実装されていますが、公開releaseの配布フローは未整備で、ライセンスも未選定です。既知の制約は[user-guide.md](user-guide.md)と[architecture.md](architecture.md)に記載しています。

## Requirements

- Android 7.0（API 24）以上
- メールサーバーへのネットワーク接続と、対応プロトコルで利用できるアカウント
- 通知の許可。「停止まで継続」の自動開始には、対応Androidで「アラームとリマインダー」の許可も必要

開発時はAndroid Studio、Android SDK 37、Gradle wrapperに対応するGradle JDKを使用します。compileSdk / targetSdkは37です。

## Build

Android Studioでリポジトリを開き、SDKと依存関係を準備します。Windowsではリポジトリのルートから実行できます。

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat lint
```

Debug APKはapp/build/outputs/apk/debug/app-debug.apkに生成されます。端末・エミュレータでのテストは、接続後に .\gradlew.bat connectedAndroidTest を使用します。サーバー設定やパスワードはビルドに不要です。

## 利用開始

FCM Gateway端末登録には`app/google-services.json`をローカルに配置し、端末の設定画面でMyDNS.JP MasterIDとMasterPwdを保存します。初回はHTTPSでGatewayへ一次認証し、発行されたdeviceTokenをAndroid Keystoreで暗号化して保存します。以後はdeviceTokenのBearer認証を使用し、Bearer失効時だけMyDNS資格情報で再認証します。

メールアカウントの接続テストと保存、ルール設定を行い、通知・継続警報に必要な権限を許可して監視を有効にします。FCMが設定間隔の主監視となり、WorkManagerは設定間隔と15分の大きい方を周期とするバックアップです。MasterID未設定でもバックアップ確認は継続します。詳しい操作は[user-guide.md](user-guide.md)を参照してください。

監視中のFCM受信では専用の「異常なし」通知を1件更新します。音を避けたい場合はAndroidの通知設定で「監視状態」をサイレントにできます。警報・監視失敗・1日1回の通知とは別チャンネルです。

メール確認でルールに一致すると、この同じ監視状態通知にも採用された警報内容を表示します。次の正常確認で一致がなければ「異常なし」に戻ります。

## Documentation

| 文書 | 読む目的 |
| --- | --- |
| [user-guide.md](user-guide.md) | 初期設定、日常操作、警報・削除設定、困ったときの確認 |
| [architecture.md](architecture.md) | 現在の内部構造、永続化、受信・監視・警報の動作仕様 |
| [AGENTS.md](AGENTS.md) | AI・開発作業者が守る設計原則と変更時の注意 |

## 注意事項

Androidのバックグラウンド制限により確認時刻は遅れることがあります。端末音量やDND設定をアプリが強制変更することはありません。有限回数の警報はプロセス終了時に完遂できない場合があります。重要な監視では実際の端末・サーバー環境で動作を確認してください。

サーバー削除はPOP3 / POP3Sだけに対応し、初期値は「削除しない」です。有効にする前に利用ガイドを読み、削除専用のテストメールで確認してください。TLS検証は「厳密に検証する」「ホスト名の不一致を許可する」「自己署名証明書を許可する」「検証しない」の4モードです。自己署名でも期限・ホスト名を検証し、fingerprintは使用しません。「検証しない」は明示選択時だけ適用し、自動fallbackは行いません。IMAP削除、JSON Export / Import、保存期限による受信メールの自動削除は現在未対応です。

## License

License selection pending. 現在LICENSEファイルはありません。公開・再配布時の条件は未確定です。
