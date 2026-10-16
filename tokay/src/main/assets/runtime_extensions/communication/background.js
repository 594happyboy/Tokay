// background.js - 负责处理来自原生端的规则更新，并通过 webRequest 实现拦截
// DEBUG 只控制生命周期/配置类日志（含整份 rulesConfig 的打印）；命中日志另由 rulesConfig.debug.logMatches 控制。
const DEBUG = false;

if (DEBUG) console.log('background.js启动');

const LOG_PREFIX = '[RequestInterceptor][Gecko]';
const MESSAGE_TYPE_UPDATE_REQUEST_INTERCEPTION_RULES = 'updateRequestInterceptionRules';
const DEFAULT_MAX_RULES = 200;
const DEFAULT_MAX_REGEX_LENGTH = 2048;

let currentRulesConfig = null;
let compiledRules = [];

function buildRegexFlags(regexOptions) {
  const opt = regexOptions || {};
  let flags = '';
  if (opt.ignoreCase) flags += 'i';
  if (opt.multiline) flags += 'm';
  if (opt.dotAll) flags += 's';
  return flags;
}

function compileRules(cfg) {
  compiledRules = [];
  if (!cfg || !cfg.enabled) return;

  const limits = cfg.limits || {};
  const maxRules = typeof limits.maxRules === 'number' ? limits.maxRules : DEFAULT_MAX_RULES;
  const maxRegexLength = typeof limits.maxRegexLength === 'number' ? limits.maxRegexLength : DEFAULT_MAX_REGEX_LENGTH;

  const flags = buildRegexFlags(cfg.regexOptions);
  const rules = Array.isArray(cfg.rules) ? cfg.rules : [];
  const sorted = rules
    .slice(0, maxRules)
    .filter(r => r && r.match && typeof r.match.urlRegex === 'string')
    .filter(r => r.match.urlRegex.length > 0 && r.match.urlRegex.length <= maxRegexLength)
    .sort((a, b) => (b.priority || 0) - (a.priority || 0));

  for (const rule of sorted) {
    try {
      const urlRegex = rule.match.urlRegex;
      const reg = new RegExp(urlRegex, flags);
      const methods = Array.isArray(rule.match.methods)
        ? rule.match.methods.map(m => String(m).toUpperCase())
        : null;
      const effect = rule.effect && typeof rule.effect === 'object' ? rule.effect : {};
      compiledRules.push({
        id: String(rule.id || ''),
        isMainFrame: typeof rule.match.isMainFrame === 'boolean' ? rule.match.isMainFrame : null,
        methods,
        regex: reg,
        actionType: String(rule.actionType || ''),
        effect,
      });
    } catch (e) {
      console.warn(`${LOG_PREFIX} compile rule failed`, rule, e);
    }
  }
}

function isMainFrameRequest(details) {
  // Gecko webRequest: type 通常为 main_frame / sub_frame / script / image ...
  return details && details.type === 'main_frame';
}

function handleRequest(details) {
  const cfg = currentRulesConfig;
  if (!cfg || !cfg.enabled || compiledRules.length === 0) {
    return {};
  }

  const rawUrl = details.url;
  const method = (details.method || '').toUpperCase();
  const mainFrame = isMainFrameRequest(details);

  for (const rule of compiledRules) {
    if (rule.isMainFrame !== null && rule.isMainFrame !== mainFrame) continue;
    if (rule.methods && method && !rule.methods.includes(method)) continue;

    let matched = false;
    try {
      matched = rule.regex.test(rawUrl);
    } catch (e) {
      console.warn(`${LOG_PREFIX} regex test failed`, rule.id, rawUrl, e);
      continue;
    }
    if (!matched) continue;

    if (cfg.debug && cfg.debug.logMatches) {
      console.log(`${LOG_PREFIX} 命中规则`, {
        ruleId: rule.id,
        rawUrl,
        actionType: rule.actionType,
        effect: rule.effect,
      });
    }

    const effect = rule.effect || {};
    if (effect.cancel === true) {
      return { cancel: true };
    }
    if (effect.redirectUrl && String(effect.redirectUrl)) {
      return { redirectUrl: String(effect.redirectUrl) };
    }

    return {};
  }

  return {};
}

function installWebRequestListener() {
  try {
    if (browser.webRequest.onBeforeRequest.hasListener(handleRequest)) {
      browser.webRequest.onBeforeRequest.removeListener(handleRequest);
      if (DEBUG) console.log(`${LOG_PREFIX} 已移除旧的 webRequest 监听器`);
    }

    if (!currentRulesConfig || !currentRulesConfig.enabled || compiledRules.length === 0) {
      if (DEBUG) console.log(`${LOG_PREFIX} 当前没有启用的拦截规则，不注册 webRequest 监听器`);
      return;
    }

    if (DEBUG) console.log(`${LOG_PREFIX} 安装 webRequest 监听器，规则数:`, compiledRules.length);

    browser.webRequest.onBeforeRequest.addListener(
      handleRequest,
      { urls: ['<all_urls>'] },
      ['blocking']
    );
  } catch (e) {
    console.error(`${LOG_PREFIX} installWebRequestListener error`, e);
  }
}

browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (!message || !message.type) return;

  if (message.type === MESSAGE_TYPE_UPDATE_REQUEST_INTERCEPTION_RULES) {
    const cfg = message.rulesConfig || null;
    if (DEBUG) console.log(`${LOG_PREFIX} 收到规则更新消息:`, JSON.stringify(cfg));
    currentRulesConfig = cfg;
    compileRules(currentRulesConfig);
    installWebRequestListener();
    sendResponse && sendResponse({ ok: true });
  }
});
