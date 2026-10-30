# LiveLingo 0.1.8 驗證狀態

2026-10-31（台灣時間），以 JDK 17、Gradle 8.14.3、Android SDK 36 建置及測試。

```sh
JAVA_HOME=/tmp/livelingo-tools/jdk17 ANDROID_HOME=/tmp/livelingo-sdk /tmp/livelingo-tools/gradle-8.14.3/bin/gradle :core:jvmTest :shared:testDebugUnitTest :androidApp:assembleDebug --offline --console=plain
```

**BUILD SUCCESSFUL；137 項測試，0 失敗、0 錯誤、0 略過。** CI 可使用 `./gradlew` 執行相同工作。

| 測試類別 | 數量 |
| --- | ---: |
| FoundryProtocolTest | 10 |
| ProtocolAndInstructionsTest | 8 |
| SubtitleExporterTest | 12 |
| TaiwanAndParagraphsTest | 10 |
| AndroidFoundryHttpClientTest | 11 |
| AppLogicTest | 42 |
| AudioCapturePolicyTest | 3 |
| AudioSignalMeterTest | 4 |
| FoundryDiagnosticsTest | 6 |
| FoundryTransportTest | 19 |
| LiveSubtitleDisplayTest | 7 |
| ParagraphFormatterTest | 4 |
| ParagraphHttpTest | 1 |

## 本次驗證範圍

- 繁中句讀逗號、逗號後空白、換行及混合簡繁字；保留 `1,234.56`、`1,00,000`，不影響英文、原始 JSON 與字幕時間戳。
- 數字與逗號跨多個串流片段／字幕 cue 時仍正確，涵蓋即時顯示、閱讀段落、TXT 時間明細、SRT、WebVTT。
- 舊 Teams 偏好遷移到麥克風、顯示一次說明、不自動收音，歷史音源不改寫；新選擇不包含 Teams，Android 舊音源無法進入播放擷取。
- 連線仍可用的內容篩選通知不斷線；確定的 `reason=content` 與沒有結束事件的篩選後斷線分開分類；恢復字幕後的另一次網路中斷不被歸因於舊通知。
- 真實本機 TLS／OkHttp WebSocket 收到篩選後關閉碼 1008 的回歸測試；擷取數字關閉碼，不把敏感關閉文字放入診斷。
- 中斷後不彈窗遮住字幕、可開啟診斷、收音釋放與逐字稿保存；手動下一段保留舊紀錄且不重放音訊。
- 模擬磁碟寫入失敗：未儲存字幕保留在記憶體，下一段被阻擋，避免資料被新紀錄覆蓋。
- 原有 API key 隔離、授權取消與撤回、舊場次回呼隔離、背景服務、音量、段落、匯出及 HTTP／WebSocket 測試持續通過。
- Compose 新排版與固定主按鈕已通過 Android 編譯；沒有把編譯通過當成實機畫面驗收。

## 交付 APK

- 檔名：`LiveLingo-0.1.8-original-key.apk`
- Application ID：`app.livelingo.android`
- Version name／code：`0.1.8`／`9`
- Minimum／target SDK：`26`／`36`
- 大小：13,289,576 bytes
- APK SHA-256：`229d682566686d5189de12537783481d96a5a26dc6d9e720bd904062a9b449a6`
- 簽章憑證 SHA-256：`cf232e1f105c3ac5a93ce2449b41678027b4b1ef9a54c3765c85b65a045d4bfb`
- `apksigner verify`、`aapt dump badging`、`zipalign -c -P 16 4` 均成功；沿用原始 0.1.x 簽章與 App ID，可覆蓋安裝。

## 尚未驗證與限制

- 沒有連接 Android 實機或模擬器，尚未實測新排版的像素結果、大字體／橫向、MediaProjection 系統授權與長時間背景擷取。
- 未使用真實 Foundry／OpenAI 憑證重現使用者的內容篩選。0.1.7 報告顯示通知後連線已中斷，不能由此確認進一步原因。0.1.8 改善分類、可觀測性與手動下一段流程；不能保證避免服務端終止，沒有移除篩選或重送被阻擋內容。
- 本機未執行 iOS 編譯；需 macOS CI 與實機確認共用 UI。iOS 仍只支援前景麥克風。
- 本機 Android Lint 先前因離線快取缺少 `com.android.tools.lint:lint-gradle:31.11.1` 無法執行，本次未重新下載，不能宣稱 Lint 通過。

完整實機驗收項目見 `docs/ACCEPTANCE.md`，官方生命週期與 Android 音源依據見 `README.md`。
