package uz.botmaker

import android.app.Application

class BotMakerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.attach(this)
    }
}
