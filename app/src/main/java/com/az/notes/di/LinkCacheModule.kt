package com.az.notes.di

import com.az.notes.data.storage.LinkCacheStore
import com.az.notes.data.storage.RoomLinkCacheStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 双链索引持久化缓存的接口绑定。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LinkCacheModule {

    @Binds
    @Singleton
    abstract fun bindLinkCacheStore(impl: RoomLinkCacheStore): LinkCacheStore
}
