# LiveLingo 0.1.12 驗證狀態

2026-10-31，以 JDK 17、Gradle 8.14.3、Android SDK 36 建置及測試。

```sh
JAVA_HOME=/tmp/livelingo-tools/jdk17 ANDROID_HOME=/tmp/livelingo-sdk /tmp/livelingo-tools/gradle-8.14.3/bin/gradle :core:jvmTest :shared:testDebugUnitTest :androidApp:assembleDebug --offline --console=plain
```

**BUILD SUCCESSFUL；190 項測試，0 失敗、0 錯誤、0 略過。** CI 可使用 `./gradlew` 執行相同工作。

| 測試類別 | 數量 |
| --- | ---: |
| FoundryProtocolTest | 10 |
| ModelRoutingTest | 7 |
| ProtocolAndInstructionsTest | 8 |
| SubtitleExporterTest | 12 |
| TaiwanAndParagraphsTest | 14 |
| TranslationSupplementTest | 10 |
| AndroidFoundryHttpClientTest | 11 |
| AppLogicTest | 48 |
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

- 重現截圖情境：英文原文末尾沒有句點，直接接「Walkman 我要去台南去鹽湖」，下一段「我的秘書說 OK」。服務只有先前英文譯文時，中文仍以原文補充出現在譯文區；既有繁中轉換保留為臺南／祕書，數字與意思不變。
- 完全沒有譯文、只有空白或標點時顯示已收到的原文，使用明確的原文補充標籤；尚未收到任何原文時不製造文字。非中文原文不冒充成中文譯文。
- 串流來源片段先接合，保留段落與跨片段數字分隔符；原文片段、服務譯文、時間戳及 JSON 沒有被合成 OUTPUT 覆寫。
- 延遲的同文譯文可分段移除補充；只刪除已確認的前綴，保留未收到部分。相同句子重複兩次仍保留兩次；先前英文剛好譯成相同中文時，不可遮掉稍後新說出的中文。
- 字元比對保留數字中的小數點、正負號與分隔符，不把 12.34／1234、-50／50 或不同否定詞視為相同內容。日文／韓文不因含漢字就直接判為中文。
- App 整合測試驗證 AI 分段停用／拋出錯誤時仍補顯，不另發文字翻譯請求。AI 分段仍只整理服務原始兩軌，補充標籤不送入分段模型。
- 停止、保存、重新開啟、歷史閱讀及 TXT／SRT／VTT 都保留補充，時間戳明細以原文時間呈現。JSON 保留原始片段，可在 App 還原相同顯示。
- 語音與文字翻譯共用指令明確要求同語言、重複短句及句中語言切換也必須有輸出。其餘協定、模型設定、HTTP、診斷、儲存與分段回歸測試持續通過。
- Android 套件、標頭、設定、診斷與 iOS 專案版號同步為 0.1.12／13。

## 交付 APK

- 檔名：`LiveLingo-0.1.12-original-key.apk`
- Application ID：`app.livelingo.android`
- Version name／code：`0.1.12`／`13`
- Minimum／target SDK：`26`／`36`
- 大小：13,168,958 bytes
- APK SHA-256：`f0c5c2ccadab4bacd24315935ee957598416ff73d34cc7361f5bacf2efda9edc`
- 簽章憑證 SHA-256：`cf232e1f105c3ac5a93ce2449b41678027b4b1ef9a54c3765c85b65a045d4bfb`
- `apksigner verify`、`aapt dump badging`、`zipalign -c -P 16 4` 通過；與 0.1.11 原 APK 的憑證相同。

## 尚未驗證與限制

- 未使用真實 Foundry／OpenAI 憑證驗證模型是否遵守新指令；本機補顯不依賴服務遵守指令。無 Android 實機／模擬器連接，尚未進行畫面、覆蓋安裝與長時間效能驗收。
- 同文去重採保守字元及到達順序比對，不做語意推測。若模型改寫用詞、譯文早於原文抵達，或跨片段轉字不一致，補充可能保留；明示為原文補充，不冒充模型已翻譯。已有部分譯文時，僅針對中文目標補充原文中的中文，不保證偵測所有外語漏譯。
- 補充區按原文順序顯示於服務譯文下方；不宣稱兩條獨立音軌能逐句精準合併。SRT／VTT 中補充的時間來自原文，仍可能與獨立譯文音軌重疊。
- 未收到的原文與服務已阻擋的音訊無法復原；沒有自動重送音訊、重連或對受阻內容重新請求翻譯。
- iOS 版號同步但未在本機編譯；Android Lint 離線依賴仍不齊，本次沒有宣稱 Lint 通過。既有 LocalClipboardManager 棄用及 native strip 警告仍在。

實機清單見 [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)。
