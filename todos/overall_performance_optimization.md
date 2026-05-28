现在javafx桌面版程序长期跑一直开着驻留系统

当点击UI切换功能的时候，还是会僵死。

需要再整体排查下有哪些潜在的资源泄漏，尤其是网络多次间歇性中断后再重连这些情况。

这次的现象是，我点击“On this day in years past”按钮后，没反应。 再点其他按钮，也都没有反应。

看日志，倒是没看到有效信息，只有一两条warning：

```
[2026-05-28 21:51:39.614] INFO    [OkHttp https://kns.afoo.me/...] WebSocketClientService - Sync complete after DB drain: 0 notes
[2026-05-28 22:06:17.282] WARNING [WebSocket-Heartbeat] WebSocketClientService - Heartbeat timeout (877669ms silent), forcing reconnect...
[2026-05-28 22:06:17.287] INFO    [WebSocket-Heartbeat] WebSocketClientService - Scheduling reconnect in 1000ms (attempt 1/10)
[2026-05-28 22:06:17.288] WARNING [OkHttp https://kns.afoo.me/...] WebSocketClientService - WebSocket failure [SocketException]: java.net.SocketException: Socket closed
[2026-05-28 22:06:17.288] INFO    [OkHttp https://kns.afoo.me/...] WebSocketClientService - Scheduling reconnect in 2000ms (attempt 2/10)
[2026-05-28 22:06:19.290] INFO    [WebSocket-Reconnect] WebSocketClientService - Attempting reconnect...
[2026-05-28 22:06:19.292] INFO    [WebSocket-Reconnect] WebSocketClientService - Adding Authorization header: Bearer cf74...(len=36)
[2026-05-28 22:06:19.292] INFO    [WebSocket-Reconnect] WebSocketClientService - WebSocket connect request: endpoint=https://kns.afoo.me, requestUrl=wss://kns.afoo.me:443/ws, host=kns.afoo.me, port=443, path=/ws, ssl=true, origin=https://kns.afoo.me, token=Bearer cf74...(len=36), lastSyncId=19545, reconnectAttempts=2
[2026-05-28 22:06:20.708] INFO    [OkHttp https://kns.afoo.me/...] WebSocketClientService - Sent handshake with lastSyncId=19545
[2026-05-28 22:06:20.709] INFO    [OkHttp https://kns.afoo.me/...] WebSocketClientService - Heartbeat started
[2026-05-28 22:06:20.709] INFO    [OkHttp https://kns.afoo.me/...] WebSocketClientService - WebSocket connected successfully: code=101, message=Switching Protocols, url=https://kns.afoo.me/ws, headers={server=cloudflare, cf-ray=a02dd1bcb99489bc-SIN, sec-websocket-accept=zrPihXOP2DUAo8H/FfUwrrX4tQk=}
[2026-05-28 22:06:21.513] INFO    [OkHttp https://kns.afoo.me/...] WebSocketClientService - Sync complete after DB drain: 0 notes
[2026-05-28 22:24:21.779] WARNING [OkHttp https://kns.afoo.me/...] WebSocketClientService - WebSocket failure [EOFException]: java.io.EOFException
[2026-05-28 22:24:21.782] INFO    [OkHttp https://kns.afoo.me/...] WebSocketClientService - Scheduling reconnect in 1000ms (attempt 1/10)
[2026-05-28 22:24:22.831] INFO    [WebSocket-Reconnect] WebSocketClientService - Attempting reconnect...
[2026-05-28 22:24:22.835] INFO    [WebSocket-Reconnect] WebSocketClientService - Adding Authorization header: Bearer cf74...(len=36)
[2026-05-28 22:24:22.836] INFO    [WebSocket-Reconnect] WebSocketClientService - WebSocket connect request: endpoint=https://kns.afoo.me, requestUrl=wss://kns.afoo.me:443/ws, host=kns.afoo.me, port=443, path=/ws, ssl=true, origin=https://kns.afoo.me, token=Bearer cf74...(len=36), lastSyncId=19545, reconnectAttempts=1
[2026-05-28 22:24:23.591] INFO    [OkHttp https://kns.afoo.me/...] WebSocketClientService - Sent handshake with lastSyncId=19545
[2026-05-28 22:24:23.593] INFO    [OkHttp https://kns.afoo.me/...] WebSocketClientService - Heartbeat started
[2026-05-28 22:24:23.593] INFO    [OkHttp https://kns.afoo.me/...] WebSocketClientService - WebSocket connected successfully: code=101, message=Switching Protocols, url=https://kns.afoo.me/ws, headers={server=cloudflare, cf-ray=a02dec30da87fd9e-SIN, sec-websocket-accept=+RPlNlK4Ot+W/G7OqmuszZR4Pnw=}
[2026-05-28 22:24:24.331] INFO    [OkHttp https://kns.afoo.me/...] WebSocketClientService - Sync complete after DB drain: 0 notes
[2026-05-28 22:27:42.655] INFO    [Thread-21] MainContentArea - loadRecentNotes: totalCount=19478
[2026-05-28 22:27:42.703] INFO    [JavaFX Application Thread] MainContentArea - Note list loaded with 19478 total notes, tracking 0 IDs, noteModePanel.opacity=1.0, noteModePanel.visible=true
[2026-05-28 22:27:42.703] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb start: gen=11, retryAttempt=0, totalNoteCount=19478, reviewDays=0
[2026-05-28 22:27:42.704] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb: loaded 20 notes from DB (totalNoteCount=19478)
[2026-05-28 22:27:42.757] INFO    [JavaFX Application Thread] NotesDisplayPanel - loadInitialNotesFromDb: rendered 20 notes via ListView, noteItems.size=20
[2026-05-28 22:27:43.193] INFO    [JavaFX Application Thread] MainContentArea - loadReviewNotes called with period: 7 days
[2026-05-28 22:27:43.195] INFO    [Thread-22] MainContentArea - Loading notes for 7 days
[2026-05-28 22:27:43.195] INFO    [Thread-22] MainContentArea - Period info: Last 7 days, notes count: 43
[2026-05-28 22:27:43.246] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb start: gen=1, retryAttempt=0, totalNoteCount=43, reviewDays=7
[2026-05-28 22:27:43.247] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb: loaded 20 notes from DB (totalNoteCount=43)
[2026-05-28 22:27:43.272] INFO    [JavaFX Application Thread] NotesDisplayPanel - loadInitialNotesFromDb: rendered 20 notes via ListView, noteItems.size=20
[2026-05-28 22:28:10.175] INFO    [JavaFX Application Thread] MainContentArea - loadReviewNotes called with period: 7 days
[2026-05-28 22:28:10.179] INFO    [Thread-23] MainContentArea - Loading notes for 7 days
[2026-05-28 22:28:10.180] INFO    [Thread-23] MainContentArea - Period info: Last 7 days, notes count: 43
[2026-05-28 22:28:10.185] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb start: gen=2, retryAttempt=0, totalNoteCount=43, reviewDays=7
[2026-05-28 22:28:10.186] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb: loaded 20 notes from DB (totalNoteCount=43)
[2026-05-28 22:28:10.227] INFO    [JavaFX Application Thread] NotesDisplayPanel - loadInitialNotesFromDb: rendered 20 notes via ListView, noteItems.size=20
[2026-05-28 22:28:11.150] INFO    [Thread-24] MainContentArea - loadRecentNotes: totalCount=19478
[2026-05-28 22:28:11.203] INFO    [JavaFX Application Thread] MainContentArea - Note list loaded with 19478 total notes, tracking 0 IDs, noteModePanel.opacity=1.0, noteModePanel.visible=true
[2026-05-28 22:28:11.203] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb start: gen=12, retryAttempt=0, totalNoteCount=19478, reviewDays=0
[2026-05-28 22:28:11.203] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb: loaded 20 notes from DB (totalNoteCount=19478)
[2026-05-28 22:28:11.254] INFO    [JavaFX Application Thread] NotesDisplayPanel - loadInitialNotesFromDb: rendered 20 notes via ListView, noteItems.size=20
[2026-05-28 22:28:11.357] INFO    [JavaFX Application Thread] MainContentArea - loadReviewNotes called with period: 7 days
[2026-05-28 22:28:11.357] INFO    [Thread-25] MainContentArea - Loading notes for 7 days
[2026-05-28 22:28:11.357] INFO    [Thread-25] MainContentArea - Period info: Last 7 days, notes count: 43
[2026-05-28 22:28:11.411] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb start: gen=3, retryAttempt=0, totalNoteCount=43, reviewDays=7
[2026-05-28 22:28:11.411] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb: loaded 20 notes from DB (totalNoteCount=43)
[2026-05-28 22:28:11.468] INFO    [JavaFX Application Thread] NotesDisplayPanel - loadInitialNotesFromDb: rendered 20 notes via ListView, noteItems.size=20
[2026-05-28 22:28:11.890] INFO    [JavaFX Application Thread] MainContentArea - loadReviewNotes called with period: 7 days
[2026-05-28 22:28:11.891] INFO    [Thread-26] MainContentArea - Loading notes for 7 days
[2026-05-28 22:28:11.891] INFO    [Thread-26] MainContentArea - Period info: Last 7 days, notes count: 43
[2026-05-28 22:28:11.892] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb start: gen=4, retryAttempt=0, totalNoteCount=43, reviewDays=7
[2026-05-28 22:28:11.892] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb: loaded 20 notes from DB (totalNoteCount=43)
[2026-05-28 22:28:11.895] INFO    [JavaFX Application Thread] NotesDisplayPanel - loadInitialNotesFromDb: rendered 20 notes via ListView, noteItems.size=20
[2026-05-28 22:28:12.592] INFO    [JavaFX Application Thread] MainContentArea - loadReviewNotes called with period: 7 days
[2026-05-28 22:28:12.592] INFO    [Thread-27] MainContentArea - Loading notes for 7 days
[2026-05-28 22:28:12.593] INFO    [Thread-27] MainContentArea - Period info: Last 7 days, notes count: 43
[2026-05-28 22:28:12.595] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb start: gen=5, retryAttempt=0, totalNoteCount=43, reviewDays=7
[2026-05-28 22:28:12.595] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb: loaded 20 notes from DB (totalNoteCount=43)
[2026-05-28 22:28:12.643] INFO    [JavaFX Application Thread] NotesDisplayPanel - loadInitialNotesFromDb: rendered 20 notes via ListView, noteItems.size=20
[2026-05-28 22:28:13.441] INFO    [JavaFX Application Thread] MainContentArea - loadReviewNotes called with period: 7 days
[2026-05-28 22:28:13.442] INFO    [Thread-28] MainContentArea - Loading notes for 7 days
[2026-05-28 22:28:13.442] INFO    [Thread-28] MainContentArea - Period info: Last 7 days, notes count: 43
[2026-05-28 22:28:13.446] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb start: gen=6, retryAttempt=0, totalNoteCount=43, reviewDays=7
[2026-05-28 22:28:13.446] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb: loaded 20 notes from DB (totalNoteCount=43)
[2026-05-28 22:28:13.498] INFO    [JavaFX Application Thread] NotesDisplayPanel - loadInitialNotesFromDb: rendered 20 notes via ListView, noteItems.size=20
[2026-05-28 22:28:13.740] INFO    [JavaFX Application Thread] MainContentArea - loadReviewNotes called with period: 7 days
[2026-05-28 22:28:13.740] INFO    [Thread-29] MainContentArea - Loading notes for 7 days
[2026-05-28 22:28:13.740] INFO    [Thread-29] MainContentArea - Period info: Last 7 days, notes count: 43
[2026-05-28 22:28:13.741] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb start: gen=7, retryAttempt=0, totalNoteCount=43, reviewDays=7
[2026-05-28 22:28:13.741] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb: loaded 20 notes from DB (totalNoteCount=43)
[2026-05-28 22:28:13.743] INFO    [JavaFX Application Thread] NotesDisplayPanel - loadInitialNotesFromDb: rendered 20 notes via ListView, noteItems.size=20
[2026-05-28 22:28:13.924] INFO    [JavaFX Application Thread] MainContentArea - loadReviewNotes called with period: 7 days
[2026-05-28 22:28:13.924] INFO    [Thread-30] MainContentArea - Loading notes for 7 days
[2026-05-28 22:28:13.924] INFO    [Thread-30] MainContentArea - Period info: Last 7 days, notes count: 43
[2026-05-28 22:28:13.925] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb start: gen=8, retryAttempt=0, totalNoteCount=43, reviewDays=7
[2026-05-28 22:28:13.925] INFO    [LoadInitialNotes-0] NotesDisplayPanel - loadInitialNotesFromDb: loaded 20 notes from DB (totalNoteCount=43)
[2026-05-28 22:28:13.927] INFO    [JavaFX Application Thread] NotesDisplayPanel - loadInitialNotesFromDb: rendered 20 notes via ListView, noteItems.size=20
[2026-05-28 22:28:28.877] INFO    [JavaFX Application Thread] MainContentArea - MainContentArea disposed
[2026-05-28 22:28:28.880] INFO    [JavaFX Application Thread] PendingNoteService - Pending note retry scheduler stopped
[2026-05-28 22:28:28.880] INFO    [JavaFX Application Thread] WebSocketClientService - Starting immediate shutdown...
[2026-05-28 22:28:28.880] INFO    [JavaFX Application Thread] WebSocketClientService - OkHttp resources closed
[2026-05-28 22:28:28.880] INFO    [JavaFX Application Thread] WebSocketClientService - WebSocket cancelled immediately
[2026-05-28 22:28:28.881] INFO    [JavaFX Application Thread] WebSocketClientService - Shutdown completed
[2026-05-28 22:28:28.881] INFO    [OkHttp https://kns.afoo.me/...] WebSocketClientService - WebSocket failure during shutdown: java.net.SocketException: Socket closed
```





