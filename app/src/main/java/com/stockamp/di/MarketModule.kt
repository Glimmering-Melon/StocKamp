package com.stockamp.di

import com.stockamp.data.market.MarketRepository
import com.stockamp.data.market.MarketRepositoryImpl
import com.stockamp.data.market.SymbolValidator
import com.stockamp.data.market.SymbolValidatorImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MarketModule {

    @Binds
    @Singleton
    abstract fun bindMarketRepository(impl: MarketRepositoryImpl): MarketRepository

    @Binds
    @Singleton
    abstract fun bindSymbolValidator(impl: SymbolValidatorImpl): SymbolValidator
}
