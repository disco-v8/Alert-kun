# AlertKun 開発作業規約

AI coding agentと開発作業者向けの厳守事項。実装前に[architecture.md](architecture.md)を読み、コード・テストを確認する。概要は[README.md](README.md)、利用方法は[user-guide.md](user-guide.md)へ集約する。

## 作業の基本

- 依頼範囲内で最小限の変更を行う。メール取りこぼし防止、重複警報防止、復旧、監視異常の認識、認証情報保護を優先する。
- 永続データ、削除安全性、認証、監視継続性へ影響する曖昧な仕様は推測で決めず確認する。既に明示された変更はその許可範囲内で進める。
- 未実装機能を実装済みとして扱わない。既知の制約を無断で仕様変更・修正しない。
- 不要な依存追加、将来用途だけの抽象化、大規模リファクタ、package全面変更を行わない。
- Commitは明示指示がない限り行わない。ユーザーの変更を上書き・破棄しない。

## 構造と責務

本番コードはapp/src/main/java/direct/kabu/alertkun、resourcesはapp/src/main/res。単一appモジュール、Kotlin / Compose、minSdk 24 / targetSdk 37。

- UIへメール通信・Room・Keystore・ルール判定本体・警報制御本体を書かない。ViewModelへ処理本体を集中させない。
- RuleEngineはAndroid framework、Room、通信、通知から独立した純粋ロジックとする。
- monitoringは監視・1回の受信統括、mailは通信・解析、alarmは警報、dataは永続化、securityは秘密情報を担当する。
- domainへCompose色値やAndroidの音源・AudioAttributes定数を持ち込まずmapperで変換する。

## 受信・永続化・復旧

- Room、DataStore、Android Keystoreの用途を分離する。Entityとdomainの変換を維持する。
- POP3はUIDL、IMAPはUIDVALIDITY + UIDを永続IDにする。メッセージ番号を使用しない。
- UNIQUE(accountId, remoteMessageId)を維持し、ReceivedMailを重複保存しない。
- UIDL / UIDだけを先に保存しない。必要なローカルデータとRECEIVEDを同じ保存で確定する。
- RECEIVEDとPROCESSEDを混同しない。復旧は保存済みRECEIVEDから再開し、PROCESSEDを再警報しない。
- Room保存前・Rule評価前にAlarmを出さない。poll handoff成功後、全対象のPROCESSED・matchedRuleId・色・alertTriggeredをRoomの単一batch transactionで確定する。
- handoff失敗時は次点へfallbackせずRECEIVEDを残す。handoffとRoom確定の間のprocess停止についてexactly-once保証を謳わない。
- schema変更時はデータを保持するmigration、schema JSON、migration testを更新する。破壊的migrationを勝手に導入しない。
- retentionは現在自動削除に未接続。保存期限・一覧絞り込み・削除仕様を暗黙に変更しない。

## Rule・priority・snapshot

- priorityは数値が小さいほど高優先。1メール内では最優先Rule 1件、同値ならRule id順。
- 1回のCoordinator全体で復旧分・新規分・複数Accountの候補を比較し、AlarmController handoffは最大1件。
- poll選定はpriority昇順、受信保存日時降順、accountId、remoteMessageId、receivedMailId順。候補列挙順に依存させない。
- 非採用メールも正常pollではPROCESSED。matchedRuleIdと色は各メール自身のRuleから保存し、alertTriggeredは実handoff採用メールだけtrue。
- backgroundColorはReceivedMailへのsnapshot。表示時に現在のRuleから引き直さない。
- 採用AlarmEventのsoundType / soundUri / vibrationPattern / alarmModeを他候補と混ぜない。
- 部分一致・case insensitive・「件名と本文」はORという仕様を無断変更しない。未取得本文の評価制約はarchitectureを参照する。

## 監視

- Mainの「次回確認」は現在の主監視設定に有効なFCM受信時刻（未受信ならGateway登録／設定成功時刻）＋確認間隔から導出し、「yyyy/MM/dd HH:mm頃」で表示する。OFFは「なし」、有効な基準なしは「未定」。最終確認・WorkManager保険予約を流用せず、予定超過で自動繰り上げ・待機／遅延／異常表示をしない。異常検出は既存の保険・失敗通知が担当する。
- Mainの「最終確認」は実メールチェック全体が正常完了した最新時刻とする。FCM受信・Worker起動・backup skip・削除失敗では更新しない。監視OFF時の明示的な手動確認成功も対象とする。
- 主監視設定のrevisionはON/OFF・間隔・MasterID変更と同じDataStore editで更新する。保険用scheduleGenerationは起動／時計補正でも変わるため主監視のsession識別に流用しない。古いrevisionのFCM基準・Gateway成功応答を現在予定へ混入させず、予定時刻自体を永続化しない。token再登録がGatewayの送信基準を変えるかはサーバー仕様で確認し、推測で変更しない。
- 現在のGatewayは同じtoken・intervalでの間隔登録も成功時に送信基準時刻をリセットする。token再登録も成功確認時刻を新しい初期基準にし、それ以前のFCM基準を解除する。成功後の受信は保持し、失敗時は既存正常基準を上書きしない。将来API変更時はこの前提を再確認する。

- default network changeはINTERNET / VALIDATEDを補助情報として、1チェック全体で合計最大8秒の安定化待ちを行う。default networkが存在すれば実メール接続結果を最終判定とし、変更を観測した読み取り通信の一過性例外だけ最大1回retryする。auth / TLS / certificate / protocolエラーはretryしない。復旧して正常完了した途中のエラーをfailure countへ加算しない。Coordinator・警報・削除全体を再実行せず、メール保存・警報の重複を防ぎ、callbackを終了・キャンセル時に解除する。

- FCMを主監視、WorkManagerを保険・整合確認として使用する。常駐MonitoringService、dataSync FGS、監視用AlarmManager、長時間polling loop、独自長時間WakeLockを導入しない。
- 1 Workerは1回のboundedなcheckをCoordinatorへ委譲する。FCM登録間隔は設定値、保険はmax(設定値, 15分)のunique PeriodicWork。OneTimeWorkはFCM・手動・起動時の単発確認用途だけとする。正確な時刻を保証しない。
- executionMutex、Mutex取得後の設定再読込、scheduleGeneration照合を壊さない。保険skipは同世代・同一保険周期内のFCM正常成功だけを根拠とし、不明・失敗・他triggerでは実行する。FCMと手動の成功元を混同せず、FCM成功で保険周期を動かさない。
- runNowは監視ON化・世代変更を行わない。FCMは監視OFFで実行せず、明示的な手動runNowのOFF時単発確認は維持する。起動時gate、正常future schedule維持、60秒以上の時計補正rebase、timezoneで表示だけ変更する方針を維持する。
- NetworkType.CONNECTED constraintでoffline確認を隠さない。offlineも連続失敗へ含め、新着0件の成功で失敗count・異常通知状態をresetする。
- 日次監視ヘルス通知は初期値ONとし、正常check完了時にexecutionMutex内で判定する。初回正常checkから24時間待ち、以後は前回通知から24時間以上経過した最初の正常checkで通知する。監視または通知設定OFFで観測を解除し、再ON後も初回正常checkから24時間待つ。過去24時間に失敗があれば正常通知を出さず、連続失敗通知へ委ねる。失敗履歴を成功で消さず、skipを成功や失敗へ流用しない。異常検出件数はRoomのPROCESSEDかつalertTriggered=trueを初回保存時刻による過去24時間で数える。専用LOW channelは無音・無振動、固定notification ID 6003とし、通知設定で監視予約・Gateway登録・警報を変更しない。

## 警報・preview

- ONCE / THREE_TIMES / FIVE_TIMESは自動終了するprocess内処理。UNTIL_STOPPEDの継続Service経路と混ぜない。
- 各有限サイクルはisLooping=false、完了・エラー・音源長timeoutでreleaseし、次サイクルを明示的に開始する。autoLoop metadata対策を維持する。
- 継続開始はexact alarm → AlarmStartReceiver → mediaPlayback AlarmPlaybackService。監視へ流用しない。権限・通知・FGS開始失敗時の単発fallbackと、Audio Focus拒否時の音の開始抑止を維持する。
- 新警報は既存警報を置換しqueueを作らない。Stopはpending開始、timer、残回数、player、振動、通知、Serviceを解除する。
- soundTypeに対応する音源一覧・標準URIを維持し、種別変更時に旧soundUriを流用しない。有限回数・previewは種別ごとのusageを維持する。UNTIL_STOPPEDだけ選択音源を保持してUSAGE_ALARM / STREAM_ALARMを使い、Android 17の背景音声制約へ適合させる。端末の音量・DNDは変更しない。
- 継続警報のlive sessionはService activate/破棄に連動させ、Main上部の停止ボタンはactive時だけ表示する。Main・通知・確認ツールは共通AlarmController.stop()へ到達する。画面OFFでもsession・音・振動を継続し、画面OFF後の振動再設定はtoken照合でStop後・置換後に出力を復活させない。スワイプ消去はStopとせず通知だけを復元し、出力を再開始しない。pending/active中の同じメール・Ruleの再handoffは無視する。MediaPlayerのWakeLockは継続警報再生中だけで、監視へ流用しない。
- 警報channelは音・振動なし、実出力はController。端末音量変更・DND回避をしない。
- previewは共通waveformを1サイクルだけ使用し、初期表示で実行しない。選択変更で旧previewをcancel、NONE・画面離脱で停止する。
- previewはAlarmEvent・通知・FGS・Room・alertTriggeredを変更しない。所有tokenを維持し、cleanupで後から開始した本番警報を止めない。

## サーバー削除

- Account単位ServerDeletePolicyの新規・migration・未知値はNEVERとし、明示保存済みpolicyは保持する。POP3 / POP3Sのみ対応し、IMAPへ曖昧に広げない。
- ローカル保存、Rule評価、必要なpoll handoff、PROCESSED確定前に削除しない。
- accountId + UIDLをRoomと照合し、PROCESSEDと有効な初回保存時刻・期限経過を証明できる場合だけ削除する。alertTriggeredを条件にしない。
- lookup失敗、ID不一致、RECEIVED、不明・未来時刻、protocol状態不明、例外では残す。
- Connection Testでは一切削除しない。READ_ONLY受信・接続テストと専用削除セッションを分離する。
- 全DELE成功後だけQUITで確定する。途中失敗・キャンセルではQUITを送らない。QUIT後の応答喪失では確定を断定しない。
- 削除失敗後もPROCESSEDを保ち、次回再試行で再保存・再警報しない。
- 日数基準は初回保存時刻。1ヶ月はUTCカレンダー加算であり固定30日へ無断変更しない。

## 認証・TLS・公開情報

- パスワード・token・秘密鍵・本文全文をログへ出さない。Room / DataStore / 設定JSONへパスワードを平文保存しない。
- 保存済みパスワードをUIの実値へ戻さず、固定マスクを保存しない。空入力による既存秘密情報の維持を壊さない。
- CredentialStoreとAccount保存の検証・補償処理を維持し、復旧失敗を成功として隠さない。
- TLSモードはSTRICT、CA_VALIDATED_NO_HOSTNAME（CA_ONLY相当）、SELF_SIGNED_ALLOWED、INSECURE。STRICTはチェーン・信頼済みCA・期限・ホスト名を検証する。CA_ONLY相当はホスト名一致だけを要求せずCA・チェーン・期限検証を維持する。SELF_SIGNED_ALLOWEDはCA trust不要で自己署名を許可するが、leafの有効期限とホスト名一致を検証する。INSECUREはTLS暗号化のみでサーバー証明書を検証しない。
- trust-allはアカウントでINSECUREを明示選択した場合だけに限定し、Angus MailSSLSocketFactoryをその接続の設定へ渡す。初期値・旧schema migration・未知値はSTRICTで、自動的にINSECUREへ移行しない。全体のSSLContext・HostnameVerifierを変更しない。POP3S受信・削除・IMAPS・接続テストは同じTLSモードを参照する。未対応のSTARTTLSを暗黙に追加しない。
- 明示保存済みモードは保持する。互換用TEXT値PINNED_CERTIFICATEはSELF_SIGNED_ALLOWEDへ解釈し、通常保存時に正規化する。certificateFingerprint列は互換性のため未使用列として残し、TLS判断・domain・UIへ渡さない。trustedCertificateIdは互換用フィールドとして保持し、信頼判定へ流用しない。
- TLS設定UIはラベルと4選択肢のみ。常設説明・補足・赤字注意・警告・選択時ダイアログ・fingerprint表示・承認UIを追加しない。
- fingerprintを計算・承認・保存・比較しない。INSECUREへの自動fallbackを行わず、受信・POP3削除・接続テストは共通MailConnectionSettingsを維持する。
- backupは認証情報復元を保証しない。変更時はKeystore鍵と暗号文の関係を検証する。
- 公開用変更へ実Account情報・credential・秘密鍵・keystoreを追加しない。発見時は値を再掲せず場所を報告し、無断で履歴rewriteしない。

## UI・テスト・ドキュメント

- Kotlinは4スペース、classはPascalCase、関数・変数はcamelCase、resourceはlowercase_with_underscores。
- ユーザー向け文言はEnglishのres/values/strings.xmlと日本語のres/values-ja/strings.xmlへ同時に定義する。enum.nameを表示名に使わない。
- UI操作テストは安定した言語非依存testTagを使う。翻訳確認の場合だけ表示文字列をセレクタにしてよい。
- JVMはJUnit 4、Android / Room / ComposeはAndroidX instrumentation。app/src/testとapp/src/androidTestへ対象packageに合わせて置く。
- 変更に応じて重複・RECEIVED復旧・二段階priority・handoff失敗・batch rollback・削除境界・接続テスト非削除・警報回数 / Stop / previewを検証する。
- Windows rootで .\gradlew.bat test / assembleDebug / lint。必要なconnectedAndroidTestを接続端末で実行する。未実行を合格として報告しない。
- 通信・削除の試験は削除専用Account・使い捨てメールで行い、本番メールを使用しない。
- 利用者に見えるUI変更ではuser-guide.mdを確認・更新する。内部仕様変更ではarchitecture.md、厳守事項変更ではAGENTS.mdも更新する。
- READMEは概要・入口、user-guideは利用方法、architectureは内部仕様、AGENTSは作業規約。詳細を大量複製しない。
- 4文書は現在仕様を記載し、開発経緯・過去方式との比較は残さない。migration・互換性・安全上の不変条件に必要な説明は保持する。
- build/、.gradle/、local.properties等をCommitしない。既存.gitignoreを尊重する。
- 最終報告には変更・ファイル・検証結果・未実行・未解決事項を含める。PRでは利用者への影響と関連する安全性・永続化・権限変更を明記する。
