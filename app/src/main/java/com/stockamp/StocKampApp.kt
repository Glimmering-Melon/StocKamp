package com.stockamp

import android.app.Application
import com.stockamp.data.market.MarketRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class StocKampApp : Application() {

    @Inject
    lateinit var marketRepository: MarketRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            marketRepository.prefetchSymbols()
        }
    }
}
