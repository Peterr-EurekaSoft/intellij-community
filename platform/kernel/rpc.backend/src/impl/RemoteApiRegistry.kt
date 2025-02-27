// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.rpc.backend.impl

import com.intellij.openapi.extensions.ExtensionPointListener
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.platform.rpc.RemoteApiProviderService
import com.intellij.platform.rpc.backend.RemoteApiProvider
import com.intellij.platform.rpc.backend.RemoteApiProvider.Companion.EP_NAME
import com.intellij.util.containers.ContainerUtil
import fleet.rpc.RemoteApi
import fleet.rpc.core.InstanceId
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class RemoteApiRegistry(coroutineScope: CoroutineScope) : RemoteApiProviderService {

  private val remoteApis = ConcurrentHashMap<InstanceId, Pair<KClass<out RemoteApi<Unit>>, RemoteApi<Unit>>>()
  private val visitedEPs = ContainerUtil.createConcurrentWeakKeyWeakValueMap<RemoteApiProvider, Unit>()

  init {
    EP_NAME.addExtensionPointListener(coroutineScope, object : ExtensionPointListener<RemoteApiProvider> {
      override fun extensionAdded(extension: RemoteApiProvider, pluginDescriptor: PluginDescriptor) {
        if (visitedEPs.putIfAbsent(extension, Unit) == null) {
          val apis = extension.getApis()
          for (api in apis) {
            remoteApis[api.klass.toInstanceId] = api.klass to api.service()
          }
        }
      }

      override fun extensionRemoved(extension: RemoteApiProvider, pluginDescriptor: PluginDescriptor) {
        visitedEPs.remove(extension)
        val apis = extension.getApis()
        synchronized(this) {
          apis.forEach { api ->
            remoteApis.remove(api.klass.toInstanceId)
          }
        }
      }
    })
    EP_NAME.extensions.filter { visitedEPs.putIfAbsent(it, Unit) == null }.flatMap { it.getApis() }.forEach { api ->
      remoteApis[api.klass.toInstanceId] = api.klass to api.service()
    }
  }

  override fun <T : RemoteApi<Unit>> resolve(klass: KClass<T>): T {
    @Suppress("UNCHECKED_CAST")
    return remoteApis[klass.toInstanceId]?.second as? T
           ?: throw IllegalStateException("No remote API found for $klass")
  }

  fun resolve(instanceId: InstanceId): Pair<KClass<out RemoteApi<Unit>>, RemoteApi<Unit>> {
    return remoteApis[instanceId]
           ?: throw IllegalStateException("No remote API found for $instanceId")
  }
}

private val KClass<out RemoteApi<Unit>>.toInstanceId: InstanceId
  get() = InstanceId(this.qualifiedName!!)
