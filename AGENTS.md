# Alert-kun 開発作業規約

AI coding agentと開発作業者向けの厳守事項。実装前に[architecture.md](architecture.md)を読み、コード・テストを確認する。概要は[README.md](README.md)、利用方法は[user-guide.md](user-guide.md)へ集約する。

## 作業の基本

- 依頼範囲内で最小限の変更を行う。メール取りこぼし防止、重複警報防止、復旧、監視異常の認識、認証情報保護を優先する。
- 永続データ、削除安全性、認証、監視継続性へ影響する曖昧な仕様は推測で決めず確認する。既に明示された変更はその許可範囲内で進める。
- 未実装機能を実装済みとして扱わない。既知の制約を無断で仕様変更・修正しない。
- 不要な依存追加、将来用途だけの抽象化、大規模リファクタ、package全面変更を行わない。
- Commitは明示指示がない限り行わない。ユーザーの変更を上書き・破棄しない。

## 構造と責務

本番コードはapp/src/main/java/direct/kabu/alertkun、resourcesはapp/src/main/res。単一appモジュール、Kotlin / Compose、minSdk 26 / targetSdk 37。

- UIへメール通信・Room・Keystore・ルール判定本体・警報制御本体を書かない。ViewModelへ処理本体を集中させない。
- RuleEngineはAndroid framework、Room、通信、通知から独立した純粋ロジックとする。
- monitoringは監視・1回の受信統括、mailは通信・解析、alarmは警報、dataは永続化、securityは秘密情報を担当する。
- domainへCompose色値やAndroidの音源・AudioAttributes定数を持ち込まずmapperで変換する。

## 受信・永続化・復旧

- Room、DataStore、Android Keystoreの用途を分離する。Entityとdomainの変換を維持する。
- POP3はUIDL、IMAPはUIDVALIDITY + UIDを永続IDにする。メッセージ番号を使用しない。
- UNIQUE(accountId, remoteMessageId)を維持し、ReceivedMailを重複保存しない。
- 内容削除済み・UNSUPPORTEDを含む既知accountId + remoteMessageIdは取得前にskipする。Account単位の既知Setを一度照会し、保存成功後に即時追加してretryへ引き継ぐ。1通ずつ完全なboundedデータをRECEIVED保存してから全Ruleを1回評価する。途中失敗では候補を破棄し、handoff・batch確定・削除へ進まない。復旧はローカルだけで行う。
- POP3 / POP3SはUIDLとLISTをheader/bodyより先に取得・検証する。既知へTOP/RETRしない。未知でもraw >16MiBはTOP/RETRせずUNSUPPORTEDとして記録し、後続へ進む。UNSUPPORTEDをpoison mailやretry対象にしない。無効LISTでは推測・terminal保存せずpollを失敗させる。ENVELOPE先行取得・disabletopによる置換は禁止。
- 通常受信ではUIDL / UIDだけを先に保存せず、必要なローカルデータとRECEIVEDを同じ保存で確定する。仕様上取得しないUNSUPPORTEDだけは、取得不能内容を空値にしてIDとterminal状態を保存する。
- RECEIVEDとPROCESSEDを混同しない。復旧は保存済みRECEIVEDから再開し、PROCESSEDを再警報しない。
- Room保存前・Rule評価前にAlarmを出さない。poll handoff成功後、全対象のPROCESSED・matchedRuleId・色・alertTriggeredをRoomの単一batch transactionで確定する。
- handoff失敗時は次点へfallbackせずRECEIVEDを残す。handoffとRoom確定の間のprocess停止についてexactly-once保証を謳わない。
- schema変更時はデータを保持するmigration、schema JSON、migration testを更新する。破壊的migrationを勝手に導入しない。
- 端末内メール削除は既存mailRetentionDays（default 7）とServerDeletePolicyの選択肢を使い、サーバー削除と分離する。実チェック終了時の既存executionMutex内で、開始時刻が属する毎時0分以降の時間帯に1回だけ実行し、試行記録をDataStoreへ先に永続化する。失敗で監視を失敗扱いにせず、同一時間帯に再試行しない。期限到達済みPROCESSED / UNSUPPORTEDの内容を消し一覧から除外するが、RECEIVED復旧・UIDL/UID重複防止・サーバー削除再試行・ヘルス件数に必要な最小記録を残す。保存日数や削除対象を暗黙に変更しない。

- 本文永続化・Rule入力は1通合計16,384 UTF-8 bytes以下。code point境界で切り、CTE/charset decode後に制限する。multipart/alternativeはplain優先で二重連結せず、添付・filename付きpart・message/rfc822を本文へ混ぜない。HTMLを含め入力サイズ比例のheapを使わない。
- POP3 raw全量をheap cacheしない。16MiB以下だけ1通単位でprivate tempへstream取得し、次の1通の前・例外・キャンセル時にcleanupする。POP3 / IMAPのraw MIMEは共通Mime4j streaming解析へ渡す。本文bufferが16KiBへ達しても正常rawはEOFまで解析し、後続plain alternativeを確認する。新dependencyは安全上の必要性を証明し互換性probeを通したものだけ採用する。
- IMAP受信はBODYSTRUCTURE・高レベルcontent APIを使わず、既知除外後にUID付きRFC822.SIZEと64KiB単位のUID BODY.PEEK[] partial FETCHを使う。既知はbody fetch 0、raw上限16MiB。UID・offset・payload長を検証後にparserへ渡す。不一致・サイズ矛盾はprotocol failureとして接続を閉じ、terminal保存しない。size不明で16MiBに達しEOFを証明できなければUNSUPPORTED。添付rawは通信され得るが保存・判定しない。
- IMAPはpartial取得長を遵守する標準準拠serverを前提とする。巨大literalの宣言によるAngus内部allocation前の防御は保証しない。Angus patch/fork/ByteArray改造・独自IMAP parserは禁止。OutOfMemoryErrorを通常message失敗としてcatchして継続しない。
- POP3 / IMAPは1Account・1attemptにつき1受信sessionを所有し、Jakarta Messageを外へ出さない。例外・キャンセルでもFolder/Storeを閉じ、削除sessionと共有しない。上限対応を理由にRoom v7を変更しない。

## Rule・priority・snapshot

- priorityは数値が小さいほど高優先。1メール内では最優先Rule 1件、同値ならRule id順。
- 1回のCoordinator全体で復旧分・新規分・複数Accountの候補を比較し、AlarmController handoffは最大1件。
- poll選定はpriority昇順、受信保存日時降順、accountId、remoteMessageId、receivedMailId順。候補列挙順に依存させない。
- 非採用メールも正常pollではPROCESSED。matchedRuleIdと色は各メール自身のRuleから保存し、alertTriggeredは実handoff採用メールだけtrue。
- backgroundColorはReceivedMailへのsnapshot。表示時に現在のRuleから引き直さない。
- 採用AlarmEventのsoundType / soundUri / vibrationPattern / alarmModeを他候補と混ぜない。
- 部分一致・case insensitive・「件名と本文」はORという仕様を無断変更しない。旧RECEIVEDの未取得本文を取り直さず、保存済みデータだけで評価する。

## 監視

- 利用者向け名称は6004を「監視状態通知」/「Monitoring state」、6003を日次の「監視ステータス通知」/「Monitoring status」とする。内部のMonitoringHealth系class/resource名、notification ID、channel IDは名称整理だけで変更しない。

- 通常モードでenabled=trueのメールアカウントが0件なら、監視開始前に拒否し、既存monitoringEnabledを変更しない。既に走ったFCM・Worker・手動確認は通信・Dummy・Coordinatorを実行せず正常skipとし、成功・失敗記録へ流用しない。DummyMailReceiverはユーザーが明示的にONにしたDemo Mode（demo_mode_enabled、default false）だけで使用し、実受信と混在させない。アカウントなしを理由にDemoへ自動切替したり、既存設定・Accountを変換したりしない。

- 監視状態通知6004は直近のMailCheck全体の最終結果を表示する。FCM受信直後はCHECKING（メール確認中）とし、開始・account単位の成功・retry途中・safe skipをNORMALの根拠にしない。共通Worker完了点で既存MonitoringStatusRepositoryの成功reset／失敗加算を1pollにつき1回行い、その結果を表示する。最終SUCCESSは非一致NORMAL（異常なし）、採用警報ありRULE_MATCH。最終FAILUREはFAILUREとし、設定閾値未満ではcount/threshold、到達後は連続失敗countを表示する。failure通知の発報・抑制条件は変更しない。結果表示日時は完了時刻。表示用selectedAlarmはメモリ内の成功結果だけで渡し、再handoff・別候補選定・Room再評価を行わない。専用channelとサイレント判定を維持し、OFF・古い世代の結果で通知を復活させない。observabilityはCHECKING / NORMAL / RULE_MATCH / FAILUREとRECEIPT / CHECK_RESULT、Work IDだけで結果を追跡し、通知内容・件名・差出人・ルール名をログへ出さない。

- FCM受信時の監視状態通知は専用channel alertkun_monitoring_status、固定ID 6004でpost / updateし、OnlyAlertOnceで更新時の再通知を抑える。警報・監視失敗・日次通知とは分離し、監視OFFでcancelする。channelの現在importance / sound / vibrationだけでサイレント相当を判断し、ユーザー設定を上書きしない。通知失敗・権限拒否で既存dispatchを止めず、成功記録・skip・予約を変更しない。永続ログへMONITORING_NOTIFICATION_POST / UPDATE / CANCEL等とFCM照合情報を記録し、既存priorityログを維持する。HIGH配送・deep Doze突破を保証しない。

- filesDir/observabilityの永続観測ログはDebug / Release共通でrolling 15×24時間保持、総量64MiB以内とする。日別・同日8MiB超で分割し、期限境界日の有効行を早期削除しない。期限整理後に容量超過する場合だけ古いファイルから削除し、追記対象は保護する。旧形式は期限・容量条件まで保持する。許可した非秘密metadataだけを記録し、任意payload・token・接続先・username・メール内容・例外message・SSID・BSSID・IP・DNSアドレスを渡さない。既存check内callbackへ安全なnetwork変化を記録し、観測キャッシュを通信判断へ使用しない。Work IDでenqueue・Worker・接続を対応付け、skip時は既存判定の入力値を記録する。ログ失敗を監視へ伝播させず、観測のためのWakeLock・FGS・予約・通信や監視条件変更を追加しない。回収時に既存ログ・アプリデータを消さない。

- Mainの「次回確認」は現在の主監視設定に有効なFCM受信時刻（未受信ならGateway登録／設定成功時刻）＋確認間隔から導出し、「yyyy/MM/dd HH:mm頃」で表示する。OFFは「なし」、有効な基準なしは「未定」。最終確認・WorkManager保険予約を流用せず、予定超過で自動繰り上げ・待機／遅延／異常表示をしない。異常検出は既存の保険・失敗通知が担当する。
- Mainの「最終確認」は実メールチェック全体が正常完了した最新時刻とする。FCM受信・Worker起動・backup skip・削除失敗では更新しない。監視OFF時の明示的な手動確認成功も対象とする。
- 主監視設定のrevisionはON/OFF・間隔・MasterID変更と同じDataStore editで更新する。保険用scheduleGenerationは起動／時計補正でも変わるため主監視のsession識別に流用しない。古いrevisionのFCM基準・Gateway成功応答を現在予定へ混入させず、予定時刻自体を永続化しない。token再登録がGatewayの送信基準を変えるかはサーバー仕様で確認し、推測で変更しない。
- 現在のGatewayは同じtoken・intervalでの間隔登録も成功時に送信基準時刻をリセットする。token再登録も成功確認時刻を新しい初期基準にし、それ以前のFCM基準を解除する。成功後の受信は保持し、失敗時は既存正常基準を上書きしない。将来API変更時はこの外部仕様を再確認する。

- default network changeはINTERNET / VALIDATEDを補助情報として、1チェック全体で安定化とretry間隔の待機を合わせて合計最大8秒を行う。default networkが存在すれば実メール接続結果を最終判定とし、読み取り通信の型で判定できる一時的ネットワーク例外だけ、networkChangedの有無によらず1チェック全体で最大2回retry（読み取り通信は最大3試行）する。retry間隔は3秒・5秒を基本とし共有待機予算の残りまで短縮、残り予算3秒未満では即retryせず失敗を返す。auth / TLS / certificate / protocolエラーはretryしない。復旧して正常完了した途中のエラーをfailure countへ加算しない。Coordinator・警報・削除全体を再実行せず、メール保存・警報の重複を防ぎ、callbackを終了・キャンセル時に解除する。

- FCMを主監視、WorkManagerを保険・整合確認として使用する。常駐MonitoringService、dataSync FGS、監視用AlarmManager、長時間polling loop、独自長時間WakeLockを導入しない。
- 1 Workerは1回のboundedなcheckをCoordinatorへ委譲する。FCM登録間隔は設定値、保険はmax(設定値, 15分)のunique PeriodicWork。メール確認用OneTimeWorkはFCM・手動・起動時の単発確認用途だけとする。登録確認用Workerはメール確認から独立させる。正確な時刻を保証しない。
- executionMutex、Mutex取得後の設定再読込、scheduleGeneration照合を壊さない。保険skipは同世代・同一保険周期内のFCM正常成功だけを根拠とし、不明・失敗・他triggerでは実行する。FCMと手動の成功元を混同せず、FCM成功で保険周期を動かさない。
- runNowは監視ON化・世代変更を行わない。FCMは監視OFFで実行せず、明示的な手動runNowのOFF時単発確認は維持する。起動時gate、正常future schedule維持、60秒以上の時計補正rebase、timezoneで表示だけ変更する方針を維持する。
- NetworkType.CONNECTED constraintでoffline確認を隠さない。offlineも連続失敗へ含め、新着0件の成功で失敗count・異常通知状態をresetする。
- 日次監視ステータス通知は初期値ONとし、正常check完了時にexecutionMutex内で判定する。初回正常checkから24時間待ち、以後は前回通知から24時間以上経過した最初の正常checkで通知する。監視または通知設定OFFで観測を解除し、再ON後も初回正常checkから24時間待つ。過去24時間に失敗があれば正常通知を出さず、連続失敗通知へ委ねる。失敗履歴を成功で消さず、skipを成功や失敗へ流用しない。異常検出件数はRoomのPROCESSEDかつalertTriggered=trueを初回保存時刻による過去24時間で数える。専用LOW channelは無音・無振動、固定notification ID 6003とし、通知設定で監視予約・Gateway登録・警報を変更しない。

## 警報・preview

- 全警報モードの音声再生は既存mediaPlayback AlarmPlaybackService内で行う。UNTIL_STOPPEDに加え、API37以上の有限ALARMはexact alarm・通知が許可されている場合に既存exact alarm → AlarmStartReceiver経路を使う。それ以外の有限回数は直接Service開始を維持する。起動方針はAlarmDeliveryPolicyへ集約し、active Serviceの置換でexact alarmを重ねない。AlarmModeを再利用し、有限回数はSTART_NOT_STICKYで再開しない。
- 各有限サイクルはisLooping=false、完了・音源長timeoutでreleaseし、次サイクルを明示的に開始する。エラー時は残りを破棄する。autoLoop metadata対策を維持し、有限回数完了・エラー時にFocus・player・振動・foreground・Serviceを解除する。
- 警報Serviceを監視へ流用しない。権限・通知・FGS開始失敗時は説明通知と有限回の振動を残し、背景でMediaPlayerを直接再生しない。全モードでFGS開始後にAudio Focusを要求し、拒否時は音を開始しない。
- 新警報は既存警報を置換しqueueを作らない。Stopはpending開始、timer、残回数、player、振動、通知、Serviceを解除する。
- soundTypeに対応する音源一覧・標準URIを維持し、種別変更時に旧soundUriを流用しない。有限回数・preview・UNTIL_STOPPEDの再生とAudio Focusは種別ごとのusage / legacy streamを使用する。UNTIL_STOPPEDは継続時間だけを決め、ALARMへの変換やOSの無音・背景音声制約回避に使わない。端末の音量・DNDは変更しない。
- 継続警報のlive sessionはService activate/破棄に連動させ、Main上部の停止ボタンはactive時だけ表示する。Main・通知・確認ツールは共通AlarmController.stop()へ到達する。画面OFFでもsession・音・振動を継続し、画面OFF後の振動再設定はtoken照合でStop後・置換後に出力を復活させない。スワイプ消去はStopとせず通知だけを復元し、出力を再開始しない。pending/active中の同じメール・Ruleの再handoffは無視する。MediaPlayerのWakeLockは警報再生中だけで、監視へ流用しない。
- 警報channelは音・振動なし、実出力はController。端末音量変更・DND回避をしない。
- previewは共通waveformを1サイクルだけ使用し、初期表示で実行しない。選択変更で旧previewをcancel、NONE・画面離脱で停止する。
- previewはAlarmEvent・通知・FGS・Room・alertTriggeredを変更しない。所有tokenを維持し、cleanupで後から開始した本番警報を止めない。

## サーバー削除

- ReceivedMail.serverDeleteResolutionはPENDING / DELETED / ABSENTを区別する。POP3は完全なUIDL照合と正常QUIT応答、IMAPはUIDVALIDITY/UID照合と正常EXPUNGE応答の確認後だけ解決状態を保存し、以後の候補から除外する。通信・UIDL・DELE・QUIT失敗や結果不明では候補を維持する。RECEIVED / PROCESSEDを流用せず、UIDLを観測ログへ記録しない。

- Account単位ServerDeletePolicyの新規・migration・未知値はNEVERとし、明示保存済みpolicyは保持する。POP3 / POP3S / IMAP4 / IMAPS共通とし、保存済みpolicyを変換しない。
- ローカル保存、Rule評価、必要なpoll handoff、PROCESSED確定前に削除しない。
- accountId + UIDLをRoomと照合し、PROCESSEDまたはUNSUPPORTEDと有効な初回保存時刻・期限経過を証明できる場合だけ削除する。alertTriggeredを条件にしない。
- lookup失敗、ID不一致、RECEIVED、不明・未来時刻、protocol状態不明、例外では残す。PROCESSED確定後に既存削除条件でローカル候補UIDLを抽出し、0件なら削除接続を省略する。候補取得失敗を0件扱いせず、候補がある場合もサーバーUIDL照合・全Room lookup・DELE/QUIT制御を省略しない。観測ログには候補件数とskip理由だけを記録し、UIDL・メール内容を出さない。
- Connection Testでは一切削除しない。READ_ONLY受信・接続テストと専用削除セッションを分離する。
- 全DELE成功後だけQUITで確定する。途中失敗・キャンセルではQUITを送らない。QUIT後の応答喪失では確定を断定しない。
- 削除失敗後もPROCESSEDを保ち、次回再試行で再保存・再警報しない。
- 日数基準は初回保存時刻。1ヶ月はUTCカレンダー加算であり固定30日へ無断変更しない。

- IMAP削除はhandoff・batch確定後の専用READ_WRITE sessionだけで行う。UIDVALIDITY:UIDを照合し、対象\Deletedと通常EXPUNGEの正常応答後だけDELETED。不在またはUIDVALIDITY変更はABSENT、flag/EXPUNGE/通信/応答不明/キャンセルはPENDING。close(false)を維持し、候補0では接続しない。通常EXPUNGEが既存の他\Deletedも消すことは許容済み。

## 認証・TLS・公開情報

- Gateway登録ではFCMによる端末確認を必須とする。開始要求の送信前にローカル未完了要求・nonce・確認開始元の秘密値を暗号化保存し、現在のMasterID・FCM token・設定revision・認証情報世代・期限との一致を確認する。FCM tokenの文字列やVERIFY_REGISTRATION受信だけで応答しない。確認は専用Workerで行い、CHECK_MAIL・監視状態通知・警報へ流さない。確認待ちを認証失敗・登録成功とせず、確認不能時に既存の正常な登録を失効させない。応答消失・再利用・逆順配送・並行要求、複数端末・正当なMasterID変更・token更新を検証し、旧APIへfallbackしない。deviceTokenはCredentialStoreへ暗号化保存する。開始時のBearer401だけで破棄して一次認証を1回試し、401以外では維持する。MasterID / MasterPwd変更時は、新しい秘密情報の保存前に旧要求を永続世代で無効化し、旧Bearerを破棄する。設定保存と登録・token保存を専用mutexで保護し、補償で旧世代・Bearerを復活させない。秘密値・Authorization・FCM tokenの一部・nonce・challengeをログやWorkManager入出力へ出さず、新規画面・追加権限・常駐処理を設けない。

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
- 公開前secret auditはworking treeに加え、全branch・tagから到達するGit履歴と削除済みファイル履歴も対象とする。秘密情報を発見したら値を再掲せず、ファイルパス・commit等の場所だけを報告してSTOPする。履歴rewriteは別途明示許可を得る。文書整備だけの依頼をsecret scan実施の許可と解釈しない。
- 公開対象はAndroidクライアント。非公開のGatewayサーバー実装、内部DB構造、ログ名・内部パス・保存先・運用インフラ構成を公開文書やリポジトリへ転記しない。Privacy Policyは扱う情報・目的・第三者サービス・保存と削除を利用者視点で説明し、未確認の保持日数を推測しない。
- ソースコードはBSD 3-Clause、キャラクター画像はCC BY-NC-ND 4.0とし、[LICENSE](LICENSE)と[artwork/README.md](artwork/README.md)の適用範囲を維持する。Release 1.0はGitHub Releasesで署名済みAPKを配布する方針とし、署名鍵・署名用パスワードはGit管理しない。

## UI・テスト・ドキュメント

- 製品表示名は英語「Alert-kun」、日本語「アラートくん」。通知・チャンネル・画面・文書で統一し、AlertKun / Alertkunはコード識別子・クラス名に限る。

- Kotlinは4スペース、classはPascalCase、関数・変数はcamelCase、resourceはlowercase_with_underscores。
- ユーザー向け文言はEnglishのres/values/strings.xmlと日本語のres/values-ja/strings.xmlへ同時に定義する。enum.nameを表示名に使わない。
- UI操作テストは安定した言語非依存testTagを使う。翻訳確認の場合だけ表示文字列をセレクタにしてよい。
- JVMはJUnit 4、Android / Room / ComposeはAndroidX instrumentation。app/src/testとapp/src/androidTestへ対象packageに合わせて置く。
- 変更に応じて重複・RECEIVED復旧・二段階priority・handoff失敗・batch rollback・削除境界・接続テスト非削除・警報回数 / Stop / previewを検証する。
- Windows rootで .\gradlew.bat test / assembleDebug / lint。必要なconnectedAndroidTestを接続端末で実行する。未実行を合格として報告しない。
- 実機試験で監視ONへ復帰するときは共通MonitoringDevicePreparationを使い、設定ON・予約Operation完了・現在世代のunique Periodic backupがENQUEUED/RUNNINGで1件存在することを10秒以内に確認してから終了する。設定値だけで復旧成功とせず、試験終了後も別プロセスから予約の永続化を確認する。本番UIへ同期waitを追加しない。
- 通信・削除の試験は削除専用Account・使い捨てメールで行い、本番メールを使用しない。
- 利用者に見えるUI変更ではuser-guide.mdを確認・更新する。内部仕様変更ではarchitecture.md、厳守事項変更ではAGENTS.mdも更新する。
- READMEは概要・入口、user-guideは利用方法、architectureは内部仕様、AGENTSは作業規約。詳細を大量複製しない。
- 4文書は現在仕様を記載し、開発経緯・過去方式との比較は残さない。migration・互換性・安全上の不変条件に必要な説明は保持する。
- build/、.gradle/、local.properties等をCommitしない。既存.gitignoreを尊重する。
- 最終報告には変更・ファイル・検証結果・未実行・未解決事項を含める。PRでは利用者への影響と関連する安全性・永続化・権限変更を明記する。
