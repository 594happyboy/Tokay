#!/usr/bin/env python3
"""Tokay 清单签名工具（离线运行，只依赖 python3 + cryptography）。

信任链：
    内置公钥(P-256, 裸 X/Y)  --验签-->  sdkconfig.json 的分离式签名 <configUrl>.sig
                                              |
                                              +-- so.<abi>.sha256 --> 7z 内核包

分离式签名（不把 signature 内嵌进 JSON）：内嵌要求 Python 签发端与 Kotlin 校验端实现同一套
规范化序列化，一处空格或键序差异就是设备上"内核装不上"。分离式把签名对象定义为收到的原始字节。

P-256 而不是 Ed25519：minSdk 26 覆盖的机型 JCA 里没有 Ed25519（Conscrypt 到 API 33 才加）。
详细论证见同目录 README.md。

用法：
    sign.sh keygen <私钥路径> <公钥路径>      # 生成 P-256 密钥对
    sign.sh sign    <私钥路径> <文件路径>      # 生成 <文件路径>.sig
    sign.sh verify  <公钥路径> <文件路径>      # 校验 <文件路径>.sig
    sign.sh pubxy   <公钥路径>                # 打印裸 X/Y 十六进制（贴进 App 常量用）
"""

import base64
import os
import sys

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.exceptions import InvalidSignature
except ImportError:  # pragma: no cover
    sys.exit("缺少依赖：pip install cryptography")

CURVE = ec.SECP256R1()
HASH = hashes.SHA256()


def _fail(msg):
    sys.exit(f"错误：{msg}")


def keygen(priv_path, pub_path):
    if os.path.exists(priv_path):
        _fail(f"私钥已存在，拒绝覆盖：{priv_path}")
    key = ec.generate_private_key(CURVE)
    with open(priv_path, "wb") as f:
        f.write(key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        ))
    os.chmod(priv_path, 0o600)
    with open(pub_path, "wb") as f:
        f.write(key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ))
    print(f"已生成私钥: {priv_path} (权限 600)")
    print(f"已生成公钥: {pub_path}")
    print("\n贴进 App 的裸 X/Y 十六进制：")
    for line in pubxy_lines(key.public_key()):
        print("  " + line)


def pubxy_lines(public_key):
    numbers = public_key.public_numbers()
    x = numbers.x.to_bytes(32, "big").hex()
    y = numbers.y.to_bytes(32, "big").hex()
    return [f"X = {x}", f"Y = {y}"]


def _load_private(path):
    with open(path, "rb") as f:
        return serialization.load_pem_private_key(f.read(), password=None)


def _load_public(path):
    with open(path, "rb") as f:
        return serialization.load_pem_public_key(f.read())


def sign(priv_path, target):
    if not os.path.exists(target):
        _fail(f"待签名文件不存在：{target}")
    key = _load_private(priv_path)
    with open(target, "rb") as f:
        payload = f.read()
    # DER 编码，与 Android JCA 的 SHA256withECDSA 直接互通
    signature = key.sign(payload, ec.ECDSA(HASH))
    sig_path = target + ".sig"
    with open(sig_path, "w", encoding="ascii") as f:
        f.write(base64.b64encode(signature).decode("ascii") + "\n")
    print(f"已签名: {target} ({len(payload)} 字节)")
    print(f"签名  : {sig_path} ({len(signature)} 字节 DER, base64)")


def verify(pub_path, target):
    sig_path = target + ".sig"
    if not os.path.exists(sig_path):
        _fail(f"签名文件不存在：{sig_path}")
    key = _load_public(pub_path)
    with open(target, "rb") as f:
        payload = f.read()
    with open(sig_path, "r", encoding="ascii") as f:
        signature = base64.b64decode(f.read().strip())
    try:
        key.verify(signature, payload, ec.ECDSA(HASH))
    except InvalidSignature:
        _fail(f"验签失败：{target}")
    print(f"验签通过：{target}")


def pubxy(pub_path):
    key = _load_public(pub_path)
    print(f"曲线: {key.curve.name}")
    for line in pubxy_lines(key):
        print(line)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd = argv[1]
    if cmd == "keygen" and len(argv) == 4:
        keygen(argv[2], argv[3])
    elif cmd == "sign" and len(argv) == 4:
        sign(argv[2], argv[3])
    elif cmd == "verify" and len(argv) == 4:
        verify(argv[2], argv[3])
    elif cmd == "pubxy" and len(argv) == 3:
        pubxy(argv[2])
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
