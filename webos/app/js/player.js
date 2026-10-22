(function (global) {
  'use strict';

  var KEY = {
    PLAY: 415,
    PAUSE: 19,
    FAST_FORWARD: 417,
    REWIND: 412,
    STOP: 413
  };

  function noop() {}

  function clamp(value, minimum, maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  function finiteNumber(value, fallback) {
    var number = Number(value);
    return isFinite(number) ? number : fallback;
  }

  function resolveVideo(video) {
    if (typeof video === 'string' && global.document) {
      return global.document.querySelector(video);
    }
    return video;
  }

  function isHlsUrl(url) {
    return /\.m3u8(?:$|[?#])/i.test(String(url || ''));
  }

  function mediaErrorDetails(video, event, attempt, url) {
    var mediaError = video && video.error;
    var codes = {
      1: 'aborted',
      2: 'network',
      3: 'decode',
      4: 'source_not_supported'
    };
    var code = mediaError && mediaError.code ? mediaError.code : 0;
    return {
      kind: 'media',
      code: code,
      name: codes[code] || 'unknown',
      message: mediaError && mediaError.message ? mediaError.message : '',
      attempt: attempt,
      url: url || '',
      event: event || null
    };
  }

  function normalizedSource(source, options) {
    var value = typeof source === 'string' ? { url: source } : source || {};
    var overrides = options || {};
    var directUrl =
      overrides.directUrl ||
      value.directUrl ||
      overrides.url ||
      value.url ||
      value.src ||
      '';
    var hlsUrl =
      overrides.hlsUrl ||
      value.hlsUrl ||
      overrides.fallbackUrl ||
      value.fallbackUrl ||
      '';

    return {
      directUrl: String(directUrl || ''),
      hlsUrl: String(hlsUrl || ''),
      type: String(overrides.type || value.type || ''),
      autoplay:
        typeof overrides.autoplay === 'boolean'
          ? overrides.autoplay
          : Boolean(value.autoplay),
      startTime: finiteNumber(
        typeof overrides.startTime !== 'undefined'
          ? overrides.startTime
          : value.startTime,
        0
      ),
      crossOrigin:
        typeof overrides.crossOrigin !== 'undefined'
          ? overrides.crossOrigin
          : value.crossOrigin
    };
  }

  function Player(video, options) {
    if (!(this instanceof Player)) {
      return new Player(video, options);
    }

    this.video = resolveVideo(video);
    if (!this.video || typeof this.video.addEventListener !== 'function') {
      throw new Error('ShyniFlexWebOS.Player requires one HTML5 video element.');
    }

    this.options = options || {};
    this.seekStep = Math.max(1, finiteNumber(this.options.seekStep, 10));
    this.callbacks = {
      onProgress: this.options.onProgress || noop,
      onError: this.options.onError || noop,
      onStateChange: this.options.onStateChange || noop,
      onReady: this.options.onReady || noop,
      onEnded: this.options.onEnded || noop
    };
    this._listeners = [];
    this._hls = null;
    this._sourceElement = null;
    this._source = null;
    this._attempt = 'idle';
    this._activeUrl = '';
    this._pendingStartTime = 0;
    this._autoplayRequested = false;
    this._autoplayAttempt = '';
    this._autoplayRetryCount = 0;
    this._metadataReady = false;
    this._hasStarted = false;
    this._reportedFinalError = false;
    this._destroyed = false;
    this._generation = 0;
    this._startupTimer = null;
    this._bindVideoEvents();
  }

  Player.KEY = KEY;

  Player.prototype._listen = function (name, handler) {
    this.video.addEventListener(name, handler, false);
    this._listeners.push({ name: name, handler: handler });
  };

  Player.prototype._call = function (name) {
    var callback = this.callbacks[name];
    if (typeof callback !== 'function') {
      return;
    }
    try {
      callback.apply(null, Array.prototype.slice.call(arguments, 1));
    } catch (error) {
      if (global.console && typeof global.console.error === 'function') {
        global.console.error('ShyniFlexWebOS.Player callback failed', error);
      }
    }
  };

  Player.prototype._emitState = function (state, event) {
    this._call('onStateChange', state, {
      event: event || null,
      url: this._activeUrl,
      attempt: this._attempt,
      progress: this.getProgress()
    });
  };

  Player.prototype._bindVideoEvents = function () {
    var self = this;
    var progress = function (event) {
      self._call('onProgress', self.getProgress(), event || null);
    };

    this._listen('timeupdate', function (event) {
      if (self._pendingStartTime > 0 && self._metadataReady) { self._applyPendingStartTime(); }
      progress(event);
    });
    this._listen('durationchange', progress);
    this._listen('progress', progress);
    this._listen('loadedmetadata', function (event) {
      // Only the bounded text overlay renders subtitles. Do not also ask the
      // native decoder to render embedded/image subtitle tracks.
      var tracks = self.video.textTracks || [];
      for (var i = 0; i < tracks.length; i += 1) { try { tracks[i].mode = 'disabled'; } catch (ignoreTrack) {} }
      self._metadataReady = true;
      self._applyPendingStartTime();
      progress(event);
      self._call('onReady', self.getProgress(), event || null);
      if (self._autoplayRequested && self._autoplayAttempt !== self._attempt) {
        self._requestAutoplay();
      }
    });
    var retryPendingStart = function (event) {
      if (self._pendingStartTime <= 0) {
        return;
      }
      self._metadataReady = true;
      self._applyPendingStartTime();
      progress(event);
      if (self._pendingStartTime <= 0 && self._autoplayRequested && self._autoplayAttempt !== self._attempt) {
        self._requestAutoplay();
      }
    };
    this._listen('loadeddata', retryPendingStart);
    this._listen('canplay', retryPendingStart);
    this._listen('play', function (event) {
      self._emitState('starting', event);
    });
    this._listen('playing', function (event) {
      if (!self._source) { return; }
      self._hasStarted = true;
      self._metadataReady = true;
      self._applyPendingStartTime();
      if (self._pendingStartTime <= 0) { self._clearStartupTimer(); }
      self._emitState('playing', event);
    });
    this._listen('pause', function (event) {
      if (!self.video.ended && self._source) {
        self._emitState('paused', event);
      }
    });
    this._listen('waiting', function (event) {
      self._emitState('buffering', event);
    });
    this._listen('stalled', function (event) {
      self._emitState('buffering', event);
    });
    this._listen('ended', function (event) {
      self._emitState('ended', event);
      self._call('onEnded', event || null);
    });
    this._listen('error', function (event) {
      self._handleVideoError(event);
    });
  };

  Player.prototype.setCallbacks = function (callbacks) {
    var update = callbacks || {};
    var names = [
      'onProgress',
      'onError',
      'onStateChange',
      'onReady',
      'onEnded'
    ];
    var i;
    for (i = 0; i < names.length; i += 1) {
      if (typeof update[names[i]] === 'function') {
        this.callbacks[names[i]] = update[names[i]];
      }
    }
    return this;
  };

  Player.prototype._destroyHls = function () {
    if (this._hls && typeof this._hls.destroy === 'function') {
      try {
        this._hls.destroy();
      } catch (ignore) {
        // A partially attached hls.js instance may throw while being torn down.
      }
    }
    this._hls = null;
  };

  Player.prototype._setNativeSource = function (url, attempt, type) {
    this._generation += 1;
    this._clearStartupTimer();
    this._destroyHls();
    this._attempt = attempt;
    this._activeUrl = url;
    this._autoplayAttempt = '';
    this._autoplayRetryCount = 0;
    this._metadataReady = false;
    this._hasStarted = false;
    this.video.removeAttribute('src');

    if (this._sourceElement && this._sourceElement.parentNode === this.video) {
      this.video.removeChild(this._sourceElement);
    }
    this._sourceElement = null;

    // Native HLS engines can reject a <source> MIME hint without emitting a
    // video error. Assign HLS directly and let the HTTP response identify it.
    if (!isHlsUrl(url) && type && global.document && typeof global.document.createElement === 'function') {
      var source = global.document.createElement('source');
      source.src = url;
      source.type = type;
      var self = this;
      source.addEventListener('error', function (event) {
        if (self._sourceElement === source) { self._handleVideoError(event); }
      }, false);
      this.video.appendChild(source);
      this._sourceElement = source;
    } else {
      this.video.src = url;
    }

    this.video.preload = this._autoplayRequested ? 'auto' : 'metadata';
    if (typeof this.video.load === 'function') {
      this.video.load();
    }
    this._emitState('loading');
    this._armStartupTimer();
    if (this._autoplayRequested && (this._pendingStartTime <= 0 || isHlsUrl(url))) {
      this._requestAutoplay();
    }
  };

  Player.prototype._clearStartupTimer = function () {
    if (this._startupTimer !== null) { global.clearTimeout(this._startupTimer); }
    this._startupTimer = null;
  };

  Player.prototype._armStartupTimer = function () {
    var self = this;
    var generation = this._generation;
    this._clearStartupTimer();
    this._startupTimer = global.setTimeout(function () {
      self._startupTimer = null;
      if (self._generation !== generation || !self._source || self._reportedFinalError) { return; }
      if (self._attempt === 'direct' && self._source.hlsUrl && self._source.hlsUrl !== self._source.directUrl) {
        self._handleVideoError({ type: 'startup-timeout' });
      } else {
        var ready = finiteNumber(self.video.readyState, 0);
        var network = finiteNumber(self.video.networkState, 0);
        self._reportFinalError({ kind: 'timeout', name: 'startup_timeout', message: '영상이 시작되지 않아 재생을 중단했습니다. ' +
          (ready > 0 ? '영상 정보를 받았지만 TV가 재생을 시작하지 못했습니다.' : 'TV가 재생 가능한 영상 데이터를 받지 못했습니다.') +
          ' (단계 ' + ready + '/' + network + ')' });
      }
    }, finiteNumber(this.options.startupTimeout, 25000));
  };

  Player.prototype._requestAutoplay = function () {
    if (!this._autoplayRequested || this._autoplayAttempt === this._attempt) {
      return null;
    }
    var self = this;
    var attempt = this._attempt;
    var generation = this._generation;
    this._autoplayAttempt = attempt;
    var result = this.play({ suppressError: this._autoplayRetryCount === 0 });
    var retryAfterFailure = function () {
      if (self._generation !== generation || self._attempt !== attempt || self._autoplayAttempt !== attempt || self._autoplayRetryCount >= 1) {
        return;
      }
      self._autoplayRetryCount += 1;
      self._autoplayAttempt = '';
      if (self._metadataReady && self._autoplayRequested) {
        global.setTimeout(function () {
          if (self._generation === generation) { self._requestAutoplay(); }
        }, 0);
      }
    };
    if (result === null) {
      retryAfterFailure();
    } else if (result && typeof result.catch === 'function') {
      result.catch(retryAfterFailure);
    }
    return result;
  };

  Player.prototype._canUseHlsJs = function () {
    var Hls = global.Hls;
    if (!Hls) {
      return false;
    }
    return typeof Hls.isSupported !== 'function' || Hls.isSupported();
  };

  Player.prototype._startHlsJs = function (url) {
    if (!url || !this._canUseHlsJs()) {
      return false;
    }

    var self = this;
    var Hls = global.Hls;
    var hls;
    try {
      hls = new Hls(this.options.hlsConfig || {});
      this._destroyHls();
      this._hls = hls;
      this._generation += 1;
      this._attempt = 'hls-js';
      this._activeUrl = url;
      this._autoplayAttempt = '';
      this._autoplayRetryCount = 0;
      this._metadataReady = false;
      this._hasStarted = false;
      this.video.removeAttribute('src');
      if (this._sourceElement && this._sourceElement.parentNode === this.video) {
        this.video.removeChild(this._sourceElement);
      }
      this._sourceElement = null;

      if (Hls.Events && Hls.Events.ERROR && typeof hls.on === 'function') {
        hls.on(Hls.Events.ERROR, function (eventName, data) {
          if (!data || !data.fatal) {
            return;
          }
          self._reportFinalError({
            kind: 'hls',
            name: data.type || 'hls_error',
            message: data.details || '',
            url: url,
            attempt: self._attempt,
            event: eventName || null,
            data: data
          });
        });
      }

      if (
        Hls.Events &&
        Hls.Events.MEDIA_ATTACHED &&
        typeof hls.once === 'function'
      ) {
        hls.once(Hls.Events.MEDIA_ATTACHED, function () {
          hls.loadSource(url);
        });
        hls.attachMedia(this.video);
      } else {
        hls.attachMedia(this.video);
        hls.loadSource(url);
      }
      this._emitState('loading');
      this._armStartupTimer();
      if (this._autoplayRequested) {
        this._requestAutoplay();
      }
      return true;
    } catch (error) {
      this._destroyHls();
      this._reportFinalError({
        kind: 'hls',
        name: 'hls_initialization_failed',
        message: error && error.message ? error.message : String(error),
        url: url,
        attempt: 'hls-js',
        error: error
      });
      return false;
    }
  };

  Player.prototype._reportFinalError = function (details) {
    if (this._reportedFinalError || this._destroyed) {
      return;
    }
    this._reportedFinalError = true;
    this._clearStartupTimer();
    this._autoplayRequested = false;
    this._generation += 1;
    this._source = null;
    try { this.video.pause(); } catch (ignorePause) {}
    this._destroyHls();
    this.video.removeAttribute('src');
    if (this._sourceElement && this._sourceElement.parentNode === this.video) { this.video.removeChild(this._sourceElement); }
    this._sourceElement = null;
    try { this.video.load(); } catch (ignoreLoad) {}
    this._emitState('error', details && details.event);
    this._call('onError', details);
  };

  Player.prototype._rememberFallbackPosition = function () {
    if (this._pendingStartTime > 0) {
      return;
    }
    if (this._metadataReady) {
      this._pendingStartTime = Math.max(0, finiteNumber(this.video.currentTime, 0));
    } else if (this._source) {
      this._pendingStartTime = Math.max(0, finiteNumber(this._source.startTime, 0));
    }
  };

  Player.prototype._handleVideoError = function (event) {
    if (this._destroyed || this._reportedFinalError || !this._source) {
      return;
    }

    var source = this._source;
    if (
      this._attempt === 'direct' &&
      source.hlsUrl &&
      source.hlsUrl !== source.directUrl
    ) {
      this._rememberFallbackPosition();
      this._setNativeSource(
        source.hlsUrl,
        'hls-native',
        'application/vnd.apple.mpegurl'
      );
      return;
    }

    if (
      (this._attempt === 'direct' || this._attempt === 'hls-native') &&
      this._canUseHlsJs()
    ) {
      var hlsUrl = source.hlsUrl || (isHlsUrl(source.directUrl) ? source.directUrl : '');
      if (hlsUrl) {
        this._rememberFallbackPosition();
      }
      if (hlsUrl && this._startHlsJs(hlsUrl)) {
        return;
      }
    }

    this._reportFinalError(
      mediaErrorDetails(this.video, event, this._attempt, this._activeUrl)
    );
  };

  Player.prototype.load = function (source, options) {
    if (this._destroyed) {
      throw new Error('Cannot load media after Player.destroy().');
    }

    var normalized = normalizedSource(source, options);
    if (!normalized.directUrl && !normalized.hlsUrl) {
      throw new Error('A directUrl, url, src, hlsUrl, or fallbackUrl is required.');
    }

    this._generation += 1;
    this._source = normalized;
    this._reportedFinalError = false;
    this._pendingStartTime = Math.max(0, normalized.startTime);
    this._autoplayRequested = normalized.autoplay;
    this._autoplayAttempt = '';
    this._autoplayRetryCount = 0;
    this._metadataReady = false;

    if (typeof normalized.crossOrigin === 'string') {
      this.video.crossOrigin = normalized.crossOrigin;
    }

    if (normalized.directUrl) {
      this._setNativeSource(normalized.directUrl, 'direct', normalized.type);
    } else {
      this._setNativeSource(
        normalized.hlsUrl,
        'hls-native',
        'application/vnd.apple.mpegurl'
      );
    }
    return this;
  };

  Player.prototype.play = function (options) {
    var suppressError = Boolean(options && options.suppressError);
    var result;
    try {
      result = this.video.play();
    } catch (error) {
      if (!suppressError) {
        this._reportFinalError({
          kind: 'playback',
          name: 'play_failed',
          message: error && error.message ? error.message : String(error),
          error: error,
          url: this._activeUrl
        });
      }
      return null;
    }

    if (result && typeof result.catch === 'function') {
      var self = this;
      var generation = this._generation;
      result.catch(function (error) {
        if (!suppressError && self._generation === generation && self._source) {
          self._reportFinalError({
            kind: 'playback',
            name: 'play_rejected',
            message: error && error.message ? error.message : String(error),
            error: error,
            url: self._activeUrl
          });
        }
      });
    }
    return result;
  };

  Player.prototype.pause = function () {
    this._autoplayRequested = false;
    this._clearStartupTimer();
    this.video.pause();
    return this;
  };

  Player.prototype.toggle = function () {
    if (this.video.paused || this.video.ended) {
      this.play();
    } else {
      this.pause();
    }
    return this;
  };

  Player.prototype.stop = function () {
    this.video.pause();
    this.seekTo(0);
    this._emitState('stopped');
    return this;
  };

  Player.prototype._applyPendingStartTime = function () {
    if (this._pendingStartTime <= 0) {
      return true;
    }
    var pendingStartTime = this._pendingStartTime;
    this._pendingStartTime = 0;
    this.seekTo(pendingStartTime);
    if (this._hasStarted && this._pendingStartTime <= 0 && !this.video.paused && finiteNumber(this.video.readyState, 0) >= 3) { this._clearStartupTimer(); }
    return this._pendingStartTime <= 0;
  };

  Player.prototype.unload = function () {
    if (this._destroyed) {
      return this;
    }
    this._generation += 1;
    this._clearStartupTimer();
    this._autoplayRequested = false;
    this._autoplayAttempt = '';
    this._autoplayRetryCount = 0;
    this._metadataReady = false;
    this._pendingStartTime = 0;
    this._source = null;
    this._reportedFinalError = false;
    try {
      this.video.pause();
    } catch (ignorePause) {
      // A partially initialized media element may reject pause during teardown.
    }
    this._destroyHls();
    this.video.removeAttribute('src');
    if (this._sourceElement && this._sourceElement.parentNode === this.video) {
      this.video.removeChild(this._sourceElement);
    }
    this._sourceElement = null;
    this.video.preload = 'metadata';
    if (typeof this.video.load === 'function') {
      try {
        this.video.load();
      } catch (ignoreLoad) {
        // Older webOS engines may reject load after a failed source.
      }
    }
    this._attempt = 'idle';
    this._activeUrl = '';
    return this;
  };

  Player.prototype.seekTo = function (seconds) {
    var duration = finiteNumber(this.video.duration, Infinity);
    var target = Math.max(0, finiteNumber(seconds, 0));
    if (isFinite(duration) && duration > 0) {
      target = clamp(target, 0, duration);
    }
    try {
      this.video.currentTime = target;
    } catch (ignore) {
      // Some engines reject seeks until metadata is available; retry then.
      this._pendingStartTime = target;
    }
    return target;
  };

  Player.prototype.seekBy = function (seconds) {
    return this.seekTo(finiteNumber(this.video.currentTime, 0) + finiteNumber(seconds, 0));
  };

  Player.prototype.getProgress = function () {
    // Do not overwrite the saved resume position while HLS is starting at 0.
    var position = this._pendingStartTime > 0 ? this._pendingStartTime : Math.max(0, finiteNumber(this.video.currentTime, 0));
    var duration = Math.max(0, finiteNumber(this.video.duration, 0));
    var buffered = 0;
    var ranges = this.video.buffered;
    var i;

    if (ranges && typeof ranges.length === 'number') {
      try {
        for (i = 0; i < ranges.length; i += 1) {
          if (ranges.start(i) <= position + 0.25) {
            buffered = Math.max(buffered, ranges.end(i));
          }
        }
      } catch (ignore) {
        buffered = 0;
      }
    }

    return {
      position: position,
      duration: duration,
      buffered: buffered,
      percent: duration > 0 ? clamp(position / duration, 0, 1) : 0,
      bufferedPercent: duration > 0 ? clamp(buffered / duration, 0, 1) : 0,
      paused: Boolean(this.video.paused),
      ended: Boolean(this.video.ended)
    };
  };

  Player.prototype.handleRemoteKey = function (eventOrCode) {
    var code =
      typeof eventOrCode === 'number'
        ? eventOrCode
        : eventOrCode && (eventOrCode.keyCode || eventOrCode.which);

    switch (code) {
      case KEY.PLAY:
        this.play();
        return true;
      case KEY.PAUSE:
        this.pause();
        return true;
      case KEY.FAST_FORWARD:
        this.seekBy(this.seekStep);
        return true;
      case KEY.REWIND:
        this.seekBy(-this.seekStep);
        return true;
      case KEY.STOP:
        this.stop();
        return true;
      default:
        return false;
    }
  };

  Player.prototype.destroy = function () {
    var i;
    if (this._destroyed) {
      return;
    }
    this.unload();
    this._destroyed = true;
    this._destroyHls();
    for (i = 0; i < this._listeners.length; i += 1) {
      this.video.removeEventListener(
        this._listeners[i].name,
        this._listeners[i].handler,
        false
      );
    }
    this._listeners = [];
  };

  var namespace = global.ShyniFlexWebOS || {};
  namespace.Player = Player;
  namespace.PlayerKeys = KEY;
  global.ShyniFlexWebOS = namespace;
  global.ShyniFlexPlayer = Player;
})(
  typeof window !== 'undefined'
    ? window
    : typeof global !== 'undefined'
      ? global
      : this
);
