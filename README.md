# Alert-kun

**アラートくん**は、メールアカウントを定期確認し、件名・本文・送信元がルールに一致したときに、Android端末の音・振動・通知で知らせるメール監視型アラートアプリです。システムの異常通知など、メールで届く情報を端末上で確認する用途を想定しています。

## Features

- POP3 / POP3S / IMAP4 / IMAPSによる受信と接続テスト
- 標準準拠のメールサーバーを前提に、1通16MiBまで受信し、本文の先頭16KiBを保存・ルール判定（添付は対象外）
- 件名・本文・送信元の部分一致ルール、優先順位による1回の確認全体での警報選定
- 1回・3回・5回・停止まで継続の警報、アラーム・通知・着信カテゴリの音選択
- 振動パターンと設定時のプレビュー
- 受信メール一覧・保存済み本文の詳細表示と、各メールに保存されるルールの背景色
- 端末内受信メールの期限削除（初期値1週間、毎時最初の実メール確認時）
- AccountごとのPOP3 / POP3S / IMAP4 / IMAPSサーバー削除ポリシー
- FCMによる主監視、WorkManagerによるバックアップ確認、連続失敗時の通知
- 回線切替時の短時間の復旧待ち、英語・日本語UI
- FCM主監視の「次回確認」の目安と、実メール確認が正常完了した「最終確認」の表示
- アカウントごとのTLS証明書検証4モード（標準は厳密検証）
- 過去24時間の監視が正常な場合に1日1回知らせる、静かな監視ステータス通知
- Android Keystoreを利用したパスワード保護

Release 1.0はGitHub Releasesで署名済みAPKを配布する方針です。AndroidクライアントのソースコードをBSD 3-Clause Licenseで公開し、キャラクター画像には別ライセンスを適用します。

## Requirements

- Android 8.0（API 26）以上
- 動作確認済み実機：Pixel 7a / Android 17。最低対応OSを含むすべての端末・OSでの動作を確認したものではありません。
- メールサーバーへのネットワーク接続と、対応プロトコルで利用できるアカウント
- FCMによる主監視を利用する場合：MyDNS.JPアカウント（MasterID / MasterPwd）。A MyDNS.JP account is required for FCM-based primary monitoring.
- 通知の許可。「停止まで継続」の自動開始には、対応Androidで「アラームとリマインダー」の許可も必要

Android 17以降では、アラーム種別の1 / 3 / 5回も、画面OFF時の背景再生のため「アラームとリマインダー」の許可を使用します。未許可でも有限警報は従来のサービス起動経路を使いますが、背景音声が制限される場合があります。

Continuous alarm auto-start requires the Alarms & reminders permission on supported Android versions. On Android 17 or later, Alarm-category alerts with 1, 3, or 5 cycles also use it for screen-off playback. Finite alerts without this permission retain the regular service entry, but background audio may be restricted.

開発時はAndroid Studio、Android SDK 37、Gradle wrapperに対応するGradle JDKを使用します。compileSdk / targetSdkは37です。

## インストール

Google Playでは配布しません。このリポジトリの「Releases」から、対象バージョンの署名済みAPKを取得して端末で開いてください。未公開の場合はRelease 1.0の公開をお待ちください。

ブラウザやファイル管理アプリに対して「この提供元のアプリを許可」など、提供元不明アプリのインストール許可が必要になる場合があります。画面の案内に従い、インストール後は必要に応じて許可を戻してください。更新時は同じ配布元のAPKを使用し、設定を保持するためアンインストールせず上書き更新してください。署名が異なるAPKでは上書きできません。

初期設定は[user-guide.md](user-guide.md)、受信と削除の英語説明は[English guide](user-guide.en.md)を参照してください。利用前に[Privacy Policy](PRIVACY.md)をご確認ください。

## Build

Android Studioでリポジトリを開き、SDKと依存関係を準備します。Windowsではリポジトリのルートから実行できます。

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat lint
```

Debug APKはapp/build/outputs/apk/debug/app-debug.apkに生成されます。Google Services pluginは常時適用されるため、自己ビルドには自分のFirebaseプロジェクトの`app/google-services.json`をローカルに用意します。このファイルはGit管理しません。メールサーバーの設定やパスワードはビルドに不要です。instrumentationは専用の試験端末・エミュレーターで実行し、実運用端末ではGradleによるアプリの再インストールや試験データの変更を行わないでください。

**独自Firebaseプロジェクトを使った自己ビルドは、そのままではMyDNS.JP公式GatewayのFCM配送対象になりません。** FCM tokenと送信元のFirebaseプロジェクトを一致させる必要があり、現在のクライアントのGateway接続先は公式サービスに固定されています。公式FCM主監視は公式配布APKで利用してください。自分のFirebaseプロジェクトでFCMを試験するには、対応する送信環境と接続先の調整が別途必要です。[Firebaseのエラーコード説明](https://firebase.google.com/docs/cloud-messaging/error-codes)も参照してください。

The Google Services plugin is always applied, so a self-build needs a local `app/google-services.json` for your own Firebase project. **Such a build cannot receive FCM checks from the official MyDNS.JP Gateway as-is.** The token and sender must belong to the same Firebase project; the client currently targets the official Gateway. Use the official distributed APK for official FCM monitoring. Testing your own Firebase project requires a matching sender and an appropriate client endpoint configuration.

Release APKは署名済みで配布します。署名鍵とそのパスワードはGit管理しません。

## 利用開始

FCM主監視には、端末の設定画面でMyDNS.JP MasterIDとMasterPwdを保存します。初回はHTTPSでGatewayへ一次認証し、発行されたdeviceTokenをAndroid Keystoreを利用して暗号化保存します。以後はdeviceTokenのBearer認証を使用し、Bearer失効時だけMyDNS資格情報で再認証します。配布APKの利用者がFirebase設定ファイルを配置する必要はありません。

メールアカウントの接続テストと保存、ルール設定を行い、通知・継続警報に必要な権限を許可して監視を有効にします。FCMが設定間隔の主監視となり、WorkManagerは設定間隔と15分の大きい方を周期とするバックアップです。MasterID未設定でもバックアップ確認は継続します。詳しい操作は[user-guide.md](user-guide.md)を参照してください。

通常モードでは有効なメールアカウントがなければ監視を開始できず、既に届いた確認要求も通信せず正常にスキップします。ダミーメールへの自動切替はありません。設定の確認ツールで「Demo Mode」（初期値OFF）を明示的にONにした場合だけ、実メール接続の代わりにダミーデータを使い、Mainにデモモードと表示します。

## Gatewayとプライバシー

GatewayはGoogle Firebase Cloud Messaging（FCM）を通じてメール確認のきっかけを端末へ送ります。メールの取得とルール判定はAndroid端末で行います。メールサーバーの認証情報、受信メールの本文・件名・送信元はGatewayへ送信しません。

OSSとして公開するのはAndroidクライアントです。Gateway / FCMサーバー側実装は非公開で、このリポジトリには含めません。Gatewayで扱う認証・登録情報や運用ログについては[PRIVACY.md](PRIVACY.md)を参照してください。

監視中のFCM受信では専用の「監視状態通知」を1件表示し、受信直後は「メール確認中」、確認全体の最終結果に応じて「異常なし」・採用された警報内容・受信確認失敗へ更新します。確認開始や途中retryだけで「異常なし」にはしません。音を避けたい場合はAndroidの通知設定で「監視状態」をサイレントにできます。警報・監視失敗・1日1回の「監視ステータス通知」とは別チャンネルです。

メール確認でルールに一致すると、この同じ監視状態通知にも採用された警報内容を表示します。次の正常確認で一致がなければ「異常なし」に戻ります。

The Monitoring state notification shows “Checking mail” when FCM arrives, then the final result: “No issues”, the selected matching alert, or a check failure. It is separate from the once-daily Monitoring status notification.

## Documentation

| 文書 | 読む目的 |
| --- | --- |
| [user-guide.md](user-guide.md) | 初期設定、日常操作、警報・削除設定、困ったときの確認 |
| [architecture.md](architecture.md) | 現在の内部構造、永続化、受信・監視・警報の動作仕様 |
| [AGENTS.md](AGENTS.md) | AI・開発作業者が守る設計原則と変更時の注意 |
| [PRIVACY.md](PRIVACY.md) | 端末・Gateway・FCMで扱う情報、利用目的、保存と削除 |
| [artwork/README.md](artwork/README.md) | キャラクター画像の対象ファイルと利用条件 |

## 注意事項

Androidのバックグラウンド制限により確認時刻は遅れることがあります。端末音量やDND設定をアプリが強制変更することはありません。有限回数の警報はプロセス終了時に完遂できない場合があります。重要な監視では実際の端末・サーバー環境で動作を確認してください。

「サーバーからメール削除」はPOP3 / POP3S / IMAP4 / IMAPSに対応し、初期値は「削除しない」です。端末内だけを対象とする「内部からメール削除」は別設定で、初期値は1週間です。削除後は一覧から除外しますが、重複受信・再警報を防ぐ最小記録は保持します。有効にする前に利用ガイドを読み、削除専用のテストメールで確認してください。TLS検証は「厳密に検証する」「ホスト名の不一致を許可する」「自己署名証明書を許可する」「検証しない」の4モードです。自己署名でも期限・ホスト名を検証し、fingerprintは使用しません。「検証しない」は明示選択時だけ適用し、自動fallbackは行いません。JSON Export / Importは現在未対応です。

## 未対応機能・問い合わせ

JSON Export / Import、添付ファイル表示、OAuth専用認可UI、複数アカウント追加導線の改善、独自BOOT_COMPLETED reconcileは現在未対応です。今後のバージョンでの実装を検討しますが、対応を約束するものではありません。安全上の制約も含め、詳細は[user-guide.mdの「現在の制約」](user-guide.md#20-現在の制約)を参照してください。

一般的な問い合わせ・不具合報告は、このリポジトリの「Issues」を基本窓口とします。Gateway登録情報の削除依頼など本人確認が必要な連絡や、公開できない内容は、[MyDNS.JPのお問い合わせ](https://www.mydns.jp/contact-us/)へお送りください。アラートくんについての質問も受け付けます。投稿前に[PRIVACY.mdの問い合わせ案内](PRIVACY.md#問い合わせ)をご確認ください。

Use GitHub Issues for general reports. For private questions or Gateway registration-data deletion requests requiring identity verification, use the [MyDNS.JP contact form](https://www.mydns.jp/contact-us/), which also accepts Alert-kun questions. Never post credentials or personal information in a public Issue.

## License

Copyright (c) 2026 Future Versatile Group/MyDNS.JP

ソースコードは[BSD 3-Clause License](LICENSE)です。依存ライブラリにはそれぞれのライセンスが適用されます。

Alert-kunキャラクター画像はソースコード用BSD 3-Clauseの対象外で、[CC BY-NC-ND 4.0](https://creativecommons.org/licenses/by-nc-nd/4.0/)を適用します。著作権は放棄しません。対象と条件は[artwork/README.md](artwork/README.md)を参照してください。

本リポジトリ全体がBSD 3-Clauseで提供されるものではなく、キャラクター画像を含む再配布にはCC BY-NC-ND 4.0の条件が別途適用されます。
