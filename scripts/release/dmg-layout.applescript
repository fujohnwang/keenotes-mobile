-- Shared by local releases and GitHub Actions. Target the actual mount path,
-- since another KeeNotes volume may already be mounted.
on run argv
  set mountPath to item 1 of argv
  set mountedFolder to POSIX file mountPath as alias
  set backgroundFile to POSIX file (mountPath & "/.background/background.tiff") as alias
  tell application "Finder"
    set dmgFolder to folder mountedFolder
    open dmgFolder
    set current view of container window of dmgFolder to icon view
    set toolbar visible of container window of dmgFolder to false
    set statusbar visible of container window of dmgFolder to false
    set viewOptions to icon view options of container window of dmgFolder
    set arrangement of viewOptions to not arranged
    set icon size of viewOptions to 100
    set text size of viewOptions to 12
    set label position of viewOptions to bottom
    set background picture of viewOptions to backgroundFile
    set position of item "KeeNotes.app" of dmgFolder to {150, 150}
    set position of item "Applications" of dmgFolder to {350, 150}
    set bounds of container window of dmgFolder to {100, 100, 600, 400}
    update dmgFolder without registering applications
    delay 2
    close container window of dmgFolder

    -- Reopen after assigning the background, then persist the final window
    -- geometry and chrome instead of Finder's initial/default window state.
    open dmgFolder
    set toolbar visible of container window of dmgFolder to false
    set statusbar visible of container window of dmgFolder to false
    set bounds of container window of dmgFolder to {100, 100, 600, 400}
    update dmgFolder without registering applications
    delay 2
    close container window of dmgFolder
  end tell
end run
