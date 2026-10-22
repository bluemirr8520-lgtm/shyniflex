(function (global) {
  'use strict';

  var ready = false;
  var failed = false;
  var watchdog = null;
  var previousOnError = global.onerror;

  function errorText(error) {
    if (!error) { return '알 수 없는 시작 오류'; }
    return String(error.message || error);
  }

  function showFailure(error) {
    var overlay;
    var message;
    if (ready || failed || !global.document) { return; }
    failed = true;
    if (watchdog) {
      global.clearTimeout(watchdog);
      watchdog = null;
    }
    overlay = global.document.getElementById('startup-error');
    message = global.document.getElementById('startup-error-message');
    if (!overlay) { return; }
    overlay.className = 'startup-error';
    overlay.style.cssText = 'position:fixed;z-index:9999;top:0;right:0;bottom:0;left:0;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:64px;background:#0e0f12;color:#ffffff;font-family:Arial,"Noto Sans KR",sans-serif;font-size:24px;text-align:center;';
    if (message) {
      message.style.cssText = 'display:block;margin-top:18px;color:#ffb3b6;font-size:20px;';
      message.textContent = errorText(error);
    }
  }

  function markReady() {
    var overlay;
    ready = true;
    failed = false;
    global.__shyniflexStarted = true;
    if (watchdog) {
      global.clearTimeout(watchdog);
      watchdog = null;
    }
    if (!global.document) { return; }
    overlay = global.document.getElementById('startup-error');
    if (overlay) {
      overlay.className = 'startup-error hidden';
      overlay.style.cssText = '';
    }
  }

  global.ShyniFlexBoot = {
    ready: markReady,
    fail: showFailure
  };

  global.onerror = function (message, source, line, column, error) {
    if (!ready) {
      showFailure(error || message);
    }
    if (typeof previousOnError === 'function') {
      return previousOnError.apply(global, arguments);
    }
    return false;
  };

  watchdog = global.setTimeout(function () {
    if (!ready) {
      showFailure('앱 초기화가 지연되고 있습니다. TV를 다시 시작한 뒤 재시도해 주세요.');
    }
  }, 8000);
})(typeof window !== 'undefined' ? window : this);
