(function (global) {
  'use strict';

  var KEY = {
    OK: 13,
    LEFT: 37,
    UP: 38,
    RIGHT: 39,
    DOWN: 40,
    BACK: 461
  };
  var FONT_DB_NAME = 'ShyniFlexWebOSSubtitleFonts';
  var FONT_STORE_NAME = 'fonts';
  var MAX_SUBTITLE_BYTES = 2 * 1024 * 1024;
  var MAX_CUES = 10000;

  function noop() {}

  function finiteNumber(value, fallback) {
    var number = Number(value);
    return isFinite(number) ? number : fallback;
  }

  function clamp(value, minimum, maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  function resolved(value) {
    if (global.Promise && typeof global.Promise.resolve === 'function') {
      return global.Promise.resolve(value);
    }
    return {
      then: function (callback) {
        callback(value);
        return this;
      },
      catch: function () {
        return this;
      }
    };
  }

  function decodeEntities(text) {
    var named = {
      amp: '&',
      apos: "'",
      gt: '>',
      lt: '<',
      nbsp: '\u00a0',
      quot: '"',
      lrm: '\u200e',
      rlm: '\u200f'
    };
    return String(text || '').replace(
      /&(#x[0-9a-f]+|#\d+|[a-z]+);/gi,
      function (match, entity) {
        var lower = entity.toLowerCase();
        var code;
        if (lower.charAt(0) === '#') {
          code =
            lower.charAt(1) === 'x'
              ? parseInt(lower.slice(2), 16)
              : parseInt(lower.slice(1), 10);
          if (isFinite(code) && code >= 0 && code <= 1114111) {
            if (code <= 65535) {
              return String.fromCharCode(code);
            }
            code -= 65536;
            return String.fromCharCode(
              55296 + (code >> 10),
              56320 + (code & 1023)
            );
          }
          return match;
        }
        return Object.prototype.hasOwnProperty.call(named, lower)
          ? named[lower]
          : match;
      }
    );
  }

  function cleanCueText(text) {
    return decodeEntities(
      String(text || '')
        .replace(/<br\s*\/?>/gi, '\n')
        .replace(/<\/?(?:b|i|u|ruby|rt|c|v|lang)(?:\.[^ >]+|\s+[^>]*)?>/gi, '')
        .replace(/<\d{1,2}:\d{2}(?::\d{2})?[.,]\d{3}>/g, '')
        .replace(/<[^>]*>/g, '')
    ).trim();
  }

  function parseTimestamp(value) {
    var normalized = String(value || '').trim().replace(',', '.');
    var parts = normalized.split(':');
    var hours = 0;
    var minutes = 0;
    var seconds = 0;

    if (parts.length === 3) {
      hours = Number(parts[0]);
      minutes = Number(parts[1]);
      seconds = Number(parts[2]);
    } else if (parts.length === 2) {
      minutes = Number(parts[0]);
      seconds = Number(parts[1]);
    } else {
      return null;
    }

    if (
      !isFinite(hours) ||
      !isFinite(minutes) ||
      !isFinite(seconds) ||
      hours < 0 ||
      minutes < 0 ||
      minutes >= 60 ||
      seconds < 0 ||
      seconds >= 60
    ) {
      return null;
    }
    return hours * 3600 + minutes * 60 + seconds;
  }

  function parseTimingLine(line) {
    var match = String(line || '').match(
      /^\s*([^\s]+)\s+-->\s+([^\s]+)(?:\s+(.*))?\s*$/
    );
    var start;
    var end;
    if (!match) {
      return null;
    }
    start = parseTimestamp(match[1]);
    end = parseTimestamp(match[2]);
    if (start === null || end === null || end <= start) {
      return null;
    }
    return { start: start, end: end, settings: match[3] || '' };
  }

  function parse(text) {
    if (String(text || '').length > MAX_SUBTITLE_BYTES || /\x00/.test(String(text || ''))) { return []; }
    var normalized = String(text || '')
      .replace(/^\ufeff/, '')
      .replace(/\r\n?/g, '\n');
    var blocks = normalized.split(/\n[\t ]*\n+/);
    var cues = [];
    var order = 0;

    blocks.forEach(function (block) {
      if (cues.length >= MAX_CUES || block.length > 8192) { return; }
      var lines = block.split('\n');
      var timingIndex = -1;
      var timing = null;
      var i;
      var identifier = '';

      while (lines.length && !lines[0].trim()) {
        lines.shift();
      }
      if (!lines.length) {
        return;
      }
      if (/^(WEBVTT|NOTE(?:\s|$)|STYLE(?:\s|$)|REGION(?:\s|$))/i.test(lines[0].trim())) {
        return;
      }

      for (i = 0; i < lines.length; i += 1) {
        timing = parseTimingLine(lines[i]);
        if (timing) {
          timingIndex = i;
          break;
        }
      }
      if (timingIndex < 0) {
        return;
      }
      if (timingIndex > 0) {
        identifier = lines[timingIndex - 1].trim();
      }

      var cueText = cleanCueText(lines.slice(timingIndex + 1).join('\n'));
      if (!cueText) {
        return;
      }
      cues.push({
        id: identifier || String(order + 1),
        start: timing.start,
        end: timing.end,
        text: cueText,
        settings: timing.settings,
        order: order
      });
      order += 1;
    });

    cues.sort(function (a, b) {
      return a.start === b.start ? a.order - b.order : a.start - b.start;
    });
    return cues;
  }

  function resolveElement(value) {
    if (typeof value === 'string' && global.document) {
      return global.document.querySelector(value);
    }
    return value || null;
  }

  function style(element, values) {
    var key;
    for (key in values) {
      if (Object.prototype.hasOwnProperty.call(values, key)) {
        element.style[key] = values[key];
      }
    }
  }

  function safeFamily(value, fallback) {
    var family = String(value || '').replace(/[\r\n"']/g, '').trim();
    return family || fallback;
  }

  function cssFontFamily(value, fallback) {
    var family = safeFamily(value, fallback);
    if (family === fallback || /,/.test(family)) {
      return family;
    }
    return '"' + family + '", ' + fallback;
  }

  // Old webOS Chromium/font combinations may rotate narrow ASCII symbols
  // even when text-orientation is upright. Fullwidth presentation forms keep
  // those glyphs upright without changing word boundaries. Brackets stay in
  // their original form so opening/closing bracket behavior is preserved.
  var VERTICAL_ASCII_SYMBOLS =
    /[!"#$%&'*+,\-.\/:;<=>?@\\^_`|~]/g;

  function uprightVerticalSymbols(text) {
    return String(text || '').replace(
      VERTICAL_ASCII_SYMBOLS,
      function (character) {
        return String.fromCharCode(character.charCodeAt(0) + 0xfee0);
      }
    );
  }

  function eventPoint(event) {
    var source = event;
    if (event.touches && event.touches.length) {
      source = event.touches[0];
    } else if (event.changedTouches && event.changedTouches.length) {
      source = event.changedTouches[0];
    }
    if (!source || !isFinite(source.clientX) || !isFinite(source.clientY)) {
      return null;
    }
    return { x: source.clientX, y: source.clientY };
  }

  function readFileBuffer(file) {
    if (!file) {
      return resolved(null);
    }
    if (typeof file.arrayBuffer === 'function') {
      return file.arrayBuffer();
    }
    if (!global.Promise || !global.FileReader) {
      return resolved(null);
    }
    return new global.Promise(function (resolve) {
      var reader = new global.FileReader();
      reader.onload = function () {
        resolve(reader.result || null);
      };
      reader.onerror = function () {
        resolve(null);
      };
      try {
        reader.readAsArrayBuffer(file);
      } catch (ignore) {
        resolve(null);
      }
    });
  }

  function readFileText(file) {
    if (!file || !global.Promise || !global.FileReader) {
      return resolved(null);
    }
    return new global.Promise(function (resolve) {
      var reader = new global.FileReader();
      reader.onload = function () {
        resolve(typeof reader.result === 'string' ? reader.result : null);
      };
      reader.onerror = function () {
        resolve(null);
      };
      try {
        reader.readAsText(file, 'UTF-8');
      } catch (ignore) {
        resolve(null);
      }
    });
  }

  function Subtitles(video, options) {
    if (!(this instanceof Subtitles)) {
      return new Subtitles(video, options);
    }

    this.options = options || {};
    this.video = resolveElement(video || this.options.video);
    this.container = resolveElement(this.options.container) ||
      (this.video && this.video.parentNode);
    if (!global.document || !this.container) {
      throw new Error('ShyniFlexWebOS.Subtitles requires a DOM container.');
    }

    this.cues = [];
    this.enabled = this.options.enabled !== false;
    this.timeOffset = 0;
    this._loadToken = 0;
    this._request = null;
    this._cueEnds = [];
    this.mode = this.options.mode === 'vertical-rl' ? 'vertical-rl' : 'horizontal';
    this.x = clamp(finiteNumber(this.options.x, 50), 0, 100);
    this.y = clamp(
      finiteNumber(this.options.y, this.mode === 'vertical-rl' ? 50 : 84),
      0,
      100
    );
    this.fontSize = Math.max(8, finiteNumber(this.options.fontSize, 42));
    this.lineSpacing = Math.max(0.6, finiteNumber(this.options.lineSpacing, 1.25));
    this.fallbackFont = safeFamily(this.options.fallbackFont, 'sans-serif');
    this.fontFamily = safeFamily(this.options.fontFamily, this.fallbackFont);
    this.adjustmentStep = Math.max(0.25, finiteNumber(this.options.adjustmentStep, 2));
    this.adjustmentMode = false;
    this._lastText = null;
    this._dragging = false;
    this._listeners = [];
    this._ownsOverlay = false;
    this._positionWasChanged = false;
    this._originalContainerPosition = '';

    this.onCueChange = this.options.onCueChange || noop;
    this.onPositionChange = this.options.onPositionChange || noop;
    this.onAdjustmentModeChange = this.options.onAdjustmentModeChange || noop;
    this.onFontLoaded = this.options.onFontLoaded || noop;
    this.onFontError = this.options.onFontError || noop;

    this._createOverlay();
    this._bindPlayback();
    this._bindDrag();
    this._applyPresentation();
  }

  Subtitles.KEY = KEY;
  Subtitles.parse = parse;
  Subtitles.parseWebVTT = parse;
  Subtitles.parseSRT = parse;
  Subtitles.parseTimestamp = parseTimestamp;

  Subtitles.prototype._listen = function (target, name, handler, capture) {
    if (!target || typeof target.addEventListener !== 'function') {
      return;
    }
    target.addEventListener(name, handler, Boolean(capture));
    this._listeners.push({
      target: target,
      name: name,
      handler: handler,
      capture: Boolean(capture)
    });
  };

  Subtitles.prototype._createOverlay = function () {
    var computed;
    this.overlay = resolveElement(this.options.overlay);
    if (!this.overlay) {
      this.overlay = global.document.createElement('div');
      this.overlay.className = 'shyniflex-subtitle-overlay';
      this.overlay.setAttribute('aria-hidden', 'true');
      this.container.appendChild(this.overlay);
      this._ownsOverlay = true;
    }

    this.cueElement = resolveElement(this.options.cueElement);
    if (!this.cueElement) {
      this.cueElement = global.document.createElement('div');
      this.cueElement.className = 'shyniflex-subtitle-cue';
      this.overlay.appendChild(this.cueElement);
    }

    if (global.getComputedStyle) {
      computed = global.getComputedStyle(this.container);
      if (computed && computed.position === 'static') {
        this._originalContainerPosition = this.container.style.position || '';
        this.container.style.position = 'relative';
        this._positionWasChanged = true;
      }
    }

    style(this.overlay, {
      position: 'absolute',
      left: '0',
      top: '0',
      right: '0',
      bottom: '0',
      overflow: 'hidden',
      pointerEvents: 'none',
      zIndex: String(this.options.zIndex || 20)
    });
    style(this.cueElement, {
      position: 'absolute',
      display: 'none',
      boxSizing: 'border-box',
      color: this.options.color || '#ffffff',
      backgroundColor: this.options.backgroundColor || 'rgba(0, 0, 0, 0.28)',
      padding: this.options.padding || '0.12em 0.25em',
      borderRadius: this.options.borderRadius || '0.12em',
      textShadow:
        this.options.textShadow ||
        '0 0 0.10em #000, 0 0.04em 0.10em #000, 0 -0.04em 0.10em #000',
      transform: 'translate(-50%, -50%)',
      pointerEvents: 'auto',
      cursor: 'move',
      userSelect: 'none',
      WebkitUserSelect: 'none'
    });
  };

  Subtitles.prototype._bindPlayback = function () {
    var self = this;
    if (!this.video) {
      return;
    }
    var update = function () {
      self.update(finiteNumber(self.video.currentTime, 0));
    };
    this._listen(this.video, 'timeupdate', update);
    this._listen(this.video, 'seeked', update);
    this._listen(this.video, 'loadedmetadata', update);
    this._listen(this.video, 'emptied', function () {
      self._render('');
    });
  };

  Subtitles.prototype._bindDrag = function () {
    var self = this;
    var start = function (event) {
      if (!self._lastText) {
        return;
      }
      self._dragging = true;
      self._dragTo(event);
      if (event && typeof event.preventDefault === 'function') {
        event.preventDefault();
      }
    };
    var move = function (event) {
      if (!self._dragging) {
        return;
      }
      self._dragTo(event);
      if (event && typeof event.preventDefault === 'function') {
        event.preventDefault();
      }
    };
    var end = function () {
      self._dragging = false;
    };

    if (global.PointerEvent) {
      this._listen(this.cueElement, 'pointerdown', start);
      this._listen(global.document, 'pointermove', move);
      this._listen(global.document, 'pointerup', end);
      this._listen(global.document, 'pointercancel', end);
    } else {
      this._listen(this.cueElement, 'mousedown', start);
      this._listen(global.document, 'mousemove', move);
      this._listen(global.document, 'mouseup', end);
      this._listen(this.cueElement, 'touchstart', start);
      this._listen(global.document, 'touchmove', move);
      this._listen(global.document, 'touchend', end);
      this._listen(global.document, 'touchcancel', end);
    }
  };

  Subtitles.prototype._dragTo = function (event) {
    var point = eventPoint(event);
    var rect = this.overlay.getBoundingClientRect();
    if (!point || !rect || rect.width <= 0 || rect.height <= 0) {
      return;
    }
    this.setPosition(
      ((point.x - rect.left) / rect.width) * 100,
      ((point.y - rect.top) / rect.height) * 100,
      'pointer'
    );
  };

  Subtitles.prototype._applyPresentation = function () {
    var vertical = this.mode === 'vertical-rl';
    style(this.cueElement, {
      left: this.x + '%',
      top: this.y + '%',
      fontFamily: cssFontFamily(this.fontFamily, this.fallbackFont),
      fontSize: this.fontSize + 'px',
      lineHeight: String(this.lineSpacing),
      textAlign: 'center',
      whiteSpace: vertical ? 'pre-wrap' : 'pre-line',
      writingMode: vertical ? 'vertical-rl' : 'horizontal-tb',
      WebkitWritingMode: vertical ? 'vertical-rl' : 'horizontal-tb',
      webkitWritingMode: vertical ? 'vertical-rl' : 'horizontal-tb',
      textOrientation: vertical ? 'upright' : 'mixed',
      WebkitTextOrientation: vertical ? 'upright' : 'mixed',
      webkitTextOrientation: vertical ? 'upright' : 'mixed',
      unicodeBidi: 'plaintext',
      maxHeight: vertical ? '90%' : '45%',
      maxWidth: vertical ? '90%' : '92%',
      overflow: 'hidden',
      overflowWrap: 'break-word',
      wordBreak: 'normal'
    });
    this.cueElement.setAttribute('data-writing-mode', this.mode);
    if (this._lastText !== null) {
      this._writeCueText(this._lastText);
    }
  };

  Subtitles.prototype.setCues = function (cues) {
    this.cues = (cues || []).slice(0, MAX_CUES).sort(function (a, b) {
      return a.start === b.start ? (a.order || 0) - (b.order || 0) : a.start - b.start;
    });
    var end = 0;
    this._cueEnds = this.cues.map(function (cue) { end = Math.max(end, cue.end); return end; });
    this._lastText = null;
    this.update(this.video ? finiteNumber(this.video.currentTime, 0) : 0);
    return this.cues;
  };

  Subtitles.prototype.loadText = function (text) {
    return this.setCues(parse(text));
  };

  Subtitles.prototype.loadFromUrl = function (url) {
    var self = this;
    this.cancelLoad();
    var token = this._loadToken;
    if (!global.Promise || !global.XMLHttpRequest) {
      return resolved(false);
    }
    return new global.Promise(function (resolve) {
      var request = new global.XMLHttpRequest();
      self._request = request;
      var settled = false;
      function finish(loaded, abort) {
        if (settled) { return; }
        settled = true;
        if (self._request === request) { self._request = null; }
        resolve(loaded);
        if (abort && typeof request.abort === 'function') { request.abort(); }
      }
      request.open('GET', url, true);
      request.timeout = 15000;
      if (typeof request.overrideMimeType === 'function') {
        request.overrideMimeType('text/plain; charset=utf-8');
      }
      request.onreadystatechange = function () {
        if (settled) { return; }
        if (token !== self._loadToken) { finish(false, true); return; }
        if (request.readyState >= 2 && typeof request.getResponseHeader === 'function') {
          var length = Number(request.getResponseHeader('Content-Length'));
          var mime = String(request.getResponseHeader('Content-Type') || '').toLowerCase();
          if (length > MAX_SUBTITLE_BYTES || /^(?:image|video|audio)\//.test(mime)) { finish(false, true); return; }
        }
        if (request.readyState !== 4) {
          return;
        }
        if (request.status >= 200 && request.status < 300) {
          var content = request.responseText || '';
          var cues = content.length <= MAX_SUBTITLE_BYTES ? parse(content) : [];
          if (cues.length) { self.setCues(cues); }
          finish(cues.length > 0);
        } else {
          finish(false);
        }
      };
      request.onprogress = function (event) {
        if (event.loaded > MAX_SUBTITLE_BYTES || (event.lengthComputable && event.total > MAX_SUBTITLE_BYTES)) { finish(false, true); }
      };
      request.onerror = request.ontimeout = request.onabort = function () { finish(false); };
      try {
        request.send();
      } catch (ignore) {
        finish(false);
      }
    });
  };

  Subtitles.prototype.loadFromFile = function (file) {
    var self = this;
    this.cancelLoad();
    var token = this._loadToken;
    if (file && file.size > MAX_SUBTITLE_BYTES) { return resolved(false); }
    return readFileText(file).then(function (text) {
      if (token !== self._loadToken || typeof text !== 'string' || text.length > MAX_SUBTITLE_BYTES) {
        return false;
      }
      var cues = parse(text);
      if (cues.length) { self.setCues(cues); }
      return cues.length > 0;
    });
  };

  Subtitles.prototype._activeText = function (time) {
    var active = [];
    var i;
    var low = 0;
    var high = this._cueEnds.length;
    while (low < high) {
      var mid = Math.floor((low + high) / 2);
      if (this._cueEnds[mid] <= time) { low = mid + 1; } else { high = mid; }
    }
    for (i = low; i < this.cues.length; i += 1) {
      if (this.cues[i].start > time) {
        break;
      }
      if (this.cues[i].end > time) {
        active.push(this.cues[i].text);
      }
    }
    return active.join('\n');
  };

  Subtitles.prototype.update = function (time) {
    var text = this.enabled ? this._activeText(finiteNumber(time, 0) - this.timeOffset) : '';
    this._render(text);
    return text;
  };

  Subtitles.prototype._writeCueText = function (text) {
    this.cueElement.textContent = this.mode === 'vertical-rl'
      ? uprightVerticalSymbols(text)
      : String(text || '');
  };

  Subtitles.prototype._render = function (text) {
    if (text === this._lastText) {
      return;
    }
    this._lastText = text;
    this._writeCueText(text);
    this.cueElement.style.display = text ? 'block' : 'none';
    try {
      this.onCueChange(text);
    } catch (ignore) {
      // A consumer callback must not stop subtitle timing updates.
    }
  };

  Subtitles.prototype.setMode = function (mode) {
    this.mode = mode === 'vertical' || mode === 'vertical-rl' ? 'vertical-rl' : 'horizontal';
    this._applyPresentation();
    return this.mode;
  };

  Subtitles.prototype.setPosition = function (x, y, source) {
    this.x = clamp(finiteNumber(x, this.x), 0, 100);
    this.y = clamp(finiteNumber(y, this.y), 0, 100);
    this.cueElement.style.left = this.x + '%';
    this.cueElement.style.top = this.y + '%';
    try {
      this.onPositionChange(
        { x: this.x, y: this.y, mode: this.mode },
        source || 'api'
      );
    } catch (ignore) {
      // Position remains applied even if persistence in the host app fails.
    }
    return { x: this.x, y: this.y };
  };

  Subtitles.prototype.moveBy = function (deltaX, deltaY, source) {
    return this.setPosition(
      this.x + finiteNumber(deltaX, 0),
      this.y + finiteNumber(deltaY, 0),
      source || 'api'
    );
  };

  Subtitles.prototype.center = function () {
    return this.setPosition(50, 50, 'center');
  };

  Subtitles.prototype.setFontSize = function (pixels) {
    this.fontSize = clamp(finiteNumber(pixels, this.fontSize), 8, 180);
    this.cueElement.style.fontSize = this.fontSize + 'px';
    return this.fontSize;
  };

  Subtitles.prototype.setLineSpacing = function (multiplier) {
    this.lineSpacing = clamp(finiteNumber(multiplier, this.lineSpacing), 0.6, 3);
    this.cueElement.style.lineHeight = String(this.lineSpacing);
    return this.lineSpacing;
  };

  Subtitles.prototype.setFontFamily = function (family) {
    this.fontFamily = safeFamily(family, this.fallbackFont);
    this.cueElement.style.fontFamily = cssFontFamily(
      this.fontFamily,
      this.fallbackFont
    );
    return this.fontFamily;
  };

  Subtitles.prototype.setStyle = function (settings) {
    var values = settings || {};
    if (typeof values.enabled !== 'undefined') { this.enabled = values.enabled !== false; }
    if (typeof values.timeOffset !== 'undefined') { this.timeOffset = clamp(finiteNumber(values.timeOffset, 0), -10, 10); }
    if (typeof values.mode !== 'undefined') {
      this.setMode(values.mode);
    }
    if (typeof values.x !== 'undefined' || typeof values.y !== 'undefined') {
      this.setPosition(
        typeof values.x === 'undefined' ? this.x : values.x,
        typeof values.y === 'undefined' ? this.y : values.y
      );
    }
    if (typeof values.fontSize !== 'undefined') {
      this.setFontSize(values.fontSize);
    }
    if (typeof values.lineSpacing !== 'undefined') {
      this.setLineSpacing(values.lineSpacing);
    }
    if (typeof values.fontFamily !== 'undefined') {
      this.setFontFamily(values.fontFamily);
    }
    if (values.color) {
      this.cueElement.style.color = values.color;
    }
    if (typeof values.backgroundColor === 'string') {
      this.cueElement.style.backgroundColor = values.backgroundColor;
    }
    this.update(this.video ? finiteNumber(this.video.currentTime, 0) : 0);
    return this.getSettings();
  };

  Subtitles.prototype.getSettings = function () {
    return {
      enabled: this.enabled,
      timeOffset: this.timeOffset,
      mode: this.mode,
      x: this.x,
      y: this.y,
      fontSize: this.fontSize,
      lineSpacing: this.lineSpacing,
      fontFamily: this.fontFamily,
      adjustmentMode: this.adjustmentMode
    };
  };

  Subtitles.prototype.setAdjustmentMode = function (enabled) {
    var next = Boolean(enabled);
    if (next === this.adjustmentMode) {
      return next;
    }
    this.adjustmentMode = next;
    this.cueElement.setAttribute('data-adjusting', next ? 'true' : 'false');
    this.cueElement.style.outline = next
      ? '3px solid rgba(255, 255, 255, 0.9)'
      : 'none';
    try {
      this.onAdjustmentModeChange(next);
    } catch (ignore) {
      // Adjustment remains active even if host UI notification fails.
    }
    return next;
  };

  Subtitles.prototype.toggleAdjustmentMode = function () {
    return this.setAdjustmentMode(!this.adjustmentMode);
  };

  Subtitles.prototype.handleRemoteKey = function (eventOrCode) {
    var code =
      typeof eventOrCode === 'number'
        ? eventOrCode
        : eventOrCode && (eventOrCode.keyCode || eventOrCode.which);
    if (!this.adjustmentMode) {
      return false;
    }
    switch (code) {
      case KEY.LEFT:
        this.moveBy(-this.adjustmentStep, 0, 'dpad');
        return true;
      case KEY.RIGHT:
        this.moveBy(this.adjustmentStep, 0, 'dpad');
        return true;
      case KEY.UP:
        this.moveBy(0, -this.adjustmentStep, 'dpad');
        return true;
      case KEY.DOWN:
        this.moveBy(0, this.adjustmentStep, 'dpad');
        return true;
      case KEY.OK:
      case KEY.BACK:
        this.setAdjustmentMode(false);
        return true;
      default:
        return false;
    }
  };

  Subtitles.prototype._fontFailure = function (error) {
    try {
      this.onFontError(error || new Error('External font could not be loaded.'));
    } catch (ignore) {
      // Keep the already configured fallback font.
    }
    return false;
  };

  Subtitles.prototype._activateFont = function (family, source, descriptors) {
    var self = this;
    if (
      !global.FontFace ||
      !global.document ||
      !global.document.fonts ||
      typeof global.document.fonts.add !== 'function'
    ) {
      return resolved(false);
    }
    var font;
    try {
      font = new global.FontFace(
        safeFamily(family, 'ShyniExternalSubtitle'),
        source,
        descriptors || {}
      );
    } catch (error) {
      return resolved(this._fontFailure(error));
    }
    return font.load().then(
      function (loaded) {
        global.document.fonts.add(loaded);
        self.setFontFamily(loaded.family || family);
        try {
          self.onFontLoaded(self.fontFamily);
        } catch (ignore) {
          // The activated font remains usable.
        }
        return true;
      },
      function (error) {
        return self._fontFailure(error);
      }
    );
  };

  Subtitles.prototype.loadFontFromUrl = function (family, url, descriptors) {
    var escaped = String(url || '')
      .replace(/\\/g, '\\\\')
      .replace(/"/g, '\\"')
      .replace(/[\r\n]/g, '');
    if (!escaped) {
      return resolved(false);
    }
    return this._activateFont(
      family,
      'url("' + escaped + '")',
      descriptors
    );
  };

  Subtitles.prototype._openFontDatabase = function () {
    if (!global.Promise || !global.indexedDB) {
      return resolved(null);
    }
    return new global.Promise(function (resolve) {
      var request;
      try {
        request = global.indexedDB.open(FONT_DB_NAME, 1);
      } catch (ignore) {
        resolve(null);
        return;
      }
      request.onupgradeneeded = function () {
        var database = request.result;
        if (!database.objectStoreNames.contains(FONT_STORE_NAME)) {
          database.createObjectStore(FONT_STORE_NAME, { keyPath: 'key' });
        }
      };
      request.onsuccess = function () {
        resolve(request.result);
      };
      request.onerror = function () {
        resolve(null);
      };
      request.onblocked = function () {
        resolve(null);
      };
    });
  };

  Subtitles.prototype._storeFont = function (key, family, data) {
    return this._openFontDatabase().then(function (database) {
      if (!database || !global.Promise) {
        return false;
      }
      return new global.Promise(function (resolve) {
        var transaction;
        try {
          transaction = database.transaction(FONT_STORE_NAME, 'readwrite');
          transaction.objectStore(FONT_STORE_NAME).put({
            key: key,
            family: family,
            data: data,
            updatedAt: Date.now()
          });
        } catch (ignore) {
          database.close();
          resolve(false);
          return;
        }
        transaction.oncomplete = function () {
          database.close();
          resolve(true);
        };
        transaction.onerror = function () {
          database.close();
          resolve(false);
        };
        transaction.onabort = transaction.onerror;
      });
    });
  };

  Subtitles.prototype._readFont = function (key) {
    return this._openFontDatabase().then(function (database) {
      if (!database || !global.Promise) {
        return null;
      }
      return new global.Promise(function (resolve) {
        var request;
        try {
          request = database
            .transaction(FONT_STORE_NAME, 'readonly')
            .objectStore(FONT_STORE_NAME)
            .get(key);
        } catch (ignore) {
          database.close();
          resolve(null);
          return;
        }
        request.onsuccess = function () {
          var value = request.result || null;
          database.close();
          resolve(value);
        };
        request.onerror = function () {
          database.close();
          resolve(null);
        };
      });
    });
  };

  Subtitles.prototype.loadFontFromFile = function (file, family, storageKey) {
    var self = this;
    var selectedFamily = safeFamily(
      family || (file && file.name ? file.name.replace(/\.[^.]+$/, '') : ''),
      'ShyniExternalSubtitle'
    );
    var key = String(storageKey || 'selected');
    return readFileBuffer(file).then(function (buffer) {
      if (!buffer) {
        return self._fontFailure(new Error('The selected font file could not be read.'));
      }
      return self._activateFont(selectedFamily, buffer).then(function (loaded) {
        if (!loaded) {
          return false;
        }
        return self._storeFont(key, selectedFamily, buffer).then(function () {
          return true;
        });
      });
    });
  };

  Subtitles.prototype.restoreFont = function (storageKey) {
    var self = this;
    return this._readFont(String(storageKey || 'selected')).then(function (record) {
      if (!record || !record.data) {
        return false;
      }
      return self._activateFont(record.family, record.data);
    });
  };

  Subtitles.prototype.clear = function () {
    this.cancelLoad();
    this.cues = [];
    this._cueEnds = [];
    this._render('');
  };

  Subtitles.prototype.cancelLoad = function () {
    this._loadToken += 1;
    var request = this._request;
    this._request = null;
    if (request && typeof request.abort === 'function') { request.abort(); }
  };

  Subtitles.prototype.destroy = function () {
    this.clear();
    var i;
    for (i = 0; i < this._listeners.length; i += 1) {
      this._listeners[i].target.removeEventListener(
        this._listeners[i].name,
        this._listeners[i].handler,
        this._listeners[i].capture
      );
    }
    this._listeners = [];
    if (this._ownsOverlay && this.overlay.parentNode) {
      this.overlay.parentNode.removeChild(this.overlay);
    }
    if (this._positionWasChanged) {
      this.container.style.position = this._originalContainerPosition;
    }
  };

  var namespace = global.ShyniFlexWebOS || {};
  namespace.Subtitles = Subtitles;
  namespace.parseSubtitles = parse;
  global.ShyniFlexWebOS = namespace;
  global.ShyniFlexSubtitles = Subtitles;
})(
  typeof window !== 'undefined'
    ? window
    : typeof global !== 'undefined'
      ? global
      : this
);
