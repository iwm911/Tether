package app.tether

import android.app.Application
import app.tether.analytics.Analytics
import app.tether.data.DebugSeeder
import app.tether.service.ServiceController
import app.tether.ssh.SshjManager

class TetherApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        SshjManager.installSecurityProvider()
        container = AppContainer(this)
        if (BuildConfig.DEBUG) DebugSeeder.run(this, container)
        ServiceController.install(this, container)
        app.tether.ssh.LinkGuardian.install(this, container.ssh, container.scope)
        Analytics.install(this, container)
    }
}
