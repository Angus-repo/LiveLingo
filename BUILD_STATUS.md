# LiveLingo 0.1.1 驗證狀態

本版改為 App 直接連 Foundry，移除中繼後端依賴。0.1.0 的後端測試不計入本版驗證。

## 已執行：2026-10-29

以 JDK 17、Gradle 8.14.3、Android SDK 36 執行：

```sh
./gradlew :core:jvmTest :shared:testDebugUnitTest :androidApp:assembleDebug
```

結果：**BUILD SUCCESSFUL，53 項測試全部通過，0 失敗、0 錯誤、0 略過。**

| 範圍 | 測試數 | 覆蓋內容 |
| --- | ---: | --- |
| Core | 27 | Foundry 端點、事件與 PCM；語言與背景指示；SRT／WebVTT／TXT／JSON 匯出與字幕時間 |
| Shared | 26 | 直接連線狀態、停止排空、逾時、錯誤遮蔽；舊版遷移、金鑰與端點綁定、資料保存及損壞金鑰復原 |

Android 開發測試 APK：`LiveLingo-0.1.1.apk`，12,397,019 bytes。

- Application ID：`app.livelingo.android`。
- Version name／code：`0.1.1`／`2`。
- Minimum／target SDK：`26`／`36`。
- `apksigner verify` 與 `zipalign` 檢查通過。
- 簽章憑證 SHA-256 與 0.1.0 相同，可覆蓋安裝保留資料。
- 憑證 SHA-256：`cf232e1f105c3ac5a93ce2449b41678027b4b1ef9a54c3765c85b65a045d4bfb`。
- APK SHA-256：`87e3ebbc5d9e6cb23acd60ab69fd7d8a94fc57dd236eea47cb817e86518a640d`。

上述 APK 是本次交付的檔案；其他環境重新建置的雜湊可能不同。CI 若未設定 Android 說明所列的簽章 secret，runner 會使用另一把開發簽章。

Android Manifest、iOS plist、Xcode 專案引用與 CI YAML 已做靜態檢查。Gradle Wrapper scripts 與 JAR 已核對官方版本。

## GitHub Actions 實際結果

[Run 40888243981](https://github.com/Angus-repo/LiveLingo/actions/runs/40888243981) 的三個工作全部成功，驗證程式碼 commit：`d3332a4e1bdba0fedb9f4647309f0b7b91936f24`。

| 工作 | 結果 |
| --- | --- |
| shared-tests | 無 Android SDK 的 headless core 測試通過 |
| android | Android APK 建置及 shared 單元測試通過 |
| ios | Kotlin/Native framework 及未簽章 ARM64 模擬器 App 編譯通過 |

iOS 使用 Xcode 16.4（16F6）、iOS Simulator 18.5 SDK。Xcode 日誌於 2026-10-29 14:10:33 UTC 回報 `BUILD SUCCEEDED`。

## 尚待驗證

沒有提供真實 Foundry 認證，尚未驗證雲端連線與翻譯品質。

尚未做 Android／iOS 實機麥克風、背景切換與長時間會話驗證。iPhone 簽章與實機安裝仍須使用自己的 Apple Developer Team。

iOS 專案宣告最低 16.0；此次 linker 提示 Skiko 內 ICU 資料物件是以 iOS Simulator 17.2 編譯。模擬器 18.5 SDK 建置成功不代表已驗證 iOS 16 的執行相容性，發佈前需完成最低支援版本的實機驗證。
