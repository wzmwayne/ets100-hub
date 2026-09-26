# 版本记录

## v1-original
- original/E听说中学_7.0.67.2495.apk
- sha256: 127da9671d87a313c93a27cf0ae04c9256e66b07cf9772864cadfd84b1694427

## v2-deshell
- deshell/deshell-release.apk
- sha256: c207d7fc4b95900cdd35dec156e6ffcc62e2d20f5b8f56fc027420b166d58ef9
- 包名 com.ets100.secondary（去壳）

## v3-repackapp（已废弃，含推送，安装冲突）
- deshell/repack-release.apk
- 包名 com.ets100.repackapp

## v4-nopush
- deshell/nopush-release.apk
- sha256: 86bc1557d4cfa764aeb84c227aeeaf9bc8fa054b6fe5f74678adca07a76267ba
- 包名 com.ets100.repackapp，移除全部推送服务与推送权限

## v5-safe（当前安装版，防闪退 + 异常落盘）
- deshell/safe-release.apk
- sha256: c9e6851cd57ad175810b7166bb549656812bd6a6a34e791144a64d2270632ad7
- 在 v4 基础上新增：
  - SafeApp(com.ets100.repackapp.SafeApp) 继承真实 EtsApp（dex 中清除 EtsApp 的 ACC_FINAL），
    attachBaseContext/onCreate 全部 try/catch 记录异常，不让初始化异常冒泡；
  - CrashGuard：Thread.setDefaultUncaughtExceptionHandler 记录堆栈后**不再交系统默认处理**，
    阻止闪退弹窗/进程被杀；异常写入
    /sdcard/Android/data/com.ets100.repackapp/files/crash.log
    （回退 getExternalFilesDir / 内部 files/crash.log）；
  - AP(AppComponentFactory) 包裹 Application/Activity/Service/Receiver/Provider 实例化并记录；
  - CP(ContentProvider) 在 Application.onCreate 之前安装 CrashGuard 并绑定 Context。
- 清单 application 已指向 com.ets100.repackapp.SafeApp。
