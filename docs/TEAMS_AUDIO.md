# Teams 會議收音排查（0.1.16）

Android 的「裝置播放音訊」只擷取來源允許錄製的媒體、遊戲與未指定用途音訊，不涵蓋通話用途音訊。無法因為畫面正在顯示 Teams、已授權螢幕擷取或聽得到對方聲音，就判定第三方 App 能擷取該音訊。同機麥克風也可能由通話優先使用；Teams 靜音不保證釋放麥克風。

## 判讀畫面

- 「等待聲音」及約 15 秒靜音警告：代表本機收到的 PCM 幾乎沒有訊號，不能單憑靜音判定是哪一個 App 阻擋。
- 「收音遭系統靜音」：Android 10+ 對本 App 的錄音設定回報 `isClientSilenced`。0.1.16 每個音量更新視窗（最多每秒四次）讀取本 App 的設定，不列舉其他 App，也不修改通話路由、音訊焦點或擷取政策。系統未提供資訊或 API 失敗時，不宣稱已確認靜音。
- 已持續靜音且裝置處於 `MODE_IN_CALL`／`MODE_IN_COMMUNICATION`：說明同機通話限制，但不把通話模式當成 Teams 身分或擷取被封鎖的證據。
- 「啟動 GPT-Live」錯誤：尚未執行麥克風 `start()`。先使用設定中的「測試連線」隔離服務、模型與參數問題，不能拿此錯誤證明麥克風損壞。

0.1.16 將服務 `error` 事件與無法解析的協定資料分開。即使 `error.code` 是 null 或不在已知清單，仍分類為 `PROVIDER_ERROR`；保留已知 `error.type`、已知 schema 參數與握手成功狀態。未知字串與伺服器 message 不直接顯示。0.1.15 截圖缺少這些資訊，因此目前不能確認該次雲端啟動失敗的根因。

## 建議驗收

1. 在設定以目前服務與語音模型執行「測試連線」。此測試不使用麥克風；若失敗，複製新版診斷。
2. 完全離開同機 Teams 通話，以麥克風開始翻譯並說話，確認音量與字幕。
3. 由電腦或另一台裝置加入 Teams 並外放，手機只執行 LiveLingo 的麥克風模式。耳機中的遠端聲音不會自然進入手機麥克風。
4. 若需全部在同一支 Android 手機內完成，目前不能保證此 App 的兩種音源可以取得 Teams 會議音訊。提高音量、換模型或更新本版不會解除 Android 限制。
5. 裝置播放模式以允許擷取的本機媒體驗證；持續靜音後顯示提示，恢復訊號後移除提示。通話期間切換麥克風、停止通話及背景執行需在實機驗證。

此版修正診斷與提示，不宣稱已解決 Teams 音訊擷取，也未使用使用者的真實 API key 重現雲端錯誤。

## 官方參考

- [Android 播放擷取規則](https://developer.android.com/media/platform/av-capture)
- [Android 收音共用與靜音狀態](https://developer.android.com/media/platform/sharing-audio-input)
- [OpenAI GPT-Live WebSocket 啟動順序](https://developers.openai.com/api/docs/guides/voice-websockets)
- [OpenAI Live 錯誤事件](https://developers.openai.com/api/reference/resources/live/primary-websocket)
