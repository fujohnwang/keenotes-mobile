## JavaFX Keep it 卡死修复记录

- 根因定位在 FX 线程的 optimistic note resolve 阶段：`noteItems.set()` 触发 `NoteListCell.updateItem()`，进而走 `NoteCardView.update()` 的 `TextArea.setText()` 和同步 layout 刷新，长期驻留后容易进入布局反馈循环。
- 修复取舍：确认远端 note 时只走 lightweight metadata update，更新 id/date/channel，不重新 setText；内容本身不变，真实数据会在后续 reload 中自然对齐。
- 同时把 `NoteCardView` 的 layout refresh 合并到下一次 FX pulse，并让边框 `AnimationTimer` 跑满后停止，降低长期驻留的 FX 线程压力。
