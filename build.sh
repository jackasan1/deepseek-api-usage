#!/data/data/com.termux/files/usr/bin/bash
# 用法: ./build.sh [full|fast|clean|dbg]
#   full  = release 全量优化（R8 + 资源压缩）—— 要装到手机上用的版本
#   fast  = 跳过 R8 与资源压缩，仍然 release 签名 —— 改代码后快速验证
#   clean = 全量重编
#   dbg   = debug 变体
cd "$(dirname "$0")" || exit 1
MODE="${1:-full}"
APK=app/build/outputs/apk/release/app-release.apk
case "$MODE" in
  fast)  ARGS=":app:assembleFast";    APK=app/build/outputs/apk/fast/app-fast.apk ;;
  dbg)   ARGS=":app:assembleDebug";   APK=app/build/outputs/apk/debug/app-debug.apk ;;
  clean) ARGS="clean :app:assembleRelease" ;;
  *)     ARGS=":app:assembleRelease" ;;
esac
S=$(date +%s)
echo "▶ ./gradlew $ARGS"
./gradlew $ARGS --console=plain
RC=$?
E=$(date +%s)
echo "──────────────────────────────────"
echo "耗时 $((E-S)) 秒 | 退出码 $RC"
[ $RC -eq 0 ] && ls -la "$APK"
exit $RC
