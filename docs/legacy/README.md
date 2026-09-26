# E听说中学 7.0.67.2495 无壳重建工程

## 目录
- original/  原始签名 APK（只读备份，git 管理）
- dex/       静态脱壳还原出的 10 个 DEX
- shim/      壳组件替身 smali（com.secneo.apkwrapper.AP / CP）
- scripts/   axml_patch.py（二进制改清单）、repack.py（重打包）、build_deshell.sh（流水线）
- deshell/   无壳重建 APK 输出

## 校验移除原理（重要）
壳 libDexHelper 使用 native RSA 文件清单校验：
assets/meta-data/manifest.mf + rsa.pub + rsa.sig（RSA-1024，签名 128B）。
无 RSA 私钥无法伪造，因此**直接移除壳本体**：
1. AndroidManifest.xml 的 application 由 com.secneo.apkwrapper.AW 改为 com.ets100.secondary.app.EtsApp
   （用二进制 AXML 补丁，不重建任何资源，避免 aapt2 对资源名 '$...' 的兼容问题）；
2. 提供壳组件替身 com.secneo.apkwrapper.AP（AppComponentFactory）与 CP（ContentProvider）；
3. 删除 assets/meta-data/*、lib/**/libDexHelper*.so、libdexjni.so；
4. classes.dex 替换为 shim + 还原的 10 个 DEX（classes.dex + classes2..11.dex）；
5. 重新签名。

## 构建
    bash scripts/build_deshell.sh

## 已知说明
- 重建后签名变为本地 key，第三方 SDK 的应用签名校验需配合模块伪造（见 ../runtime_unpack 的 AntiAnti）。
- 本工程不修改原 APK；original/ 保留原样以便随时回溯。

## 改包名版本（v3-repackapp）
    bash scripts/build_repack.sh
- 新包名 com.ets100.repackapp，与原包名等长（20 字符），manifest 字符串池与 dex
  字符串可原位替换，不需重算偏移；
- 组件/Application 的类名仍为 com.ets100.secondary.*（未重命名类），只改 appId 相关；
- authorities/自定义 permission 及其引用同步改名，保证与 getPackageName() 运行时一致；
- 输出 deshell/repack-release.apk。


## 无推送版本（v4-nopush）
    bash scripts/build_nopush.sh
- 在改包名基础上，用 scripts/axml_strip_push.py 从二进制清单中删除全部推送组件与推送权限；
- 解决 INSTALL_FAILED_DUPLICATE_PERMISSION（getui.permission.GetuiService.<旧包名>）；
- 输出 deshell/nopush-release.apk。


## 防闪退版本（v5-safe）
    bash scripts/build_safe.sh
- shim_java/ 内的 CrashGuard + SafeApp + AP + CP 用 javac+d8 编译为 shim.dex；
- scripts/dex_unfinal.py 清除 EtsApp 的 final 以便 SafeApp 继承；
- 异常日志：/sdcard/Android/data/com.ets100.repackapp/files/crash.log
- 输出 deshell/safe-release.apk。

