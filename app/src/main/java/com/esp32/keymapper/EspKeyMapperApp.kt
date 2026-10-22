package com.esp32.keymapper

import android.app.Application
import com.esp32.keymapper.core.ControllerHub
import com.esp32.keymapper.service.NotificationHelper

class EspKeyMapperApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ControllerHub.init(this)
        NotificationHelper.ensureChannel(this)
    }
}
