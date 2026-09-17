package com.whitegame.app.di

import android.content.Context
import com.whitegame.app.data.AssetLoader
import com.whitegame.app.data.RemoteCatalog
import com.whitegame.app.repository.ResultsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideRemoteCatalog(@ApplicationContext context: Context): RemoteCatalog =
        RemoteCatalog(context)

    @Provides
    @Singleton
    fun provideAssetLoader(
        @ApplicationContext context: Context,
        remoteCatalog: RemoteCatalog
    ): AssetLoader = AssetLoader(context, remoteCatalog)

    @Provides
    @Singleton
    fun provideResultsRepository(@ApplicationContext context: Context): ResultsRepository =
        ResultsRepository(context)
}
