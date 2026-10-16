package com.happyboy.tokay.core

import android.util.Log
import com.happyboy.tokay.LOG_TAG
import com.happyboy.tokay.domain.models.InstallState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 状态机守卫：合法转换见 [InstallState]；非法转换只告警并保持原状态。 */
internal class InstallStateManager {
    
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    fun updateState(newState: InstallState) {
        val oldState = _state.value
        
        if (!isValidTransition(oldState, newState)) {
            Log.w(LOG_TAG, "无效的状态转换: ${oldState::class.simpleName} -> ${newState::class.simpleName}")
            return
        }
        
        _state.value = newState
        Log.d(LOG_TAG, "状态更新: ${oldState::class.simpleName} -> ${newState::class.simpleName}")
    }
    
    private fun isValidTransition(from: InstallState, to: InstallState): Boolean {
        if (to is InstallState.Failed || to is InstallState.Retrying) {
            return true
        }
        
        if (from is InstallState.Success) {
            return false
        }
        
        return when (from) {
            is InstallState.Idle -> {
                to is InstallState.Checking
            }
            
            is InstallState.Checking -> {
                to is InstallState.Downloading || 
                to is InstallState.Validating || 
                to is InstallState.Extracting ||
                to is InstallState.Success
            }
            
            is InstallState.Downloading -> {
                to is InstallState.Downloading || // 进度更新
                to is InstallState.Validating
            }
            
            is InstallState.Validating -> {
                to is InstallState.Extracting ||
                to is InstallState.Success
            }
            
            is InstallState.Extracting -> {
                to is InstallState.Extracting || // 进度更新
                to is InstallState.Success
            }
            
            is InstallState.Retrying -> {
                to is InstallState.Checking
            }
            
            is InstallState.Failed -> {
                to is InstallState.Checking // 允许从失败状态重新开始
            }
            
            is InstallState.Success -> false
        }
    }
    
    fun reset() {
        _state.value = InstallState.Idle
        Log.d(LOG_TAG, "状态已重置为 Idle")
    }
    
    fun isInProgress(): Boolean {
        return when (_state.value) {
            is InstallState.Checking,
            is InstallState.Downloading,
            is InstallState.Validating,
            is InstallState.Extracting,
            is InstallState.Retrying -> true
            else -> false
        }
    }
    
    fun isSuccess(): Boolean {
        return _state.value is InstallState.Success
    }
}

