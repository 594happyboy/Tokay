package com.happyboy.tokay.engine.webview.bridge

object BridgeMessages {
    const val TYPE = "messageType"

    const val BRIDGE_READY = "bridgeReady"
    const val REGISTER_INTERFACE = "registerInterface"
    const val INTERFACE_REGISTERED = "interfaceRegistered"
    const val INTERFACE_CREATION_FAILED = "interfaceCreationFailed"
    const val JS_TO_NATIVE_METHOD_CALL = "jsToNativeMethodCall"
    const val JS_TO_NATIVE_METHOD_RESPONSE = "jsToNativeMethodResponse"
    const val EVALUATE_JS = "evaluateJavaScript"
    const val EVALUATE_RESULT = "evaluateResult"
    const val EVALUATE_ERROR = "evaluateError"
    const val UPDATE_REQUEST_INTERCEPTION_RULES = "updateRequestInterceptionRules"

    const val FIELD_NAME = "name"
    const val FIELD_METHODS = "methods"
    const val FIELD_INTERFACE = "interface"
    const val FIELD_METHOD = "method"
    const val FIELD_ARGS = "args"
    const val FIELD_CALL_ID = "callId"
    const val FIELD_ID = "id"
    const val FIELD_CODE = "code"
    const val FIELD_RESULT = "result"
    const val FIELD_ERROR = "error"
    const val FIELD_RULES_CONFIG = "rulesConfig"

    const val FIELD_VALUE_TYPE = "valueType"
    const val FIELD_VALUE = "value"
}
