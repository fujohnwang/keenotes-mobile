#!/bin/bash
set -euo pipefail
# Pass a dedicated simulator UDID. This script never selects/erases an existing device.
: "${1:?Usage: validate-iap.sh DEDICATED_SIMULATOR_UDID [OUTPUT_DIRECTORY]}"
validation_device="$1"
validation_dir="${2:-/tmp/keenotes-iap-validation/full}"
validation_root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$validation_dir"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$validation_dir/tls.key" -out "$validation_dir/tls.crt" -days 1 -subj '/CN=localhost' > "$validation_dir/tls-generation.log" 2>&1
python3 "$validation_root/scripts/tls-fixture.py" "$validation_dir/tls.crt" "$validation_dir/tls.key" 18443 > "$validation_dir/tls-server.log" 2>&1 &
validation_tls_pid=$!
python3 "$validation_root/scripts/websocket-fixture.py" > "$validation_dir/websocket-server.log" 2>&1 &
validation_ws_pid=$!
trap 'kill "$validation_tls_pid" "$validation_ws_pid" 2>/dev/null || true' EXIT
xcodebuild -project "$validation_root/KeeNotes.xcodeproj" -scheme KeeNotes -destination "platform=iOS Simulator,id=$validation_device" -derivedDataPath "$validation_dir/DerivedData" -skip-testing:KeeNotesTests/LocalStoreKitTests -skip-testing:KeeNotesUITests -parallel-testing-enabled NO -resultBundlePath "$validation_dir/behavior.xcresult" CODE_SIGN_IDENTITY=- test > "$validation_dir/behavior.log" 2>&1
xcodebuild -project "$validation_root/KeeNotes.xcodeproj" -scheme KeeNotes -destination "platform=iOS Simulator,id=$validation_device" -derivedDataPath "$validation_dir/DerivedData" -only-testing:KeeNotesUITests -parallel-testing-enabled NO -resultBundlePath "$validation_dir/ui.xcresult" CODE_SIGN_IDENTITY=- test > "$validation_dir/ui.log" 2>&1
xcrun xcresulttool export attachments --path "$validation_dir/ui.xcresult" --output-path "$validation_dir/ui-attachments"
# Real StoreKitTest is separate because iOS 26.5 currently fails SKTestSession with Code=3.
# Re-run after a working runtime is available:
# xcodebuild -project "$validation_root/KeeNotes.xcodeproj" -scheme KeeNotesLocalStoreKit -destination "platform=iOS Simulator,id=$validation_device" -derivedDataPath "$validation_dir/DerivedData" -only-testing:KeeNotesTests/LocalStoreKitTests CODE_SIGN_IDENTITY=- test
