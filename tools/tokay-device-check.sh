#!/usr/bin/env bash
# Tokay 真机验收：验证「内核外置」整条链路在真机上成立。
#
# 为什么必须跑：ASM 插桩与内核外置的失效**不在构建期**（构建永远绿），只在真机上表现为
# `dlopen failed: library "libmozglue.so" not found`。改构建链路后必须跑本脚本，退出码即结论；
# `adb devices` 为空时如实标注「待真机」，不要声称已验证。
#
# 流程：检查 adb 设备与本地发布服务可达 → 清空 App 数据（保证从零下载内核）→
# adb reverse + installDebug → 启动并轮询 logcat 等 GeckoThread MOZGLUE_READY 与 RUNNING →
# 核验设备侧落盘事实（state.json / .so 文件 / base.apk / `-greomni` 的实际落点）。
#
# 为什么不止步于 MOZGLUE_READY：它只证明 .so 加载成功；`-greomni` 指向的资源档
# （`res/base.apk`）是 XRE 在那之后才打开的，这一环坏了同样只在真机上暴露。
#
# 用法：
#   bash tools/tokay-device-check.sh                     # 默认全部步骤（远程下发，需先起发布服务）
#   bash tools/tokay-device-check.sh --packaged          # 内核随包：不启发布服务、不建 adb reverse
#   bash tools/tokay-device-check.sh --keep-data         # 不清数据（复用已装内核）
#   bash tools/tokay-device-check.sh --no-install        # 不重新构建安装，只跑启动+断言
#   bash tools/tokay-device-check.sh --pkg com.happyboy.tokay.demo
#   bash tools/tokay-device-check.sh --sdk-url <清单URL>  # 验真实发布渠道（GitHub Release 等）：
#                                                        # 不启本地服务、不建 adb reverse
#
# 退出码：0 = 通过；1 = 失败（失败原因打印在最后一行 `FAIL:` 开头）。
set -uo pipefail

PKG="${PKG:-com.happyboy.tokay.demo}"
PORT="${PORT:-28080}"
HOST="${HOST:-127.0.0.1}"
ARCH="${ARCH:-arm64-v8a}"
TIMEOUT="${TIMEOUT:-180}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_URL="http://$HOST:$PORT/sdkconfig.json"
# 1 = 清单在公网（--sdk-url 给了地址）：不启本地服务、不建 adb reverse。它覆盖的正是本地服务
# 测不到的那一段——GitHub Release 的资产地址先回 302 再落到 CDN。
REMOTE_CHANNEL=0

CLEAR_DATA=1
DO_INSTALL=1
PACKAGED=0
# 用 while+shift 而不是 `for arg in "$@"`：带值的参数必须吃掉它后面那个位置参数，
# 否则 `--pkg com.foo` 会把 com.foo 再当成一个位置参数报「未知参数」。
while [ $# -gt 0 ]; do
  case "$1" in
    --packaged)   PACKAGED=1 ;;
    --keep-data)  CLEAR_DATA=0 ;;
    --no-install) DO_INSTALL=0 ;;
    --sdk-url)    SDK_URL="${2:?--sdk-url 需要一个清单地址}"; REMOTE_CHANNEL=1; shift ;;
    --sdk-url=*)  SDK_URL="${1#*=}"; REMOTE_CHANNEL=1 ;;
    --pkg)        PKG="${2:?--pkg 需要一个包名}"; shift ;;
    --pkg=*)      PKG="${1#*=}" ;;
    --timeout)    TIMEOUT="${2:?--timeout 需要秒数}"; shift ;;
    --timeout=*)  TIMEOUT="${1#*=}" ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
  shift
done

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "── $* ──────────────────────────────"; }

# wait_for <logcat 里的固定子串>：在 DEADLINE 之前轮询，命中返回 0
wait_for() {
  while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    if adb logcat -d 2>/dev/null | grep -qF "$1"; then return 0; fi
    sleep 3
  done
  return 1
}

step "1/5 环境检查"
adb get-state >/dev/null 2>&1 || fail "没有 adb 设备（adb devices 为空）"
echo "设备: $(adb shell getprop ro.product.model | tr -d '\r') / Android $(adb shell getprop ro.build.version.release | tr -d '\r') / $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"

if [ "$PACKAGED" = 1 ]; then
  # 随包模式的意义就是"不需要网络"：这一轮既不检查服务可达，也不建 adb reverse。
  # 若代码走错到远程路径，会因为没有地址/没有服务直接失败——所以通过即证明用的是包里那份。
  echo "模式: 内核随包（不使用发布服务，也不建 adb reverse）"
elif [ "$REMOTE_CHANNEL" = 1 ]; then
  # -L 必须加：GitHub Release 的资产地址先回 302 再落到 CDN，不跟随等于只证明了"302 可达"。
  curl -sfL --max-time 20 "$SDK_URL" >/dev/null 2>&1 || fail "清单不可达: $SDK_URL"
  echo "发布渠道: $SDK_URL 可达（公网，不建 adb reverse）"
else
  curl -sf --max-time 5 "$SDK_URL" >/dev/null 2>&1 || fail "本地发布服务不可达: $SDK_URL
  先起服务：bash tools/gecko-package/serve-local.sh"
  echo "发布服务: $SDK_URL 可达"

  adb reverse "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1 || fail "adb reverse 失败"
  echo "adb reverse: tcp:$PORT → 本机 tcp:$PORT"
fi

step "2/5 清空 App 数据（确保从零下载内核）"
if [ "$CLEAR_DATA" = 1 ]; then
# "没装过"等价于"没有数据要清"，pm clear 对未安装的包必然失败，不该在这里中止
#
# 先把列表整个取出来再判断：`grep -q` 命中就退出会让上游 `tr` 收到 SIGPIPE，pipefail 下这条管道
# 判为失败 —— 于是"已安装"被误判成"未安装"、悄悄跳过 pm clear，而"从零开始"正是这一步的理由。
  INSTALLED="$(adb shell pm list packages 2>/dev/null | tr -d '\r')"
  if printf '%s\n' "$INSTALLED" | grep -qx "package:$PKG"; then
    adb shell pm clear "$PKG" >/dev/null 2>&1 || fail "pm clear 失败（包名对吗：$PKG）"
    echo "已清空 $PKG 数据"
  else
    echo "包尚未安装（全新安装即为干净状态），跳过 pm clear"
  fi
else
  echo "跳过（--keep-data）"
fi

if [ "$DO_INSTALL" = 1 ]; then
  if [ "$PACKAGED" = 1 ]; then
    step "3/5 构建并安装（-PpackageGeckoKernel=true，不传清单地址）"
    ( cd "$REPO" && sh gradlew :demo:installDebug -PpackageGeckoKernel=true 2>&1 | tail -5 ) \
      || fail "gradlew :demo:installDebug（随包模式）失败"
  else
    step "3/5 构建并安装（-PsdkConfigUrl=$SDK_URL）"
    ( cd "$REPO" && sh gradlew :demo:installDebug "-PsdkConfigUrl=$SDK_URL" 2>&1 | tail -5 ) \
      || fail "gradlew :demo:installDebug 失败"
  fi
else
  step "3/5 跳过构建安装（--no-install）"
fi

step "4/5 启动 App 并等待 Gecko 内核加载"
adb logcat -c 2>/dev/null
# 进程已在运行时 monkey 只把它调到前台、不产生新日志，下面的轮询必然超时
adb shell am force-stop "$PKG" >/dev/null 2>&1
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
  || fail "启动 App 失败: $PKG"

DEADLINE=$(( $(date +%s) + TIMEOUT ))

if ! wait_for "State changed to MOZGLUE_READY"; then
  echo "--- 最后 40 行相关日志 ---"
  adb logcat -d 2>/dev/null | grep -iE "Gecko|dlopen|mozglue|Tokay|Installer" | tail -40
  fail "等待 ${TIMEOUT}s 未见 GeckoThread MOZGLUE_READY"
fi
echo "✅ GeckoThread 已进入 MOZGLUE_READY"

# .so 加载成功不等于 Gecko 起得来；资源档要等 XRE 打开，缺了它同样只在真机上炸
if ! wait_for "State changed to RUNNING"; then
  echo "--- 最后 40 行相关日志 ---"
  adb logcat -d 2>/dev/null | grep -iE "Gecko|XRE|incomplete|dlopen|Tokay" | tail -40
  fail "等待 ${TIMEOUT}s 未见 GeckoThread RUNNING（.so 已加载但引擎没起来，先查资源档与 -greomni 落点）"
fi
echo "✅ GeckoThread 已进入 RUNNING"

step "5/5 核验设备侧落盘事实"
adb logcat -d 2>/dev/null | grep -E "使用多版本 ABI 目录|Couldn't load.*libmozglue|dlopen failed" | tail -5

STATE="$(adb shell run-as "$PKG" cat files/geckoview/meta/state.json 2>/dev/null | tr -d '\r')"
[ -n "$STATE" ] || fail "读不到 files/geckoview/meta/state.json"
echo "state.json: $STATE"
# state.json 只记 javaApiVersion + currentCoreVersion（回滚移除后不再有 installedCoreVersions / ACTIVE）。
# 这两条是"安装真的记了账"的最小事实；缺了它们，下面从 currentCoreVersion 推导路径的检查会以
# "目录不存在"这种误导性的方式失败。
echo "$STATE" | grep -qE '"currentCoreVersion":"[^"]+"' || fail "state.json 里没有 currentCoreVersion（安装没记账）"
echo "$STATE" | grep -qE '"javaApiVersion":"[^"]+"' || fail "state.json 里没有 javaApiVersion（代际没记账）"

VERSION="$(echo "$STATE" | sed -n 's/.*"currentCoreVersion":"\([^"]*\)".*/\1/p')"
LIBS="$(adb shell run-as "$PKG" ls "files/geckoview/versions/$VERSION/libs/jni/$ARCH" 2>/dev/null | tr -d '\r')"
echo "$LIBS" | grep -q "libmozglue.so" || fail "外置目录里没有 libmozglue.so"
echo "外置 .so 数量: $(echo "$LIBS" | grep -c '\.so$')"

adb shell run-as "$PKG" ls -l "files/geckoview/versions/$VERSION/libs/res/base.apk" >/dev/null 2>&1 \
  || fail "外置 base.apk 不存在"
echo "base.apk: 存在"

# 资源档必须在设备上被真的用起来：插桩把 -greomni 改写成了这个外置路径。
# 只看文件存在不够——路径解析回退到 APK 自身时文件照样在，而那个 APK 里没有 omni.ja。
adb logcat -d 2>/dev/null | grep -F "使用多版本 base.apk:" | grep -qF "versions/$VERSION/libs/res/base.apk" \
  || fail "日志里没有 -greomni 指向 OTA res/base.apk 的记录（资源档走错路径）"
echo "-greomni 落点: versions/$VERSION/libs/res/base.apk"

if [ "$PACKAGED" = 1 ]; then
  # 通过本身就是结论：数据已清空、没有任何发布服务，内核只可能来自 APK 里那份
  echo "--- 随包来源线索（不作为判据） ---"
  adb logcat -d 2>/dev/null | grep -iE "gecko-kernel|随包|packaged" | tail -5
fi

echo
if [ "$PACKAGED" = 1 ]; then
  echo "PASS: 内核随包链路成立（无网络，版本 $VERSION，ABI $ARCH）"
else
  echo "PASS: 内核外置链路在真机上成立（版本 $VERSION，ABI $ARCH）"
fi
