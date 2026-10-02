#!/system/bin/sh
# FaceBiometricFix 2.2.5-test dual-biometric collector
# No arguments required. Reproduce one FACE -> FINGERPRINT attempt, then run as root.

set -u

TS="$(date +%Y%m%d_%H%M%S 2>/dev/null || echo unknown)"
OUT="/sdcard/Download/FaceBiometricFix_DualAuth_${TS}.txt"

{
  echo "========================================"
  echo " FaceBiometricFix DualAuth diagnostics"
  echo " generated_at: $(date 2>/dev/null)"
  echo "========================================"
  echo
  echo "=== Device ==="
  echo "manufacturer=$(getprop ro.product.manufacturer)"
  echo "brand=$(getprop ro.product.brand)"
  echo "model=$(getprop ro.product.model)"
  echo "device=$(getprop ro.product.device)"
  echo "android=$(getprop ro.build.version.release)"
  echo "sdk=$(getprop ro.build.version.sdk)"
  echo "build=$(getprop ro.build.display.id)"
  echo

  echo "=== FaceBiometricFix ==="
  logcat -d -v threadtime 2>/dev/null \
    | grep -a -i "FaceBiometricFix" \
    | tail -n 1600
  echo

  echo "=== DualAuth / AuthSession context ==="
  logcat -d -v threadtime 2>/dev/null \
    | grep -a -i -E "DualAuth|BiometricService/AuthSession|onDialogAnimatedIn|onStartFingerprint|onAuthenticationSucceeded|onAuthenticationTimedOut|onAuthenticationRejected|onErrorReceived|onCancelAuthSession|onClientDied|onDeviceCredentialPressed|Udfps|FingerprintService|FaceService" \
    | tail -n 2800
  echo

  echo "=== LSPosed module log fragments ==="
  FOUND=0
  for DIR in /data/adb/lspd/log /data/adb/lsposed/log /data/adb/lsp/log; do
    [ -d "$DIR" ] || continue
    for F in "$DIR"/*; do
      [ -f "$F" ] || continue
      case "$F" in
        *.log|*.txt)
          MATCH="$(grep -a -i -E "FaceBiometricFix|DualAuth" "$F" 2>/dev/null | tail -n 600)"
          if [ -n "$MATCH" ]; then
            FOUND=1
            echo "--- $F ---"
            echo "$MATCH"
          fi
          ;;
        *.log.gz)
          if command -v gzip >/dev/null 2>&1; then
            MATCH="$(gzip -cd "$F" 2>/dev/null | grep -a -i -E "FaceBiometricFix|DualAuth" | tail -n 600)"
            if [ -n "$MATCH" ]; then
              FOUND=1
              echo "--- $F ---"
              echo "$MATCH"
            fi
          fi
          ;;
      esac
    done
  done
  [ "$FOUND" -eq 1 ] || echo "(no matching LSPosed log files found)"
  echo
} > "$OUT" 2>&1

chmod 0644 "$OUT" 2>/dev/null || true
echo "Saved: $OUT"
