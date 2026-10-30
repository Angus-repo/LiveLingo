# LiveLingo 0.1.7 驗證狀態

2026-10-31（台灣時間），以 JDK 17、Gradle 8.14.3、Android SDK 36 建置及測試。

```sh
JAVA_HOME=/tmp/livelingo-tools/jdk17 ANDROID_HOME=/tmp/livelingo-sdk /tmp/livelingo-tools/gradle-8.14.3/bin/gradle :core:jvmTest :shared:testDebugUnitTest :androidApp:assembleDebug --offline --console=plain
```

**BUILD SUCCESSFUL；127 項測試，0 失敗、0 錯誤、0 略過。** 本機使用已下載的 Gradle，避免 wrapper 重複下載。CI 可使用 `./gradlew` 執行相同工作。

| 測試類別 | 數量 |
| --- | ---: |
| FoundryProtocolTest | 10 |
| ProtocolAndInstructionsTest | 8 |
| SubtitleExporterTest | 12 |
| TaiwanAndParagraphsTest | 7 |
| AndroidFoundryHttpClientTest | 10 |
| AppLogicTest | 39 |
| AudioCapturePolicyTest | 3 |
| AudioSignalMeterTest | 4 |
| FoundryDiagnosticsTest | 6 |
| FoundryTransportTest | 17 |
| LiveSubtitleDisplayTest | 6 |
| ParagraphFormatterTest | 4 |
| ParagraphHttpTest | 1 |

## 本次驗證範圍

- 音源選擇保存與工作階段快照；舊設定、舊逐字稿預設麥克風；不支援音源時阻擋，不暗中切換。
- 拒絕／取消授權、遲到回覆、連線中撤回擷取、停止後重開與舊場次回呼隔離；已有字幕保存。
- Teams UID 篩選必要性、僅允許 Android 公開 API 支援的媒體用途，麥克風與 mediaProjection 前景服務類型分開。
- PCM16 音量、完整負值樣本、部分 buffer、約 15 秒無聲通知與訊號恢復。
- `content_filter` 於會話開始後出現時保留原連線、顯示通知、繼續後續字幕；啟動前篩選、內容終止、篩選後斷線及原生傳輸例外仍安全釋放。
- 內容篩選分類、內層 ResponsibleAIPolicyViolation、HTTP 狀態、診斷遮蔽、不完整標記持久化與 TXT／JSON；沒有自動重連或重送阻擋音訊。
- 原有兩家服務的本機 TLS WebSocket／HTTP、金鑰隔離、繁中、數值保留及 AI 段落驗證持續通過。

## 交付 APK

- 檔名：`LiveLingo-0.1.7-original-key.apk`
- Application ID：`app.livelingo.android`
- Version name／code：`0.1.7`／`8`
- Minimum／target SDK：`26`／`36`
- 大小：13,262,630 bytes
- APK SHA-256：`4ddf55af363dbe489df6e10784069bfe33b722f0f12eb7adae6c838ced991188`
- 簽章憑證 SHA-256：`cf232e1f105c3ac5a93ce2449b41678027b4b1ef9a54c3765c85b65a045d4bfb`
- `apksigner verify`、`aapt dump badging` 與 `zipalign -c -P 16 4` 驗證；沿用原始 0.1.x 簽章，可覆蓋安裝並保留 App 資料。

## 未完成的驗證與限制

- 本機 `:androidApp:lintDebug --offline` 因未快取 `com.android.tools.lint:lint-gradle:31.11.1` 而無法執行，不能宣稱 Lint 通過。
- 未連接 Android 實機或模擬器，未實測 MediaProjection 系統授權 UI、長時間背景擷取及 Teams 會議。Android 僅允許適當媒體用途且來源允許擷取；Teams 通話用途、禁止擷取或跨工作設定檔可能無聲。
- 未使用真實 Foundry／OpenAI 憑證重現內容篩選。本次修正 App 把所有內容篩選事件立即斷線的處理；不能移除服務端篩選或復原已阻擋內容，仍以後續事件／正式終止為準。
- 本機未執行 iOS 編譯。iOS 只提供麥克風，版號與共用介面同步；需由 macOS CI 與實機驗收。

實機驗收項目見 `docs/ACCEPTANCE.md`。Android 音訊及 GPT-Live 生命週期官方依據見 `README.md`。
