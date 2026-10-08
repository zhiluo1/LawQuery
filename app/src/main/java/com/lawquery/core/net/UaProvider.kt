package com.lawquery.core.net

/**
 * UA 提供者(项目文档 4.1:UA 如实标识)。
 *
 * 需求 7.2.2:不绕过防护、UA 如实标识。所有 UA 均携带 LawQuery/<version> 应用标识;
 * 对需要浏览器 UA 兼容的官方源,在浏览器 UA 基础上追加应用标识并在《数据源接入说明》
 * 中逐源注明理由(gov.cn / court.gov.cn 部分接口对无浏览器特征的 UA 返回异常页,
 * 逐源核实记录于 docs/数据源接入说明.md)。
 */
object UaProvider {

    const val APP_ID = "LawQuery"

    /**
     * UA 里如实标注的应用版本 —— 取构建产物的 [com.lawquery.BuildConfig.VERSION_NAME],
     * 与关于页、APK 清单始终一致(此前硬编码 "1.0.0",发版后 UA 一直谎报旧版本)。
     */
    const val APP_VERSION = com.lawquery.BuildConfig.VERSION_NAME

    /** 原生解析通道:浏览器兼容 UA + 应用标识(如实标注,不伪装为纯浏览器) */
    fun browserCompatibleUa(androidRelease: String, deviceModel: String): String =
        "Mozilla/5.0 (Linux; Android $androidRelease; $deviceModel) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36 " +
            "$APP_ID/$APP_VERSION"

    /** 纯应用标识 UA(默认形态,项目文档 4.1) */
    fun appUa(androidRelease: String, deviceModel: String): String =
        "$APP_ID/$APP_VERSION (Android $androidRelease; $deviceModel)"
}
