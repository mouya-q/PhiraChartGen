package com.phiragen.app

import android.app.Application
import com.phiragen.app.data.DiagLog

class PhiraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // 只记录异常类型与调用栈, 不记录任何用户内容
            DiagLog.e("crash", "未捕获异常 ${e.javaClass.name}", e)
            previous?.uncaughtException(t, e)
        }
    }
}
