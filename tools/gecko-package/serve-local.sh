#!/usr/bin/env bash
# 本地联调用的临时发布服务（仅监听回环地址）。
# 把仓库里的发布源文件改写成指向本机的版本，起静态服务，配合 `adb reverse` 让设备上的
# APK 用「远程下载」的同一套代码路径完成安装（URL 形如 http://127.0.0.1:<port>/...）。
# 只改写内核那一段（`so.<abi>.url`），并保留 sha256 一致性校验。
#
# 用法：
#   bash tools/gecko-package/serve-local.sh              # 组装目录并启动服务
#   bash tools/gecko-package/serve-local.sh --prepare    # 只组装目录，不起服务
#
# 环境变量：
#   PORT=28080                     监听端口（避开 App 内 SimpleHttpServer 的 28471）
#   ROOT=~/Downloads/gecko-kernel/serve   组装出来的服务根目录
#   PKG=<内核 7z 路径>              默认取 ROOT 同级的 arm64 包
#   HOST=127.0.0.1                 写进 sdkconfig 的主机名（配 adb reverse 用回环）
#   ARCH=arm64-v8a                 目标 ABI
set -euo pipefail

PORT="${PORT:-28080}"
HOST="${HOST:-127.0.0.1}"
ARCH="${ARCH:-arm64-v8a}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOT="${ROOT:-$HOME/Downloads/gecko-kernel/serve}"
VERSION="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['geckoJavaVersion'])" "$REPO/sdkconfig.json")"
PKG="${PKG:-$(dirname "$ROOT")/geckoview-$ARCH-$VERSION.7z}"
BASE="http://$HOST:$PORT"

[ -f "$PKG" ] || { echo "缺少内核包: $PKG" >&2; exit 1; }
[ -f "$PKG.sha256" ] || { echo "缺少内核包摘要: $PKG.sha256" >&2; exit 1; }

# 摘要必须与仓库 sdkconfig 里声明的一致，否则设备端会校验失败。
# 仓库里**没有**内核包（它是运行时下发的产物），所以 clone 下来第一次跑必然对不上——
# 报错必须直接给出两条出路，而不是只丢两个十六进制串让人猜。
EXPECT="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['so'][sys.argv[2]]['sha256'])" "$REPO/sdkconfig.json" "$ARCH")"
ACTUAL="$(sha256sum "$PKG" | cut -d' ' -f1)"
if [ "$EXPECT" != "$ACTUAL" ]; then
  echo "sha256 不一致：本机这份内核包与仓库 sdkconfig.json 声明的那份不是同一个。" >&2
  echo "  sdkconfig.json 声明: $EXPECT" >&2
  echo "  本机包的摘要      : $ACTUAL" >&2
  echo "  本机包            : $PKG" >&2
  echo "二选一：" >&2
  echo "  ① 就用这份包联调：把 sdkconfig.json 里 so.$ARCH.sha256 改成上面那个摘要" >&2
  echo "     （改完必须重签，脚本会在组装服务目录时重新签）" >&2
  echo "  ② 换回当初打包的那份：PKG=<那份包的路径> bash tools/gecko-package/serve-local.sh" >&2
  exit 1
fi

mkdir -p "$ROOT"
# 保留服务根目录本身：服务可能在运行中，删掉它会让服务失效
find "$ROOT" -maxdepth 1 -type f \( -name '*.7z' -o -name 'sdkconfig.json' -o -name 'sdkconfig.json.sig' \) -delete
cp "$PKG" "$ROOT/"

REPO="$REPO" ROOT="$ROOT" BASE="$BASE" ARCH="$ARCH" VERSION="$VERSION" \
python3 - <<'PY'
import json, os

repo, root, base = os.environ['REPO'], os.environ['ROOT'], os.environ['BASE']
arch, version = os.environ['ARCH'], os.environ['VERSION']
pkg_name = 'geckoview-%s-%s.7z' % (arch, version)

cfg = json.load(open(os.path.join(repo, 'sdkconfig.json')))
cfg['so'][arch]['url'] = '%s/%s' % (base, pkg_name)
json.dump(cfg, open(os.path.join(root, 'sdkconfig.json'), 'w'), ensure_ascii=False, indent=2)
PY

# ── 重新签名 ──
# 上面改写了 so.<abi>.url，sdkconfig.json 的字节变了 → 旧签名立刻失效，必须在改写后重签，
# 产出 <configUrl>.sig（此处即服务根目录下的 sdkconfig.json.sig）。
# 失败必须响亮：静默跳过的症状是设备端「验签失败、拒绝安装」，而本机完全看不出原因。
SIGN_DIR="$REPO/tools/tokay-sign"
SIGN_KEY="${SIGN_KEY:-$SIGN_DIR/keys/demo-p256.key}"
[ -f "$SIGN_KEY" ] || { echo "缺少签名私钥: $SIGN_KEY（没有它设备端会验签失败，拒绝继续）" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "需要 python3 才能签名" >&2; exit 1; }
python3 -c "import cryptography" 2>/dev/null || {
  echo "缺少 python3 的 cryptography，无法签名（pip install cryptography）" >&2; exit 1; }
"$SIGN_DIR/sign.sh" sign "$SIGN_KEY" "$ROOT/sdkconfig.json" \
  || { echo "签名 sdkconfig.json 失败" >&2; exit 1; }

echo "服务根目录: $ROOT"
ls -l "$ROOT"
grep -n '"url"' "$ROOT/sdkconfig.json"

if [ "${1:-}" = "--prepare" ]; then
  exit 0
fi

echo "启动本地发布服务: $BASE  （设备侧执行 adb reverse tcp:$PORT tcp:$PORT）"
exec python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$ROOT"
