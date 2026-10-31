# 即時譯 LiveLingo

使用 Kotlin Multiplatform + Compose Multiplatform 的 Android／iOS 即時字幕翻譯 App。**0.1.9 加入語音與文字模型下拉選單，依模型使用不同串流協定；AI 分段預設 GPT-6 Luna。可用 Microsoft Foundry 或 OpenAI，由 App 直接連線，不需自架後端。**

API key 由使用者自行輸入，Android 以 Keystore 保護加密金鑰並加密保存，iOS 以 Keychain 保存。原始碼、APK、逐字稿匯出及一般設定均不內建或保存明文 API key。

## 功能

| 功能 | 說明 |
| --- | --- |
| 直接連線 | Microsoft Foundry／OpenAI，模型與加密金鑰獨立保存 |
| 音源選擇 | 麥克風、裝置播放音訊；Android 播放音訊需 Android 10+ 與每次系統授權 |
| 收音狀態 | Android 顯示音量訊號；播放音訊持續約 15 秒無聲時提示檢查來源、音量或擷取限制 |
| 內容篩選處理 | 執行中收到 content_filter 時顯示片段不完整提示，保留仍可用的會話；服務正式結束時才釋放收音與連線 |
| 連線診斷 | 不開啟麥克風測試連線，顯示錯誤階段、HTTP 狀態、WebSocket 關閉碼與服務回報的結束原因 |
| 來源語言 | 自動、英文、印度英文、繁體中文、日文、韓文；自動模式於收到字幕後顯示文字推定語言 |
| 目標語言 | 英文、印度英文、繁體中文、日文、韓文 |
| 背景知識 | 管理多組場景與術語，翻譯前選擇一組或不使用 |
| 即時字幕 | 譯文優先顯示，原文在下；依時間由舊至新排列並自動捲至最新，文字不再按每段音訊換行，畫面不顯示時間戳 |
| 逐字稿 | 自動保存在裝置，保留原文與譯文各自時間戳，支援歷史記錄、重新命名與刪除 |
| 匯出 | SRT／WebVTT 保留時間戳，TXT 含閱讀段落與時間明細，JSON 保留原始片段及 AI 段落位置 |
| 繁中校正 | 目標選繁中台灣時，提示詞加本機 OpenCC 字詞轉換與全型句讀逗號；保留數字分隔符、時間戳與原始片段 |
| AI 語意分段 | 使用所選服務的文字模型判斷原文、譯文的段落位置；未設定時採基本句子分段 |
| 發言者 | 手動標記接下來收到的原文，或於停止後修正 |
| Android 背景翻譯 | 前景啟動錄音服務後，可切換 App／鎖定螢幕，從通知停止並儲存；iOS 仍為前景翻譯 |
| 翻譯策略 | 速度：優先即時／平衡；品質：忠實原文／自然流暢，工作階段開始前選定並保存到本機 |

「自動」是來源語言偵測，目標語言需明確選定。印度英文是口音與慣用語提示，並非另一個保證準確的辨識引擎。繁體中文使用台灣用語。

本版顯示字幕，不播放合成譯音。直接口譯模型仍可能在服務端產生語音，App 使用其輸出逐字稿作為譯文，仍會有模型使用量。

## 0.1.9 模型選擇與請求格式

「設定」先選服務，再以語音模型、文字模型兩個下拉選單選擇類型。OpenAI 選擇會帶入模型 ID；Foundry 另填實際部署名稱，可沿用自訂名稱。選單是已實作的協定清單，不代表帳號已有權限、配額或部署。

| 語音選項 | 服務 | 行為與限制 |
| --- | --- | --- |
| GPT-Live 1 | OpenAI／Foundry | 保留既有直接翻譯、背景知識、速度及品質設定；使用 `/live/sessions` 與 `session.start` |
| GPT Realtime Translate | OpenAI／Foundry | 使用 `/realtime/translations?model=…`、`session.update`、`session.input_audio_buffer.append`；自動偵測來源語言，不支援背景知識或自訂策略 |
| GPT Live Transcribe ＋文字翻譯 | OpenAI／Foundry | 使用 `/realtime?intent=transcription` 與 transcription session；先取得原文，再由所選文字模型產生譯文 |
| MAI Transcribe 2 Streaming ＋文字翻譯（預覽） | Foundry | 需 `services.ai.azure.com` 資源與可用部署；使用 `/mai/v1/realtime?intent=transcription`，不傳送 GPT-Live 專用欄位 |

- Realtime Translate 的原文字幕須另外設定轉錄模型。OpenAI 預填 `gpt-realtime-whisper`；Foundry 預設留空，需另部署並填入實際名稱。留空只有譯文；轉錄另計用量。
- 兩種轉錄方案使用 24 kHz／16-bit／單聲道 PCM，約每 3 秒 commit，停止時提交剩餘音訊。確認全文會取代暫存 delta，依 commit 順序翻譯，不重複串接 delta 與全文；不提供自動說話者辨識。
- 文字翻譯逐段執行，附上有限前文；來源字幕先保存，文字翻譯失敗會停止本次翻譯並保留原文，不重送音訊或重試被阻擋內容。AI 分段失敗則沿用基本分段，不中止翻譯。
- 轉錄模式時間由音訊區段估算；Realtime Translate 由串流 elapsed time 估算，均非逐字對齊。GPT-Live 原有時間戳保留。

| 文字選項 | Chat Completions 參數 |
| --- | --- |
| GPT-6 Luna（預設、建議） | `reasoning_effort: none` |
| GPT-5.4 Mini | `reasoning_effort: none` |
| GPT-6.1 Sol | `reasoning_effort: low`，增加推理與輸出 token 預算 |
| GPT-4.1 Mini（原有模型） | 不傳推理參數 |
| 自訂相容文字模型 | 不傳推理參數；須支援 Chat Completions，AI 分段另須支援 JSON object |

全部文字選項使用 `max_completion_tokens`，不傳 `temperature`。分段只取得句子換段編號；轉錄方案的文字翻譯使用獨立請求，不受 AI 分段開關影響。兩者共用所選文字模型及服務金鑰，分別計算用量。

升級會將舊的空白／`gpt-4.1-mini` 預設遷移為 `gpt-6-luna`；原有任意自訂文字部署名稱保留，請在下拉選單指定其基礎模型。遷移只執行一次，之後可自行改回其他選項。Foundry 必須先部署對應文字模型。切換同一資源的語音模型不會清除或改綁金鑰；兩個服務的模型與金鑰獨立保存。

「測試連線」依目前表單測試語音模型，成功後仍須儲存。它不開啟收音、不建立逐字稿、不測試文字模型。需實際收到字幕後確認文字翻譯與 AI 分段。

版本 `0.1.9`／versionCode `10`。協定與原生 HTTP 自動化測試結果見 [BUILD_STATUS.md](BUILD_STATUS.md)，真實服務與裝置驗收另列於 [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)。

協定依據：[Foundry Realtime 翻譯與轉錄](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/realtime-audio-websockets)、[MAI Transcribe 串流協定](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-realtime)、[GPT-6 Luna 模型](https://developers.openai.com/api/docs/models/gpt-6-luna)。

## 開始使用

1. 在 Microsoft Foundry 中準備所選語音模型部署與配額；文字翻譯／AI 分段另需文字模型部署。
2. 打開 App「設定」，填入三個欄位：

| 欄位 | 範例／用途 |
| --- | --- |
| Foundry 端點 | `https://YOUR-RESOURCE.openai.azure.com`，使用資源根端點 |
| 語音模型／部署名稱 | 選擇基礎模型類型，再填入實際部署名稱，例如 `gpt-live-1` |
| 文字模型／部署名稱 | 預設 GPT-6 Luna；填入同一資源的實際部署名稱 |
| API key | 該 Foundry 資源的 API key |

3. 按「測試連線」檢查目前欄位。測試成功後按「儲存連線設定」，再返回「即時翻譯」。測試本身不會儲存欄位。
4. 選擇音源、來源／目標語言與本次背景知識。開始翻譯後依系統提示授權；播放音訊模式須再同意音訊擷取，然後切換到來源 App 播放。
5. 停止後可到「逐字稿」檢視、修正發言者與匯出字幕。

不需要 relay token、Docker、Java 後端或另外申請伺服器。App 會依選擇的模型將 HTTPS 資源端點轉成對應 WSS 路徑。請填資源根網址，不要填 Foundry Portal、專案管理網址或自行拼接 API 路徑。

API key 儲存後不會重新顯示。只修改部署名稱時，API key 留空可保留原值；變更資源端點時必須重新輸入對應 key，避免將舊 key 傳給另一個資源。若按「移除 API key」，再次翻譯前須重新輸入。

## 0.1.8 操作與排版

- 語言與音源先選；「聽到的語言 → 翻譯成」直接呈現方向，較長的語言名稱可換行。
- 移除大幅首頁橫幅，縮短頂部列。「更多選項」收納背景知識、速度、品質；收音說明可按需展開。
- 開始／取消／停止按鈕固定在捲動區下方、導覽列上方。未設定連線時直接前往設定，連線中可取消，整理字幕期間避免重複開始。
- 收音時以字幕為主；結束後保留上一段字幕與「調整設定」、「開始下一段」。中斷不再用彈出視窗遮住字幕，可按「查看診斷」。
- 下一段是使用者手動開始的新工作階段，前一份逐字稿保留在歷史。沒有自動重連、音訊補送或背景重試。未儲存的字幕存在時禁止新場次覆蓋記憶體中的唯一副本。

### 繁中逗號

繁中台灣字幕中的句讀逗號使用 `，`，移除逗號後的多餘半形空白。數字之間的逗號仍保留，例如 `1,234.56`、`1,00,000`，不推算或改寫數值。非繁中語言不受影響。即時顯示、歷史閱讀、TXT、SRT、WebVTT 採相同規則，跨音訊分片／字幕 cue 的數字逗號也保留；JSON 原始片段不變，SRT 時間戳的半形逗號不變。

### 音源

在「即時翻譯 → 準備翻譯 → 音源」選擇，停止後才能切換。選擇會保存，工作階段及 TXT／JSON 也記錄實際選定音源。

| 音源 | 用法與限制 |
| --- | --- |
| 麥克風 | 收取目前麥克風的聲音，包含現場對話或外放音訊；Android 支援背景與鎖螢幕收音 |
| 裝置播放音訊 | Android 10+ 收取允許擷取的媒體播放聲音，不混入麥克風；其他 App 同時播放的媒體也可能被收取 |

Teams 選項及套件查詢、UID 擷取程式已移除。若 0.1.7 儲存的是 Teams，升級後提示改選麥克風，需使用者開始才會收音；不會擴大成所有 App 的播放擷取。歷史逐字稿保留舊音源資料。

Android 公開 AudioPlaybackCapture 只允許 `USAGE_MEDIA`、`USAGE_GAME`、`USAGE_UNKNOWN` 且來源允許其他 App 擷取。通話用途與禁止錄製的內容可能無聲，App 不能繞過來源的限制。

播放擷取需要錄音權限，以及每次開始的螢幕分享／錄製授權。LiveLingo **只建立音訊擷取，不建立虛擬顯示器、不讀取或傳送畫面**。Android 14+ 先取得同意，再啟動 `mediaProjection` 前景服務，最後使用一次性授權建立擷取。撤回授權或鎖定螢幕時可能停止；取消或拒絕時不連線。音量條只表示音訊訊號；約 15 秒無聲會提示，不自動改變音源。iOS 與 Android 8／9 仍只提供麥克風。

實作依據：[Android 音訊播放擷取](https://developer.android.com/media/platform/av-capture)、[前景服務類型與授權順序](https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection)、[MediaProjection 資源回收](https://developer.android.com/media/grow/media-projection#resource-recovery)。

### 中斷與內容篩選

0.1.7 已修正「僅收到篩選通知便由 App 主動斷線」的錯誤。這次提供的 0.1.7 報告屬於**篩選通知後傳輸連線也已中斷**，沒有足夠資訊確認是服務的內容終止或其他傳輸原因。0.1.8 明確區分：

| 觀察到的事件 | 處理 |
| --- | --- |
| `content_filter`，連線仍可用 | 提示部分片段不完整，同一連線繼續接收後續字幕 |
| `session.closed(reason=content)` | 分類 `CONTENT_FILTER`，記錄服務確定回報的 `content` 原因，停止收音 |
| 篩選通知後斷線，尚未恢復字幕且沒有正式結束原因 | 分類 `DISCONNECTED_AFTER_FILTER`，保留最近的篩選錯誤碼與可取得的 WebSocket 關閉碼；不宣稱篩選是斷線的確定原因 |
| 篩選後已恢復字幕，稍後另行斷線 | 依本次連線事件分類，舊篩選仍保留在逐字稿的不完整標記 |

報告只輸出數字關閉碼及已知的工作階段原因，不輸出伺服器關閉文字、音訊、逐字稿、背景知識或金鑰。沒有收到的欄位明示未取得，不使用 App 自己清理時產生的關閉碼冒充服務回報。

真正由服務結束的工作階段無法從 App 保持開啟，已阻擋內容也不能復原。若一般對話反覆中斷，請使用新版診斷由資源管理者核對服務端記錄。本版本不改變服務端篩選設定，也不承諾消除服務端誤判；尚未使用真實憑證重現使用者的案例。

依據：[GPT-Live 內容審核與工作階段生命週期](https://developers.openai.com/api/docs/guides/live-conversations#handle-moderation)、[Microsoft Foundry GPT-Live 錯誤與結束事件](https://learn.microsoft.com/en-us/azure/foundry/openai/gpt-live-reference#error-handling)。

版本 `0.1.8`／versionCode `9`；驗證結果見 [BUILD_STATUS.md](BUILD_STATUS.md)。

## 0.1.6 繁中、AI 分段與服務切換

- 修正即時字幕把換行全部轉成空白的問題。原文、譯文、歷史閱讀及 TXT 都能呈現段落；音訊分片不會被當成句子。
- 繁中台灣以嚴格提示詞加離線字詞轉換處理。使用 OpenCC 授權字典，以最長詞組匹配，再套用台灣詞彙；不是重寫內容的 AI 校稿。字幕中的數值不做修正或推算。原始 provider fragments 與時間戳繼續保留在 JSON。
- 設定可選 Microsoft Foundry／OpenAI，兩者模型名稱與加密 API key 獨立保存。切換不會刪除另一服務的設定；舊版 Foundry 設定自動沿用，工作階段中不可切換服務。
- OpenAI 預設 `gpt-live-1`，使用官方 `wss://api.openai.com/v1/live/sessions` 與 Bearer 認證。Foundry 使用資源 `/openai/v1/live/sessions` 與 `api-key` 標頭。兩者皆為 GPT-Live 協定，不是一般 Realtime 模型。

### AI 分段設定（0.1.9 更新）

設定開啟「AI 語意分段」，填入相容 Chat Completions 與 JSON object 的**文字模型**後儲存：

| 服務 | AI 分段文字模型 |
| --- | --- |
| Microsoft Foundry | 預設選 GPT-6 Luna；先在同一資源部署，再填入實際部署名稱 |
| OpenAI | 預設 `gpt-6-luna`，可從文字模型選單切換為帳號可使用的模型 |

GPT-Live 是語音模型，不能填在 AI 分段文字模型欄。分段只將字幕送至所選服務，使用該服務金鑰，另計文字模型用量。

約每 20 秒與停止時，將每個軌道最近最多 40 個完整句子、約 6,000 字元送出。AI 只回傳「哪些句子之後換段」的編號；App 驗證編號後插入空白行，不接受模型改寫原文、數字或譯文。尚未完成的句子立即顯示，後續再整理。保留既有模型換行；沒有設定、暫時失敗或尚未完成 AI 判斷時，先以 2–3 句的基本分段顯示，畫面明示狀態。

「測試連線」只測所選語音模型。AI 分段於有字幕時使用，分段失敗不停止錄音或丟棄逐字稿。轉錄方案的文字翻譯另有獨立請求，翻譯失敗會停止本次工作階段並保留原文。AI 段落位置與來源快照一起保存，亂序或新增文字造成快照不符時不套用舊位置。

### OpenAI 開始使用

選「OpenAI」，選取帳號可使用的語音模型並填入 API key，測試後儲存。端點固定為官方 API 網域，不能輸入代理主機。背景知識、速度與品質是否生效依上方模型表；Android 音源與背景服務沿用原本操作。

本次 Android 為 `0.1.6`／versionCode `7`。建置、測試、簽章驗證與未驗證項目見 [BUILD_STATUS.md](BUILD_STATUS.md)。

實作依據：[OpenAI GPT-Live WebSocket](https://developers.openai.com/api/docs/guides/voice-websockets?api=live)、[OpenAI GPT-Live 工作階段](https://developers.openai.com/api/docs/guides/live-conversations)、[Foundry v1 Chat Completions](https://learn.microsoft.com/en-us/azure/foundry/openai/latest?view=foundry-classic)。OpenCC 授權與版本見 `third_party/OpenCC/`，授權亦包含於 App 資源。

## 0.1.5 語言推定、字幕閱讀與翻譯策略

- **自動偵測語言**：當來源語言為「自動偵測」時，原文卡片顯示「自動偵測（英文）」等。此文字由 App **根據已收到的原文字幕推定**，並非 Foundry 回傳的語音語言識別碼；僅有少量文字、拉丁字母多語、日文純漢字與口音時無法可靠辨識。印度英文與一般英文無法單靠逐字稿區分。提示會有不確定標示，並可能在接收更多文字後變動。
- **字幕排版**：Foundry 的 `session.input_transcript.delta` 與 `session.output_transcript.delta` 是音訊分片，並非句子。即時畫面將原始片段串接成一份可自然換行的文章，不再依片段插入空白行。字幕原始片段、時間戳與 SRT／WebVTT／TXT／JSON 匯出不改動。
- **翻譯速度**：可選「優先即時」或「平衡」（預設），由 prompt 指示模型將短語盡早譯出或保留短距離上下文。這並非可保證的延遲上限，設定會於下一場工作階段生效。
- **翻譯品質**：可選「忠實原文」（預設）或「自然流暢」，前者特別強化金額、單位、否定、語意邊界，後者優先自然語法但同樣禁止補充未說出的內容。品質仍可能受模型與收音影響。
- **不支援的假參數不傳送**：GPT-Live 的 `session.start` 只使用官方支援的 model、instructions、audio.output.voice、delegation。沒有將一般 Realtime API 的 VAD、temperature、reasoning_effort 等項目塞入嚴格的 GPT-Live session 物件。
- 版本號更新至 `0.1.5`／versionCode `6`，維持原 App ID 與舊版相容的逐字稿／金鑰儲存格式。

## 0.1.4 即時字幕與 Android 背景翻譯

即時字幕頁面先顯示「原文」再顯示「譯文」。每個字幕軌道依時間從上往下排列，不限最新三段且自動捲動到最後一段。即時顯示不含時間戳；「逐字稿」檢視及 SRT／WebVTT／TXT／JSON 匯出仍包含時間資訊，不改變既有記錄格式。

Android 會先在可見畫面取得麥克風權限並啟動 `microphone` 型態前景服務，再連上 Microsoft Foundry。收音、WebSocket 與即時寫入逐字稿由不依賴 Activity 的工作階段管理，切換其他 App、按 Home 或鎖定螢幕不會主動停止翻譯。錄音時有通知與「停止並儲存」操作。停止後仍會嘗試完成最後音訊的送出與逐字稿保存。

**限制與安全性**：必須在 App 畫面中手動開始；無法從背景自動啟動麥克風，也不會在重新開機／程序遭系統終止後自動恢復。Android 仍可能因為使用者強制停止、來電、麥克風被其他 App 占用、網路失效或系統限制中止翻譯。通知權限被拒絕時，部分 Android 版本可能不在通知抽屜顯示通知，仍可從 App 停止；建議允許通知。iOS 目前仍只在前景翻譯，不會假裝 iOS 已支援背景收音。

## 0.1.3 認證修正

修正 Azure GPT-Live WebSocket 的 API key 認證：現在以 `api-key` 標頭傳送資源金鑰。0.1.1／0.1.2 將資源 key 放在 `Authorization: Bearer`；LiveKit 的 Azure GPT-Live 實作記錄此用法會引發帶 `?api-key=` 的 HTTP 302 轉址。`Bearer` 用於 Entra 存取權杖，不能直接將其他 HTTP API 的認證範例套用在此流程。

修正套用於 Android 與 iOS。端點、部署名稱及已加密儲存的 key 可繼續使用；App 仍不跟隨轉址，也不把 key 放進 URL。診斷報告會顯示「認證方式：api-key 標頭」，不包含金鑰值。

實作依據：[LiveKit Azure GPT-Live 認證程式碼](https://github.com/livekit/agents/blob/78c154d6edfff305b755ede26f42f11cf280bf4e/livekit-agents/livekit/agents/llm/_realtime/gpt_live.py#L580)。此修正針對已收到的 302 握手診斷；實際資源的連線結果仍需在裝置上驗證。

## 0.1.2 連線與診斷功能

修正 Android 的 WebSocket 設定：Ktor 3.3.1 的 OkHttp 引擎不支援變更 `maxFrameSize`，舊版會在握手成功後拋出例外。Android 現在保留引擎預設值，仍由共用接收程式檢查 1 MiB 訊息上限。iOS 的 Darwin 引擎支援此設定，因此保留原有限制。

「測試連線」會建立並關閉一個短暫 GPT-Live 工作階段，不啟用麥克風，也不傳送背景知識或逐字稿；建立會話仍可能產生服務使用量。測試使用目前表單，API key 留空時只會使用同一資源已儲存的 key。

若連線失敗，可用「複製診斷」或設定頁的「複製／分享」取得報告。報告包含失敗階段、App 診斷碼與可取得的 HTTP 狀態、服務錯誤代碼及請求識別碼，不包含 API key、背景知識、逐字稿或原始錯誤內容。資源名稱與部署名稱也會遮蔽；網路尚未收到 HTTP 回應時，不會有 HTTP 狀態碼。

從 0.1.1 使用相同簽章覆蓋安裝可保留已儲存的連線設定與資料，不必先移除 App。

## 從 0.1.0 更新

保持相同 App ID 與開發簽章，可直接覆蓋安裝。原有背景知識、逐字稿、來源及目標語言設定會保留。

0.1.0 的自架服務網址與存取權杖不會被當成 Foundry 認證。更新後請重新填入 Foundry 端點、部署名稱及 API key；舊 relay token 於載入與遷移成功後清除。

## 專案與建置

- `core/`：語言、背景知識、GPT-Live 事件解析與字幕匯出。
- `shared/`：Compose 介面、會話控制、Foundry WebSocket、本機保存與平台錄音介面。
- `androidApp/`：Android 主機、權限與生命週期。
- `iosApp/`：SwiftUI 主機、AVAudioEngine、Keychain、系統分享及 Xcode 專案。
- `.github/workflows/build.yml`：共用測試、Android APK 與 iOS 編譯。

Android 需求：JDK 17、Android SDK 36，最低 Android 8／API 26。

```bash
./gradlew :core:jvmTest :shared:testDebugUnitTest :androidApp:assembleDebug
```

輸出：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`。

只有 JDK、沒有 Android SDK 時，仍可執行核心測試：

```bash
./gradlew -Pheadless=true :core:jvmTest
```

iOS 需要 macOS 與相容 Xcode。開啟 `iosApp/LiveLingo.xcodeproj`，依 [iOS 說明](iosApp/README.md) 建置；實體 iPhone 安裝需設定自己的 Apple 開發者簽章。

Android 正式簽章與 GitHub Actions 開發簽章設定見 [Android 說明](androidApp/README.md)。不要將 keystore 或真實 API key 提交到 GitHub。

## 連線、錄音與資料處理

- App 以 WSS 直接連接所選服務，沒有中繼後端。Foundry 使用 `api-key`，OpenAI 使用 `Authorization: Bearer`；金鑰不放在 URL。
- 錄音為 24 kHz、單聲道、PCM16 little-endian。iOS 會從裝置實際取樣率轉換。
- 收到 `session.started` 後才開始收音；停止時送出最後已擷取的音訊與 `session.close`，等候最後字幕及 `session.closed`。
- Android 在前景建立麥克風前景服務後可持續背景收音，切換 App 或鎖定螢幕不會主動停止；iOS 目前仍只在前景收音。來電、音訊中斷、系統強制停止、斷線或錯誤可能中止，且不會自動重啟錄音或重播舊音訊。
- 未收到任何服務事件達 90 秒時視為連線異常並停止；這是 App 的逾時策略，並非模型的回覆時限保證。
- 不保存原始錄音檔。音訊與選定背景知識只傳至選定服務；啟用 AI 分段時，最近字幕也送至該服務的文字模型。
- 逐字稿與背景知識保存在 App 專用空間；API key 使用系統保護的安全儲存區，與一般資料分開。
- 停用重新導向，避免認證資訊跟隨轉址；Foundry 僅接受受支援的 Azure 資源端點，OpenAI 僅接受官方 API 主機。

## 字幕與發言者限制

GPT-Live 回傳的是帶時間範圍的串流片段，沒有每個字的精準對齊或多人 `speaker_id`。App 合併相鄰片段方便閱讀，原始片段仍保存於 JSON。

原文與譯文時間各自保留，分別匯出字幕軌；即時字幕依各軌道時間從舊到新排列並自動捲動，時間戳僅於歷史記錄與匯出時顯示。不假造逐句配對或自動說話者辨識。即時手動標籤套用到接下來收到的原文，字幕可能延遲抵達，因此可在停止後修正。

背景知識只用來輔助語境與術語，不是保證採用的字典；翻譯仍可能有誤。JSON 匯出包含本次背景知識全文，但不包含 API key。

## 驗證狀態

本版測試及建置結果見 [BUILD_STATUS.md](BUILD_STATUS.md)。測試涵蓋兩家服務的本機 TLS 連線、繁中與數值保留、AI 段落位置、金鑰隔離、音源選擇，以及內容篩選事件後繼續／結束的不同生命週期。單元測試不代表已驗證 Teams 實機擷取或服務端誤判是否消失。

## 官方介面參考

- [GPT-Live WebSocket 指南](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/gpt-live)
- [GPT-Live 事件參考](https://learn.microsoft.com/en-us/azure/foundry/openai/gpt-live-reference)
- [GPT-Live 工作階段與認證範例](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/gpt-live-webrtc)
- [Microsoft Foundry 端點](https://learn.microsoft.com/en-us/azure/ai-studio/ai-services/concepts/endpoints)

GPT-Live 與傳統 GPT Realtime API 協定不同；不要將事件改成 `input_audio_buffer.append` 或 `response.audio.delta`。
