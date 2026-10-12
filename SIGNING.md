# 发布签名密钥

本目录/仓库根目录下的 `wakeup-release.jks` 是 Wakeup 的**发布签名密钥**。

> ## ⚠️ 这份密钥是公开的
>
> 它被有意放进公开仓库，因此**任何人都能拿到它**。
> 这意味着：任何持有该文件的人都可以签署一个签名与本项目官方 APK 完全一致的安装包，
> 而 Android 会认为它可以覆盖安装。**不要把它当作安全凭证，也不要用它来证明某个包"来自官方"。**

## 为什么还是放进来了

这是仓库所有者的明确决定，用来解决"密钥只存在于 CI Secret 里、丢失即无法再发版"的风险。
代价是密钥不再是秘密。

## 改用其它密钥？

wakeup-release.jks 的证书 SHA-256 是

`
3E:E9:FB:94:0B:DC:A4:66:C5:83:32:5C:FE:4B:61:42:48:00:F5:0A:A9:EE:A8:72:95:2A:AE:77:93:70:94:21
`

**已经用它的版本（v1.0.2 起）无法换用新密钥继续覆盖升级** —— Android 要求签名一致，
换密钥只能改包名重新发布。所以除非下定决心放弃现有用户，否则只能继续用它。

## 密钥信息

| 项 | 值 |
| --- | --- |
| 文件 | wakeup-release.jks（仓库根目录） |
| alias | wakeup |
| storePassword | VYY96wGCATQHV3yfcfbiBDaqFpi9 |
| keyPassword | VYY96wGCATQHV3yfcfbiBDaqFpi9 |
| 算法 | RSA 2048 |
| 有效期 | 10950 天（约 30 年） |
| DN | CN=Wakeup, OU=Wakeup, O=giaocat, L=Shenyang, ST=Liaoning, C=CN |

## 用法

CI 通过仓库 Secret `KEYSTORE_BASE64`（本文件的 base64）+ `KEYSTORE_PASSWORD` /
`KEY_ALIAS` / `KEY_PASSWORD` 取用，见根目录 README「九、发布与签名」。

本地：

```bash
export KEYSTORE_FILE=/绝对路径/wakeup-release.jks
export KEYSTORE_PASSWORD=VYY96wGCATQHV3yfcfbiBDaqFpi9
export KEY_ALIAS=wakeup
export KEY_PASSWORD=VYY96wGCATQHV3yfcfbiBDaqFpi9
./gradlew assembleRelease -PrequireSigning=true
```
