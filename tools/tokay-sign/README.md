# tokay-sign — 内核清单签名工具（离线）

给内核清单 `sdkconfig.json` 生成**分离式签名** `<configUrl>.sig`。App 侧用内置公钥验签，
验不过就拒绝安装——信任链是「内置公钥 → 清单签名的原始字节 → 清单里的 `so.<abi>.sha256` → 内核 7z 包」。

## ⚠️ 仓库里那对 `keys/demo-p256.*` 是**演示密钥**

私钥 `keys/demo-p256.key` 随仓库公开，**任何人都能读**。它存在的唯一目的是让你 `git clone`
之后能立刻把整条链路跑通（简历/教学向项目的首要标准就是「克隆下来能跑」）。

**生产环境必须自建密钥并把 App 内置的公钥换掉**：

```bash
./sign.sh keygen keys/my-p256.key keys/my-p256.pub
./sign.sh pubxy  keys/my-p256.pub      # 打印裸 X/Y，替换库里的内置公钥常量
```

内嵌 `demo-p256.key` 不构成对生产链路的风险，因为**默认信任的是公开的那把公钥**——
但如果你把自己项目的私钥交出去，那这个签名机制就只剩形式了。

## 为什么是分离式签名

不把 `signature` 字段写进 JSON，而是单独放 `<文件>.sig`。原因：内嵌字段就要求签发端（Python）
和校验端（Kotlin）实现**同一套规范化（canonical）JSON 序列化**，两边只要有一处空格、键序或
数字格式差异，症状就是设备上「内核装不上」且没有任何能指向根因的报错。分离式把签名对象
定义成「你收到的那些字节」，无歧义。

## 为什么是 ECDSA P-256 而不是 Ed25519

真机实测（Android 12 / API 31）JCA 里**没有** Ed25519，系统自带的 stripped BouncyCastle 也没有：

```
ALG Ed25519   KeyFactory=NoSuchAlgorithmException  Signature=NoSuchAlgorithmException
ALG EdDSA     KeyFactory=NoSuchAlgorithmException  Signature=NoSuchAlgorithmException
ALG SHA256withECDSA  Signature=OK
BC Ed25519    KeyFactory=NoSuchAlgorithmException
```

Conscrypt 到 API 33 才加 Ed25519。`minSdk 26` 用它等于在 Android 13 以下全线失败。P-256 从
API 26 起原生可用，且零新增依赖——不用为了签名打包一个 4MB 的 BouncyCastle 去和系统 provider 抢包名。

## 命令

```bash
./sign.sh keygen keys/my-p256.key keys/my-p256.pub   # 生成密钥对（私钥权限 600）
./sign.sh sign   keys/my-p256.key serve/sdkconfig.json
./sign.sh verify keys/my-p256.pub serve/sdkconfig.json
./sign.sh pubxy  keys/my-p256.pub
```

`sign` 必须在**所有内容改动之后**执行。本地联调的 `serve-local.sh` 会重写
`so.<abi>.url`（指向本机），内容变了签名的字节就变了——所以它会在改写完成后重新签名。
忘记重签的症状是设备端「验签失败、拒绝安装」。
