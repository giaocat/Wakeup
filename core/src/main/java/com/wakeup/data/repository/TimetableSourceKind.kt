package com.wakeup.data.repository

/**
 * 课表数据来源。
 *
 * - [ZF_LOGIN]：新的正方教务系统（`jwxt.sut.edu.cn/jsxsd/...`），需要登录会话，
 *   由应用内 WebView 手动登录一次后复用 Cookie；
 * - [SHARE_LINK]：旧的"教务通分享页 + token"方式（`sutjwt.sut.edu.cn/user/kb/table/token/...`），
 *   不需要登录，由服务端直接把课表内联在页面里。
 *
 * 默认用新系统：旧分享接口目前会返回 `系统繁忙`（服务端问题），但它恢复后仍然可用，
 * 所以保留为可选项而不是删掉。
 */
enum class TimetableSourceKind {
    /** 默认：分享链接 / token，无需登录，课表与教务系统分享页一致。 */
    SHARE_LINK,

    /** 备选：教务系统（强智），需要登录一次；课表周次可能与实际有出入。 */
    ZF_LOGIN,
    ;

    companion object {
        fun fromName(name: String?): TimetableSourceKind =
            entries.firstOrNull { it.name == name } ?: SHARE_LINK
    }
}
