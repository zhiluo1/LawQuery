package com.lawquery.core.net

import java.io.IOException

/** 官方源返回 403/429 或处于限流冷却期时抛出(需求 7.2.3:立即停止该源请求并退避) */
class RateLimitedException(message: String) : IOException(message)
