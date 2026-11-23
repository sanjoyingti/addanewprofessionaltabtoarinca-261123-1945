package com.aibuilt.addanewprofessionaltabtoarinca

import android.app.Application
import com.aibuilt.addanewprofessionaltabtoarinca.bypass.BypassStore

class ArinApp : Application() {

    lateinit var store: BypassStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = BypassStore(this)
        container = this
    }

    companion object {
        lateinit var container: ArinApp
            private set

        fun store(): BypassStore = container.store
    }
}
