// 页面端桥接：由 bridge.plugin.js 注入页面执行，向页面暴露 window.<接口名>.<方法>()。
// 消息类型（messageType）与 native 侧 tokay/.../bridge/BridgeMessages.kt 一致：
//   接收 createInterface / jsToNativeMethodResponse，发出 jsToNativeMethodCall。
// __BRIDGE_CONFIG__ 可覆盖各档调用超时；DEBUG 控制 log 输出。

function getInjectionCode() {
  return function() {
    const DEBUG = true;
    
    const log = {
      d: (...args) => { if (DEBUG) console.log('[HybridBridge]', ...args); },
      w: (...args) => { if (DEBUG) console.warn('[HybridBridge]', ...args); },
      e: (...args) => { console.error('[HybridBridge]', ...args); }
    };

    const BridgeState = {
      initialized: false,
      interfaces: new Map(),
      pendingCalls: new Map(),
      callIdCounter: 0
    };

    const ERROR_CODES = {
      NATIVE_EXCEPTION: 'NATIVE_EXCEPTION',
      REQUEST_TIMEOUT: 'REQUEST_TIMEOUT'
    };

    const BridgeUtils = {
      generateCallId() {
        return `call_${++BridgeState.callIdCounter}_${Date.now()}`;
      },
      
      serializeArgs(args) {
        return Array.from(args).map(arg => {
          if (arg === undefined) return { valueType: 'undefined' };
          if (arg === null) return { valueType: 'null' };
          
          const type = typeof arg;
          switch (type) {
            case 'string':
            case 'number':
            case 'boolean':
              return { valueType: type, value: arg };
            case 'object':
              try {
                return { valueType: 'string', value: JSON.stringify(arg) };
              } catch (e) {
                return { valueType: 'string', value: String(arg) };
              }
            default:
              return { valueType: 'string', value: String(arg) };
          }
        });
      },
      
      deserializeResult(data) {
        if (!data || !data.valueType) return data;
        
        switch (data.valueType) {
          case 'string': return data.value;
          case 'number': return Number(data.value);
          case 'boolean': return data.value === 'true' || data.value === true;
          case 'null': return null;
          case 'undefined': return undefined;
          default: return data.value;
        }
      }
    };

    if (BridgeState.initialized) {
      log.d('桥接系统已初始化，跳过');
      return;
    }
    BridgeState.initialized = true;
    log.d('开始初始化混合桥接系统');

    const TIMEOUT_CONFIG = {
      QUICK_OPERATION: 3000,      // 3秒 - 快速操作（状态查询、简单计算）
      NORMAL_OPERATION: 8000,     // 8秒 - 普通操作（文件操作、配置获取）
      SLOW_OPERATION: 15000,      // 15秒 - 慢操作（网络请求、大文件处理）
      VERY_SLOW_OPERATION: 30000, // 30秒 - 极慢操作（下载、解压）
      
      CALLBACK_TIMEOUT: 10000,    // 10秒 - 回调函数超时
      
      DEFAULT: 8000               // 8秒 - 默认超时时间
    };
    
    const getConfig = () => window.__BRIDGE_CONFIG__ || {};
    
    const getTimeout = (operationType = 'DEFAULT') => {
      const config = getConfig();
      
      if (config.callTimeoutMs) {
        return config.callTimeoutMs;
      }
      
      switch (operationType) {
        case 'QUICK':
          return config.quickTimeoutMs || TIMEOUT_CONFIG.QUICK_OPERATION;
        case 'NORMAL':
          return config.normalTimeoutMs || TIMEOUT_CONFIG.NORMAL_OPERATION;
        case 'SLOW':
          return config.slowTimeoutMs || TIMEOUT_CONFIG.SLOW_OPERATION;
        case 'VERY_SLOW':
          return config.verySlowTimeoutMs || TIMEOUT_CONFIG.VERY_SLOW_OPERATION;
        default:
          return TIMEOUT_CONFIG.DEFAULT;
      }
    };
    
    const getOperationType = (methodName) => {
      const quickMethods = ['getStatus', 'ping', 'echo', 'test', 'isReady', 'check'];
      const slowMethods = ['download', 'install', 'extract', 'process', 'load'];
      const verySlowMethods = ['downloadAndInstall', 'batchProcess', 'sync'];
      
      if (quickMethods.some(method => methodName.toLowerCase().includes(method))) {
        return 'QUICK';
      } else if (verySlowMethods.some(method => methodName.toLowerCase().includes(method))) {
        return 'VERY_SLOW';
      } else if (slowMethods.some(method => methodName.toLowerCase().includes(method))) {
        return 'SLOW';
      }
      
      return 'NORMAL';
    };

    const MessageHandler = {
      init() {
        if (this.initialized) return;
        this.initialized = true;
        
        window.addEventListener('message', (event) => {
          if (!event || event.source !== window) return;
          
          const data = event.data;
          if (!data || !data.messageType) return;
          
          this.handleMessage(data);
        });
        
        log.d('消息处理器初始化完成');
      },
      
      handleMessage(data) {
        switch (data.messageType) {
          case 'jsToNativeMethodResponse':
            this.handleJsToNativeMethodResponse(data);
            break;
          case 'createInterface':
            this.handleCreateInterface(data);
            break;
          default:
            // 页面自身 postMessage 的 jsToNativeMethodCall 会回环到本监听器，它不是未知类型；
            // 只有真正不认识的消息才告警。
            if (data.messageType !== 'jsToNativeMethodCall') {
              log.w('未知消息类型:', data.messageType);
            }
        }
      },
      
      handleJsToNativeMethodResponse(data) {
        const pending = BridgeState.pendingCalls.get(data.callId);
        if (!pending) return;
        
        if (data.error) {
          this.cleanupCall(data.callId);
          log.d('JS->Native方法调用失败:', data.callId, data.error);
          pending.reject(new Error(data.error));
        } else {
          log.d('JS->Native方法调用成功:', data.callId);
          const result = BridgeUtils.deserializeResult(data.result);
          this.cleanupCall(data.callId);
          pending.resolve(result);
        }
      },
      
      cleanupCall(callId) {
        const pending = BridgeState.pendingCalls.get(callId);
        if (pending && pending.timeoutId) {
          clearTimeout(pending.timeoutId);
        }
        BridgeState.pendingCalls.delete(callId);
      },
      
      handleCreateInterface(data) {
        log.d('创建接口:', data.name, data.methods);
        InterfaceManager.registerInterface(data.name, data.methods);
      },
      
    };

    const InterfaceManager = {
      normalizeMethodNames(methods) {
        if (!Array.isArray(methods)) {
          log.w('normalizeMethodNames: methods不是数组', methods);
          return [];
        }
        
        const normalized = methods.map(m => {
          if (typeof m === 'string') return m;
          if (m && typeof m === 'object') return m.name || m.method || m.methodName || m.funcName || null;
          return null;
        }).filter(Boolean);
        
        log.d('normalizeMethodNames: 输入', methods, '输出', normalized);
        return normalized;
      },

      createBridgeMethod(name, methodName) {
        const bridgeFn = (...args) => {
          const callId = BridgeUtils.generateCallId();
          const startTime = Date.now();

          const serializedArgs = BridgeUtils.serializeArgs(args);
          log.d('调用方法:', `${name}.${methodName}`, callId);

          return new Promise((resolve, reject) => {
            const operationType = getOperationType(methodName);
            const timeoutMs = getTimeout(operationType);
            
            log.d('方法调用超时设置:', `${name}.${methodName}`, '类型:', operationType, '超时:', timeoutMs + 'ms');
            
            const timeoutId = setTimeout(() => {
              MessageHandler.cleanupCall(callId);
              log.w('方法调用超时:', `${name}.${methodName}`, callId);
              
              const timeoutError = new Error(`方法调用超时: ${name}.${methodName}`);
              timeoutError.code = ERROR_CODES.REQUEST_TIMEOUT;
              reject(timeoutError);
            }, timeoutMs);

            BridgeState.pendingCalls.set(callId, { 
              resolve: (result) => {
                MessageHandler.cleanupCall(callId);
                
                log.d('方法调用成功，返回原生响应:', `${name}.${methodName}`);
                resolve(result);
              }, 
              reject: (error) => {
                MessageHandler.cleanupCall(callId);
                
                log.e('方法调用失败:', `${name}.${methodName}`, error);
                const nativeError = new Error(`方法调用失败: ${error.message}`);
                nativeError.code = ERROR_CODES.NATIVE_EXCEPTION;
                reject(nativeError);
              }, 
              timeoutId 
            });

            const message = {
              messageType: 'jsToNativeMethodCall',
              interface: name,
              method: methodName,
              args: serializedArgs,
              callId: callId
            };

            window.postMessage(message, '*');
          });
        };

        bridgeFn.__isBridgeMethod = true;
        return bridgeFn;
      },

      registerInterface(name, methods) {
        if (!Array.isArray(methods)) {
          log.e('创建接口失败，methods必须是数组:', name, methods);
          return;
        }

        const methodNames = this.normalizeMethodNames(methods);
        log.d('创建接口:', name, methodNames);
        MessageHandler.init();

        const interfaceObj = {};
        methodNames.forEach(methodName => {
          if (!interfaceObj[methodName]?.__isBridgeMethod) {
            interfaceObj[methodName] = this.createBridgeMethod(name, methodName);
          }
        });

        window[name] = interfaceObj;

        log.d('接口注册完成:', name);
      }
    };

    MessageHandler.init();
    
    log.d('混合桥接系统初始化完成');
  };
}
