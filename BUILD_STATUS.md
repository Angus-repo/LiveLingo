# LiveLingo 0.1.11 驗證狀態

2026-10-31，以 JDK 17、Gradle 8.14.3、Android SDK 36 建置及測試。

```sh
JAVA_HOME=/tmp/livelingo-tools/jdk17 ANDROID_HOME=/tmp/livelingo-sdk /tmp/livelingo-tools/gradle-8.14.3/bin/gradle :core:jvmTest :shared:testDebugUnitTest :androidApp:assembleDebug --offline --console=plain
```

**BUILD SUCCESSFUL；178 項測試，0 失敗、0 錯誤、0 略過。** CI 可使用 `./gradlew` 執行相同工作。

| 測試類別 | 數量 |
| --- | ---: |
| FoundryProtocolTest | 10 |
| ModelRoutingTest | 7 |
| ProtocolAndInstructionsTest | 8 |
| SubtitleExporterTest | 12 |
| TaiwanAndParagraphsTest | 14 |
| AndroidFoundryHttpClientTest | 11 |
| AppLogicTest | 46 |
| AudioCapturePolicyTest | 3 |
| AudioSignalMeterTest | 4 |
| FoundryDiagnosticsTest | 6 |
| FoundryTransportTest | 19 |
| LiveSubtitleDisplayTest | 7 |
| ModelSettingsTest | 5 |
| ParagraphFormatterTest | 13 |
| ParagraphHttpTest | 1 |
| RealtimeTransportTest | 11 |
| TextTranslatorHttpTest | 1 |

## 本次驗證範圍

- 以印度口音／T 發音截圖文字驗證：原文兩段、譯文四個既有段落時，使用模擬的正確語意對應可合併為兩段；原始文字、標點、數字、時間戳不變，儲存還原及 TXT 保留新排版。
- 原文優先的單次請求加入對應表驗證：每個譯文換段必須對應已選原文換段，不允許無對應、多對一、逆序或字串 ID；原文既有換行列為必要參考。
- 原文單段／無標點／超長內容仍作為譯文依據，請求大小維持有限；缺少語意對應顯示等待，原文更新會使舊譯文對應失效。
- 新譯文排版可合併既有換行，尚未處理的新尾段仍保留換行；長會議視窗移動保留先前已確認段落，不讓已合併的換行重現。舊版 JSON 的新欄位採相容預設，既有歷史不自動重排。
- 停止時即使 AI 判斷已快取，仍會保存含已收到文字的最後快照，避免週期分段取消與字幕寫入交錯，造成畫面有分段而歷史未保存。未收到文字且已保存的空場次不增加 IO；拒絕／取消收音流程保留原行為。
- 真實本機 TLS／OkHttp 測試兩服務的雙語請求、對應表解析、譯文換行合併、模型參數及認證隔離；仍拒絕轉址與無效回傳。
- OpenAI 標準文字模型只顯示選單，自訂 ID 移到進階設定；既有自訂 ID 會展開，Foundry 明示同一模型的部署名稱。共用 UI 通過 Android 編譯，尚未做實機像素驗收。
- 先以截圖文字建立回歸案例，舊程式確實只產生原文 window、漏掉逗號譯文；修正後兩軌均進入分段請求，換行保存到歷史與 TXT，原始文字及時間戳保持不變。
- 分句候選保護數字（含 `1,234.56`、`1,00,000`）、引號與括號中的逗號；AI 僅選邊界，不自動將每個逗號換行。尾段只作參考，尾段變動會重新判斷；缺少回傳軌道被拒絕，單段與等待狀態分別顯示。
- 原文、譯文分批抵達時允許中途只整理已有軌道；停止後的回歸測試要求兩軌均有 AI 段落，且正確保存與顯示狀態。
- 語音及文字下拉選單顯示美元參考價格、單位、查核日期與官方來源，依服務切換；無法確認的 Azure 費率明示待確認，自訂部署不猜測價格。價格為 2026-10-31 公開資料快照，不是帳號即時費用。
- 共用標頭右上角、設定頁及診斷共用 `APP_VERSION`；Android 套件與 iOS 專案版號為 0.1.11／12。
- GPT-Live、Realtime Translate、GPT Live Transcribe、MAI Transcribe 分別產生正確端點、初始化與音訊事件；MAI 限 Foundry services.ai.azure.com。
- 語音模型與文字模型下拉選單、可編輯部署名稱與各服務獨立設定；切換協定沿用同資源金鑰，變更端點不外送舊金鑰。
- Luna 預設與一次性遷移、保留自訂部署及原金鑰；未儲存表單依選取模型測試，不寫入設定、不收音。
- Realtime Translate 不傳 GPT-Live 指令、背景知識或不支援的來源限制，支援可選原文轉錄與兩種文字事件。
- 兩種轉錄方案的短尾音提交、delta／全文去重、完成亂序重排、文字翻譯排隊與原文先保存；MAI delta 有 ID 而完成事件無 ID 時清除對應暫存，40 次提交不累積舊文字。
- 網路中斷與停止等待逾時保留暫存原文，不重放音訊；文字翻譯失敗保留原文且不自動重試。語音探測不觸發文字翻譯。
- 真實本機 TLS／OkHttp 的文字翻譯及段落 HTTP：兩服務 URL／標頭隔離、Luna none 參數、禁止轉址、內容篩選與未完整結果不顯示為成功。
- 分段只回傳句子／分句邊界；Luna／5.4 Mini 使用 none、6.1 Sol 使用 low 並增加 token 預算，4.1 Mini／自訂不傳推理參數，不傳 temperature。
- 原有背景收音、播放授權、語言與繁中逗號、字幕匯出、逐字稿保存、內容篩選分類與診斷遮蔽測試持續通過。
- 共用 Compose 設定畫面通過 Android 編譯；沒有把編譯通過當作實機畫面驗收。

## 交付 APK

- 檔名：`LiveLingo-0.1.11-original-key.apk`
- Application ID：`app.livelingo.android`
- Version name／code：`0.1.11`／`12`
- Minimum／target SDK：`26`／`36`
- 大小：13,073,212 bytes
- APK SHA-256：`08ec5b2fad9a3687f6e1e060b440e806a31456367aa39c1254c853bb66895bba`
- 簽章憑證 SHA-256：`cf232e1f105c3ac5a93ce2449b41678027b4b1ef9a54c3765c85b65a045d4bfb`
- `apksigner verify`、`aapt dump badging`、`zipalign -c -P 16 4` 驗證；沿用原始 0.1.x 簽章與 App ID。

## 尚未驗證與限制

- 未使用真實 Foundry／OpenAI 憑證測試新模型，不能宣稱實際帳號有部署、權限或配額；需依服務可用性填入真正的部署名稱。連線測試僅驗證語音初始化，不包含文字翻譯或 AI 分段部署。
- 分段回歸測試使用模擬的模型回傳，未比較真實 Luna 與 Mini 的段落語意品質；對應表驗證能限制額外換段，但不能替代真實語意判斷。字幕延遲、兩軌內容不一致或缺少可對應邊界時，可能暫時段數不同並顯示等待。
- 無 Android 實機／模擬器連接，尚未驗收新下拉選單的像素結果、大字體／橫向、覆蓋安裝、MediaProjection 授權與長時間背景擷取。
- 轉錄方案每約 3 秒取得一段全文再翻譯，延遲包含文字模型回應；轉錄時間是區段估算、Realtime Translate 使用串流 elapsed time，均非逐字對齊。未量測真實會議的延遲或翻譯品質。
- Realtime Translate 不支援自訂提示詞或背景知識，繁中由本機字詞轉換補強。MAI Streaming 為預覽。沒有自動說話者辨識、音訊重送或自動重連。
- 未使用真實憑證重現先前內容篩選中斷；保留診斷及已收到內容，不保證避免服務端終止或復原被阻擋片段。
- 本機未編譯 iOS；需 macOS 與實機驗證共用 UI 及 Darwin 串流。iOS 仍只支援前景麥克風。
- Android Lint 先前因離線快取缺少 `com.android.tools.lint:lint-gradle:31.11.1` 無法執行，本次未重新下載，不能宣稱 Lint 通過。既有 LocalClipboardManager 棄用警告尚在。

實機清單見 [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)，協定與模型依據見 [README.md](README.md)。
