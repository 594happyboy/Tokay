// 插件（content script）侧桥接：连接原生端口、向页面注入 bridge.page.js、转发消息。
// 消息类型与 native 侧 BridgeMessages.kt 一致——原生→此处：registerInterface / evaluateJavaScript；
// 页面→原生：jsToNativeMethodCall；
// 原样转发：jsToNativeMethodResponse。
// DEBUG 关闭后 log.d / log.w 静默，log.e 始终输出。

const DEBUG = true;

const log = {
  d: (...args) => { if (DEBUG) console.log('[Bridge]', ...args); },
  w: (...args) => { if (DEBUG) console.warn('[Bridge]', ...args); },
  e: (...args) => { console.error('[Bridge]', ...args); }
};

const MSG = {
  registerInterface: 'registerInterface',           // 注册原生接口
  evaluateJavaScript: 'evaluateJavaScript',        // 执行JavaScript代码
  evaluateResult: 'evaluateResult',                 // JavaScript执行结果
  evaluateError: 'evaluateError',                   // JavaScript执行错误
  jsToNativeMethodCall: 'jsToNativeMethodCall',     // JS调用原生方法
  jsToNativeMethodResponse: 'jsToNativeMethodResponse', // 原生方法调用响应
  interfaceRegistered: 'interfaceRegistered',       // 接口注册完成通知
  bridgeReady: 'bridgeReady',                       // 桥接系统就绪通知
  createInterface: 'createInterface',               // 创建页面接口
};

MSG.updateRequestInterceptionRules = 'updateRequestInterceptionRules';

const nativePort = browser.runtime.connectNative("browser");
log.d('插件环境：已连接原生端口');

class BridgeManager {
  constructor() {
    this.injectionCompleted = false;  // 页面注入是否完成
  }
  
  isInjectionCompleted() { 
    return this.injectionCompleted; 
  }
  
  markInjectionCompleted() { 
    this.injectionCompleted = true; 
  }
}

class MessageHandler {
  async handleMessage(message) {
    try {
      log.d('插件环境：收到原生消息', message.messageType, message);
      
      const messageType = message.messageType;
      
      if (messageType === MSG.registerInterface) {
        this.registerJavaInterface(message.name, message.methods);
      } else if (messageType === MSG.evaluateJavaScript) {
        await this.handleEvaluateJavaScript(message);
      } else if (this.isForwardableMessage(messageType)) {
        this.sendToPage(message);
      } else {
        log.w('插件环境：未知消息类型', messageType);
        this.sendErrorResponse(message, `未知消息类型: ${messageType}`);
      }
    } catch (error) {
      log.e('插件环境：处理原生端消息失败', error);
      this.sendErrorResponse(message, error.message || String(error));
    }
  }
  
  isForwardableMessage(messageType) {
    const forwardableTypes = [
      MSG.jsToNativeMethodResponse
    ];
    return forwardableTypes.includes(messageType);
  }
  
  sendErrorResponse(originalMessage, errorMessage) {
    this.sendToNative({ 
      messageType: 'error', 
      id: originalMessage && originalMessage.id, 
      error: errorMessage
    });
  }
  
  async handleEvaluateJavaScript(message) {
    const { id, code } = message;
    
    try {
      log.d('插件环境：执行JS代码片段', id);
      
      const result = await window.eval(code);
      
      this.sendToNative({ 
        messageType: MSG.evaluateResult, 
        id: id, 
        result: this.serializeResult(result) 
      });
      
      log.d('插件环境：执行JS成功', id);
    } catch (error) {
      this.sendToNative({ 
        messageType: MSG.evaluateError, 
        id: id, 
        error: this.getErrorMessage(error)
      });
      log.e('插件环境：执行JS失败', id, error);
    }
  }
  
  getErrorMessage(error) {
    return error && error.message ? error.message : String(error);
  }
  
  registerJavaInterface(name, methods) {
    if (!Array.isArray(methods)) { 
      log.w('插件环境：注册接口 methods 非数组', name, methods); 
      return; 
    }
    
    log.d('插件环境：通知页面创建接口', name, methods);
    
    window.postMessage({ 
      messageType: MSG.createInterface, 
      name: name, 
      methods: methods 
    }, '*');
    
    this.sendToNative({ 
      messageType: MSG.interfaceRegistered, 
      name: name, 
      methods: methods 
    });
    
    log.d('插件环境：接口注册成功', name);
  }
  
  // 包装形状必须与 native 的 BridgeSerialization 对齐：
  //   string / number / boolean 带类型；对象与数组用 'json' 携带 **JSON 文本**（原生原样交给回调，
  //   这样 `evaluateJavascript` 的对象结果与 WebView 的形状一致：不带外层引号）；
  //   undefined / null 不带 value。
  serializeResult(result) {
    if (result === undefined) return { valueType: 'undefined' };
    if (result === null) return { valueType: 'null' };
    
    const t = typeof result;
    switch (t) {
      case 'string':
      case 'number':
      case 'boolean':
        return { valueType: t, value: result };
      case 'bigint':
        return { valueType: 'string', value: String(result) };
      case 'object':
        try { 
          return { valueType: 'json', value: JSON.stringify(result) }; 
        } catch (e) { 
          return { valueType: 'string', value: String(result) }; 
        }
      default:
        return { valueType: 'string', value: String(result) };
    }
  }
  
  sendToNative(message) { 
    log.d('发送消息到原生端:', message.messageType); 
    nativePort.postMessage(message); 
  }
  
  sendToPage(message) {
    log.d('发送消息到页面:', message.messageType);
    window.postMessage(message, '*');
  }
}

class InjectionManager {
  constructor(bridgeManager) {
    this.bridgeManager = bridgeManager;
    this.listenerInstalled = false;
    
    log.d('插件环境：初始化注入管理器');
    
    // 构造即注入并安装监听（幂等）
    this.ensureInjectionReady();
    this.installPageMessageListener();
  }

  ensureInjectionReady() {
    if (this.bridgeManager.isInjectionCompleted()) { 
      log.d('插件环境：页面注入已完成，跳过'); 
      return; 
    }
    
    try {
      this.injectBridgeCode();
      this.bridgeManager.markInjectionCompleted();
      log.d('插件环境：页面桥接代码注入完成');
    } catch (e) {
      log.e('页面环境桥接代码注入失败', e);
      throw new Error(`桥接代码注入失败: ${e.message}`);
    }
  }
  
  injectBridgeCode() {
    const injectionCode = getInjectionCode();
    if (typeof injectionCode !== 'function') {
      throw new Error('注入代码不是有效的函数');
    }
    
    const codeString = `(${injectionCode.toString()})()`;
    window.eval(codeString);
  }

  installPageMessageListener() {
    if (this.listenerInstalled) { 
      log.d('插件环境：页面消息监听器已安装，跳过'); 
      return; 
    }
    
    log.d('插件环境：安装页面消息监听器');
    
    window.addEventListener('message', (event) => {
      this.handlePageMessage(event);
    });
    
    log.d('插件环境：页面消息监听器安装完成');
    this.listenerInstalled = true;
  }
  
  handlePageMessage(event) {
    if (!this.isValidMessageEvent(event)) return;
    
    const data = event.data;
    if (!this.isValidMessageData(data)) return;
    
    if (this.shouldForwardToNative(data.messageType)) {
      this.forwardToNative(data);
    }
  }
  
  isValidMessageEvent(event) {
    return event && event.source === window;
  }
  
  isValidMessageData(data) {
    return data && data.messageType;
  }
  
  shouldForwardToNative(messageType) {
    const forwardableTypes = [
      MSG.jsToNativeMethodCall
    ];
    return forwardableTypes.includes(messageType);
  }
  
  forwardToNative(data) {
    if (data.messageType === MSG.jsToNativeMethodCall) {
      log.d('插件环境：转发页面->原生 jsToNativeMethodCall', 
            `${data.interface}.${data.method}`, data.callId);
    }
    
    nativePort.postMessage(data);
  }
}

const bridgeManager = new BridgeManager();
const messageHandler = new MessageHandler();
const injectionManager = new InjectionManager(bridgeManager);

log.d('插件环境：启动消息通道');

nativePort.onMessage.addListener(async (message) => {
  if (message && message.messageType === MSG.updateRequestInterceptionRules) {
    try {
      // 将规则转发给 background，由其通过 webRequest 实现真正拦截
      await browser.runtime.sendMessage({
        type: MSG.updateRequestInterceptionRules,
        rulesConfig: message.rulesConfig
      });
    } catch (e) {
      log.e('转发请求拦截规则失败', e);
    }
    return;
  }

  await messageHandler.handleMessage(message);
});

log.d('插件环境：发送 bridgeReady');
nativePort.postMessage({ messageType: MSG.bridgeReady });
