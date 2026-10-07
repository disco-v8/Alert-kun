# AlertKun

**アラートくん**は、メールアカウントを定期確認し、件名や本文がルールに一致したときに、Android端末の音・振動・通知で知らせるメール監視型アラートアプリです。システムの異常通知など、メールで届く情報を端末上で確認する用途を想定しています。

## Features

- POP3 / POP3S / IMAP4 / IMAPSによる受信と接続テスト
- 件名・本文の部分一致ルール、優先順位による1回の確認全体での警報選定
- 1回・3回・5回・停止まで継続の警報、アラーム・通知・着信カテゴリの音選択
- 振動パターンと設定時のプレビュー
- 受信メール一覧と、各メールに保存されるルールの背景色
- AccountごとのPOP3 / POP3Sサーバー削除ポリシー
- バックグラウンド監視、連続失敗時の通知、英語・日本語UI
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

## Documentation

| 文書 | 読む目的 |
| --- | --- |
| [user-guide.md](user-guide.md) | 初期設定、日常操作、警報・削除設定、困ったときの確認 |
| [architecture.md](architecture.md) | 現在の内部構造、永続化、受信・監視・警報の動作仕様 |
| [AGENTS.md](AGENTS.md) | AI・開発作業者が守る設計原則と変更時の注意 |

## 注意事項

Androidのバックグラウンド制限により確認時刻は遅れることがあります。端末音量やDND設定をアプリが強制変更することはありません。有限回数の警報はプロセス終了時に完遂できない場合があります。重要な監視では実際の端末・サーバー環境で動作を確認してください。

サーバー削除は初期値「削除しない」です。有効にする前に利用ガイドを読み、削除専用のテストメールで確認してください。IMAP削除、自己署名証明書の信頼登録、JSON Export / Import、保存期限による受信メールの自動削除は現在未対応です。

## License

License selection pending. 現在LICENSEファイルはありません。公開・再配布時の条件は未確定です。
