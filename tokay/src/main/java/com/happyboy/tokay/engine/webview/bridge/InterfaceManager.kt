package com.happyboy.tokay.engine.webview.bridge

import android.util.Log
import android.webkit.JavascriptInterface
import com.happyboy.tokay.LOG_TAG
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class InterfaceManager {
    companion object {
        private const val TAG = LOG_TAG
    }
    
    private val interfaces = ConcurrentHashMap<String, Any>()
    
    // 方法缓存：interfaceName.methodName#signature -> Method
    private val methodCache = ConcurrentHashMap<String, Method>()
    
    private val methodListCache = ConcurrentHashMap<String, List<MethodInfo>>()
    
    fun addInterface(name: String, obj: Any) {
        interfaces[name] = obj
        clearCacheForInterface(name)
        Log.d(TAG, "添加接口: $name (${obj.javaClass.simpleName})")
    }

    fun removeInterface(name: String) {
        interfaces.remove(name)
        clearCacheForInterface(name)
        Log.d(TAG, "移除接口: $name")
    }
    
    
    fun getAllInterfaceNames(): Set<String> {
        return interfaces.keys.toSet()
    }
    
    fun getInterfaceMethods(name: String): JSONArray {
        val obj = interfaces[name] ?: return JSONArray()
        
        val cachedMethods = methodListCache[name]
        if (cachedMethods != null) {
            val jsonArray = JSONArray()
            cachedMethods.forEach { methodInfo ->
                val methodJson = JSONObject().apply {
                    put("name", methodInfo.name)
                    put("paramCount", methodInfo.paramCount)
                    put("paramTypes", JSONArray(methodInfo.paramTypes))
                    put("signature", methodInfo.signature)
                }
                jsonArray.put(methodJson)
            }
            return jsonArray
        }
        
        val methods = parseInterfaceMethods(obj)
        methodListCache[name] = methods
        
        val jsonArray = JSONArray()
        methods.forEach { methodInfo ->
            val methodJson = JSONObject().apply {
                put("name", methodInfo.name)
                put("paramCount", methodInfo.paramCount)
                put("paramTypes", JSONArray(methodInfo.paramTypes))
                put("signature", methodInfo.signature)
            }
            jsonArray.put(methodJson)
        }
        return jsonArray
    }
    
    fun findMethod(interfaceName: String, methodName: String, args: Array<Any?>): Method? {
        val obj = interfaces[interfaceName] ?: return null
        
        val signature = generateMethodSignature(methodName, args)
        val cacheKey = "$interfaceName.$signature"
        
        return methodCache.getOrPut(cacheKey) {
            findBestMatchMethod(obj.javaClass, methodName, args)
        }
    }
    
    fun invokeMethod(
        interfaceName: String,
        methodName: String,
        args: Array<Any?>
    ): MethodInvocationResult {
        val obj = interfaces[interfaceName]
            ?: return MethodInvocationResult.error("接口不存在: $interfaceName")
        
        val method = findMethod(interfaceName, methodName, args)
            ?: return MethodInvocationResult.error("方法不存在或参数不匹配: $methodName")
        
        return try {
            val convertedArgs = convertArguments(args, method.parameterTypes)
            val result = method.invoke(obj, *convertedArgs)
            MethodInvocationResult.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "方法调用失败: $interfaceName.$methodName", e)
            val errorMsg = when (e) {
                is IllegalArgumentException -> "参数类型不匹配: ${e.message}"
                is IllegalAccessException -> "方法访问权限不足: ${e.message}"
                else -> e.message ?: "方法调用失败"
            }
            MethodInvocationResult.error(errorMsg)
        }
    }
    
    fun clear() {
        interfaces.clear()
        methodCache.clear()
        methodListCache.clear()
        Log.d(TAG, "清除所有接口和缓存")
    }

    private fun parseInterfaceMethods(obj: Any): List<MethodInfo> {
        val methods = mutableListOf<MethodInfo>()
        
        obj.javaClass.methods.forEach { method ->
            if (!method.isAnnotationPresent(JavascriptInterface::class.java)) {
                return@forEach
            }
            
            val isSupported = isSupportedMethod(method)
            
            if (!isSupported) {
                Log.w(TAG, "忽略不支持的方法: ${obj.javaClass.simpleName}.${method.name}" +
                          "(${method.parameterTypes.joinToString { it.simpleName }})")
                return@forEach
            }
            
            val paramTypes = method.parameterTypes.map { it.simpleName }
            val signature = generateMethodSignature(method.name, method.parameterTypes)
            
            val methodInfo = MethodInfo(
                name = method.name,
                paramCount = method.parameterCount,
                paramTypes = paramTypes,
                signature = signature
            )
            
            methods.add(methodInfo)
        }
        
        Log.d(TAG, "解析接口方法: ${obj.javaClass.simpleName} -> ${methods.size}个方法")
        return methods
    }
    
    private fun findBestMatchMethod(clazz: Class<*>, methodName: String, args: Array<Any?>): Method? {
        val candidates = clazz.methods.filter { method ->
            method.name == methodName &&
            method.parameterCount == args.size &&
            method.isAnnotationPresent(JavascriptInterface::class.java) &&
            isSupportedMethod(method)
        }
        
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        
        return findBestTypeMatch(candidates, args)
    }
    
    private fun isSupportedMethod(method: Method): Boolean {
        if (method.parameterCount == 0) return true
        
        return method.parameterTypes.all { paramType ->
            when (paramType) {
                String::class.java,
                Int::class.java, Integer::class.java,
                Long::class.java, java.lang.Long::class.java,
                Float::class.java, java.lang.Float::class.java,
                Double::class.java, java.lang.Double::class.java,
                Boolean::class.java, java.lang.Boolean::class.java,
                Any::class.java -> true
                else -> {
                    paramType.isArray || 
                    Collection::class.java.isAssignableFrom(paramType) ||
                    Map::class.java.isAssignableFrom(paramType) ||
                    isFunctionType(paramType)
                }
            }
        }
    }
    
    private fun isFunctionType(paramType: Class<*>): Boolean {
        val className = paramType.name
        return className.startsWith("kotlin.jvm.functions.Function") ||
               className.startsWith("kotlin.Function") ||
               paramType.isInterface && 
               paramType.methods.size == 1 &&
               paramType.methods[0].name == "invoke"
    }
    
    private fun generateMethodSignature(methodName: String, args: Array<Any?>): String {
        val argTypes = args.map { arg ->
            when (arg) {
                null -> "null"
                is String -> "String"
                is Int -> "Int"
                is Long -> "Long"
                is Float -> "Float"
                is Double -> "Double"
                is Boolean -> "Boolean"
                is Array<*> -> "Array"
                is Collection<*> -> "Collection"
                is Map<*, *> -> "Map"
                else -> "Object"
            }
        }.joinToString(",")
        return "$methodName($argTypes)"
    }
    
    private fun generateMethodSignature(methodName: String, paramTypes: Array<Class<*>>): String {
        val typeNames = paramTypes.map { it.simpleName }.joinToString(",")
        return "$methodName($typeNames)"
    }
    
    private fun findBestTypeMatch(candidates: List<Method>, args: Array<Any?>): Method? {
        var bestMatch: Method? = null
        var bestScore = -1
        
        for (method in candidates) {
            val score = calculateTypeMatchScore(method.parameterTypes, args)
            if (score > bestScore) {
                bestScore = score
                bestMatch = method
            }
        }
        
        return bestMatch
    }
    
    private fun calculateTypeMatchScore(paramTypes: Array<Class<*>>, args: Array<Any?>): Int {
        var score = 0
        
        for (i in paramTypes.indices) {
            val paramType = paramTypes[i]
            val arg = args[i]
            
            score += when {
                arg == null && !paramType.isPrimitive -> 10
                arg != null && isExactMatch(paramType, arg) -> 100
                arg != null && isCompatibleMatch(paramType, arg) -> 50
                else -> 0
            }
        }
        
        return score
    }
    
    private fun isExactMatch(paramType: Class<*>, arg: Any): Boolean {
        return when (paramType) {
            String::class.java -> arg is String
            Int::class.java, Integer::class.java -> arg is Int
            Long::class.java, java.lang.Long::class.java -> arg is Long
            Float::class.java, java.lang.Float::class.java -> arg is Float
            Double::class.java, java.lang.Double::class.java -> arg is Double
            Boolean::class.java, java.lang.Boolean::class.java -> arg is Boolean
            else -> paramType.isInstance(arg)
        }
    }
    
    private fun isCompatibleMatch(paramType: Class<*>, arg: Any): Boolean {
        return when (paramType) {
            String::class.java -> true
            Int::class.java, Integer::class.java -> arg is Number
            Long::class.java, java.lang.Long::class.java -> arg is Number
            Float::class.java, java.lang.Float::class.java -> arg is Number
            Double::class.java, java.lang.Double::class.java -> arg is Number
            Boolean::class.java, java.lang.Boolean::class.java -> arg is String || arg is Number
            else -> false
        }
    }
    
    private fun convertArguments(args: Array<Any?>, paramTypes: Array<Class<*>>): Array<Any?> {
        return Array(args.size) { i ->
            val arg = args[i]
            val paramType = paramTypes[i]
            
            if (arg == null) {
                null
            } else {
                convertArgument(arg, paramType)
            }
        }
    }
    
    private fun convertArgument(arg: Any, paramType: Class<*>): Any? {
        return when (paramType) {
            String::class.java -> arg.toString()
            Int::class.java, Integer::class.java -> {
                when (arg) {
                    is Number -> arg.toInt()
                    is String -> arg.toIntOrNull() ?: 0
                    else -> 0
                }
            }
            Long::class.java, java.lang.Long::class.java -> {
                when (arg) {
                    is Number -> arg.toLong()
                    is String -> arg.toLongOrNull() ?: 0L
                    else -> 0L
                }
            }
            Float::class.java, java.lang.Float::class.java -> {
                when (arg) {
                    is Number -> arg.toFloat()
                    is String -> arg.toFloatOrNull() ?: 0f
                    else -> 0f
                }
            }
            Double::class.java, java.lang.Double::class.java -> {
                when (arg) {
                    is Number -> arg.toDouble()
                    is String -> arg.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
            }
            Boolean::class.java, java.lang.Boolean::class.java -> {
                when (arg) {
                    is Boolean -> arg
                    is String -> arg.toBoolean()
                    is Number -> arg.toDouble() != 0.0
                    else -> false
                }
            }
            else -> arg
        }
    }
    
    private fun clearCacheForInterface(interfaceName: String) {
        methodListCache.remove(interfaceName)
        
        val keysToRemove = methodCache.keys.filter { it.startsWith("$interfaceName.") }
        keysToRemove.forEach { methodCache.remove(it) }
    }
}

data class MethodInfo(
    val name: String,
    val paramCount: Int,
    val paramTypes: List<String>,
    val signature: String
)

sealed class MethodInvocationResult {
    data class Success(val result: Any?) : MethodInvocationResult()
    data class Error(val message: String) : MethodInvocationResult()
    
    companion object {
        fun success(result: Any?) = Success(result)
        fun error(message: String) = Error(message)
    }
}
