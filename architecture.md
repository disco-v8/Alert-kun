# AlertKun 内部設計

この文書は2026-10-06時点の作業ツリーの実装を基準とする内部設計・動作仕様の正本である。操作方法は[user-guide.md](user-guide.md)、開発時の厳守事項は[AGENTS.md](AGENTS.md)、概要とビルドの入口は[README.md](README.md)を参照する。将来案と現在の実装を区別し、変更時はコード・テストと一緒に更新する。

## 1. 実装範囲と構成

単一appモジュールのKotlin / Jetpack Composeアプリ。namespace / applicationIdはdirect.kabu.alertkun。minSdk 24、compileSdk / targetSdk 37、versionName 1.0。UIは英語・日本語。実通信はPOP3 / POP3S / IMAP4 / IMAPSに対応する。

| パッケージ | 責務 |
| --- | --- |
| model | Android frameworkから独立したAccount・Rule・ReceivedMail・設定enum |
| ui/main・account・rule・mail・settings・theme | Compose画面、表示mapper、ViewModel |
| data/entity・dao・database・repository・preferences | Room、DataStore、domain変換、Repository |
| mail/common・pop3・imap・parser | 通信、安定したリモートID、本文抽出、POP3削除 |
| rules | 純粋なRuleEngine |
| monitoring | WorkManager登録、1回の確認、実行調停、状態・失敗管理 |
| alarm | 通知、MediaPlayer、振動、有限回数制御、継続警報開始・停止 |
| security | CredentialStore、Android Keystore |
| receiver | 時計・タイムゾーン変更への対応 |
| push | Firebase token取得・更新、保存済みMasterIDによるGateway登録、CHECK_MAIL受信のWorkerへの委譲 |

Composeはメール通信・Room・Keystore・ルール判定本体・警報制御本体を直接実装しない。ViewModelは操作と画面状態を調整し、処理本体はRepositoryや専用クラスへ委譲する。AlarmPermissionControlsなどのUIは権限操作のためにControllerの状態を参照する。

監視の主経路:

FCM / WorkManager保険 → MailCheckWorker → MailCheckCoordinator → MailReceiver → Room保存 → RuleEngine → poll全体の候補選定 → AlarmController → Room処理確定 → POP3削除判定

### FCM token・Gateway疎通試験

MainActivityのprocess内初回起動でFcmRegistrationCoordinatorがFirebaseMessaging.getInstance().tokenを取得する。専用AlertKunFirebaseMessagingService.onNewTokenも同じ登録経路へ委譲する。FCM dataのactionがCHECK_MAILと完全一致する場合のみ、FcmMailCheckDispatcherから既存MonitoringScheduler.runNowへ渡す。onMessageReceived内では通信せず、既存MailCheckWorkerがscheduled Workerと共通のexecutionMutexで1回チェックを直列実行する。未知action・data欠落は無視し、任意dataはログへ出さない。DEBUGログには受信・判定・enqueueの端末時刻とpriorityを記録する。FCMはMonitoring ONの場合だけ実行し、ON化・interval変更・scheduleGeneration変更・予約キャンセルは行わない。手動runNowはMonitoring OFFでも単発確認可能。WorkManagerは15分以上の保険予約を維持する（詳細は7章）。画面OFF・充電中の疎通試験が対象で、自然Doze時の即時実行保証はない。

Gateway登録はSettingsRepositoryのDataStoreキーmydns_master_idが空でない場合に行い、Debug・Releaseとも保存済みMasterIDを利用する。local.propertiesの試験用MasterID取得とBuildConfig固定値は廃止。FCM tokenをRoom・DataStoreへ追加保存せず、ログはDEBUGだけ先頭6文字・末尾4文字・長さを表示する（短いtokenは全体を隠す）。MasterID・MasterPwd・HTTP本文・例外messageはログに出さない。

FcmTokenRegistrarは標準HttpsURLConnectionでhttps://fcm.gpg.jp:8119/alertkun-api/set-intervalへmasterid / fcmtoken / rcvintervalをPOSTする。標準TLS・ホスト名検証を使用し、redirectは追従しない。接続・読取timeoutは各15秒、応答解析は8192文字まで。HTTP 2xxとJSON status="success"の両方で成功とする。HTTP・network・JSON・応答statusエラーを区別する。

rcvintervalは登録直前にSettingsRepositoryから現在のpollIntervalMinutesを読む（1～1500分）。DataStoreの同一snapshotからMasterID・間隔・監視ON/OFF・主監視revisionをdistinctUntilChangedで監視し、初回・設定変更で現在tokenを取得して再登録する。onNewTokenも登録する。MasterPwdだけの変更では再登録しない。MasterID空欄は正常skip。監視OFFでも既存APIへの登録は維持するが表示基準を作らず、OFF→ONで新しい登録を行う。Gateway送信停止・削除APIは追加しない。登録は独立SupervisorJob内で直列化し、失敗を監視へ伝播させない。process終了を跨ぐ登録保証・永続retryはなく、次回アプリ起動・token更新・設定変更が次の機会になる。正式MyDNS認証・Gateway認証・削除APIは未実装。

設定画面のMyDNS.JPセクションでMasterID／MasterPwdを保存する。MyDnsSettingsRepositoryは既存AndroidKeystoreCredentialStoreの専用credential key mydns_master_password_v1を使い、同じAES/GCM鍵・暗号文保存方式を再利用する。MasterPwdは入力中だけUIのメモリに保持し、成功・画面離脱で空にする。保存済み値をUIへ復号表示せず、存在状態のみ返す。空入力は既存秘密情報を保持する。新規入力は保存・復号一致を確認してからMasterIDをDataStoreへ書き、失敗時は旧credentialとMasterIDの補償を試みる。復元失敗は別の失敗として表示する。保存と補償をprocess内Mutex・NonCancellableで保護するが、異なる保存先を跨ぐprocess停止時の原子性は保証しない。バックアップでKeystore鍵の復元は保証されない。MyDNS.JPへの通信・認証テストは行わず、Gateway JSONへMasterPwdを含めない。

## 2. 永続化

### Room

AlertKunDatabaseの現在のschemaはv5。accounts / rules / received_mailsを保存する。Entityとdomain modelはmapperで分離する。enumは安定した名前で保存し、Android定数やCompose色値を保存しない。

| Migration | 追加内容 | 既存データの扱い |
| --- | --- | --- |
| 1 → 2 | rules / received_mailsのbackgroundColor | DEFAULT |
| 2 → 3 | rulesのsoundType | ALARM。既存soundUriを保持 |
| 3 → 4 | accountsのserverDeletePolicy | NEVER。旧deleteAfterReceiveの値から削除を有効にしない |
| 4 → 5 | accountsのtlsValidationMode / certificateFingerprint | STRICT / NULL。旧予約値から信頼を登録しない |

1→5のmigrationを登録し、破壊的fallbackは使用しない。schema JSONはapp/schemas以下に置く。AlarmModeのTHREE_TIMES / FIVE_TIMES追加は既存TEXT列を利用し、schema変更を伴わない。旧ONCE / UNTIL_STOPPEDはそのまま読み込む。

ReceivedMailの重要な情報はid、accountId、remoteMessageId、receivedAtEpochMillis、From、ヘッダー、Subject、必要な本文、bodyFetched、processingState、alertTriggered、matchedRuleId、backgroundColor。UNIQUE(accountId, remoteMessageId)で重複保存を防ぐ。UIDL / UIDだけを先に保存せず、再処理に必要なデータとRECEIVEDを同じ行の挿入で確定する。

### DataStore

軽量設定とMonitoringStatusRepositoryの状態を保存する。確認間隔、連続失敗閾値、監視ON/OFF、起動時確認、言語に加え、確認開始・完了・成功時刻、次回target、scheduleGeneration、schedule種別、連続失敗・異常通知状態などを保持する。

mailRetentionDaysはモデル・永続化・validationに存在し、default 7、範囲1～99。ただし現在、保存日数の設定UI、期限による一覧絞り込み、定期削除の呼び出しは存在しない。ReceivedMail一覧は保存済みの全件を表示する。retentionによる自動削除を実装済みとして扱わない。

### 認証情報

AndroidKeystoreCredentialStoreはAndroid KeystoreのAES鍵を使用したAES/GCM/NoPaddingでパスワードを暗号化し、IVと暗号文を専用SharedPreferencesへ保存する。RoomにはcredentialKeyだけを置く。パスワードをRoom / DataStore / ログへ平文保存しない。

AccountCredentialSaverはRoomとCredentialStoreが共通トランザクションを持てないため、Accountを一度disabledで保存し、認証情報の保存・読戻しを検証してから最終状態を公開する。失敗時は補償処理を試み、復旧失敗時はdisabledへ退避して失敗を返す。プロセス停止を含めた完全な分散トランザクションではない。

保存済みパスワードはUIへ実値を戻さず固定マスクを表示する。空入力は既存値を保持し、新しい入力がある場合だけ変更する。マスク文字列はeditor値・保存値として使わない。

ManifestはallowBackup=true。現在のbackup / data extraction規則はpending_alarm.xmlを除外するが、暗号化認証情報のSharedPreferencesは除外していない。Keystore鍵を含む端末移行・復元の成功は保証しない。復元後は認証情報の再入力が必要になる場合がある。

## 3. メール通信と識別

JakartaMailReceiverはAngus Mailで受信する。POP3S / IMAPSはimplicit TLSで、アカウントのTlsValidationModeを使用する。既存・新規アカウントはSTRICTが標準。POP3 / IMAP4は非TLSで、STARTTLSは有効にしない。接続・読取・書込タイムアウトは各30秒であり、Coordinator全体の制限時間ではない。

受信対象はINBOX。READ_ONLYで開き、IMAPの既読変更やexpungeを行わない。接続テストは接続・認証と最小限の確認だけを行い、削除経路を呼ばない。

MailConnectionSettingsで受信用SessionとPOP3削除専用socketのprotocol・timeout・TLS設定を共通化する。削除socketも受信と同じAngus SocketFetcherを使用する。STRICTは標準TrustManagerFactoryのCA検証にleafの有効期間確認を加え、POP3S/IMAPSともssl.checkserveridentity=trueとAngus標準MailHostnameVerifierを明示する。AndroidのLDAPS endpoint検証の有無に依存せずホスト名を検証する。CA_VALIDATED_NO_HOSTNAMEは明示設定時だけ名前一致を要求しないが、同じCA検証・有効期間・TLS暗号化を維持する。TLS検証を全体で一律無効にせず、trust-allや常にtrueのHostnameVerifierを使わない。

- POP3の永続IDはUIDL。セッション内のメッセージ番号を保存しない。
- IMAPの永続IDはUIDVALIDITYとUIDを組み合わせた文字列。UIDVALIDITY変更後の別メールを混同しない。
- 同一IDでもAccountが異なれば別メールとして扱う。
- 現在のfetchNewMessagesはサーバー上のメール一覧のヘッダーを取得する。既受信メールの除外は最終的にRoom UNIQUE制約で行う。サーバー側差分取得やUIDL一覧だけによる先行除外を実装済みとして扱わない。

本文が必要と判断された場合、対象リモートIDの本文を別途取得する。plain textを優先し、HTMLしかない場合はタグ等を除去する。添付はローカル保存・表示・ルール判定の対象にしない。ただしMIME content取得時の通信量について、添付の転送自体を完全に抑止する保証はない。

有効Accountがある場合はAccountMailReceiverがそれらを処理する。複数Accountの候補は1回のCoordinator全体で比較する。有効Accountが0件ならDummyMailReceiverへ切り替わる。これは開発確認用であり、実サーバーへの接続成功を意味しない。Dummyのキューと失敗再現はprocess-local。通常Account UIには、既存Accountを残したまま新規Accountを追加する専用操作が現在ない。

PINNED_CERTIFICATEは明示承認済みleafのDER証明書全体のSHA-256を照合し、CA・名前一致の代わりに固定証明書の一致と有効期間を要求する。初回の接続テストは証明書を取得してhandshakeを拒否し、認証/受信/削除を行わない。fingerprintを表示し「この証明書を信頼する」でeditorへ採用した後、通常の接続テスト成功と保存を必要とする。変更時はCERTIFICATE_CHANGEDとして拒否し、設定画面の再確認・再承認まで自動更新しない。接続テストへ変更エラーを表示し、既存の連続失敗閾値で通知する際も証明書変更の案内を付記する。ホスト・ポート・protocolの編集で承認を解除する。Room v5 migrationはtlsValidationModeをSTRICT、certificateFingerprintをNULLにする。旧trustedCertificateIdは予約情報のまま信頼判定に使用しない。

## 4. RECEIVED / PROCESSEDと復旧

RECEIVEDは、安定したリモートIDと必要なローカルデータの保存完了。PROCESSEDは、ルール評価、poll全体の警報選定、必要な1件のhandoff、および処理結果の永続化が完了した状態。

MailCheckCoordinatorは各実行で次の順序を守る。

1. Rule一覧を取得する。
2. 既存RECEIVEDを保存済みデータから評価する。
3. サーバーからメールを取得し、必要なら本文を取得する。
4. 新規メールをRECEIVEDとして保存する。重複挿入が拒否されたメールは再評価・再警報しない。
5. 各メールの評価結果とAlarm候補を収集する。
6. 全候補から最大1件を選び、AlarmControllerへhandoffする。
7. 評価した全メールのPROCESSEDとsnapshotをbatch確定する。
8. 削除ポリシーの対象を確認する。

ProductionのReceivedMailDao.finalizeBatchはRoomの単一トランザクションで、全対象がRECEIVEDであることを確認して更新する。matchedRuleId、backgroundColor、alertTriggered、processingStateを一緒に更新する。非採用・非一致メールも正常なpollではPROCESSEDになる。

handoff失敗時は次点を鳴らさず、batchを未確定のRECEIVEDとして残し、そのpollを失敗とする。削除を実行しない。後続pollで再処理できる。サーバー取得等が候補収集途中で失敗した場合もhandoff・batch確定・削除へ進まない。

PROCESSED済みメールはサーバーに残っていても再警報しない。復旧対象はRECEIVED。ただしhandoff後・Room確定前のプロセス停止では次回再handoffの可能性が残る。外部副作用とRoomを跨ぐexactly-once保証は現在ない。

## 5. RuleEngineと二段階priority

RuleEngineはAndroid framework、Room、通信、通知から独立した純粋ロジック。有効Ruleについて検索文字列の部分一致を行い、大文字・小文字を区別しない。正規表現や完全一致の選択機能はない。

| 検索対象 | 判定 |
| --- | --- |
| SUBJECT / 件名 | 件名に検索文字列を含む |
| BODY / 本文 | 本文に検索文字列を含む |
| SUBJECT_AND_BODY / 件名と本文 | 件名または本文に含む。ANDではない |

1メール内の一致Ruleはpriority昇順、同値ならRule id昇順で1件を選ぶ。数値が小さいほど高優先。

現在の本文取得判断には制約がある。取得済みデータで一致RuleがあればMatchを先に返し、未取得本文が必要な別RuleがあってもBodyRequiredを返さない。したがって件名に低優先Ruleが一致すると、本文の高優先Ruleを評価しない場合がある。本文なしで一致がなく、有効な本文対象Ruleがある場合だけ本文取得へ進む。この点は理想上の「全Ruleを評価して最優先」と区別する。

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

FCM = primary monitoring trigger、WorkManager = backup/reconciliation。Gatewayへ送るrcvintervalはユーザー設定のpollIntervalMinutes（1～1500分）のまま。WorkManagerのbackup intervalはmax(pollIntervalMinutes, 15分)で、全設定値にunique PeriodicWorkを使用する。Gateway側の送信停止・解除APIは今回変更しない。MasterID未設定やFCM未到達では短周期の主監視は働かず、15分以上の保険が残る。

| ユーザー設定 | FCM登録間隔 | WorkManager保険 |
| --- | --- | --- |
| 1～14分 | 設定値 | 15分PeriodicWork |
| 15～1500分 | 設定値 | 設定値PeriodicWork |

onMessageReceivedはCHECK_MAILのみMonitoringScheduler.runNow(context, FCM)へ渡して短時間で返す。手動はMANUAL、通常予約はSCHEDULED_WORK、起動時即時確認はSTARTUPとしてinputDataで識別する。FCMと手動は別unique work名でKEEPし、要求の吸収による成功元の混同を避ける。すべて同じMailCheckWorkerとprocess-local executionMutexを使用し、設定と実行状態はmutex取得後に再読込する。サービス内にメール通信・独自mutexを置かない。

### 成功記録とbackup skip

DataStoreに最後の正常成功時刻、MailCheckTrigger、成功時のscheduleGeneration、最終試行成功flagを同じeditで保存する。試行開始時に成功flagをfalseにして、同じミリ秒でのキャンセル・process終了でも過去の成功を流用しない。正常な新着0件も成功。受信のみ、offline、通信・認証・TLS失敗、例外、キャンセルはFCM成功にしない。後続失敗や未完了checkがあれば古い成功をskipの根拠にしない。旧データにtrigger・backup intervalがない場合や未知値は安全に実行側へ倒す。

保険のnext targetを永続化し、そのtargetを基準にbackup interval単位の周期を定める。window startは今回のtarget minus backup interval。Workerが1周期以上遅れた場合はtargetを整数周期だけ進めて現在の区間を求める。遅延中のFCM成功もWorker判定時刻まで含めるが、未来の成功時刻は受け入れない。「now minus 15分」だけのsliding windowは使わない。

scheduled WorkerはMonitoring ON、世代一致、周期metadata一致、最後の正常成功が同世代のFCM、成功時刻がこの周期内、かつその後に失敗・未完了checkがない場合だけメール接続をskipする。DEBUGへscheduled backup skippedを記録し、次の保険targetだけ進める。skipは成功記録や連続失敗countを変更しない。最後の成功がMANUAL・STARTUP・SCHEDULED_WORK、FCM成功が古い、または不明・不整合なら保険チェックを実行する。

FCMが先にmutexを取得して成功した場合、待機中のscheduled Workerは最新成功を読んでskipする。逆順で保険チェックが先に成功した場合、ユーザー設定間隔内の同世代SCHEDULED_WORK正常成功があるFCMは接続を省く。これはFCM成功には記録せず、MANUALには適用しない。失敗・キャンセル済みbackupはFCMを抑制しない。

### 予約・世代・起動時調整

FCMと手動runNowはON/OFF、interval、scheduleGeneration、保険targetや予約を変更しない。Monitoring OFFではFCM・scheduled・startupは実行しない。明示的な手動runNowは従来どおりOFFでも単発確認できる。

設定保存、起動時rebase、欠落・不整合修復、大きな時計補正で世代を更新する。チェック開始・成功・失敗・次targetの書戻しはDataStore edit内で世代を再照合し、古いWorkerは新しい状態へ書き戻さない。保険予約は世代・周期tagも照合し、正常なfuture scheduleを維持する。FCM Gatewayの設定変更時登録は既存経路を維持する。

従来の1～14分OneTimeWork自己再予約は削除。旧short work名とONE_TIME enum値は既存予約の識別・取消・移行用にだけ残す。更新後の起動時reconcileまたは設定保存で旧short予約を取消し、PeriodicWork保険へ移行する。OneTimeWorkはFCM・手動・startupの単発チェック用途にだけ使う。

StartupReconcileGateはprocess内1回を維持する。監視ONでcheckImmediatelyOnAppStart=trueなら即時STARTUPチェック後、開始時刻からbackup intervalのtargetを登録する。falseなら正常future backup予約を維持し、期限切れ・欠落・周期不整合のみcheckなしでrebaseする。初回targetは既存の端末local timezone分境界丸めを用い、以後は保険targetを周期単位で進める。FCMの成功はこの基準を動かさない。

ACTION_TIME_CHANGEDはwall clockとelapsedRealtimeの差を使い、基準があり60秒以上の補正ならrebaseする。timezone変更は表示のみ。独自BOOT_COMPLETED reconcileは未実装で、WorkManager自身の永続work管理に依存する。

常駐MonitoringService、dataSync FGS、監視用AlarmManager、Expedited Work、独自長時間WakeLock、長時間polling loopは使用しない。NetworkType.CONNECTED constraintでoffline失敗を隠さない。FCMもPeriodicWorkもAndroidの実行時刻や自然Doze中の即時実行を保証しない。2026-10-08のPixel 7a・画面OFF/充電中の20分観測ではFCM正常確認、15分保険の自然実行とskip、旧short予約取消、二重受信抑止を確認した。自然Dozeでの長時間確認は別途実施する。

### デフォルトネットワーク切替への猶予

実チェックのexecutionMutex内でDefaultNetworkMonitorを1個だけ登録し、終了・失敗・キャンセル時に解除する。registerDefaultNetworkCallbackのonAvailable / onLostでdefault networkの変更世代を追跡し、onCapabilitiesChangedの引数からINTERNET / VALIDATEDを更新する。callback内では同期問い合わせや通信を行わない。Wi-Fi・モバイル・VPNなどtransportで判定を分けない。

NetworkStabilizationは1チェック全体で合計最大8秒だけcallbackによる復旧を待つ。既存の各接続・読み書きtimeout（30秒）より短く、manualにも同じ上限を適用する。利用可能になれば即再開する。capabilityは補助情報であり、猶予後にdefault networkが存在すれば実接続結果を最終判定とする。存在しなければDEVICE_OFFLINEで通常の失敗処理へ進む。

読み取り接続中にdefault network変更を観測し、socket・EOF・DNS・接続timeoutなど通信経路の例外が発生した場合だけ、同じ待機予算内で最大1回再試行する。認証・TLS・証明書・protocolエラーは対象外。Coordinator全体、Room確定、Rule評価、警報handoff、POP3削除は再実行しない。DELE / QUITの結果が不明な削除は即失敗とし、通常の次回チェックへ委ねる。UID/UIDL重複防止を維持する。

FCM・保険・手動・起動時は同じMailReceiver wrapperを利用する。Workerは最終結果だけを成功時刻・failure count・health履歴へ反映し、復旧した途中のエラーは通知しない。OFF・generation・backup skip判定は登録前のまま維持する。長時間待機・無限retry・監視FGS・WakeLockは追加しない。

Angus Mail 2.0.4のPOP3Store.protocolConnectは一部EOFExceptionをcauseなしのAuthenticationFailedExceptionへ変換する。回線切断を実機で再現したが、認証拒否との確実な区別ができないため、文言だけで通信例外へ読み替えず再試行対象外とする。この場合は通常の失敗として記録され得る。

## 8. 失敗管理と表示

### 1日1回の監視ヘルス通知

正常なmail checkを永続成功として記録した直後、既存executionMutex内でMonitoringHealthReporterを呼ぶ。FCM / SCHEDULED_WORK / MANUAL / STARTUPの正常成功が対象で、保険skip・OFF skip・世代不一致skipでは呼ばない。AlarmControllerと独立し、新しい定期Worker・AlarmManagerは追加しない。

SettingsRepositoryのhealthNotificationEnabledは初期値true。監視設定の「監視を有効にする」直下にある「異常がなくても一日一回通知」チェックボックスで選び、他の監視項目と同じ保存操作で原子的に保存する。画面上のMonitoring OFFでは操作できないがチェック状態を保持する。保存時は既存executionMutexで通知の判定・投稿と排他し、通知設定OFFなら既存ヘルス通知だけを取り消す。Monitoring OFFでも通知しない。初回正常checkから24時間の観測期間を確保し、通知設定またはMonitoringをOFFにした場合は観測開始を解除する。再ON後も初回正常checkから24時間待つ。通常のprocess再起動・世代変更は観測開始と前回通知時刻を保持する。未完了checkの痕跡がある場合は観測期間を再開始し、知らない期間を正常と扱わない。

DataStoreへ観測開始、前回通知時刻、最後のmail check失敗時刻を保存する。正常判定にはMonitoring ON、設定ON、最新試行の正常成功、連続失敗0、観測開始から24時間以上、過去24時間に失敗なしを要求する。失敗時刻は成功後も消さず、offline・通信/認証/TLS・削除失敗・その他例外を既存recordFailureと同時に記録する。実チェック中のキャンセルはヘルス履歴だけを不明/中断扱いにし、既存failure countや異常通知仕様は変更しない。実チェックが世代変更中に失敗した場合もヘルス履歴の事実は残すが、旧世代から監視状態・成功・count・保険targetは書き戻さない。skipは失敗履歴を作らない。

異常時の日次通知は出さず、既存の閾値に基づくfailure notificationへ任せる。異常検出件数が1件以上でもmail checkが正常ならヘルスは正常。件数はRoomのPROCESSEDかつalertTriggered=trueの行をreceivedAtEpochMillisによる通知時点の過去24時間（両端を含む）でCOUNTする。単なる新着件数や非採用の一致メールは数えない。重複データ・schema変更はない。既存列は初回保存日時であり、過去に保存したRECEIVEDを後日復旧した警報の実発生時刻を数えるものではない。

最終メール確認は既存lastSuccessfulCheckAtEpochMillis。端末local timezoneのyyyy/MM/dd HH:mmで日付も表示し、backup skipを成功時刻へ流用しない。専用channel alertkun_monitoring_healthはIMPORTANCE_LOW、音/振動なし、badgeなし、full-screen intentなし。固定notification ID 6003で置換する。既存警報ID 5001/5005、監視失敗ID 6002とは分離する。

通知判定はMonitoringHealthNotificationPolicyの純粋ロジックでepoch millisの24時間（86,400,000ms）を使用する。前回通知から24時間以上経過した最初の正常checkが対象。timezone変更は判定へ影響しない。時計の巻戻りや未来の通知/失敗時刻は通知を抑止する。DataStore editで最新設定・世代・成功時刻を再照合し、前回通知時刻をnotifyより先に永続claimして競合と再起動での二重通知を防ぐ。claim後のprocess停止や通知API失敗ではその日の通知を欠く場合があるが、重複回避を優先する。権限/channelが無効ならclaimしない。ヘルス集計・通知の例外はmail check成功を失敗へ変更しない。

実機の24時間境界検証はisolated DataStoreとin-memory Roomを使う選択instrumentationで行い、本番時計・設定・DBの時刻を書き換えない。本番の24時間定数は短縮しない。

MonitoringFailureKindでDEVICE_OFFLINEとSERVER_UNAVAILABLE、DNS、timeout、認証、TLS・証明書、protocolエラー等を区別する。連続失敗閾値は1～99、default 3。端末offlineも失敗countへ含める。新着0通を含む正常なcheckでcountと異常通知状態をresetする。

通常の失敗ではWorkerの即時retry queueを作らず、次の通常pollで再確認する。キャンセルは伝播させる。監視異常通知は設定した連続失敗回数と、その回の失敗日時を改行して表示する。本文日時は端末local timezoneのyyyy/MM/dd HH:mm:ss、BigTextStyleで複数行を表示する。内部の失敗分類は維持する。認証・TLS等の詳細分類は接続テストUIで表示する。メイン画面に全分類や失敗countを表示する実装はない。

メイン画面の「最終確認」は日次監視ステータス通知と同じlastSuccessfulCheckAtEpochMillisを参照する。FCM・SCHEDULED_WORK・MANUAL・STARTUPの実チェック全体が正常完了した時刻だけを保存し、削除失敗・backup skip・旧世代skipでは更新しない。明示的な監視OFF時の手動確認成功は更新する。

「次回確認」はFCM主監視によるおおよその予定であり確定実行予約ではない。現在の主監視設定に有効な直近CHECK_MAILの端末受信時刻＋ユーザー確認間隔を優先し、未受信なら現在設定のGateway登録／設定成功時刻＋間隔を使用する。local timezoneのyyyy/MM/dd HH:mm頃を表示し、秒を省く。監視OFFは「なし」、有効な基準が両方ない場合だけ「未定」。最終確認・WorkManagerの保険targetは算出に使わない。

主監視revision（fcm_schedule_revision）はON/OFF・間隔・MasterID変更と同じDataStore editで更新する。既存scheduleGenerationは保険予約の起動時rebase・時計補正でも変わるため、その変更で主監視の時刻を無効化しないよう主監視revisionを分離する。FCM時刻とGateway成功時刻にはそれぞれrevisionを付けて保存し、現在revisionと一致する値だけFlowに公開する。revisionのない旧履歴は無効。設定変更前に受信して非同期保存を待っていたFCMは設定変更時刻との照合で排除する。UIへ基準の間隔も渡し、別Flowとの更新順序により旧基準＋新間隔を一時表示しない。

Gateway成功はHTTP 2xxかつJSON status=successを確認した完了時刻だけを保存する。通信中に設定が変わった応答はDataStore edit内でもrevision照合して排除する。失敗・skip・要求送信時には保存せず、同じ設定の以前の成功を失敗時刻で上書きしない。新設定が未成功なら以前の設定の基準は公開しない。予定時刻自体は保存せず、基準＋間隔から導出する。既存monitoring_next_scheduledは保険の内部状態として維持する。

MonitoringStatusRepositoryのFlowをMainのStateFlowで購読し、新FCM・Gateway成功・設定変更を画面再オープンなしで反映する。期限を過ぎても同じ日時を保持し、時計だけの周期繰り上げ・毎秒／毎分タイマー・待機／遅延／リトライ表示を追加しない。FCM未達そのものはMainで異常扱いせず、既存の保険による実接続と連続失敗通知が異常認識を担当する。表示状態は実行mutex・保険skip・警報・メール処理へ影響しない。

現在のGatewayの外部仕様では、同じtoken・間隔での間隔登録も成功時に送信基準時刻をリセットする。したがってtoken再登録・アプリ起動時再登録も、成功時にGatewayの送信基準時刻がリセットされる。端末は成功応答確認時刻を新しい初期基準とし、その時刻以前のFCM基準を解除する。応答成功後に受信したFCMが先に保存された場合は保持し、古い受信の遅延保存は拒否する。失敗では現在設定の既存正常基準を維持する。将来APIがtoken同期のみへ変わる場合は、サーバーの動作を再確認してこの処理も変更する。

設定変更後、Gatewayが現在revisionの設定成功を返すまで、新たなFCMから表示基準は作らない。CHECK_MAIL payloadには間隔・revisionがないため、設定変更失敗後に旧Gatewayスケジュールで届いたFCMを新間隔の成功証明にしないためである。メール確認のhandoffは従来どおりで、表示だけを未定に保つ。

ReceivedMail一覧は件名、差出人、初回受信日時、snapshot色を表示し、本文詳細・matchedRuleId・alertTriggeredの表示UIはない。

## 9. 警報・有限回数

UI名は「アラーム回数」。domain / 保存列名は既存のAlarmMode / alarmModeを維持する。

| AlarmMode | 音・振動 | 終了 |
| --- | --- | --- |
| ONCE | 1サイクル | 自動終了 |
| THREE_TIMES | 3サイクル | 自動終了 |
| FIVE_TIMES | 5サイクル | 自動終了 |
| UNTIL_STOPPED | 継続再生・repeat振動 | Stop |

有限回数はAlarmControllerのprocess内処理で、継続Service / exact alarm経路へ入れない。FiniteAlarmSequenceで回数を明示的に数え、各サイクルでMediaPlayerを新規作成する。isLooping=false、完了・エラーlistenerでreleaseし、次サイクルを明示的に開始する。

autoLoop metadataにより完了listenerが来ない音源への対策として、各サイクルにMediaPlayer.durationのtimerを併用してreleaseする。音源に複数フレーズが入っていても音源全体で1サイクル。音と振動を同時に開始し、次サイクルは振動1サイクルも完了するまで待つ。音源を開けない場合も振動と回数終了処理は進める。プロセス終了・OS制限による有限回数の完遂保証はない。

通知channelは警報音・振動なし。実音はMediaPlayer、振動はVibratorで行う。有限警報の通知は通常Stop actionなしで残り、音・振動だけが自動終了する。新警報・Stopでは古いplayer、timer、振動、残回数を破棄する。

## 10. UNTIL_STOPPED・exact alarm・FGS

新規の自動開始経路はAlarmController → AlarmManager exact alarm → AlarmStartReceiver → AlarmPlaybackService。exact alarmは継続警報開始用だけで、監視scheduleには使用しない。AlarmPlaybackServiceは鳴動中だけのmediaPlayback FGSであり、監視や受信を担当しない。

SCHEDULE_EXACT_ALARM、通知許可・channel有効状態等を確認する。権限不足やFGS開始失敗時は単発出力と説明通知へ退避し、通知の「警報を開始」からユーザー操作で開始できる経路を用意する。継続開始時のAudio Focus取得が拒否された場合は音を開始せず、振動とServiceは継続する。Workerはhandoff後に終了する。

PendingAlarmStoreはtokenと最新AlarmEventを保持し、古いtokenの開始を拒否する。新警報で既存出力を置換し、queueを作らない。Stop actionはpending exact alarm、pending情報、音、振動、通知、Serviceを解除する。Serviceは自身の起動・foreground管理を担当し、実出力は同じAlarmControllerへ委譲する。プロセス・端末再起動後の無条件な継続再開は保証しない。

AlarmController.sessionはServiceのactivateで公開するStateFlow<AlarmSession?>。メールID、Rule ID、開始時刻を保持し、Main画面はactive時だけ監視状態の上に大きな「停止」ボタンを表示する。通知Stop、Main UI、確認ツールは既存の同じstopへ到達する。Service破棄でlive stateと出力を解除し、START_STICKYで再生成されたServiceは既存handoffからactivateする。保存済みhandoffだけをUI上のactive証明にはしない。Activity起動・再生成・Composeは状態購読だけで警報を開始しない。

同じメールID・Rule IDのUNTIL_STOPPED再handoffはpending/active中に無視する。別メールの警報は現在の出力を解放して置換し、実行中Serviceを再利用する。FCM・Workerにはセッション制御を置かない。通知は既存HIGH channelのongoing + Stop action。Android 14以降はongoingでもスワイプ可能な場合があるため、deleteIntentでactive時だけ通知を復元する。音・振動は再startしない。通知許可取消などで復元不能でもMain画面のStopを残す。

継続再生だけMediaPlayer.setWakeMode(PARTIAL_WAKE_LOCK)を使用する。playerの停止・解放に連動し、画面を点灯させず、監視WorkerにWakeLockを追加しない。継続playerのエラー・予期しない完了は共通Stopで全出力・Service・通知・sessionを解除する。Audio Focusによる中断・端末音量・DNDなどOSの音声制御には従う。

電源操作によるscreen offではAndroidがALARM振動もキャンセルする場合がある。ServiceだけがACTION_SCREEN_OFFを購読し、300ms後に一度、現在のtokenがまだactiveなら同じ振動出力を再設定する。音・focus・session・通知を再開始せず、Service破棄でcallbackを解除する。token照合によりStop後・置換後の古いcallbackは出力を復活させない。自然なscreen timeoutと手動screen offの両方でsessionは解除しない。

## 11. Sound type・AudioAttributes・振動preview

| AlarmSoundType | 音源一覧・標準音 | AudioAttributes usage |
| --- | --- | --- |
| ALARM | TYPE_ALARM | USAGE_ALARM |
| NOTIFICATION | TYPE_NOTIFICATION | USAGE_NOTIFICATION |
| RINGTONE | TYPE_RINGTONE | USAGE_NOTIFICATION_RINGTONE |

AlarmSoundMappingでAndroid定数へ変換する。soundUri指定があれば使用し、未指定・開けない場合はそのカテゴリのAndroid標準URIへfallbackする。種別変更時はUIで旧soundUriをnullへ戻す。picker結果は起動時の種別と現在種別が一致する場合のみ反映する。

表のusageは有限回数とpreviewに適用する。UNTIL_STOPPEDだけ選択した音源URI・カテゴリfallbackを維持し、再生用途とAudio FocusをUSAGE_ALARM（API 24～25はSTREAM_ALARM）に統一する。Android 17 / target 37では、FCM経由で開始したFGSがwhile-in-use能力を持たず、通知音usageの背景再生がOSにミュートされる。exact alarm権限によるUSAGE_ALARM例外へ適合させる（[Android公式仕様](https://developer.android.com/about/versions/17/changes/bg-audio)）。アラーム音量・DNDに従い、端末設定は変更しない。CONTENT_TYPE_SONIFICATIONと有限回数のAudio Focus非要求を維持する。

VibrationPatternはNONE / PATTERN_1 / PATTERN_2 / PATTERN_3。共通AlarmVibration.timingsはそれぞれなし、[0,300]、[0,200,150,200]、[0,350,150,350,150,350]ミリ秒。継続用のみ末尾1000ms休止を追加してrepeat index 0、有限回数とpreviewはrepeat -1。

AndroidVibrationOutputはAPI 31以降のVibratorManager、26以降のVibrationEffect、minSdk 24向け互換分岐を扱う。Rule設定と確認用UIは共通VibrationPatternSelectorを使用する。変更時だけpreviewをcancelして新しい1サイクルを実行する。初期表示・同値再選択では実行しない。NONE、Lifecycle ON_STOP、Composable破棄でcancelする。所有tokenを検査するため、古いpreviewのcleanupは後から開始した本番警報を止めない。previewは通知・AlarmEvent・FGS・Room・alertTriggeredを変更しない。

## 12. ServerDeletePolicy

Account単位でIMMEDIATELY / AFTER_1_DAY / AFTER_1_WEEK / AFTER_10_DAYS / AFTER_15_DAYS / AFTER_1_MONTH / NEVERを保存する。既存Account、新規Account、未知値はNEVERへ倒す。旧deleteAfterReceiveフィールドは互換用に残るが、削除判定には使用しない。POP3 / POP3Sだけ実装し、IMAP4 / IMAPSの削除はno-op。UIもIMAPでは選択操作を無効にして説明する。

### 判定・時刻

accountId + UIDLでRoomを照合し、PROCESSED、ID一致、有効なreceivedAtEpochMillis、閾値経過をすべて確認したメールだけ削除対象とする。alertTriggeredは条件にしない。Rule非一致・poll選定で非採用でも、正常にPROCESSEDなら対象になる。

基準時刻はアプリの初回保存時刻receivedAtEpochMillis。メールDateヘッダーを使用しない。1 / 7 / 10 / 15日はそれぞれ固定24時間単位。1ヶ月はUTCのGregorianCalendarでMONTHを1加え、月末は翌月の有効な日へ丸める。固定30日ではない。lookup失敗、ID・Account不一致、RECEIVED、不明・未来時刻、overflow、protocol状態不明では削除しない。

### POP3の実行順と失敗

CoordinatorのhandoffとRoom batch確定が成功した後、Pop3ServerDeletionが専用の新しい接続で認証しUIDL一覧を確認する。全UIDL形式・一意性と全Room lookupを確認してからDELEへ進む。対象0件 / NEVERでは削除commandを送らない。

全DELEの+OK確認後だけQUITを送信し、正常終了で削除を確定する。DELE途中のエラー・キャンセル・不明状態ではQUITを送らずsocketを閉じ、削除確定を避ける。QUIT送信後に応答が失われた場合の確定有無は不明なので、次回pollでサーバー状態を確認する。

削除失敗は接続timeout・DNS・TLS・認証・protocolの原因に応じたpoll失敗として既存の監視失敗管理へ渡す。DEBUGログは接続/TLS、greeting、認証、UIDL、lookup、DELE、QUITの段階と対象件数・例外型だけを記録し、ホスト名・認証値・UIDL・メール内容・サーバー応答本文は出さない。保存済みPROCESSEDは維持し、次回pollで削除を再試行する。再保存・再警報しない。受信や接続テストのREAD_ONLYセッションを削除用に流用しない。

## 13. UI・権限・確認用UI

ユーザー向け文字列はEnglishのres/valuesと日本語のres/values-jaへ置く。初回のアプリ言語はEnglish。選択はDataStoreとAppCompatのlocale設定へ反映する。端末言語から日本語を自動選択する仕様ではない。

Account SettingsのPrimary Actionは接続テスト成功 → 設定を保存 → 保存済み。編集で検証状態を無効化する。接続テストは保存操作の代わりではない。

Settingsは言語、監視間隔・連続失敗閾値・監視ON/OFF・起動時確認と保存、Divider以下の開発者ツール、アラーム確認用UIで構成する。確認用sound / vibration / countはRuleへ保存しない。手動アラーム確認は本番と同じControllerを使う。Dummy受信操作は有効Accountが0件の場合の確認用。開発者UIは現在debug限定表示ではない。

Manifestのアプリ宣言はINTERNET、ACCESS_NETWORK_STATE、VIBRATE、WAKE_LOCK、POST_NOTIFICATIONS、SCHEDULE_EXACT_ALARM、FOREGROUND_SERVICE、FOREGROUND_SERVICE_MEDIA_PLAYBACK。通知はAndroid 13以降でruntime許可、exact alarmはAndroid 12以降で設定画面への導線を持つ。DND policy access、battery最適化除外を要求するUIはない。

## 14. テスト・ビルド

JVMはJUnit 4で純粋ロジック、fake store / receiverで復旧・重複・priority・削除境界を検証する。AndroidX / Compose instrumentationでRoom migration・transaction、UI、MediaPlayer、振動、exact alarm / FGS / Stopを検証する。

POP3削除・priorityの通信テストはapp/src/testSupportの使い捨てローカルTCPサーバーを利用し、本番メールを削除しない。認証値・本文全文をログに出さない。

Windowsのリポジトリrootから実行:

- .\gradlew.bat test
- .\gradlew.bat assembleDebug
- .\gradlew.bat lint
- .\gradlew.bat connectedAndroidTest（端末・エミュレータ接続時）

テスト件数や端末結果は実行時に変わるため、この文書を固定の合格証明にしない。

## 15. 未対応・設計履歴

現在未対応なのは、JSON Export / Import、保存期限による自動purgeと設定UI、メール本文詳細・添付表示、IMAP削除、独自boot時reconcile、公開release配布フロー。OAuth専用の認可UIはない。ライセンスは未選定でLICENSEファイルもない。

初期のPhase 0～13は実装順の計画だった。現在はUI・Room・Rule・警報・WorkManager・POP3S・IMAPS・POP3削除まで実装が進んでおり、「Phase 6はDummyのみ」等を現状説明として残さない。boot対応・export/import・長期試験を、計画の存在だけで実装済みにしない。

過去の常駐MonitoringService案は廃止し、現在はFCMを主監視とし、WorkManagerを保険として使用する。継続警報専用AlarmPlaybackServiceとは区別する。Room migration履歴は本書2章とschema JSONに保持する。利用者向け操作の詳細はuser-guideへ、作業者向け規約はAGENTSへ集約する。
