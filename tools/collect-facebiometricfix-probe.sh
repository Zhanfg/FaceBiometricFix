#!/system/bin/sh
# FaceBiometricFix 2.2-test3 probe collector
# No arguments required. Reproduce the overlap once, then run this script as root.

set -u

TS="$(date +%Y%m%d_%H%M%S 2>/dev/null || echo unknown)"
OUT="/sdcard/Download/FaceBiometricFix_Probe_${TS}.txt"

{
  echo "========================================"
  echo " FaceBiometricFix runtime probe"
  echo " generated_at: $(date 2>/dev/null)"
  echo "========================================"
  echo
  echo "=== Device ==="
  echo "manufacturer=$(getprop ro.product.manufacturer)"
  echo "model=$(getprop ro.product.model)"
  echo "device=$(getprop ro.product.device)"
  echo "android=$(getprop ro.build.version.release)"
  echo "sdk=$(getprop ro.build.version.sdk)"
  echo "build=$(getprop ro.build.display.id)"
  echo

  echo "=== logcat: FaceBiometricFix ==="
  logcat -d -v threadtime 2>/dev/null | grep -a -i "FaceBiometricFix" | tail -n 1200
  echo

  echo "=== LSPosed/LSP module logs: FaceBiometricFix ==="
  FOUND=0
  for DIR in /data/adb/lspd/log /data/adb/lsposed/log /data/adb/lsp/log; do
    [ -d "$DIR" ] || continue
    for F in "$DIR"/*; do
      [ -f "$F" ] || continue
      case "$F" in
        *.log|*.txt|*.log.gz)
          if echo "$F" | grep -q '\.gz$'; then
            if command -v gzip >/dev/null 2>&1; then
              MATCH="$(gzip -cd "$F" 2>/dev/null | grep -a -i "FaceBiometricFix" | tail -n 400)"
            else
              MATCH=""
            fi
          else
            MATCH="$(grep -a -i "FaceBiometricFix" "$F" 2>/dev/null | tail -n 400)"
          fi
          if [ -n "$MATCH" ]; then
            FOUND=1
            echo "--- $F ---"
            echo "$MATCH"
          fi
          ;;
      esac
    done
  done
  [ "$FOUND" -eq 1 ] || echo "(no matching LSPosed log files found)"
  echo

  echo "=== Recent biometric-related logcat context ==="
  logcat -d -v threadtime 2>/dev/null \
    | grep -a -i -E "FaceBiometricFix|AuthBiometric|AuthContainer|Udfps|Fingerprint.*overlay|BiometricPrompt" \
    | tail -n 1600
  echo
} > "$OUT" 2>&1

chmod 0644 "$OUT" 2>/dev/null || true
echo "Saved: $OUT"
