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

Android 開發測試 APK：`LiveLingo-0.1.1.apk`，12,413,403 bytes。

- Application ID：`app.livelingo.android`。
- Version name／code：`0.1.1`／`2`。
- Minimum／target SDK：`26`／`36`。
- `apksigner verify` 與 `zipalign` 檢查通過。
- 簽章憑證 SHA-256 與 0.1.0 相同，可覆蓋安裝保留資料。
- 憑證 SHA-256：`cf232e1f105c3ac5a93ce2449b41678027b4b1ef9a54c3765c85b65a045d4bfb`。
- APK SHA-256：`fd8a6bc9876006e642e5f3826b9ed1a463a9ffe58717524d73ef746864252991`。

上述 APK 是本次交付的檔案；其他環境重新建置的雜湊可能不同。CI 若未設定 Android 說明所列的簽章 secret，runner 會使用另一把開發簽章。

Android Manifest、iOS plist、Xcode 專案引用與 CI YAML 已做靜態檢查。Gradle Wrapper scripts 與 JAR 已核對官方版本。

## 尚待驗證

iOS 原始碼與 Xcode 專案已更新，但本環境沒有 macOS／Xcode。沒有提供真實 Foundry 認證，尚未驗證雲端連線與翻譯品質。

尚未做 Android／iOS 實機麥克風、背景切換與長時間會話驗證。GitHub Actions 已配置 Android 與 iOS 建置，但配置存在不代表雲端建置已通過。
