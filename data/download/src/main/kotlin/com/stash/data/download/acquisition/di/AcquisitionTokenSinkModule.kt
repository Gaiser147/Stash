package com.stash.data.download.acquisition.di

import com.stash.core.common.AcquisitionTokenSink
import com.stash.data.download.acquisition.AcquisitionTokenSinkImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AcquisitionTokenSinkModule {

    @Binds
    @Singleton
    abstract fun bindAcquisitionTokenSink(impl: AcquisitionTokenSinkImpl): AcquisitionTokenSink
}
