package com.lawquery

import android.app.Application
import com.lawquery.di.AppContainer

class LawQueryApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
