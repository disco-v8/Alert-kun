# Alert-kun 内部設計

この文書はAlert-kunの現在の内部設計・動作仕様を記載する。操作方法は[user-guide.md](user-guide.md)、開発時の厳守事項は[AGENTS.md](AGENTS.md)、概要とビルドの入口は[README.md](README.md)を参照する。仕様変更時はコード・テストと一緒に更新する。

## 1. 実装範囲と構成

単一appモジュールのKotlin / Jetpack Composeアプリ。namespace / applicationIdはdirect.kabu.alertkun。minSdk 26、compileSdk / targetSdk 37、versionName 1.0。UIは英語・日本語。実通信はPOP3 / POP3S / IMAP4 / IMAPSに対応する。

| パッケージ | 責務 |
| --- | --- |
| model | Android frameworkから独立したAccount・Rule・ReceivedMail・設定enum |
| ui/main・account・rule・mail・settings・theme | Compose画面、表示mapper、ViewModel |
| data/entity・dao・database・repository・preferences | Room、DataStore、domain変換、Repository |
| mail/common・pop3・imap・parser | 通信、安定したリモートID、本文抽出、POP3 / IMAPサーバー削除 |
| rules | 純粋なRuleEngine |
| monitoring | WorkManager登録、1回の確認、実行調停、状態・失敗管理 |
| alarm | 通知、MediaPlayer、振動、有限回数制御、継続警報開始・停止 |
| security | CredentialStore、Android Keystore |
| receiver | 時計・タイムゾーン変更への対応 |
| push | Firebase token取得・更新、保存済みMasterIDによるGateway登録、CHECK_MAIL受信のWorkerへの委譲 |

Composeはメール通信・Room・Keystore・ルール判定本体・警報制御本体を直接実装しない。ViewModelは操作と画面状態を調整し、処理本体はRepositoryや専用クラスへ委譲する。AlarmPermissionControlsなどのUIは権限操作のためにControllerの状態を参照する。

監視ステータス通知6003と監視状態通知6004の本文タップは、OPEN_MAIN actionとCLEAR_TOP | SINGLE_TOPでMainActivityを開く。新規起動・既存ActivityのonNewIntentの両方でトップ画面へ戻し、Composeのトップまでpopするためトップ画面を追加しない。サイレント設定や通知のルール一致表示にかかわらず同じ経路。警報通知5001 / 5005は既存OPEN_RECEIVED_MAIL actionとCLEAR_TOP | SINGLE_TOPを維持し、受信メール一覧へ進む。通知タップの再利用経路から監視起動処理を再呼出ししない。

監視の主経路:

FCM / WorkManager保険 → MailCheckWorker → MailCheckCoordinator → MailReceiver内で1通ずつ取得・Room保存・RuleEngine評価 → 全Account完了後のpoll全体候補選定 → AlarmController → Room処理確定 → POP3 / IMAPサーバー削除判定

### FCM token・Gateway登録

MainActivityのprocess内初回起動でFcmRegistrationCoordinatorがFirebaseMessaging.getInstance().tokenを取得する。専用AlertKunFirebaseMessagingService.onNewTokenも同じ登録経路へ委譲する。FCM dataのactionがCHECK_MAILと完全一致する場合のみ、FcmMailCheckDispatcherから既存MonitoringScheduler.runNowへ渡す。onMessageReceived内では通信せず、既存MailCheckWorkerがscheduled Workerと共通のexecutionMutexで1回チェックを直列実行する。未知action・data欠落は無視し、任意dataはログへ出さない。DEBUGログには受信・判定・enqueueの端末時刻とpriorityを記録する。FCMはMonitoring ONの場合だけ実行し、ON化・interval変更・scheduleGeneration変更・予約キャンセルは行わない。手動runNowはMonitoring OFFでも単発確認可能。WorkManagerは15分以上の保険予約を維持する（詳細は7章）。自然Doze時の即時実行保証はない。

Gateway登録はMasterIDが設定された場合に行う。開始前に、乱数clientNonceと確認開始元の秘密値をMasterID・FCM token・間隔・主監視revision・認証情報世代と関連付け、Android Keystoreで暗号化保存する。未完了要求は1件・期限30分とし、バックアップと端末移行から除外する。期限切れ・設定変更・token更新・認証情報変更に対応しない確認は使用しない。

FcmTokenRegistrarは標準HttpsURLConnectionと標準TLS・ホスト名検証を使用し、redirectを追従しない。接続・読取timeoutは各15秒、応答解析は8192文字まで。登録開始はBearerまたはMasterID・MasterPwdで認証する。開始時のBearerが401の場合だけ破棄し、新しい未完了要求で一次認証を1回試す。401以外の障害ではBearerを維持する。確認待ちは登録成功・認証失敗のどちらにも扱わず、旧プロトコルへのfallbackは行わない。

FCMのVERIFY_REGISTRATIONは、ローカル要求のnonce・対象tokenのハッシュ・要求ID・チャレンジ世代と照合する。対応する要求がない通知、古い世代の通知には応答しない。確認処理は専用RegistrationVerificationWorkerへ委譲し、現在のFirebase token・設定revision・認証情報世代・期限を再確認してからHTTPSで応答する。onMessageReceived内ではHTTPSを実行せず、CHECK_MAIL経路より先に分離し、メール確認・監視状態通知・予定表示のFCM基準・警報を変更しない。

確認にはFCMチャレンジと登録開始元だけが持つ秘密値を使用する。成功後のdeviceTokenだけを既存CredentialStoreのfcm_gateway_device_token_v1へ暗号化保存する。通信断・保存失敗は同じ要求と証明を保持して再試行する。逆順の再送通知が新しい証明を置換しない。MasterID・MasterPwd・Bearer・FCM token（一部を含む）・nonce・チャレンジ・確認開始元の秘密値・HTTP本文・例外messageはログやWorkManager入出力へ出さない。

確認通知は通常優先度であり、FCM配送・Worker実行の期限内完了は保証しない。専用Workerは1つのWorkにつき最大8試行・要求期限までとし、その後は次回アプリ起動・token更新・設定変更が次の機会となる。新しい確認通知は期限内の同じ要求のWorkを置換できる。確認不能時も既存の正常な登録とWorkManager保険を維持する。常駐処理・追加権限・専用画面は設けない。同じMasterIDで複数端末を利用でき、端末を譲り受けた利用者は通常のMyDNS.JP設定と自動確認だけで登録できる。旧利用者の承認やMasterPwdは不要。

Gateway / FCMサーバー側実装は非公開で、本リポジトリにはAndroidクライアントだけを含める。情報の取り扱いは[PRIVACY.md](PRIVACY.md)を参照し、サーバーの内部構成は本書に記載しない。

rcvintervalは現在のpollIntervalMinutes（1～1500分）から取得する。MasterID・間隔・監視ON/OFF・主監視revisionを同じDataStore snapshotで監視し、初回・設定変更・token更新・MasterPwd変更で確認を開始する。MasterID空欄は正常skip。監視OFFでも登録を維持するが表示基準を作らない。確認成功時だけ同じrevisionへの登録成功時刻を記録し、再登録も送信基準をリセットする。確認待ち・失敗・古いrevisionで予定表示基準を変更しない。Gateway送信停止・削除APIは追加しない。

設定画面のMyDNS.JPセクションでMasterID／MasterPwdを保存する。MyDnsSettingsRepositoryは既存credential key mydns_master_password_v1を使う。MasterPwdは入力中だけUIのメモリに保持し、成功・画面離脱で空にする。保存済み値をUIへ復号表示せず、存在状態のみ返す。空入力は既存passwordを保持する。新規入力は保存・復号一致を確認してからMasterIDをDataStoreへ書き、失敗時は旧passwordとMasterIDの補償を試みる。MasterIDまたはMasterPwd変更時は、ID公開前に旧deviceTokenを削除する。保存失敗の補償でも旧Bearerは復活させず、次回一次認証へ委ねる。保存と認証・token書込は共通process内Mutexで保護し、保存・補償はNonCancellableだが、異なる保存先を跨ぐprocess停止時の原子性は保証しない。バックアップでKeystore鍵の復元は保証されない。一次認証401はMyDNS設定確認、一時障害はGateway登録失敗として既存画面に簡潔に表示し、Bearer用UIは設けない。

永続観測ログへGATEWAY_AUTH_START / SUCCESS / FAILURE / RETRY、GATEWAY_SET_INTERVAL_START / SUCCESS / FAILUREを追加する。HTTP status、retry number、interval、generation、categoryだけを保存し、既存GATEWAY_INTERVAL_REGISTEREDと予定表示基準の更新条件は維持する。

認証情報変更の無効化は、新しいMasterPwdの書込より先に行う。認証情報世代を暗号化保存・読戻し確認し、旧Bearerを削除してから新しい秘密情報を保存する。これにより、その間のprocess停止でも設定変更前の確認要求を使わない。補償時も旧世代を復活させない。

### アイコン資産

原画はartwork/に保持する。Alert-kun-Master.pngをアプリの基準画像とし、LauncherはこのMasterから生成する。Manifestの@mipmap/ic_launcher / @mipmap/ic_launcher_roundを使用する。Launcherは108dpのforeground/backgroundを持つAdaptive Iconを使用する。foregroundはMasterを96dp相当で中央配置し、本体・目・足を中央66dpの安全領域に収める。背景は白、monochromeはMasterのキャラクターを表す既存の通常ピクト輪郭を余白付きで使用する。再生成はtools/artwork/New-LauncherIcons.ps1で行い、原画を変更しない。Launcherの動的切替は行わない。緑色のAlert-kun-Normal.png、赤い発報中のAlert-kun-RuleMatch.png、Alert-kun-Notify.pngは原画として保持し、表示には使用しない。

通知small iconは白単色・透明背景の24dp VectorDrawable。ic_notification_normalは6003とCHECKING / NORMALの6004、ic_notification_rule_matchは5001 / 5005 / 6002とRULE_MATCH / FAILUREの6004で使用する。6004は表示用selectedAlarmと最終結果のFailureで切り替え、結果選定・警報handoff・永続化に影響しない。ピクト原画の細かなにじみを除き、ルール一致版は小サイズでも分離する6本の光線とする。

## 2. 永続化

### Room

AlertKunDatabaseの現在のschemaはv7。accounts / rules / received_mailsを保存する。Entityとdomain modelはmapperで分離する。enumは安定した名前で保存し、Android定数やCompose色値を保存しない。

| Migration | 追加内容 | 既存データの扱い |
| --- | --- | --- |
| 1 → 2 | rules / received_mailsのbackgroundColor | DEFAULT |
| 2 → 3 | rulesのsoundType | ALARM。既存soundUriを保持 |
| 3 → 4 | accountsのserverDeletePolicy | NEVER。旧deleteAfterReceiveの値から削除を有効にしない |
| 4 → 5 | accountsのtlsValidationMode / certificateFingerprint | STRICT / NULL。旧予約値から信頼を登録しない |

1→7のmigrationを登録し、破壊的fallbackは使用しない。6→7はreceived_mailsへlocallyDeleted（既存行はfalse）を追加し、メール内容と重複防止情報を保持する。schema JSONはapp/schemas以下に置く。AlarmModeはTEXT列にONCE / THREE_TIMES / FIVE_TIMES / UNTIL_STOPPEDを保存する。

POP3 / IMAPの削除候補の解決状態はReceivedMail.serverDeleteResolution（PENDING / DELETED / ABSENT）に保存する。POP3は完全UIDL照合と正常QUIT応答、IMAPはUIDVALIDITY:UID照合と正常EXPUNGE応答に基づいて、削除確定・サーバー不在をRoom transactionで解決し、以後の候補から除外する。UIDVALIDITY変更も保存済みIDを安全に特定できないためABSENTとする。通信・削除command・確定応答の失敗や結果不明では候補を維持する。RECEIVED / PROCESSED / UNSUPPORTEDと独立し、5→6 migrationは既存データをPENDINGとして保持する。SERVER_UIDL_MATCH / DELETE_CANDIDATES_RESOLVEDは件数・理由だけを記録する。

ReceivedMailの重要な情報はid、accountId、remoteMessageId、receivedAtEpochMillis、From、ヘッダー、Subject、必要な本文、bodyFetched、processingState、alertTriggered、matchedRuleId、backgroundColor。UNIQUE(accountId, remoteMessageId)で重複保存を防ぐ。通常受信ではUIDL / UIDだけを先に保存せず、再処理に必要なデータとRECEIVEDを同じ行の挿入で確定する。UNSUPPORTEDは取得不能内容を空値にしてIDとterminal状態を同時に保存する。

### DataStore

軽量設定とMonitoringStatusRepositoryの状態を保存する。確認間隔、連続失敗閾値、監視ON/OFF、起動時確認、言語に加え、確認開始・完了・成功時刻、次回target、scheduleGeneration、schedule種別、連続失敗・異常通知状態などを保持する。

端末内メール削除は既存mailRetentionDays / mail_retention_daysだけを設定値に使い、defaultは7（1週間）。設定画面の「内部からメール削除」は、アカウントの「サーバーからメール削除」と同じServerDeletePolicy・ラベル・7選択肢を再利用する。保存値は-1=即削除、0=削除しない、1/7/10/15=経過日数、30=UTCカレンダー1ヶ月。未知の値は安全側で削除しない。サーバー側のポリシーと保存先・処理を共有せず、監視予約・Gateway登録を変更しない。

LocalMailCleanupはFCM / WorkManager / 手動等の実チェック終了時、既存executionMutex内で呼ぶ。受信・enqueue・Worker skip・接続テストでは呼ばない。チェック開始時の端末ローカル時刻が属する毎時0分の時間帯について、DataStoreのlast_local_mail_cleanup_hourへ試行を先に記録し、同一時間帯の重複をプロセス再起動後も防ぐ。これは設定ではなく実行済み状態。チェックが時間帯を跨いでも開始時の時間帯に帰属する。失敗時もその時間帯は再実行せず、次の時間帯の実チェックへ委ねる。時計逆行時は記録済み時間帯より後になるまで再実行しない。新規スケジューラは追加しない。

期限判定は実行時刻とreceivedAtEpochMillis（初回ローカル受信日時）を比較し、期限ちょうどを含む。1日は24時間、1ヶ月はUTCカレンダー加算。無効・未来日時と未処理RECEIVEDは保護する。Room transactionで期限到達済みPROCESSED / UNSUPPORTEDをlocallyDeleted=trueにし、差出人・headers・件名・本文・本文取得状態・Rule参照・背景色を消す。一覧observeAllはlocallyDeleted=falseだけを返す。accountId / remoteMessageId / 初回受信日時 / 処理済み状態 / サーバー削除解決状態と識別子を保持し、同じメールの再受信・再警報とサーバー削除再試行の破損を防ぐ。alertTriggeredは24時間ヘルス通知の件数集計用に保持する。削除済み最小記録は自動消去しない。削除はサーバーへ接続せず、失敗はLOCAL_MAIL_CLEANUP_FAILUREの例外classだけを記録し、監視の成功・失敗へ伝播しない。

### 認証情報

AndroidKeystoreCredentialStoreはAndroid KeystoreのAES鍵を使用したAES/GCM/NoPaddingでパスワードを暗号化し、IVと暗号文を専用SharedPreferencesへ保存する。RoomにはcredentialKeyだけを置く。パスワードをRoom / DataStore / ログへ平文保存しない。

AccountCredentialSaverはRoomとCredentialStoreが共通トランザクションを持てないため、Accountを一度disabledで保存し、認証情報の保存・読戻しを検証してから最終状態を公開する。失敗時は補償処理を試み、復旧失敗時はdisabledへ退避して失敗を返す。プロセス停止を含めた完全な分散トランザクションではない。

保存済みパスワードはUIへ実値を戻さず固定マスクを表示する。空入力は既存値を保持し、新しい入力がある場合だけ変更する。マスク文字列はeditor値・保存値として使わない。

ManifestはallowBackup=true。現在のbackup / data extraction規則はpending_alarm.xmlと端末内観測ログを除外するが、暗号化認証情報のSharedPreferencesは除外していない。Keystore鍵を含む端末移行・復元の成功は保証しない。復元後は認証情報の再入力が必要になる場合がある。

## 3. メール通信と識別

JakartaMailReceiverはAngus Mailで受信する。POP3S / IMAPSはimplicit TLSで、アカウントのTlsValidationModeを使用する。新規アカウントはSTRICTが標準で、明示保存済みモードは保持する。POP3 / IMAP4は非TLSで、STARTTLSは有効にしない。接続・読取・書込タイムアウトは各30秒であり、Coordinator全体の制限時間ではない。

受信対象はINBOX。READ_ONLYで開き、IMAPの既読変更やexpungeを行わない。接続テストは接続・認証と最小限の確認だけを行い、削除経路を呼ばない。

MailConnectionSettingsで受信用Session・接続テストとPOP3削除専用socketのprotocol・timeout・TLS設定を共通化する。削除socketも受信と同じAngus SocketFetcherを使用する。TLS検証はアカウントの4モードで選択する。

| 保存モード | UI | 検証内容 |
| --- | --- | --- |
| STRICT | 厳密に検証する（推奨） | 標準TrustManagerFactoryのチェーン・信頼済みCA・通常のTLS検証＋leafの有効期限＋SAN/CNと接続先ホスト名 |
| CA_VALIDATED_NO_HOSTNAME（CA_ONLY相当） | ホスト名の不一致を許可する | STRICTと同じCA・チェーン・有効期限検証。ホスト名一致だけを要求しない |
| SELF_SIGNED_ALLOWED | 自己署名証明書を許可する | CA trust不要。自己署名を許可し、有効期限＋ホスト名を検証。fingerprint不要 |
| INSECURE | 検証しない | TLS暗号化のみ。証明書真正性・期限・ホスト名の検証なし |

STRICTとSELF_SIGNED_ALLOWEDはPOP3S/IMAPSともssl.checkserveridentity=trueとAngus標準MailHostnameVerifierで名前を検証する。CA_ONLY相当とINSECUREだけcheckserveridentity=false。STRICTとCA_ONLY相当はValidatedCaTrustManagerでleafの期限と標準CA検証を行う。SELF_SIGNED_ALLOWEDはExpiryValidatedTrustManagerで非空のleafとcheckValidity()を要求し、信頼ストアによるchain検証を行わない。INSECUREだけAngus標準MailSSLSocketFactory("TLS").setTrustAllHosts(true)を接続単位で使用する。ssl.enable=trueを維持し、socketFactory.fallback=falseで自動fallbackを防ぐ。全体のSSLContext・HostnameVerifier・CA信頼設定は変更しない。

TLSモードはRoom v5のTEXT列に保存する。初期値・4→5 migration・未知の保存値はSTRICTで、INSECUREへの自動移行・自動fallbackは行わない。失敗はCA・ホスト名不一致・期限・その他handshakeエラーに分類する。

互換性のため、TEXT保存値PINNED_CERTIFICATEはconverterでSELF_SIGNED_ALLOWEDとして読み、通常アカウント保存時に正規化する。certificateFingerprint列は互換性のため未使用列として残る。domain・UI・TLS判断には使用せず、通常保存ではNULLとする。trustedCertificateIdも互換性のため未使用列として残る。

設定画面のTLS欄はラベルと選択中のモード、4選択肢だけを表示する。常設説明・補足・警告・選択時ダイアログ・証明書承認UIは表示しない。

- POP3の永続IDはUIDL。セッション内のメッセージ番号を保存しない。
- IMAPの永続IDはUIDVALIDITYとUIDを組み合わせた文字列。UIDVALIDITY変更後の別メールを混同しない。
- 同一IDでもAccountが異なれば別メールとして扱う。
- Account単位で保存済みremoteMessageIdを一度取得してSetにし、内容削除済み・RECEIVED・PROCESSED・UNSUPPORTEDをすべて既知とする。取得前に除外し、既知POP3へTOP/RETR、既知IMAPへ本文FETCHを行わない。Room UNIQUE(accountId, remoteMessageId)は最終防御として維持する。
- POP3 / POP3Sは軽量Message参照からUIDLを一括取得し、public POP3Folder.listCommandによるLISTを検証する。UIDL/LISTが不正・欠落なら内容に触れずprotocol failureとする。LISTサイズ16,777,216 bytes以下だけPOP3Message.writeToでRETRする。ENVELOPE・TOP先行走査やdisabletopによる置換は使用しない。
- 16MiB超はTOP/RETRせず、取得していない内容は空値、bodyFetched=false、警報なしのUNSUPPORTEDとしてReceivedMailに永続化する。後続メールへ進み、次回は既知となる。安全上MIME構造を解析不能なメールも同じterminal状態とする。UNSUPPORTEDは復旧Rule評価の対象外で、ServerDeletePolicy・内容retentionの対象となる。
- POP3 rawはアプリ専用cache領域の1通分の一時ファイルへstream出力する。heapへの全量cacheやFolder単位のfile cacheは使わない。解析・保存callback終了後、次の1通の前に削除し、例外・キャンセルでもcleanupする。次回checkで自身の古い一時ファイルを整理する。rawは永続メールデータやbackup対象へ追加しない。
- POP3 / IMAP MIME解析は共通StreamingMailMimeのApache Mime4j core MimeTokenStreamを使用し、DOMを作らない。depth 16、part 256、1 header block 65,536 bytes・100件、total header 262,144 bytes・1,024件、line 8,192 bytes、preamble/epilogue各65,536 bytesの制限を解析途中に適用する。raw 16MiB上限だけに頼らず、巨大header・preamble・多数partをobject tree構築前に停止する。
- IMAPはUIDVALIDITYとUIDを一括解決して既知除外後にUID RFC822.SIZEだけを取得する。BODYSTRUCTURE・ENVELOPE・高レベルcontent APIは使用しない。size >16,777,216 bytesはbody fetchなしでUNSUPPORTED。size不明・欠落・取得拒否の場合はbounded raw取得を許可する。UNSEENは受信済み判定に使わず、READ_ONLYとBODY.PEEKで既読状態を変えない。
- IMAPFolder.doCommandと未改変IMAPProtocol.commandでUID FETCH <uid> (UID BODY.PEEK[]<offset.65536>)を発行し、ImapRawInputStreamが必要時だけ次のchunkを取得する。応答UID・origin・section・payload長を検査し、連続offsetとraw累計16MiBを守る。最終short chunkはEOF、size既知の16MiBちょうどはscalarと累計で終端を確認する。size不明で上限までfull chunkが続けば追加fetchせずUNSUPPORTED。size既知では短すぎるEOF・累計超過をprotocol failureとする。UID/offset/長さ不一致や通信異常もterminal保存せず接続を閉じる。
- POP3 / IMAPとも1Account・1受信attemptにつき1 Store接続・1 Folder openを所有する。RemoteMailへの変換、1通の保存・評価callbackをsession内で終え、Jakarta Messageを外へ渡さない。全Account終了時には接続を閉じ、警報handoff・サーバー削除へ持ち越さない。finallyでcloseし、CancellationExceptionを通常失敗へ変換しない。

### 本文とMIME part選択

IMAPはpartial FETCHで要求した最大取得長を遵守する標準準拠serverを信頼境界とする。返却後のUID・offset・lengthは必ず検査するが、要求を無視する巨大literal宣言では、その検査より前にAngusがbufferを確保しOOMとなり得る。この悪意ある／重大に破損したserverによる残留リスクは防御対象外であり、Angus patch/fork・ByteArray改造・独自response parserを導入しない。OOMをUNSUPPORTEDへ変換して処理継続しない。通常応答は固定chunk内のbufferで処理する。

chunkは64KiB。15MiB loopback測定では64/128/256KiBの要求回数は241/121/61回となり、大きいchunkほどround-tripを減らせる一方、構造上限検出前の先読み量が増える。64KiBで15MiB正常メールと10MiB添付を32MiB heapで処理可能なため、小さい先読み上限を優先する。BODYSTRUCTURE応答を要求せず、多数part rawはMime4jの解析中上限で止める。

永続保存・RuleEngineへ渡す本文は1メール全体で最大16,384 UTF-8 bytes。CTE（base64/quoted-printable等）とcharsetのdecode、HTMLテキスト化、part連結後の最終本文を基準にUnicode code point境界で切る。raw MIMEの16KiB切断ではない。超過は正常なprefix取得であり失敗にしない。bodyFetched=trueはこの取得仕様を完了した意味で、空本文や切り詰め済み本文も含む。RuleEngineはboundedなStringだけを受け取る。

multipart/alternativeはtext/plainを優先し、なければHTMLをテキスト化する。同じalternativeのplain/htmlを二重連結しない。mixed/related等は添付でない本文候補をMIME順に改行で連結し、区切りも総16KiBに含める。attachment disposition、filename付きpart、message/rfc822添付は本文へ含めない。HTMLは固定長のtag scratchとstreaming Readerで処理し、入力全体のStringを生成しない。本文partのbufferは各16KiB以下、POP3 parserのframe数もdepth制限で固定上限を持つ。

専用Mailbox・text/plainは運用上の推奨であり、登録条件ではない。POP3 / IMAPともraw上限は16MiB。正常rawは本文bufferが満杯でもEOFまでstream解析し、添付も通信上通過する場合がある。添付をheapへ全量保持・永続保存・表示・Rule評価しない。
Fromは共通MIME解析内でInternetAddress.toUnicodeString()によりMIME encoded-wordの表示名をデコードし、既存のRemoteMail.from → ReceivedMail.fromへ保存する。RuleEngineへこの文字列を渡し、SENDERも件名・本文と同じcontains(searchText, ignoreCase = true)で比較する。検索対象は既存TEXT列にSENDERを保存し、schema変更・重複フィールド追加は行わない。既存保存行のFromは書き換えない。

通常モードではenabled=trueのAccountだけをAccountMailReceiverが処理する。複数Accountの候補は1回のCoordinator全体で比較する。有効Accountが0件なら実通信・Dummy・Coordinatorを実行せず、正常skipする。MailReceiverSelectionは既存enabled判定だけを使用し、接続先等の新しいvalidationは行わない。

Demo Modeは既存settings DataStoreに追加したdemo_mode_enabled（未保存時false）だけで切り替える。ユーザーが明示的にONにした場合に限り、Accountの有無によらずDummyMailReceiverだけを選択し、実メール接続・network callback・network retryを使わない。Dummyのキューと失敗再現はprocess-local。RECEIVED復旧もDemoは既存Dummy用accountIdだけ、通常モードはそれ以外に分離し、既存行を変換・再保存しない。通常Account UIには、既存Accountを残したまま新規Accountを追加する専用操作が現在ない。

監視開始の入口であるMonitoringSettingsViewModel.updateはexecutionMutex内で最新Demo設定とAccountを読み、通常モードで有効Accountが0件なら設定保存・監視予約より前に拒否する。既存monitoringEnabledを一度ONにすることはない。MainはAccountのFlowを購読し、未設定表示またはデモモード表示を行う。監視中の最後のAccount削除・無効化によってmonitoringEnabledを自動変更しない。

FCM・WorkManager・手動・起動時確認の共通MailCheckWorkerは、既存mutex取得・設定再読込・世代確認の後、network monitor生成とCHECK_STARTより前にアカウントなしを判定する。WORKER_SKIP skipReason=NO_CONFIGURED_MAIL_ACCOUNTをWork ID付きで永続記録しResult.success()を返す。成功・失敗・最終確認・ヘルス通知・受信メール削除には流用しない。既存の起動時からPeriodicへの引継ぎと保険周期の進行だけは維持する。FCM受信観測・6004受信時通知・Gateway・既存周期は変更しない。

## 4. RECEIVED / PROCESSEDと復旧

RECEIVEDは、安定したリモートIDと必要なローカルデータの保存完了。PROCESSEDは、ルール評価、poll全体の警報選定、必要な1件のhandoff、および処理結果の永続化が完了した状態。

MailCheckCoordinatorは各実行で次の順序を守る。

1. Rule一覧を取得する。
2. 既存RECEIVEDを保存済みデータから評価する。
3. Accountごとのremote ID一覧を得て既知を除外し、未知メールを1通ずつ仕様上完全なboundedデータへ変換する。
4. その1通をRECEIVEDとして永続保存し、同じcheck/retryで共有する既知Setへ直ちに追加する。保存失敗では追加しない。重複挿入が拒否されたメールは再評価・再警報しない。
5. 保存した1通の全有効Ruleを1回評価して候補を保持し、次の1通へ進む。巨大な一括保存transactionは作らない。
6. 全候補から最大1件を選び、AlarmControllerへhandoffする。
7. 評価した全メールのPROCESSEDとsnapshotをbatch確定する。
8. 削除ポリシーの対象を確認する。

ProductionのReceivedMailDao.finalizeBatchはRoomの単一トランザクションで、全対象がRECEIVEDであることを確認して更新する。matchedRuleId、backgroundColor、alertTriggered、processingStateを一緒に更新する。非採用・非一致メールも正常なpollではPROCESSEDになる。

handoff失敗時は次点を鳴らさず、batchを未確定のRECEIVEDとして残し、そのpollを失敗とする。削除を実行しない。後続pollで再処理できる。サーバー取得等が候補収集途中で失敗した場合もhandoff・batch確定・削除へ進まない。

PROCESSED済みメールはサーバーに残っていても再警報しない。復旧対象はRECEIVED。ただしhandoff後・Room確定前のプロセス停止では次回再handoffの可能性が残る。外部副作用とRoomを跨ぐexactly-once保証は現在ない。

## 5. RuleEngineとpoll全体priority

RuleEngineはAndroid framework、Room、通信、通知から独立した純粋ロジック。有効Ruleについて検索文字列の部分一致を行い、大文字・小文字を区別しない。正規表現や完全一致の選択機能はない。

| 検索対象 | 判定 |
| --- | --- |
| SUBJECT / 件名 | 件名に検索文字列を含む |
| BODY / 本文 | 本文に検索文字列を含む |
| SUBJECT_AND_BODY / 件名と本文 | 件名または本文に含む。ANDではない |
| SENDER / 送信元 | デコード後のFromに検索文字列を含む |

1メール内の一致Ruleはpriority昇順、同値ならRule id昇順で1件を選ぶ。数値が小さいほど高優先。

新着は仕様上必要なbounded本文を取得・保存してから全Ruleを1回評価する。本文Rule priority 10と件名Rule priority 20が同時一致すれば本文Ruleを採用する。旧bodyFetched=falseのRECEIVEDは保存済みデータだけで復旧し、本文を再取得しない。未取得の本文に対するretroactive一致を保証しない。

MailCheckCoordinatorは各メールの最優先RuleからPendingAlarmCandidateを作る。保持情報はreceivedMailId、accountId、remoteMessageId、matchedRuleId、priority、receivedAtEpochMillis、AlarmEvent。poll全体の選定順は次の通り。

1. priority昇順。
2. receivedAtEpochMillis降順（アプリが保存した日時が新しいメール）。
3. accountId昇順。
4. remoteMessageId昇順。
5. receivedMailId昇順。

候補の列挙順をtie-breakに使用しない。ただしreceivedAtEpochMillis自体は初回保存時の時刻であり、メールヘッダーDateやサーバー上の並びを表さない。

比較範囲は復旧RECEIVEDと今回の新規メール、全有効Accountを含む1回のCoordinator実行。候補0件ならhandoffなし、1件以上なら最大1件。採用メールだけalertTriggered=true。他の一致メールはfalseのまま、各メール自身のmatchedRuleIdと色を保存する。Rule非一致もfalse / null / DEFAULTでPROCESSEDになる。

AlarmEventのsoundType、soundUri、vibrationPattern、alarmModeなどは採用Ruleの値を使い、他候補の設定を混ぜない。AlarmControllerの「新しい警報で古い警報を置換する」方針は別責務として維持する。

## 6. Background color snapshot

RuleのpresetはDEFAULT、DARK、PURPLE、RED、ORANGE、YELLOW、LIME、LIGHT_BLUE。ユーザー表示はデフォルト、黒系、紫系、赤系、オレンジ系、黄色系、黄緑系、水色系。

各メールの処理確定時に、そのメール自身が採用したRuleのbackgroundColorをReceivedMailへsnapshot保存する。poll全体で警報に採用されなかったメールにもその色を保存する。非一致・旧データはDEFAULT。表示時に現在のRuleから色を引き直さないため、Rule変更・削除後も過去メールの色は変わらない。Composeの具体的色・文字色はUI mapperだけで決める。

## 7. FCM主監視とWorkManager保険

FCM = primary monitoring trigger、WorkManager = backup/reconciliation。Gatewayへ送るrcvintervalはユーザー設定のpollIntervalMinutes（1～1500分）。WorkManagerのbackup intervalはmax(pollIntervalMinutes, 15分)で、全設定値にunique PeriodicWorkを使用する。MasterID未設定やFCM未到達では短周期の主監視は働かず、15分以上の保険が残る。

| ユーザー設定 | FCM登録間隔 | WorkManager保険 |
| --- | --- | --- |
| 1～14分 | 設定値 | 15分PeriodicWork |
| 15～1500分 | 設定値 | 設定値PeriodicWork |

onMessageReceivedはCHECK_MAILのみMonitoringScheduler.runNow(context, FCM)へ渡して短時間で返す。手動はMANUAL、通常予約はSCHEDULED_WORK、起動時即時確認はSTARTUPとしてinputDataで識別する。FCMと手動は別unique work名でKEEPし、要求の吸収による成功元の混同を避ける。すべて同じMailCheckWorkerとprocess-local executionMutexを使用し、設定と実行状態はmutex取得後に再読込する。サービス内にメール通信・独自mutexを置かない。

### MailCheck結果の監視状態通知

Cellular制限の案内は読取試行内の表示専用CellularRestrictionEvidenceで判定する。DefaultNetworkMonitorが読取開始・失敗時のdefault network識別子、changeVersion、Cellular単独transport、OSのblocked callbackまたはrestrictBackgroundStatus=ENABLEDを照合する。同じ試行の型で分類できる通信例外と、Coordinatorが返した同一例外による最終失敗だけを対象にする。ネットワーク切替・喪失・不明・VPN・Wi-Fi・認証/TLS/ローカルエラー・正常終了では案内を出さない。結果をMonitor破棄前にBooleanへ縮約し、MailCheck完了点からFailure表示へ渡す。DemoにはMonitorを生成せず、ログファイルを検索しない。表示情報をreadiness・retry・失敗回数・監視制御へ流用しない。

利用者向け名称は6004が「監視状態」/「Monitoring state」、日次6003が「監視ステータス」/「Monitoring status」。内部のMonitoringHealth系識別子は維持し、channel ID・notification ID・判定処理を表示名整理のために変更しない。

監視状態通知は全Account・限定retryを含むMailCheck全体の最終結果を表示する。FCM / WorkManager保険 / 手動 / 起動時確認は共通MailCheckWorker完了点のMailCheckCompletionHandlerを通り、既存MonitoringStatusRepositoryだけが連続失敗countを保存する。SUCCESSでcountを0へresetし、非一致ならNORMAL、成功結果selectedAlarmに採用AlarmEventがあればRULE_MATCHとする。FAILUREでは1pollにつきcountを1増加し、FAILUREを表示する。設定consecutiveFailureThreshold（1～99）未満ではcount/threshold、到達後は現在の連続失敗countを表示する。既存失敗通知6002の到達時発報・再通知抑制は変更しない。途中retry・Account単位では結果を確定せず、safe skipでは成功・失敗記録も最終結果表示も更新しない。結果の表示日時はMailCheck完了時刻とする。通知失敗で結果やcountを再計上しない。

RULE_MATCHは既存alarmのtitle / subject / detailsリソースを再利用し、同じ内容の表示済み警報通知があればfallback案内も含めて本文を参照する。AlarmControllerを再呼出しせず、警報・Rule選定・Room確定・削除・skip条件を変更しない。通知失敗は監視結果へ伝播させない。監視OFFでは既存cancel、古いgenerationでは更新拒否を維持する。

MONITORING_NOTIFICATION_POST / UPDATE等へresultState（CHECKING / NORMAL / RULE_MATCH / FAILURE）、notificationPhase（RECEIPT / CHECK_RESULT）、Work IDを記録する。件名・差出人・ルール名・本文は通知表示だけに使用し、永続観測ログへ保存しない。6004へ停止action・FGS・警報出力をコピーしない。

監視ONのCHECK_MAIL受信時にMonitoringStatusNotifierが専用channel alertkun_monitoring_status（表示名「監視状態」/「Monitoring state」、初期IMPORTANCE_DEFAULT）・固定notification ID 6004で日時とCHECKING（メール確認中）をpost / updateし、その後に既存dispatch / enqueueへ進む。受信だけで「異常なし」と表示せず、成功記録・failure count・予定・skip条件を変更しない。最終結果で同じIDを置換する。警報5001/5005、失敗6002、日次6003のchannel / IDと分離する。OnlyAlertOnceで既存通知更新時の音・振動・heads-up再通知を抑制し、通常画面へ設定を追加しない。

現在channelのimportance / sound有無 / shouldVibrateを読み取る。MIN / LOWはサイレント相当、DEFAULTはsoundなし・振動なしの場合だけサイレント相当とし、不明・HIGH以上は案内文を表示する。DNDや端末音量による一時的無音をchannel設定済みと判断しない。既存channelのユーザー設定は上書きしない。

設定とgenerationを同じDataStore snapshotから読み、FCM callback内の通知用読取だけ最大1秒に制限する。専用process内lockで通知post / cancelだけを直列化し、executionMutexへ影響しない。監視OFFのstop処理でこのIDをcancelし、OFF中のFCMもpostせずcancelする。通知権限拒否・channel無効・読取／通知失敗は記録して既存dispatchを継続する。WakeLock・FGS・AlarmManager・予約・通信は追加しない。

永続ログはMONITORING_NOTIFICATION_POST / UPDATE / CANCEL / CHANNEL_STATE / BLOCKED / CANCEL_FAILEDを記録し、ID・channel・importance・silent・sound有無・振動有無・action・trigger・generation・FCM messageIdHashで受信とenqueueへ対応付ける。FCM_RECEIVEDのpriority / originalPriority / sentTimeは維持する。可視通知によるHIGH維持やdeep Doze中の到達は保証しない。

### 端末内永続観測ログ

Debug / Releaseとも、既存処理のFCM受信・enqueue・Worker/mutex・skip判定値・チェック結果・アカウント別接続・network stabilization・監視世代変更・Gateway登録成功をfilesDir/observabilityへ記録する。1イベント1行、端末の現在timezone offset付きISO8601日時とevent / key=value形式。端末ローカル日付のmonitoring-observation-yyyy-MM-dd.logへ追記し、同日8MiB超の場合は番号付きファイルへ分割する。同日でも時計がファイル先頭より前へ戻った場合は別partへ書く。保持判定はcleanup時点から15×24時間で、境界時刻ちょうどの行は保持し、それより古い行を削除する。境界日のファイルは有効行を残して整理する。cleanupは初期化・日付変更・前回から1時間以上経過後の既存書き込み・容量超過が見込まれる追記前に行う。期限直後の即時削除や無書込期間中の削除のための専用スケジューラは追加しない。期限整理後も総量64MiBを超える場合は古いファイルから削除し、追記対象は削除しない。一時整理ファイルの書込分も上限へ算入し、削除不能等で容量を確保できなければ追加ログを保存せず監視を継続する。旧形式ファイルも期限・容量条件までは保持し、回収はディレクトリ全体を対象にする。通常UI・権限・WakeLock・FGS・予約・通信は追加せず、監視条件へ使用しない。

DefaultNetworkMonitorの既存callback登録は実check内だけ、終了・キャンセル時に解除する。AVAILABLE / LOST / CAPABILITIES_CHANGED / LINK_PROPERTIES_CHANGED / BLOCKED_STATUS_CHANGEDをNETWORK_CALLBACKとしてWork IDとともに記録する。Network handle、transport、INTERNET / VALIDATED、metered、blocked、active networkとの一致とmonitor内の追跡対象との一致を含む。LOSTでは最大8 Networkの観測専用キャッシュから直近capabilityを出し、capabilitySourceでCURRENT / LAST_KNOWN / UNKNOWNを区別する。このキャッシュをDefaultNetworkState、安定化、retryへ渡さない。LinkProperties自体・SSID・BSSID・IP・DNSアドレスを出力しない。既存NETWORK_BLOCKED_STATUSも維持する。check間やprocess停止中のnetwork変化を記録する常時callbackは追加しない。

小さい追記をprocess内で直列化し、イベントごとにcloseする。未書込キューやイベントごとのfsyncは使用しない。process再起動・rebootでファイルを初期化しないが、電源断中の最後の書込・ストレージ障害時の保存は保証しない。保存失敗は監視へ伝播させない。観測ディレクトリだけをbackup / device transferから除外する。

Work IDをWorker・チェック・接続・networkイベントへ渡す。FCM message ID / collapse keyはSHA-256のみ保存し、enqueue要求Work IDと対応させる。KEEP / UPDATE後はread-onlyの非同期snapshotで残存Work IDとstateを記録する。snapshotは後続処理と競合し得るため取得時刻を併記し、プロセス終了時には未記録になり得る。enqueue要求のgenerationはprocess内で最後に観測した値（未観測ならnull）、FCM受信後snapshotとWorkerのmutex内読取で現在世代を記録する。未知actionはIGNOREDに固定し、任意payload・token・接続先・username・メール内容・例外messageは保存しない。

debug buildはadb exec-out run-as direct.kabu.alertkun tar -cf - files/observabilityでroot不要の回収が可能。Releaseにも記録するが、非debuggable buildのrun-as回収はできない。

通常設定画面に観測ログ保存UIは設けない。内部のObservationLogExporterは診断用の非公開実装として維持するが、画面・ViewModelからの呼出経路はない。

exporterはDispatchers.IOでfilesDir/observability直下の通常ログだけを扱う。日付付き・同日分割・既存の旧形式（monitoring-observation.log、monitoring-observation.1～3.log / monitoring-observation.log.1～3）を許可し、作業ファイル・子ディレクトリ・シンボリックリンク・その他のprivate fileを除外する。全対象をNOFOLLOW_LINKSの読み取り専用descriptorで開き、各ファイルの取得時点の長さを固定した後、16KiB bufferでZipOutputStreamへ元の名前・bytesのままコピーする。snapshot合計は64MiBまで。取得後の追記は含めず、保持整理によるrename・置換・削除後も開いた元descriptorから読む。全ファイルで同一瞬間のsnapshotや行単位の完結は保証しない。取得途中の消失・置換、短い読み取り、出力open/write/close失敗は成功としない。対象0件では出力streamを開かないが、SAFが作成した空ファイルが保存先に残る場合がある。途中失敗はFAILUREとして呼出元へ返す。

書き出しはlogger・executionMutexを取得せず、原本の削除・移動・切り詰め、ログ初期化・整理、観測イベント追加、監視設定・count・予約・認証情報の読書きを行わない。保存先以外へのアップロード・共有、追加のストレージ権限、FileProviderを追加しない。

実チェックの既存DefaultNetworkMonitorへWork ID付きObservationSinkを渡し、CHECK_BEGIN / CHECK_ENDのDEVICE_NETWORK_STATEへprocessId、elapsedRealtime、interactive、deviceIdle、powerSave、batteryOptimizationExempt、restrictBackgroundStatus（ConnectivityManagerの数値：1=DISABLED、2=WHITELISTED、3=ENABLED）、metered、Wi-Fi / CELLULAR / VPNを記録する。restrictBackgroundStatusはnetpolicyのpolicy bit値とは別の値である。読取不能はnull。API 29以降の既存callbackのonBlockedStatusChangedはNETWORK_BLOCKED_STATUSへ記録する。blockedは可否の観測値であり原因・policy変更元を示さない。callback中に同期問い合わせを追加せず、network readiness・retry・skip・予約・通信・警報・失敗判定を変更しない。network callbackの登録は実チェック中だけに限定する。

AlertKunApplication.onCreateはApplication contextへACTION_RESTRICT_BACKGROUND_CHANGEDの動的receiverを1回だけ登録する。RECEIVER_NOT_EXPORTEDでsystemから受信し、Intent extrasを参照・保存しない。登録後のprocess起動時はobservationPhase=PROCESS_START、対象イベント受信時はRESTRICT_BACKGROUND_CHANGEDとして同じDEVICE_NETWORK_STATEを既存の永続ログへ記録する。processIdで起動と後続イベントを対応付け、アプリ全体のイベントへWork IDを作らない。登録失敗は例外class名だけを記録し、process起動時のsnapshotは保存を試みる。取得・登録・ログ保存の失敗はアプリ起動や監視へ伝播させない。receiverはprocess生存中だけの受動観測であり、process維持、常駐Service、WakeLock、周期取得、通信、Work enqueue、監視状態や設定変更を追加しない。

PC側の`tools/diagnostics/Observe-NetworkPolicy.ps1`は対象packageのUIDを毎session解決し、既定60秒間隔の時刻付きsample、関連タグの連続logcat（epoch）、UIDのnetpolicy状態・変更履歴、画面・回線・Doze・省電力状態、既存アプリログを別sessionへ保存する。初回正常取得はPOLICY_BASELINE、値の変化はPOLICY_CHANGE、OSの新しいpolicy履歴はPOLICY_HISTORY_CHANGEとして記録する。policy変化時に直前正常sampleへの参照、変化検知sample、次sampleへの参照と追加dumpを保存する。詳細dumpは5分以上の間隔に制限する。変化時刻は前回正常sampleの取得開始と今回sampleの取得終了の間であり、OS履歴に時刻が残ればそれを照合する。短時間で元に戻る変化はsampleだけでは検知できないため連続logcat・OS履歴も残す。PC接続・実行中だけ有効で、接続断・取得失敗・logcat再接続・停止を記録する。

PC observerは端末ごとの排他lease、PAUSE / STOP制御と24時間の既定終了・256MiBのsession保存上限を持つ。logcatは10MiBを超えたら別fileへ切り替え、buffer末尾2000行を重複回収する。既存fileを消さず、停止・断線中の欠落を隠さない。ネットワーク設定・画面・idle・省電力・policy・logcat buffer/verbosityを変更せず、WakeLockも取得しない。人間へ端末を渡す前は`Control-NetworkPolicyObserver.ps1 -Action Pause`でADB/logcat停止のacknowledgementを待つ。完全に終了したsessionはResumeでは再開せず、新sessionで開始する。

DynamicDenylistManagerは候補であり端末での実行を断定しない。PC側は変化時にSettings providerのdumpからManualDenylist / DynamicDenylistと対象packageを確認し、PRESENT / ABSENT / UNKNOWNを記録する。両sectionが読めない・timeout・permission denialの場合はUNKNOWN。ON表示やpolicy値だけからmanual/dynamicを決めない。公開AOSPのdumpメソッドがあるだけではPixelの公開取得経路がある証明にならない。取得できない場合は再発時のbugreportとSettings側の追加計測で確認する。

PC側で非表示の別processとして開始する場合は`tools/diagnostics/Start-NetworkPolicyObserver.ps1 -Hours 24`を使用し、表示されたSessionとそのtimeline.jsonlで継続取得を確認する。`Control-NetworkPolicyObserver.ps1 -Session <Sessionの絶対path> -Action Pause / Resume / Stop`で制御する。Pause / Stopは収集側の停止応答を確認してから戻る。終了したsessionは再開できず、Startで新sessionを作成する。parser・取得失敗・一時的なpolicy履歴・denylist判別の確認は`Test-NetworkPolicyObserver.ps1`で行う。

### 成功記録とbackup skip

DataStoreに最後の正常成功時刻、MailCheckTrigger、成功時のscheduleGeneration、最終試行成功flagを同じeditで保存する。試行開始時に成功flagをfalseにして、同じミリ秒でのキャンセル・process終了でも過去の成功を流用しない。正常な新着0件も成功。受信のみ、offline、通信・認証・TLS失敗、例外、キャンセルはFCM成功にしない。後続失敗や未完了checkがあれば古い成功をskipの根拠にしない。旧データにtrigger・backup intervalがない場合や未知値は安全に実行側へ倒す。

保険のnext targetを永続化し、そのtargetを基準にbackup interval単位の周期を定める。window startは今回のtarget minus backup interval。Workerが1周期以上遅れた場合はtargetを整数周期だけ進めて現在の区間を求める。遅延中のFCM成功もWorker判定時刻まで含めるが、未来の成功時刻は受け入れない。「now minus 15分」だけのsliding windowは使わない。

scheduled WorkerはMonitoring ON、世代一致、周期metadata一致、最後の正常成功が同世代のFCM、成功時刻がこの周期内、かつその後に失敗・未完了checkがない場合だけメール接続をskipする。DEBUGへscheduled backup skippedを記録し、次の保険targetだけ進める。skipは成功記録や連続失敗countを変更しない。最後の成功がMANUAL・STARTUP・SCHEDULED_WORK、FCM成功が古い、または不明・不整合なら保険チェックを実行する。

FCMが先にmutexを取得して成功した場合、待機中のscheduled Workerは最新成功を読んでskipする。逆順で保険チェックが先に成功した場合、ユーザー設定間隔内の同世代SCHEDULED_WORK正常成功があるFCMは接続を省く。これはFCM成功には記録せず、MANUALには適用しない。失敗・キャンセル済みbackupはFCMを抑制しない。

### 予約・世代・起動時調整

FCMと手動runNowはON/OFF、interval、scheduleGeneration、保険targetや予約を変更しない。Monitoring OFFではFCM・scheduled・startupは実行しない。明示的な手動runNowはOFFでも単発確認できる。

設定保存、起動時rebase、欠落・不整合修復、大きな時計補正で世代を更新する。チェック開始・成功・失敗・次targetの書戻しはDataStore edit内で世代を再照合し、古いWorkerは新しい状態へ書き戻さない。保険予約は世代・周期tagも照合し、正常なfuture scheduleを維持する。FCM Gatewayの設定変更時登録は既存経路を維持する。

OneTimeWorkはFCM・手動・startupの単発チェック用途にだけ使う。互換用のshort work名とONE_TIME enum値は既存予約の識別・取消にだけ使用し、起動時reconcileまたは設定保存で該当予約を取消してPeriodicWork保険を登録する。

StartupReconcileGateはprocess内1回を維持する。監視ONでcheckImmediatelyOnAppStart=trueなら即時STARTUPチェック後、開始時刻からbackup intervalのtargetを登録する。falseなら正常future backup予約を維持し、期限切れ・欠落・周期不整合のみcheckなしでrebaseする。初回targetは既存の端末local timezone分境界丸めを用い、以後は保険targetを周期単位で進める。FCMの成功はこの基準を動かさない。

ACTION_TIME_CHANGEDはwall clockとelapsedRealtimeの差を使い、基準があり60秒以上の補正ならrebaseする。timezone変更は表示のみ。独自BOOT_COMPLETED reconcileは未実装で、WorkManager自身の永続work管理に依存する。

常駐MonitoringService、dataSync FGS、監視用AlarmManager、Expedited Work、独自長時間WakeLock、長時間polling loopは使用しない。NetworkType.CONNECTED constraintでoffline失敗を隠さない。FCMもPeriodicWorkもAndroidの実行時刻や自然Doze中の即時実行を保証しない。

### デフォルトネットワーク切替への猶予

実チェックのexecutionMutex内でDefaultNetworkMonitorを1個だけ登録し、終了・失敗・キャンセル時に解除する。registerDefaultNetworkCallbackのonAvailable / onLostでdefault networkの変更世代を追跡し、onCapabilitiesChangedの引数からINTERNET / VALIDATEDを更新する。callback内では同期問い合わせや通信を行わない。Wi-Fi・モバイル・VPNなどtransportで判定を分けない。

NetworkStabilizationは1チェック全体でcallbackによる安定化待ちとretry間隔を合わせて合計最大8秒だけ待つ。既存の各接続・読み書きtimeout（30秒）より短く、manualにも同じ上限を適用する。利用可能になれば即再開する。capabilityは補助情報であり、猶予後にdefault networkが存在すれば実接続結果を最終判定とする。存在しなければDEVICE_OFFLINEで通常の失敗処理へ進む。

読み取り通信でsocket・EOF・DNS・接続timeoutなど型で判定できる一時的ネットワーク例外が発生した場合、default network変更の有無によらず同じ待機予算内で1チェック全体に最大2回再試行する（読み取り通信は初回を含め最大3試行）。retry間隔は3秒・5秒を基本とし、共有予算の残りまで短縮する。残り予算が3秒未満なら即retryせず失敗を返す。変更の有無は観測情報として残し、retry判定・開始・終了に現在のnetwork有無・INTERNET・VALIDATED・attempt / maxAttempts・retry回数・待機時間と結果を記録する。一般的なIOExceptionだけではretryしない。認証・TLS・証明書・protocolエラーは対象外。Coordinator全体、Room確定、Rule評価、警報handoff、POP3削除は再実行しない。DELE / QUITの結果が不明な削除は即失敗とし、通常の次回チェックへ委ねる。UID/UIDL重複防止を維持する。

FCM・保険・手動・起動時は同じMailReceiver wrapperを利用する。Workerは最終結果だけを成功時刻・failure count・health履歴へ反映し、復旧した途中のエラーは通知しない。通信が3試行とも失敗してもメール確認の失敗は1回だけ記録する。OFF・generation・backup skip判定は登録前のまま維持する。長時間待機・無限retry・監視FGS・WakeLockは追加しない。

Angus Mail 2.0.4のPOP3Store.protocolConnectは一部EOFExceptionをcauseなしのAuthenticationFailedExceptionへ変換する。認証拒否との確実な区別ができないため、文言だけで通信例外へ読み替えず再試行対象外とする。この場合は通常の失敗として記録され得る。

### Android metered background restriction

CELLULAR上でFCMは正常受信しWorkerも起動するのに、受信接続（operation=RECEIVE）のMAIL_CONNECT_STARTからMAIL_CONNECT_SUCCESSまでのDNS解決で失敗する場合は、UIDの `REJECT_METERED_BACKGROUND` / `METERED_USER_RESTRICTED` を確認すること。

設定画面がONでもUIDの `REJECT_METERED_BACKGROUND` が存在する場合がある。「バックグラウンドデータ」を OFF → ONにした後も、実policy解除・その維持とCELLULAR / 画面OFFでの自然FCM確認成功を別々に確認する。表示・単発成功だけで解決と判断しない。

なお「モバイルデータの無制限利用」はData Saver例外設定であり、このpolicyとは別物。

## 8. 失敗管理と表示

### 1日1回の監視ステータス通知

正常なmail checkを永続成功として記録した直後、既存executionMutex内でMonitoringHealthReporterを呼ぶ。FCM / SCHEDULED_WORK / MANUAL / STARTUPの正常成功が対象で、保険skip・OFF skip・世代不一致skipでは呼ばない。AlarmControllerと独立し、新しい定期Worker・AlarmManagerは追加しない。

SettingsRepositoryのhealthNotificationEnabledは初期値true。監視設定の「監視を有効にする」直下にある「異常がなくても一日一回通知」チェックボックスで選び、他の監視項目と同じ保存操作で原子的に保存する。画面上のMonitoring OFFでは操作できないがチェック状態を保持する。保存時は既存executionMutexで通知の判定・投稿と排他し、通知設定OFFなら既存の監視ステータス通知だけを取り消す。Monitoring OFFでも通知しない。初回正常checkから24時間の観測期間を確保し、通知設定またはMonitoringをOFFにした場合は観測開始を解除する。再ON後も初回正常checkから24時間待つ。通常のprocess再起動・世代変更は観測開始と前回通知時刻を保持する。未完了checkの痕跡がある場合は観測期間を再開始し、知らない期間を正常と扱わない。

DataStoreへ観測開始、前回通知時刻、最後のmail check失敗時刻を保存する。正常判定にはMonitoring ON、設定ON、最新試行の正常成功、連続失敗0、観測開始から24時間以上、過去24時間に失敗なしを要求する。失敗時刻は成功後も消さず、offline・通信/認証/TLS・削除失敗・その他例外を既存recordFailureと同時に記録する。実チェック中のキャンセルはヘルス履歴だけを不明/中断扱いにし、既存failure countや異常通知仕様は変更しない。実チェックが世代変更中に失敗した場合もヘルス履歴の事実は残すが、旧世代から監視状態・成功・count・保険targetは書き戻さない。skipは失敗履歴を作らない。

異常時の日次通知は出さず、既存の閾値に基づくfailure notificationへ任せる。異常検出件数が1件以上でもmail checkが正常ならヘルスは正常。件数はRoomのPROCESSEDかつalertTriggered=trueの行をreceivedAtEpochMillisによる通知時点の過去24時間（両端を含む）でCOUNTする。単なる新着件数や非採用の一致メールは数えない。既存列は初回保存日時であり、過去に保存したRECEIVEDを後日復旧した警報の実発生時刻を数えるものではない。

最終メール確認は既存lastSuccessfulCheckAtEpochMillis。端末local timezoneのyyyy/MM/dd HH:mmで日付も表示し、backup skipを成功時刻へ流用しない。専用channel alertkun_monitoring_healthはIMPORTANCE_LOW、音/振動なし、badgeなし、full-screen intentなし。固定notification ID 6003で置換する。既存警報ID 5001/5005、監視失敗ID 6002とは分離する。

通知判定はMonitoringHealthNotificationPolicyの純粋ロジックでepoch millisの24時間（86,400,000ms）を使用する。前回通知から24時間以上経過した最初の正常checkが対象。timezone変更は判定へ影響しない。時計の巻戻りや未来の通知/失敗時刻は通知を抑止する。DataStore editで最新設定・世代・成功時刻を再照合し、前回通知時刻をnotifyより先に永続claimして競合と再起動での二重通知を防ぐ。claim後のprocess停止や通知API失敗ではその日の通知を欠く場合があるが、重複回避を優先する。権限/channelが無効ならclaimしない。ヘルス集計・通知の例外はmail check成功を失敗へ変更しない。

MonitoringFailureKindでDEVICE_OFFLINEとSERVER_UNAVAILABLE、DNS、timeout、認証、TLS・証明書、protocolエラー等を区別する。連続失敗閾値は1～99、default 3。端末offlineも失敗countへ含める。新着0通を含む正常なcheckでcountと異常通知状態をresetする。

一時的ネットワーク障害に対する限定retry後も失敗した場合やretry対象外の失敗では、Workerの即時retry queueを作らず、次の通常pollで再確認する。キャンセルは伝播させる。監視異常通知は設定した連続失敗回数と、その回の失敗日時を改行して表示する。本文日時は端末local timezoneのyyyy/MM/dd HH:mm:ss、BigTextStyleで複数行を表示する。内部の失敗分類は維持する。認証・TLS等の詳細分類は接続テストUIで表示する。メイン画面に全分類や失敗countを表示する実装はない。

メイン画面の「最終確認」は日次監視ステータス通知と同じlastSuccessfulCheckAtEpochMillisを参照する。FCM・SCHEDULED_WORK・MANUAL・STARTUPの実チェック全体が正常完了した時刻だけを保存し、削除失敗・backup skip・旧世代skipでは更新しない。明示的な監視OFF時の手動確認成功は更新する。

「次回確認」はFCM主監視によるおおよその予定であり確定実行予約ではない。現在の主監視設定に有効な直近CHECK_MAILの端末受信時刻＋ユーザー確認間隔を優先し、未受信なら現在設定のGateway登録／設定成功時刻＋間隔を使用する。local timezoneのyyyy/MM/dd HH:mm頃を表示し、秒を省く。監視OFFは「なし」、有効な基準が両方ない場合だけ「未定」。最終確認・WorkManagerの保険targetは算出に使わない。

主監視revision（fcm_schedule_revision）はON/OFF・間隔・MasterID変更と同じDataStore editで更新する。既存scheduleGenerationは保険予約の起動時rebase・時計補正でも変わるため、その変更で主監視の時刻を無効化しないよう主監視revisionを分離する。FCM時刻とGateway成功時刻にはそれぞれrevisionを付けて保存し、現在revisionと一致する値だけFlowに公開する。revisionのない旧履歴は無効。設定変更前に受信して非同期保存を待っていたFCMは設定変更時刻との照合で排除する。UIへ基準の間隔も渡し、別Flowとの更新順序により旧基準＋新間隔を一時表示しない。

Gateway成功はHTTP 2xxかつJSON status=successを確認した完了時刻だけを保存する。通信中に設定が変わった応答はDataStore edit内でもrevision照合して排除する。失敗・skip・要求送信時には保存せず、同じ設定の以前の成功を失敗時刻で上書きしない。新設定が未成功なら以前の設定の基準は公開しない。予定時刻自体は保存せず、基準＋間隔から導出する。既存monitoring_next_scheduledは保険の内部状態として維持する。

MonitoringStatusRepositoryのFlowをMainのStateFlowで購読し、新FCM・Gateway成功・設定変更を画面再オープンなしで反映する。期限を過ぎても同じ日時を保持し、時計だけの周期繰り上げ・毎秒／毎分タイマー・待機／遅延／リトライ表示を追加しない。FCM未達そのものはMainで異常扱いせず、既存の保険による実接続と連続失敗通知が異常認識を担当する。表示状態は実行mutex・保険skip・警報・メール処理へ影響しない。

現在のGatewayの外部仕様では、同じtoken・間隔での間隔登録も成功時に送信基準をリセットする。したがってtoken再登録・アプリ起動時再登録でも、端末は成功応答確認時刻を新しい初期基準とし、その時刻以前のFCM基準を解除する。応答成功後に受信したFCMが先に保存された場合は保持し、古い受信の遅延保存は拒否する。失敗では現在設定の既存正常基準を維持する。将来APIがtoken同期のみへ変わる場合は、外部仕様を再確認してこの処理も変更する。

設定変更後、Gatewayが現在revisionの設定成功を返すまで、新たなFCMから表示基準は作らない。CHECK_MAIL payloadには間隔・revisionがないため、設定変更失敗後に旧Gatewayスケジュールで届いたFCMを新間隔の成功証明にしないためである。メール確認のhandoffは行い、表示は未定に保つ。

ReceivedMail一覧は件名、差出人、初回受信日時、snapshot色を表示する。カードのタップでreceived_mail/{mailId}へ進み、件名・送信元・初回受信日時・保存済みbodyを表示する。詳細は既存ReceivedMailViewModelのRoom observeAllフローからIDで選び、locallyDeletedの除外と表示中の削除に追従する。未取得・空本文は代替文、対象なしはメールなし、読込失敗は既存エラー文を表示する。本文はCompose Textで改行を保持して縦スクロールし、再接続・HTMLレンダリングは行わない。戻る操作は一覧へ戻る。matchedRuleId・alertTriggeredの表示UIはない。

## 9. 警報・有限回数

UI名は「アラーム回数」。domain / 保存列名は既存のAlarmMode / alarmModeを維持する。

| AlarmMode | 音・振動 | 終了 |
| --- | --- | --- |
| ONCE | 1サイクル | 自動終了 |
| THREE_TIMES | 3サイクル | 自動終了 |
| FIVE_TIMES | 5サイクル | 自動終了 |
| UNTIL_STOPPED | 継続再生・repeat振動 | Stop |

有限回数は既存AlarmPlaybackServiceのmediaPlayback FGS内でFiniteAlarmSequenceの回数を数える。API37以上のALARMはexact alarm・通知が許可されている場合、AlarmStartReceiverを経由して開始する。それ以外は直接Service開始を維持する。PendingAlarmStoreの既存AlarmEvent.alarmModeで回数を渡し、各サイクルでMediaPlayerを新規作成する。isLooping=false、完了listener・音源長timerでreleaseし、次サイクルを明示的に開始する。エラー時は残りのサイクルを破棄して終了する。

autoLoop metadataにより完了listenerが来ない音源への対策として、各サイクルにMediaPlayer.durationのtimerを併用してreleaseする。音源に複数フレーズが入っていても音源全体で1サイクル。音と振動を同時に開始し、次サイクルは振動1サイクルも完了するまで待つ。FGS開始後にAudio Focusを取得し、拒否時は音を開始せず有限回の振動だけを行う。完了・エラー・Stopでplayer、timer、振動、Focus、foreground、Serviceを解除する。有限ServiceはSTART_NOT_STICKYで、null Intentによる有限handoff再開は拒否する。プロセス終了・OS制限による完遂保証はない。

通知channelは警報音・振動なし。実音はMediaPlayer、振動はVibratorで行う。有限警報の通知は通常Stop actionなしで残り、音・振動だけが自動終了する。新警報・Stopでは古いplayer、timer、振動、残回数を破棄する。

## 10. UNTIL_STOPPED・exact alarm・FGS

UNTIL_STOPPEDおよびAPI37以上の有限ALARMの自動開始経路は、許可条件を満たす場合にAlarmController → AlarmManager exact alarm → AlarmStartReceiver → AlarmPlaybackServiceとなる。exact alarmは警報開始用だけで、監視scheduleには使用しない。実行中Serviceへの置換は既存の再利用方針を維持し、不要なexact alarm再予約を行わない。AlarmPlaybackServiceは鳴動中だけのmediaPlayback FGSであり、監視や受信を担当しない。

API37以上の有限ALARMの入口は画面OFF時の実機互換対策である。公式仕様ではexact alarm権限とUSAGE_ALARMの組合せがWIU条件の免除対象だが、Pixel 7a CP3A.261005.002.A1では直接FGSからの有限ALARMがmuteされ、exact alarm入口のUNTIL_STOPPEDは継続再生した。OSの不具合とは断定せず、通知・着信用途やAPI26～36へこの対策を広げない。ALARM_EXACT_SCHEDULED / ALARM_EXACT_DELIVEREDで入口を識別し、メール内容は記録しない。

SCHEDULE_EXACT_ALARM、通知許可・channel有効状態等を確認する。exact alarm権限がない場合、UNTIL_STOPPEDは説明通知と1サイクルの振動へ退避する。有限警報は通知が許可されていれば直接Service起動を維持し、exact alarm権限不足だけでは通知fallbackへ切り替えない。通知不許可やFGS開始失敗では直接MediaPlayerへ戻さず、説明通知と有限回の振動へ退避する。有限警報は指定回数、継続警報は1サイクルの振動を行い、通知の「警報を開始」からユーザー操作で同じモードのService開始を再試行できる。継続開始時のAudio Focus取得が拒否された場合は音を開始せず、振動とServiceは継続する。Workerはhandoff後に終了する。有限回の直接起動もAndroidの背景FGS開始制限を受け、HIGH FCMの一時許可などが終了していれば拒否され得る。

PendingAlarmStoreはtokenと最新AlarmEventを保持し、古いtokenの開始を拒否する。新警報で既存出力を置換し、queueを作らない。Stop actionはpending exact alarm、pending情報、音、振動、通知、Serviceを解除する。Serviceは自身の起動・foreground管理を担当し、実出力は同じAlarmControllerへ委譲する。プロセス・端末再起動後の無条件な継続再開は保証しない。

AlarmController.sessionはServiceのactivateで公開するStateFlow<AlarmSession?>。メールID、Rule ID、開始時刻を保持し、Main画面はactive時だけ監視状態の上に大きな「停止」ボタンを表示する。通知Stop、Main UI、確認ツールは共通AlarmController.stop()へ到達する。Service破棄でlive stateと出力を解除し、START_STICKYで再生成されたServiceは既存handoffからactivateする。保存済みhandoffだけをUI上のactive証明にはしない。Activity起動・再生成・Composeは状態購読だけで警報を開始しない。

同じメールID・Rule IDのUNTIL_STOPPED再handoffはpending/active中に無視する。別メールの警報は現在の出力を解放して置換し、実行中Serviceを再利用する。FCM・Workerにはセッション制御を置かない。通知は既存HIGH channelのongoing + Stop action。Android 14以降はongoingでもスワイプ可能な場合があるため、deleteIntentでactive時だけ通知を復元する。音・振動は再startしない。通知許可取消などで復元不能でもMain画面のStopを残す。

FGS内のMediaPlayerは有限・継続ともsetWakeMode(PARTIAL_WAKE_LOCK)を使用する。playerの停止・解放に連動し、画面を点灯させず、監視WorkerにWakeLockを追加しない。継続playerのエラー・予期しない完了は共通Stopで全出力・Service・通知・sessionを解除する。Audio Focusによる中断・端末音量・DNDなどOSの音声制御には従う。観測ログには警報専用のランダムなalarmToken、mode、soundType、回数、Focus取得結果、FGS開始成否・終了、再生開始・完了・エラーを記録し、メール内容・Rule名・音源URIは記録しない。

電源操作によるscreen offではAndroidがALARM振動もキャンセルする場合がある。ServiceだけがACTION_SCREEN_OFFを購読し、300ms後に一度、現在のtokenがまだactiveなら同じ振動出力を再設定する。音・focus・session・通知を再開始せず、Service破棄でcallbackを解除する。token照合によりStop後・置換後の古いcallbackは出力を復活させない。自然なscreen timeoutと手動screen offの両方でsessionは解除しない。

## 11. Sound type・AudioAttributes・振動preview

| AlarmSoundType | 音源一覧・標準音 | AudioAttributes usage |
| --- | --- | --- |
| ALARM | TYPE_ALARM | USAGE_ALARM |
| NOTIFICATION | TYPE_NOTIFICATION | USAGE_NOTIFICATION |
| RINGTONE | TYPE_RINGTONE | USAGE_NOTIFICATION_RINGTONE |

AlarmSoundMappingでAndroid定数へ変換する。soundUri指定があれば使用し、未指定・開けない場合はそのカテゴリのAndroid標準URIへfallbackする。種別変更時はUIで旧soundUriをnullへ戻す。picker結果は起動時の種別と現在種別が一致する場合のみ反映する。

表のusageは有限回数・preview・UNTIL_STOPPEDの全てに適用する。soundTypeは再生用途・音源URI・カテゴリfallback・Audio Focusを決め、alarmModeは継続時間だけを決める。UNTIL_STOPPEDでもALARMへ強制変換しない。通知・着信・アラームそれぞれのAndroid設定に従い、端末設定は変更しない。CONTENT_TYPE_SONIFICATIONを維持し、有限回数・継続ともFGS開始後にFocusを要求する。Android 17 / targetSdk 37では背景FGSのWIU条件も適用され、許可済みexact alarm権限とUSAGE_ALARMの組合せはWIU条件の例外となる。これはFGS自体や背景起動制限の免除ではなく、通知・着信用途へも一般化しない。

Android 17 / target 37では、FCM等から始まるFGSがwhile-in-use能力を持たない場合、通知・着信用途の背景音声やAudio FocusをOSが抑止し得る。exact alarm権限の音声例外はUSAGE_ALARMだけに適用される（[Android公式仕様](https://developer.android.com/about/versions/17/changes/bg-audio)）。この制約を音種別の変更で回避しない。Focus拒否時も既存の無音session・通知・振動・Stopを維持する。

VibrationPatternはNONE / PATTERN_1 / PATTERN_2 / PATTERN_3。共通AlarmVibration.timingsはそれぞれなし、[0,300]、[0,200,150,200]、[0,350,150,350,150,350]ミリ秒。継続用のみ末尾1000ms休止を追加してrepeat index 0、有限回数とpreviewはrepeat -1。

AndroidVibrationOutputはAPI 31以降のVibratorManager、VibrationEffectを扱う。Rule設定と確認用UIは共通VibrationPatternSelectorを使用する。変更時だけpreviewをcancelして新しい1サイクルを実行する。初期表示・同値再選択では実行しない。NONE、Lifecycle ON_STOP、Composable破棄でcancelする。所有tokenを検査するため、古いpreviewのcleanupは後から開始した本番警報を止めない。previewは通知・AlarmEvent・FGS・Room・alertTriggeredを変更しない。

## 12. ServerDeletePolicy

Account単位でIMMEDIATELY / AFTER_1_DAY / AFTER_1_WEEK / AFTER_10_DAYS / AFTER_15_DAYS / AFTER_1_MONTH / NEVERを保存する。新規Account、3→4 migration、未知値はNEVERとし、明示保存済みpolicyは保持する。deleteAfterReceiveは互換用フィールドとして残るが、削除判定には使用しない。POP3 / POP3S / IMAP4 / IMAPS共通の7選択肢を使用する。

### 判定・時刻

accountId + remoteMessageIdでRoomを照合し、PROCESSEDまたはUNSUPPORTED、ID一致、有効なreceivedAtEpochMillis、閾値経過をすべて確認したメールだけ削除対象とする。alertTriggeredは条件にしない。Rule非一致・poll選定で非採用でも、正常にPROCESSEDなら対象になる。

基準時刻はアプリの初回保存時刻receivedAtEpochMillis。メールDateヘッダーを使用しない。1 / 7 / 10 / 15日はそれぞれ固定24時間単位。1ヶ月はUTCのGregorianCalendarでMONTHを1加え、月末は翌月の有効な日へ丸める。固定30日ではない。lookup失敗、ID・Account不一致、RECEIVED、不明・未来時刻、overflow、protocol状態不明では削除しない。

### POP3の実行順と失敗

CoordinatorのhandoffとRoom batch確定が成功した後、AccountMailReceiverがアカウントごとのRoomのPROCESSED / UNSUPPORTEDメールを既存ServerDeleteEligibilityで判定し、ローカル削除候補UIDLを抽出する。候補0件なら削除接続を開かず正常終了する。DELETE_CANDIDATESの件数とPOP3_DELETE_SKIP reason=NO_DELETE_CANDIDATESを観測ログに記録し、UIDL・メール内容は記録しない。候補がある場合だけPop3ServerDeletionが専用の新しい接続で認証しUIDL一覧を確認する。候補は接続要否だけの判断に使い、全UIDL形式・一意性と全Room lookupを再確認してからDELEへ進む。対象0件 / NEVERでは削除commandを送らない。候補取得失敗は0件扱いにせずpoll失敗とする。

全DELEの+OK確認後だけQUITを送信し、正常終了で削除を確定する。DELE途中のエラー・キャンセル・不明状態ではQUITを送らずsocketを閉じ、削除確定を避ける。QUIT送信後に応答が失われた場合の確定有無は不明なので、次回pollでサーバー状態を確認する。

削除失敗は接続timeout・DNS・TLS・認証・protocolの原因に応じたpoll失敗として既存の監視失敗管理へ渡す。DEBUGログは接続/TLS、greeting、認証、UIDL、lookup、DELE、QUITの段階と対象件数・例外型だけを記録し、ホスト名・認証値・UIDL・メール内容・サーバー応答本文は出さない。保存済みPROCESSEDは維持し、次回pollで削除を再試行する。再保存・再警報しない。受信や接続テストのREAD_ONLYセッションを削除用に流用しない。

### IMAPの専用削除session

受信完了・必要handoff・batch確定後だけ、期限到来候補のあるAccountで別のREAD_WRITE INBOX sessionを開く。保存済みUIDVALIDITY:UIDを照合し、対象UIDへ\Deletedを設定した後、通常のEXPUNGEを1回実行する。既に他クライアントが\Deletedを付けたメールも同時に消え得ることは承認済み。UIDPLUS / UID EXPUNGEを必須としない。

全対象のflag設定とEXPUNGE正常応答を確認した場合だけDELETED。UID不在またはUIDVALIDITY変更はABSENT。flag・EXPUNGE・通信・応答不明・キャンセルでは結果を返さずPENDINGを維持し、次回に再照合する。一部だけflag設定できた場合もDELETED確定しない。close(false)で意図しない確定を避ける。削除候補0なら削除sessionなし。受信session1回に削除session1回が増えることは正常。
## 13. UI・権限・確認用UI

ユーザー向け文字列はEnglishのres/valuesと日本語のres/values-jaへ置く。初回のアプリ言語はEnglish。選択はDataStoreとAppCompatのlocale設定へ反映する。端末言語から日本語を自動選択する仕様ではない。

Account SettingsのPrimary Actionは接続テスト成功 → 設定を保存 → 保存済み。編集で検証状態を無効化する。接続テストは保存操作の代わりではない。

Settingsは言語、監視間隔・連続失敗閾値・監視ON/OFF・起動時確認と保存、Divider以下の開発者ツール、アラーム確認用UIで構成する。確認用sound / vibration / countはRuleへ保存しない。手動アラーム確認は本番と同じControllerを使う。Dummy受信操作はDemo Modeを明示的にONにした場合だけ利用できる。開発者UIは現在debug限定表示ではない。

Manifestのアプリ宣言はINTERNET、ACCESS_NETWORK_STATE、VIBRATE、WAKE_LOCK、POST_NOTIFICATIONS、SCHEDULE_EXACT_ALARM、FOREGROUND_SERVICE、FOREGROUND_SERVICE_MEDIA_PLAYBACK。通知はAndroid 13以降でruntime許可、exact alarmはAndroid 12以降で設定画面への導線を持つ。DND policy access、battery最適化除外を要求するUIはない。

## 14. テスト・ビルド

実機試験の監視ON/OFF準備・復帰はandroidTestのMonitoringDevicePreparationへ集約する。通常のSettings ViewModel更新が返した予約／取消OperationをIOスレッドで待ち、ON時はunique名alertkun_mail_check_periodicのWorkInfoで現在世代・保険間隔・Periodic種別とENQUEUED/RUNNINGの1件を確認する。設定保存から確認まで合計10秒以内とし、未完了・取消済み・予約なし・重複・期限超過は試験失敗とする。OFF時は取消OperationとCANCELLED状態を確認する。DEVICE_MONITORING_CONFIRM_START / OPERATION_COMPLETED / WORK_INFO / CONFIRM_SUCCESS / CONFIRM_FAILUREは試験時だけ記録する。本番の予約は非同期のまま保ち、試験終了後も別プロセスでWorkManager DBの有効予約を確認する。

JVMはJUnit 4で純粋ロジック、fake store / receiverで復旧・重複・priority・削除境界を検証する。AndroidX / Compose instrumentationでRoom migration・transaction、UI、MediaPlayer、振動、exact alarm / FGS / Stopを検証する。

POP3削除・priorityの通信テストはapp/src/testSupportの使い捨てローカルTCPサーバーを利用し、本番メールを削除しない。認証値・本文全文をログに出さない。

Windowsのリポジトリrootから実行:

- .\gradlew.bat test
- .\gradlew.bat assembleDebug
- .\gradlew.bat lint
- .\gradlew.bat connectedAndroidTest（端末・エミュレータ接続時）

テスト件数や端末結果は実行時に変わるため、この文書を固定の合格証明にしない。

## 15. 未対応機能

JSON Export / Import、添付表示、OAuth専用認可UI、複数アカウント追加導線の改善、独自BOOT_COMPLETED reconcileは現在未対応。今後のバージョンでの実装を検討するが、実装を約束しない。

Release 1.0はGitHub Releasesで署名済みAPKを配布する方針で、Google Play配布ではない。署名鍵はGit管理しない。ソースコードは[BSD 3-Clause License](LICENSE)、キャラクター画像は別の[CC BY-NC-ND 4.0](artwork/README.md)を適用する。利用者向けの配布・問い合わせ案内は[README.md](README.md)へ集約する。
