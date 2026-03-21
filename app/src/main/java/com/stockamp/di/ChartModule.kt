package com.stockamp.di

import com.stockamp.data.chart.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ChartModule {

    @Binds @Singleton
    abstract fun bindPriceDataParser(impl: PriceDataParserImpl): PriceDataParser

    @Binds @Singleton
    abstract fun bindPriceDataFormatter(impl: PriceDataFormatterImpl): PriceDataFormatter

    @Binds @Singleton
    abstract fun bindTechnicalIndicatorCalculator(impl: TechnicalIndicatorCalculatorImpl): TechnicalIndicatorCalculator

    @Binds @Singleton
    abstract fun bindRemoteDataSource(impl: RemoteDataSourceImpl): RemoteDataSource

    @Binds @Singleton
    abstract fun bindPriceDataRepository(impl: PriceDataRepositoryImpl): PriceDataRepository
}
