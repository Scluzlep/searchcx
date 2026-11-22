package com.overlayai.assistant.xposed

import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class TargetProcessInfo(
    val uid: Int,
    val pid: Int,
    val processName: String,
    val state: String,
    val loadedVersionCode: Long
)

data class XposedStatusSnapshot(
    val serviceBound: Boolean = false,
    val selfHooked: Boolean = false,
    val apiVersion: Int = 0,
    val frameworkName: String = "",
    val frameworkVersion: String = "",
    val frameworkVersionCode: Long = 0L,
    val scope: List<String> = emptyList(),
    val runningTargets: List<TargetProcessInfo> = emptyList(),
    val lastActionMessage: String = ""
) {
    val isActive: Boolean
        get() = serviceBound || selfHooked

    val staleTargetCount: Int
        get() = runningTargets.count { it.state == "STALE" }
}

object ModuleStatus {
    private val initialized = AtomicBoolean(false)

    @Volatile
    private var boundService: XposedService? = null

    private val _status = MutableStateFlow(XposedStatusSnapshot(selfHooked = isSelfHooked()))
    val status: StateFlow<XposedStatusSnapshot> = _status.asStateFlow()

    /**
     * Hooked only when the assistant package itself is also placed in LSPosed scope.
     */
    @JvmStatic
    fun isSelfHooked(): Boolean = false

    @JvmStatic
    fun isActive(): Boolean = _status.value.isActive || isSelfHooked()

    fun init() {
        if (!initialized.compareAndSet(false, true)) {
            refresh()
            return
        }
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    boundService = service
                    refresh("已连接 libxposed API ${service.apiVersion} 服务")
                }

                override fun onServiceDied(service: XposedService) {
                    if (boundService === service) {
                        boundService = null
                    }
                    refresh("XposedService 连接已断开")
                }
            })
        }
    }

    fun refresh(actionMessage: String = _status.value.lastActionMessage) {
        val service = boundService
        val selfHooked = isSelfHooked()
        if (service == null) {
            _status.value = XposedStatusSnapshot(
                serviceBound = false,
                selfHooked = selfHooked,
                lastActionMessage = actionMessage
            )
            return
        }

        val scopeList = runCatching { service.scope.toList() }.getOrDefault(emptyList())
        val targets = runCatching {
            service.runningTargets.map { target ->
                TargetProcessInfo(
                    uid = target.uid,
                    pid = target.pid,
                    processName = target.processName,
                    state = target.state.name,
                    loadedVersionCode = target.loadedVersionCode
                )
            }
        }.getOrDefault(emptyList())

        _status.value = XposedStatusSnapshot(
            serviceBound = true,
            selfHooked = selfHooked,
            apiVersion = runCatching { service.apiVersion }.getOrDefault(0),
            frameworkName = runCatching { service.frameworkName }.getOrDefault(""),
            frameworkVersion = runCatching { service.frameworkVersion }.getOrDefault(""),
            frameworkVersionCode = runCatching { service.frameworkVersionCode }.getOrDefault(0L),
            scope = scopeList,
            runningTargets = targets,
            lastActionMessage = actionMessage
        )
    }

    fun hotReloadTargets(reloadAllIfNoneStale: Boolean = true) {
        val service = boundService
        if (service == null) {
            refresh("未绑定 XposedService：请在 LSPosed 管理器中启用模块并勾选目标应用")
            return
        }

        val running = runCatching { service.runningTargets }.getOrElse { err ->
            refresh("读取运行中宿主失败：${err.message}")
            return
        }
        if (running.isEmpty()) {
            val scopeCount = runCatching { service.scope.size }.getOrDefault(0)
            refresh("当前无运行中宿主 · 作用域 $scopeCount")
            return
        }

        val candidates = running.filter { it.state == HookedTarget.State.STALE }.ifEmpty {
            if (reloadAllIfNoneStale) running else emptyList()
        }
        if (candidates.isEmpty()) {
            refresh("所有目标进程均为最新版本")
            return
        }

        val total = candidates.size
        val remaining = AtomicInteger(total)
        val okCount = AtomicInteger(0)
        val notes = mutableListOf<String>()
        refresh("正在热重载 $total 个目标进程...")

        for (target in candidates) {
            runCatching {
                service.hotReloadModule(target, null) { completedTarget, result ->
                    if (result.status() == HotReloadResult.Status.SUCCEEDED) {
                        okCount.incrementAndGet()
                    } else {
                        synchronized(notes) {
                            val msg = result.message().orEmpty()
                            notes += if (msg.isBlank()) {
                                "${completedTarget.processName}: ${result.status().name}"
                            } else {
                                "${completedTarget.processName}: ${result.status().name} $msg"
                            }
                        }
                    }
                    if (remaining.decrementAndGet() == 0) {
                        val summary = if (notes.isEmpty()) {
                            "热重载完成：${okCount.get()}/$total 成功"
                        } else {
                            "热重载 ${okCount.get()}/$total 成功 · ${notes.take(2).joinToString("; ")}"
                        }
                        refresh(summary)
                    }
                }
            }.onFailure { err ->
                synchronized(notes) {
                    notes += "${target.processName}: ${err.message}"
                }
                if (remaining.decrementAndGet() == 0) {
                    refresh("热重载 ${okCount.get()}/$total 成功 · ${notes.take(2).joinToString("; ")}")
                }
            }
        }
    }
}
