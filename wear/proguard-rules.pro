# Wear OS 版（手表端）专用的混淆规则。
#
# 数据层（:core）与手机端共用，R8 规则沿用 :app 的那份约定；
# 手表端没有 ContentProvider / Binder Parcelable，这里只保留入口类，其余交给默认规则。
-keep class com.wakeup.wear.MainActivity { *; }
