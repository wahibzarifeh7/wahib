package com.wz.rasid

import android.app.Application
import com.wz.rasid.data.AppDatabase
import com.wz.rasid.data.Repository
import com.wz.rasid.ussd.UssdExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RasidApp : Application() {
    lateinit var repository: Repository
        private set
    lateinit var ussd: UssdExecutor
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        repository = Repository(AppDatabase.create(this))
        ussd = UssdExecutor(this)
        scope.launch { repository.ensureDefaults() }
    }
}
