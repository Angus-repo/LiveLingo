# LiveLingo 0.1.14 驗證狀態

本版以 GitHub Actions 執行 `:core:jvmTest`、`:shared:testDebugUnitTest`、Android APK 建置與 iOS 模擬器編譯；最終結果見 PR 的 checks 與 Actions 工作紀錄。下列 0.1.13 結果為上一版歷史紀錄，不代表本版測試結果。

本次新增驗證涵蓋自訂部署保存／重新載入、服務與資源隔離、模型與參數配對，以及切換後重新選回舊部署時實際送出的連線設定。常用模型、費用與原文字幕均使用下拉選單；只有選擇新增自訂項目才開啟名稱輸入對話框。

Android／iOS 版本同步為 `0.1.14`／`15`。尚未完成 Android 實機選單排版、覆蓋安裝或真實雲端帳號驗收；詳見 `docs/ACCEPTANCE.md`。此 UI 變更不發送額外模型探索請求，Foundry 預設名稱須已部署，自訂部署依資源保存。

---

# LiveLingo 0.1.13 驗證狀態

2026-10-31，以 JDK 17、Gradle 8.14.3、Android SDK 36 建置及測試。

```sh
JAVA_HOME=/tmp/livelingo-tools/jdk17 ANDROID_HOME=/tmp/livelingo-sdk /tmp/livelingo-tools/gradle-8.14.3/bin/gradle :core:jvmTest :shared:testDebugUnitTest :androidApp:assembleDebug --offline --console=plain
```

**BUILD SUCCESSFUL；201 項測試，0 失敗、0 錯誤、0 略過。** CI 可使用 `./gradlew` 執行相同工作。

## 本次驗證範圍

- 重現 0.1.12 截圖的中文長文，缺少既有句點也會進入 AI 整理。測試用固定模型回應驗證標點插入與語意分段介面，並確認原文、譯文區的「原文補充」顯示相同格式。
- 模型只回傳 token 編號、允許的單一標點與分段編號。越界、重複、改寫全文、無效標點及缺少必要標點的回應拒絕套用；數字、中文數量、ASCII 識別字、電子郵件與網址不被拆開。
- 收音整理每輪間隔 8 秒，另加模型回應時間；未完成句尾保持開放。正常停止時呼叫完成流程，允許短句及最後一句補上句尾標點。已確認的舊視窗標點與段落保留。
- 原文仍主導雙語段落對應；原文補充先套用原文排版再轉成繁中，涵蓋空白差異、CRLF 及轉字後偏移。原始片段、字詞、數字與時間戳保持原樣，JSON 另存排版註記且相容舊逐字稿。
- 即時字幕、歷史、TXT／SRT／VTT 使用同一排版資料；停止儲存及重新載入後保留格式。App 整合測試驗證定期整理、正常停止與持久化；本機 TLS HTTP 測試驗證 Foundry／OpenAI 的請求與回應解析。
- 0.1.12 的中文漏譯補顯、空白譯文、混合語言、延遲回覆、重複句子、數字及否定詞、AI 整理失敗／停用與匯出回歸測試持續通過。補顯本身在本機執行，不需要新增翻譯請求。
- Android 套件、標頭、設定、診斷與 iOS 專案版號同步為 0.1.13／14。

## 交付 APK

- 檔名：`LiveLingo-0.1.13-original-key.apk`
- Application ID：`app.livelingo.android`
- Version name／code：`0.1.13`／`14`
- Minimum／target SDK：`26`／`36`
- 大小：13,447,308 bytes
- APK SHA-256：`990ca659c91827bfed3501f6136ec6809120da828f9e84a194f36d9abcebd162`
- 簽章憑證 SHA-256：`cf232e1f105c3ac5a93ce2449b41678027b4b1ef9a54c3765c85b65a045d4bfb`
- `apksigner verify`、`aapt dump badging`、`zipalign -c -P 16 4` 通過；沿用 0.1.11／0.1.12 原 APK 的簽章。

## 尚未驗證與限制

- 需啟用「AI 標點與分段」，並有可用文字模型／部署與配額，另計模型用量。服務失敗或無有效回應時保留字幕與既有格式，不保證補上標點；異常斷線不執行正常停止的最後整理。
- 自動化測試使用固定回應，未使用真實 Foundry／OpenAI 驗證模型的標點語意品質。無 Android 實機／模擬器連接，尚未驗證畫面、覆蓋安裝與長時間效能。
- 整理採有限視窗；大量字幕一次抵達時，視窗之前尚未整理的內容可能保持原樣，原始文字不會截斷。
- 同文補顯去重採保守字元及到達順序比對；改寫用詞或譯文先到時可能仍保留原文補充。已有譯文時針對中文目標補充中文，不能保證找出所有外語漏譯。
- 補充按原文順序顯示於服務譯文下方，不推測兩軌逐句精準合併；SRT／VTT 補充使用原文時間，可能與譯文音軌重疊。未收到的原文與被服務阻擋的音訊無法復原。
- iOS 版號同步但未編譯。Android Lint 離線依賴不齊，本次沒有宣稱 Lint 通過；既有 LocalClipboardManager 棄用及 native strip 警告仍在。

實機清單見 [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)。
