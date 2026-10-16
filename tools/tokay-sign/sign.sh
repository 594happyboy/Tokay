#!/usr/bin/env bash
# Tokay 清单签名工具的入口（薄封装，真正逻辑在 tokay-sign.py）。
# 仓库里那对 keys/demo-p256.* 是公开的演示密钥，生产必须自建密钥并换掉 App 内置公钥。
#
#   sign.sh keygen <私钥> <公钥>    生成 P-256 密钥对
#   sign.sh sign   <私钥> <文件>    生成 <文件>.sig（base64 DER，签的是文件原始字节）
#   sign.sh verify <公钥> <文件>    校验 <文件>.sig
#   sign.sh pubxy  <公钥>          打印裸 X/Y 十六进制（贴进 App 常量）
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "需要 python3" >&2; exit 1; }
python3 -c "import cryptography" 2>/dev/null || {
  echo "缺少依赖，请先安装：pip install cryptography" >&2; exit 1; }
exec python3 "$DIR/tokay-sign.py" "$@"
