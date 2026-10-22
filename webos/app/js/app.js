(function (global) {
  'use strict';

  var APP_VERSION = '1.0.2';
  var STORAGE = {
    connection: 'shyniflex.webos.connection.v1',
    libraryOrder: 'shyniflex.webos.library-order.v1',
    subtitle: 'shyniflex.webos.subtitle.v1',
    videoFit: 'shyniflex.webos.video-fit.v1',
    playback: 'shyniflex.webos.playback-settings.v1'
  };
  var SUBTITLE_FONT = {
    cinema: 'Shyni Hangul, Arial, sans-serif',
    defaultFont: 'Arial, sans-serif',
    external: 'Shyni External'
  };
  var PLAYBACK_DEFAULTS = {
    quality: 'auto',
    seekSeconds: 10,
    resume: true,
    rewindOnResume: 0,
    videoFit: 'contain'
  };

  var state = {
    client: null,
    connection: null,
    libraries: [],
    activeLibraryId: '',
    items: [],
    searchItems: [],
    libraryMode: 'browse',
    homeRows: [],
    routeStack: [],
    currentView: 'setup-view',
    currentModal: null,
    libraryReturnView: 'home-view',
    detailItem: null,
    detailChildren: [],
    detailStack: [],
    detailLoadToken: 0,
    detailLoading: false,
    detailPendingFocus: null,
    detailReturnView: 'items-view',
    detailReturnFocus: null,
    settingsReturnView: 'home-view',
    settingsReturnFocus: null,
    modalReturnFocus: null,
    playerReturnFocus: null,
    playback: null,
    pendingPlayback: null,
    player: null,
    subtitles: null,
    remote: null,
    osdTimer: null,
    toastTimer: null,
    lastProgressReport: 0,
    playbackStartReported: false,
    playerState: 'idle',
    pendingLibraryOrder: [],
    homeLoadToken: 0,
    browseLoadToken: 0,
    playLoadToken: 0,
    backdropTimer: null,
    cardVisualTimer: null,
    artworkTimer: null,
    sortAscending: true,
    videoFitMode: 'contain',
    playbackSettings: {
      quality: PLAYBACK_DEFAULTS.quality,
      seekSeconds: PLAYBACK_DEFAULTS.seekSeconds,
      resume: PLAYBACK_DEFAULTS.resume,
      rewindOnResume: PLAYBACK_DEFAULTS.rewindOnResume,
      videoFit: PLAYBACK_DEFAULTS.videoFit
    },
    connectionAttemptToken: 0,
    authStep: 'media-login-step',
    authKind: '',
    authBusy: false,
    authFlowToken: 0,
    authPollTimer: null,
    authDeadline: 0,
    authPollDelay: 1500,
    mediaServer: null,
    shyniChallenge: '',
    quickConnectAvailable: false,
    quickConnect: null,
    plexPin: null,
    plexToken: '',
    plexServers: [],
    deviceCapabilities: {
      uhd: false,
      screenWidth: 1920,
      screenHeight: 1080
    }
  };

  var refs = {};
  var artworkObserver = null;

  function byId(id) {
    return global.document.getElementById(id);
  }

  function all(selector, root) {
    return Array.prototype.slice.call((root || global.document).querySelectorAll(selector));
  }

  function storageGet(key, fallback) {
    try {
      var raw = global.localStorage.getItem(key);
      return raw ? JSON.parse(raw) : fallback;
    } catch (ignore) {
      return fallback;
    }
  }

  function storageSet(key, value) {
    try {
      global.localStorage.setItem(key, JSON.stringify(value));
      return true;
    } catch (ignore) {
      return false;
    }
  }

  function storageRemove(key) {
    try {
      global.localStorage.removeItem(key);
    } catch (ignore) {
      // A disabled local store should not make the TV UI unusable.
    }
  }

  function text(value, fallback) {
    if (value === null || typeof value === 'undefined' || value === '') {
      return fallback || '';
    }
    return String(value);
  }

  function number(value, fallback) {
    var parsed = Number(value);
    return isFinite(parsed) ? parsed : fallback;
  }

  function clamp(value, minimum, maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  function subtitleFontFamilyForPreset(preset) {
    if (preset === 'default') {
      return SUBTITLE_FONT.defaultFont;
    }
    if (preset === 'external') {
      return SUBTITLE_FONT.external;
    }
    return SUBTITLE_FONT.cinema;
  }

  function subtitleFontPresetForFamily(family) {
    var normalized = String(family || '').trim();
    if (normalized === SUBTITLE_FONT.external) {
      return 'external';
    }
    if (normalized === SUBTITLE_FONT.defaultFont) {
      return 'default';
    }
    return 'cinema';
  }

  function normalizeServerUrl(value) {
    var url = text(value).trim();
    while (url.length > 1 && url.charAt(url.length - 1) === '/') {
      url = url.slice(0, -1);
    }
    return url;
  }

  function shouldSkipSavedConnectionTest(config) {
    config = config || {};
    var kind = text(config.kind || config.type || config.serverType, 'plex').toLowerCase();
    return kind === 'plex' || Boolean(text(config.userId).trim());
  }

  function supportsPasswordAuthentication(kind) {
    kind = text(kind).toLowerCase();
    return kind === 'shyni' || kind === 'jellyfin' || kind === 'emby';
  }

  function authenticationModeForServer(kind, requestedMode) {
    if (!supportsPasswordAuthentication(kind)) { return 'token'; }
    return requestedMode === 'password' ? 'password' : 'token';
  }

  function eagerArtworkCount(kind) {
    return kind === 'library' ? 4 : 6;
  }

  function shouldReportPlaybackProgress(startReported, playerState) {
    return Boolean(startReported) || playerState === 'playing';
  }

  function isPromise(value) {
    return value && typeof value.then === 'function';
  }

  function asPromise(value) {
    return isPromise(value) ? value : global.Promise.resolve(value);
  }

  function apiNamespace() {
    return global.ShyniFlexApi || (global.ShyniFlexWebOS && global.ShyniFlexWebOS.Api);
  }

  function createClient(config) {
    var api = apiNamespace();
    if (!api) {
      throw new Error('webOS 서버 API 모듈을 불러오지 못했습니다.');
    }
    if (typeof api.createClient === 'function') {
      return api.createClient(config);
    }
    if (typeof api.Client === 'function') {
      return new api.Client(config);
    }
    if (typeof api === 'function') {
      return new api(config);
    }
    throw new Error('지원되는 서버 API 생성자를 찾을 수 없습니다.');
  }

  function authenticateByName(config) {
    var api = apiNamespace();
    if (!api || typeof api.authenticateByName !== 'function') {
      return global.Promise.reject(new Error('webOS 서버 로그인 기능을 불러오지 못했습니다.'));
    }
    try {
      return asPromise(api.authenticateByName(config));
    } catch (error) {
      return global.Promise.reject(error);
    }
  }

  function invokeAuthenticationApi(name, config) {
    var api = apiNamespace();
    if (!api || typeof api[name] !== 'function') {
      return global.Promise.reject(new Error('webOS 로그인 기능(' + name + ')을 불러오지 못했습니다.'));
    }
    try {
      return asPromise(api[name](config || {}));
    } catch (error) {
      return global.Promise.reject(error);
    }
  }

  function invokeClientOn(client, names, args, optional) {
    var i;
    if (!client) {
      return global.Promise.reject(new Error('서버가 연결되지 않았습니다.'));
    }
    for (i = 0; i < names.length; i += 1) {
      if (typeof client[names[i]] === 'function') {
        try {
          return asPromise(client[names[i]].apply(client, args || []));
        } catch (error) {
          return global.Promise.reject(error);
        }
      }
    }
    if (optional) {
      return global.Promise.resolve(null);
    }
    return global.Promise.reject(new Error('서버 API 기능을 찾을 수 없습니다: ' + names[0]));
  }

  function invokeClient(names, args, optional) {
    return invokeClientOn(state.client, names, args, optional);
  }

  function resultArray(result, keys) {
    var i;
    if (Array.isArray(result)) {
      return result;
    }
    result = result || {};
    for (i = 0; i < keys.length; i += 1) {
      if (Array.isArray(result[keys[i]])) {
        return result[keys[i]];
      }
    }
    return [];
  }

  function errorMessage(error) {
    if (!error) {
      return '알 수 없는 오류가 발생했습니다.';
    }
    if (typeof error === 'string') { return error; }
    var message = error.userMessage || error.message || error.statusText;
    if (message && typeof message !== 'object') { return text(message, '요청에 실패했습니다.'); }
    if (number(error.code, 0) === 4 || error.name === 'source_not_supported') {
      return 'TV가 이 영상 형식을 지원하지 않습니다.';
    }
    if (number(error.code, 0) === 3 || error.name === 'decode') {
      return 'TV가 영상 데이터를 해독하지 못했습니다.';
    }
    if (number(error.code, 0) === 2 || error.name === 'network') {
      return '영상 데이터를 받는 중 네트워크 오류가 발생했습니다.';
    }
    if (error.name === 'startup_timeout') {
      return '영상이 시작되지 않아 재생을 중단했습니다.';
    }
    return '재생 요청을 처리하지 못했습니다.';
  }

  function setNetworkStatus(label, isError) {
    refs.networkStatus.textContent = label;
    refs.networkStatus.style.color = isError ? '#ff6b70' : '';
  }

  function updateSystemClock() {
    var now;
    var hours;
    var minutes;
    if (!refs.systemClock) { return; }
    now = new Date();
    hours = now.getHours();
    minutes = now.getMinutes();
    refs.systemClock.textContent = (hours < 10 ? '0' : '') + hours + ':' + (minutes < 10 ? '0' : '') + minutes;
  }

  function normalizePlaybackSettings(value) {
    value = value || {};
    var quality = ['fullhd', 'hd', 'compatible'].indexOf(value.quality) >= 0 ? value.quality : PLAYBACK_DEFAULTS.quality;
    var seekSeconds = number(value.seekSeconds, PLAYBACK_DEFAULTS.seekSeconds);
    if ([5, 10, 15, 30, 60].indexOf(seekSeconds) < 0) { seekSeconds = PLAYBACK_DEFAULTS.seekSeconds; }
    return {
      quality: quality,
      seekSeconds: seekSeconds,
      resume: value.resume !== false,
      rewindOnResume: [0, 5, 10].indexOf(number(value.rewindOnResume, PLAYBACK_DEFAULTS.rewindOnResume)) >= 0
        ? number(value.rewindOnResume, PLAYBACK_DEFAULTS.rewindOnResume)
        : PLAYBACK_DEFAULTS.rewindOnResume,
      videoFit: value.videoFit === 'cover' ? 'cover' : 'contain'
    };
  }

  function playbackStartTimeForSettings(startTime, settings) {
    settings = normalizePlaybackSettings(settings);
    if (!settings.resume) { return 0; }
    return Math.max(0, number(startTime, 0) - settings.rewindOnResume);
  }

  function playbackOptionsForCapabilities(capabilities, settings) {
    settings = normalizePlaybackSettings(settings);
    if ((capabilities && capabilities.legacy) || settings.quality === 'compatible') {
      var hd = settings.quality === 'hd';
      return { compatibility: true, adaptive: settings.quality === 'auto', maxVideoWidth: hd ? 1280 : 1920, maxVideoHeight: hd ? 720 : 1080,
        videoResolution: hd ? '1280x720' : '1920x1080', maxVideoBitrateKbps: 8000, maxStreamingBitrate: 10000000 };
    }
    if (settings.quality === 'hd') {
      return {
        maxVideoWidth: 1280,
        maxVideoHeight: 720,
        videoResolution: '1280x720',
        maxVideoBitrateKbps: 8000,
        maxStreamingBitrate: 12000000
      };
    }
    if (settings.quality === 'fullhd') {
      return {
        maxVideoWidth: 1920,
        maxVideoHeight: 1080,
        videoResolution: '1920x1080',
        maxVideoBitrateKbps: 20000,
        maxStreamingBitrate: 30000000
      };
    }
    if (!capabilities || !capabilities.uhd) {
      return {};
    }
    return {
      uhd: true,
      maxVideoWidth: 3840,
      maxVideoHeight: 2160,
      videoResolution: '3840x2160',
      maxVideoBitrateKbps: 80000,
      maxStreamingBitrate: 120000000
    };
  }

  function playbackOptions() {
    return playbackOptionsForCapabilities(state.deviceCapabilities, state.playbackSettings);
  }

  function playbackOptionsForItem(item, forceCompatibility) {
    var options = playbackOptions();
    if (state.connection && state.connection.type === 'shyni') {
      // The TV's native decoder can handle many 4K H.264/H.265 files without
      // making the server transcode them. Auto quality therefore tries the
      // original stream first and only falls back after a decoder failure.
      if (state.playbackSettings.quality === 'auto' && !forceCompatibility) {
        options.compatibility = false;
        if (state.deviceCapabilities && state.deviceCapabilities.uhd) {
          options.uhd = true;
          options.maxVideoWidth = 3840;
          options.maxVideoHeight = 2160;
          options.videoResolution = '3840x2160';
          options.maxVideoBitrateKbps = 80000;
          options.maxStreamingBitrate = 120000000;
        }
      } else if (forceCompatibility) {
        options.compatibility = true;
      }
    }
    options.startTimeSeconds = playbackStartTimeForSettings(
      number(item && item.resumePositionMs, 0) / 1000,
      state.playbackSettings
    );
    return options;
  }

  function updateDeviceCapabilityLabel() {
    var capabilities = state.deviceCapabilities;
    if (!refs.deviceCapability) { return; }
    if (capabilities.legacy) {
      refs.deviceCapability.textContent = '구형 webOS TV · 자동 재생 선택 (직접 재생 / 호환 H.264·AAC)';
      return;
    }
    if (capabilities.uhd) {
      refs.deviceCapability.textContent = '4K UHD 영상 재생 · ' + capabilities.screenWidth + '×' + capabilities.screenHeight + ' 디스플레이 감지';
      return;
    }
    refs.deviceCapability.textContent = 'HD/Full HD 영상 재생 · ' + capabilities.screenWidth + '×' + capabilities.screenHeight + ' 디스플레이 감지';
  }

  function applyUhdUiClass(enabled, root) {
    root = root || (global.document && global.document.documentElement);
    var active = Boolean(enabled);
    if (root && root.classList && typeof root.classList.toggle === 'function') {
      root.classList.toggle('uhd-tv', active);
    }
    return active;
  }

  function isLegacyTv(info, userAgent) {
    var sdk = text(info && info.sdkVersion);
    if (/^\d+\./.test(sdk)) { return number(sdk.split('.')[0], 0) < 4; }
    var ua = text(userAgent);
    var chrome = /Chrome\/(\d+)/i.exec(ua);
    return /Web0S|webOS|SmartTV/i.test(ua) && (!chrome || Number(chrome[1]) < 53);
  }

  function applyDeviceCapabilities(info) {
    var screenInfo = global.screen || {};
    var width = number(info && info.screenWidth, number(screenInfo.width, 1920));
    var height = number(info && info.screenHeight, number(screenInfo.height, 1080));
    var uhd = Boolean(info && (info.uhd === true || info.uhd === 'true')) || width >= 3840 || height >= 2160;
    state.deviceCapabilities = {
      uhd: uhd,
      screenWidth: width,
      screenHeight: height,
      legacy: isLegacyTv(info, global.navigator && global.navigator.userAgent)
    };
    applyUhdUiClass(uhd);
    updateDeviceCapabilityLabel();
    return state.deviceCapabilities;
  }

  function detectDeviceCapabilities() {
    var fallback = applyDeviceCapabilities(null);
    if (global.webOS && typeof global.webOS.deviceInfo === 'function') {
      try {
        global.webOS.deviceInfo(function (info) {
          applyDeviceCapabilities(info || null);
        });
      } catch (ignore) {
        // Browser previews and older TVs keep the safe screen-size fallback.
      }
    }
    return fallback;
  }

  function showToast(message, duration) {
    refs.toast.textContent = message;
    refs.toast.classList.remove('hidden');
    if (state.toastTimer) {
      global.clearTimeout(state.toastTimer);
    }
    state.toastTimer = global.setTimeout(function () {
      refs.toast.classList.add('hidden');
    }, duration || 2800);
  }

  function updateSideNavigation(id) {
    var isSettingsView = id === 'settings-view' || id === 'playback-settings-view';
    var selected = id === 'home-view' ? refs.navHome : id === 'search-view' ? refs.navSearch : isSettingsView ? refs.navSettings : refs.navLibraries;
    var libraryButtons = refs.sideLibraryItems ? all('.side-library-entry', refs.sideLibraryItems) : [];
    if (id === 'detail-view' && state.detailReturnView === 'home-view') {
      selected = refs.navHome;
    }
    if (state.activeLibraryId && (id === 'items-view' || (id === 'detail-view' && state.detailReturnView === 'items-view'))) {
      libraryButtons.some(function (button) {
        if (button.getAttribute('data-library-id') === state.activeLibraryId) {
          selected = button;
          return true;
        }
        return false;
      });
    }
    [refs.navHome, refs.navSearch, refs.navLibraries, refs.navRefresh, refs.navSettings].concat(libraryButtons).forEach(function (item) {
      if (!item) { return; }
      item.classList.toggle('selected', item === selected);
      if (item === selected) {
        item.setAttribute('aria-current', 'page');
      } else {
        item.removeAttribute('aria-current');
      }
    });
  }

  function showView(id, preferredFocus) {
    var target = byId(id);
    var views = all('.view');
    if (!target || !target.classList || !target.classList.contains('view')) {
      id = 'setup-view';
      target = refs.setupView || byId(id);
      preferredFocus = refs.providerPlex || null;
    }
    if (!target) {
      throw new Error('표시할 화면을 찾을 수 없습니다.');
    }
    if (state.remote) { state.remote.stopRepeating(); state.remote.invalidateLayout(); }
    views.forEach(function (view) {
      view.classList.toggle('hidden', view.id !== id);
    });
    state.currentView = id;
    if (refs.sideNavigation) {
      refs.sideNavigation.classList.toggle('hidden', id === 'setup-view');
    }
    if (refs.content) {
      refs.content.classList.toggle('setup-content', id === 'setup-view');
    }
    if (refs.appHeader) {
      refs.appHeader.classList.toggle('tv-header', id !== 'setup-view');
      refs.appHeader.classList.toggle('setup-header', id === 'setup-view');
    }
    if (refs.settingsButton) {
      refs.settingsButton.classList.toggle('hidden', id === 'setup-view' || id === 'settings-view' || id === 'playback-settings-view');
    }
    updateSideNavigation(id);
    releaseHiddenArtwork();
    scheduleVisibleArtwork();
    global.setTimeout(function () { focusInitial(preferredFocus); }, 0);
  }

  function focusInitial(preferredFocus) {
    var scope = state.currentModal
      ? byId(state.currentModal)
      : refs.playerView && !refs.playerView.classList.contains('hidden')
        ? refs.playerOsd
        : byId(state.currentView);
    var candidates = scope ? all('.focusable:not([disabled]):not(.hidden)', scope) : [];
    var marked = scope ? scope.querySelector('[data-initial-focus="true"]') : null;
    var media = scope ? scope.querySelector('.media-card.focusable:not([disabled])') : null;
    var playerPreferred = scope === refs.playerOsd ? refs.playerToggle : null;
    var preferred = preferredFocus || playerPreferred || marked || media;
    var i;
    if (state.remote) {
      if (preferred && state.remote.focus(preferred, 'initial')) {
        return;
      }
      for (i = 0; i < candidates.length; i += 1) {
        if (state.remote.focus(candidates[i], 'initial')) {
          return;
        }
      }
      state.remote.focusInitial();
      return;
    }
    var target = preferred || candidates[0] || global.document.querySelector('.focusable:not(.hidden)');
    if (target && typeof target.focus === 'function') {
      target.focus();
    }
  }

  function firstValue(source, names, fallback) {
    var i;
    source = source || {};
    for (i = 0; i < names.length; i += 1) {
      if (source[names[i]] !== null && typeof source[names[i]] !== 'undefined') {
        return source[names[i]];
      }
    }
    return fallback;
  }

  function entityId(entity) {
    return text(firstValue(entity, ['id', 'key', 'Id', 'Key', 'ratingKey'], ''));
  }

  function libraryKey(library) {
    return entityId(library) || ('title:' + entityTitle(library));
  }

  function entityTitle(entity) {
    return text(firstValue(entity, ['title', 'name', 'Name', 'Title'], '제목 없음'));
  }

  function entityType(entity) {
    return text(firstValue(entity, ['type', 'kind', 'Type', 'CollectionType'], '')).toLowerCase();
  }

  function isPlayable(entity) {
    var explicit = firstValue(entity, ['playable', 'isPlayable', 'CanPlay'], null);
    var type = entityType(entity);
    if (explicit !== null) {
      return Boolean(explicit);
    }
    return type === 'movie' || type === 'episode' || type === 'video' || type === 'audio';
  }

  function entityImage(entity) {
    var direct = firstValue(entity, ['imageUrl', 'artworkUrl', 'posterUrl', 'thumb', 'PrimaryImageUrl'], '');
    if (direct) {
      return String(direct);
    }
    if (state.client && typeof state.client.imageUrl === 'function') {
      try {
        return state.client.imageUrl(entity, 420, 630) || '';
      } catch (ignore) {
        return '';
      }
    }
    return '';
  }

  function entityBackdrop(entity) {
    var direct = firstValue(entity, ['backdropUrl', 'artUrl', 'backgroundUrl', 'BackdropImageUrl'], '');
    return direct ? String(direct) : entityImage(entity);
  }

  function entitySummary(entity) {
    return text(firstValue(entity, ['summary', 'overview', 'Overview', 'description', 'Description'], ''));
  }

  function entityTypeLabel(entity) {
    var type = entityType(entity);
    var labels = {
      movie: '영화',
      show: 'TV 프로그램',
      series: 'TV 프로그램',
      season: '시즌',
      episode: '에피소드',
      video: '비디오',
      audio: '오디오',
      artist: '아티스트',
      album: '앨범',
      photo: '사진'
    };
    return labels[type] || (type ? type : '라이브러리');
  }

  function entityMetaText(entity) {
    var values = [];
    var subtitle = text(firstValue(entity, ['subtitle', 'parentTitle', 'seriesName'], ''));
    var year = text(firstValue(entity, ['year', 'ProductionYear'], ''));
    var durationMs = number(firstValue(entity, ['durationMs', 'duration'], 0), 0);
    function append(value) {
      if (value && values.indexOf(value) < 0) { values.push(value); }
    }
    append(subtitle);
    append(year);
    append(entityTypeLabel(entity));
    if (durationMs > 60000) {
      append(Math.max(1, Math.round(durationMs / 60000)) + '분');
    }
    return values.join(' · ');
  }

  function setBackdrop(node, url) {
    if (!node) { return; }
    url = text(url).trim();
    if (!url) {
      if (node.getAttribute('data-art-blob') && global.URL && global.URL.revokeObjectURL) {
        global.URL.revokeObjectURL(node.getAttribute('data-art-blob'));
      }
      node.removeAttribute('data-art-blob');
      node.removeAttribute('data-art-requested');
      node.style.backgroundImage = '';
      node.classList.remove('has-image');
      return;
    }
    node.setAttribute('data-art-requested', url);
    if (state.client && state.connection && state.connection.type === 'shyni' && typeof state.client.fetchImage === 'function') {
      state.client.fetchImage(url).then(function (resolved) {
        if (!resolved || node.getAttribute('data-art-requested') !== url) {
          if (resolved && /^blob:/i.test(resolved) && global.URL && global.URL.revokeObjectURL) { global.URL.revokeObjectURL(resolved); }
          return;
        }
        var previous = node.getAttribute('data-art-blob');
        if (previous && previous !== resolved && global.URL && global.URL.revokeObjectURL) { global.URL.revokeObjectURL(previous); }
        if (/^blob:/i.test(resolved)) { node.setAttribute('data-art-blob', resolved); }
        node.style.backgroundImage = 'url("' + resolved.replace(/"/g, '%22') + '")';
        node.classList.add('has-image');
      }).catch(function () {});
      return;
    }
    node.style.backgroundImage = 'url("' + url.replace(/"/g, '%22') + '")';
    node.classList.add('has-image');
  }

  function scheduleBackdrop(node, url) {
    if (state.backdropTimer) {
      global.clearTimeout(state.backdropTimer);
    }
    state.backdropTimer = global.setTimeout(function () {
      setBackdrop(node, url);
      state.backdropTimer = null;
    }, 120);
  }

  function captureFocusDescriptor(element) {
    if (!element || typeof element.getAttribute !== 'function') {
      return null;
    }
    return {
      entityId: text(element.getAttribute('data-entity-id')),
      element: element
    };
  }

  function resolveFocusDescriptor(descriptor, scope) {
    var match = null;
    if (!descriptor) { return null; }
    if (descriptor.entityId) {
      all('.media-card[data-entity-id]', scope || global.document).some(function (card) {
        if (card.getAttribute('data-entity-id') === descriptor.entityId) {
          match = card;
          return true;
        }
        return false;
      });
    }
    return match || descriptor.element || null;
  }

  function renderHomeSpotlight(entity) {
    if (!entity || !refs.homeHeroTitle) { return; }
    refs.homeHeroTitle.textContent = entityTitle(entity);
    refs.homeHeroMeta.textContent = entityMetaText(entity);
    refs.homeHeroSummary.textContent = entitySummary(entity) || (entityTitle(entity) + ' 콘텐츠를 탐색해 보세요.');
    scheduleBackdrop(refs.homeBackdrop, entityBackdrop(entity));
  }

  function homeSectionForCard(card) {
    var node = card;
    while (node && node !== refs.homeHubs) {
      if (typeof node.getAttribute === 'function' && node.getAttribute('data-home-section') === 'true') {
        return node;
      }
      node = node.parentNode;
    }
    return null;
  }

  function homeSectionTop(section) {
    var hubRect;
    var sectionRect;
    if (!refs.homeHubs || !section) { return 0; }
    if (typeof refs.homeHubs.getBoundingClientRect === 'function' && typeof section.getBoundingClientRect === 'function') {
      hubRect = refs.homeHubs.getBoundingClientRect();
      sectionRect = section.getBoundingClientRect();
      if (hubRect && sectionRect && isFinite(sectionRect.top) && isFinite(hubRect.top)) {
        return Math.max(0, refs.homeHubs.scrollTop + sectionRect.top - hubRect.top);
      }
    }
    return Math.max(0, number(section.offsetTop, 0));
  }

  function homeNextRowPeek() {
    var value = '';
    if (refs.homeHubs && typeof global.getComputedStyle === 'function') {
      value = global.getComputedStyle(refs.homeHubs).getPropertyValue('--home-next-row-peek');
    }
    return clamp(number(parseFloat(value), 30), 24, 54);
  }

  function sizeHomeHubViewport(section) {
    // CSS owns a stable viewport. Changing its height on each row caused
    // layout/scroll feedback and accidental pointer focus on older TVs.
    if (refs.homeHubs) { refs.homeHubs.style.height = ''; }
  }

  function activateHomeSectionForCard(card) {
    var section = homeSectionForCard(card);
    if (!refs.homeHubs || !section) { return false; }
    if (section.classList.contains('active-home-rail')) { return true; }
    all('[data-home-section="true"]', refs.homeHubs).forEach(function (candidate) {
      candidate.classList.remove('active-home-rail');
      candidate.removeAttribute('aria-current');
    });
    section.classList.add('active-home-rail');
    section.setAttribute('aria-current', 'true');
    sizeHomeHubViewport(section);
    refs.homeHubs.scrollTop = homeSectionTop(section);
    return true;
  }

  function wireHomeSectionNavigation() {
    var sections;
    if (!refs.homeHubs) { return; }
    sections = all('[data-home-section="true"]', refs.homeHubs);
    sections.forEach(function (section, sectionIndex) {
      var cards = all('.media-card', section);
      var previousCards = sectionIndex > 0 ? all('.media-card', sections[sectionIndex - 1]) : [];
      var nextCards = sectionIndex + 1 < sections.length ? all('.media-card', sections[sectionIndex + 1]) : [];
      cards.forEach(function (card, cardIndex) {
        var previousTarget = previousCards.length ? previousCards[Math.min(cardIndex, previousCards.length - 1)] : null;
        var nextTarget = nextCards.length ? nextCards[Math.min(cardIndex, nextCards.length - 1)] : null;
        card.id = 'home-hub-' + sectionIndex + '-card-' + cardIndex;
        if (previousTarget) {
          previousTarget.id = 'home-hub-' + (sectionIndex - 1) + '-card-' + Math.min(cardIndex, previousCards.length - 1);
          card.setAttribute('data-nav-up', '#' + previousTarget.id);
        } else {
          card.setAttribute('data-nav-up', '#' + card.id);
        }
        if (nextTarget) {
          nextTarget.id = 'home-hub-' + (sectionIndex + 1) + '-card-' + Math.min(cardIndex, nextCards.length - 1);
          card.setAttribute('data-nav-down', '#' + nextTarget.id);
        } else {
          card.setAttribute('data-nav-down', '#' + card.id);
        }
      });
    });
  }

  function renderItemsBackdrop(entity) {
    if (!entity) {
      setBackdrop(refs.itemsBackdrop, '');
      return;
    }
    scheduleBackdrop(refs.itemsBackdrop, entityBackdrop(entity));
  }

  function renderDetail(entity) {
    if (!entity) { return; }
    refs.detailTitle.textContent = entityTitle(entity);
    refs.detailMeta.textContent = entityMetaText(entity);
    refs.detailSummary.textContent = entitySummary(entity) || '이 항목의 상세 설명이 없습니다.';
    var resume = isPlayable(entity) && number(entity.resumePositionMs, 0) > 0 && state.playbackSettings.resume;
    refs.detailPrimaryAction.textContent = isPlayable(entity) ? (resume ? '이어보기 · ' + formatTime(entity.resumePositionMs / 1000) : '재생') : '항목 보기';
    refs.detailRestart.classList.toggle('hidden', !resume);
    refs.detailPrimaryAction.setAttribute('aria-label', isPlayable(entity) ? entityTitle(entity) + ' 재생' : entityTitle(entity) + ' 항목 보기');
    setBackdrop(refs.detailBackdrop, entityBackdrop(entity));
  }

  function renderDetailChildren(parent, items) {
    var eagerArtwork = [];
    if (artworkObserver && typeof artworkObserver.unobserve === 'function') {
      all('.artwork[data-art-url]', refs.detailChildRail).forEach(function (art) { artworkObserver.unobserve(art); });
    }
    refs.detailChildRail.innerHTML = '';
    state.detailChildren = items || [];
    refs.detailRelated.classList.toggle('hidden', state.detailChildren.length === 0);
    byId('detail-view').classList.toggle('has-detail-children', state.detailChildren.length > 0);
    refs.detailPrimaryAction.removeAttribute('data-nav-down');
    refs.detailChildRail.scrollTop = 0;
    refs.detailChildRail.scrollLeft = 0;
    if (!state.detailChildren.length) { return; }
    var parentType = entityType(parent);
    refs.detailChildRail.classList.toggle('episode-grid', parentType === 'season');
    refs.detailChildRail.classList.toggle('season-grid', parentType === 'show' || parentType === 'series');
    refs.detailChildRail.setAttribute('data-nav-layout', 'grid');
    refs.detailChildRail.setAttribute('data-nav-left', state.detailReturnView === 'home-view' ? '#nav-home' : '#nav-libraries');
    refs.detailRelatedTitle.textContent = parentType === 'season' ? '에피소드' : parentType === 'show' || parentType === 'series' ? '시즌 및 에피소드' : '관련 항목';
    state.detailChildren.forEach(function (item, index) {
      var card = createCard(item, index, 'detail-child', index >= 6, parentType === 'season' ? 'card-wide' : 'card-tall');
      if (index < 6 && card.firstChild && card.firstChild.getAttribute('data-art-url')) {
        eagerArtwork.push(card.firstChild);
      }
      refs.detailChildRail.appendChild(card);
    });
    refs.detailPrimaryAction.setAttribute('data-nav-down', '#detail-child-rail .media-card');
    global.setTimeout(function () {
      eagerArtwork.forEach(loadCardArtwork);
      if (state.detailPendingFocus) {
        var pending = state.detailPendingFocus;
        state.detailPendingFocus = null;
        focusInitial(resolveFocusDescriptor(pending, refs.detailChildRail));
      }
    }, 0);
  }

  function loadDetailChildren(parent) {
    var token = state.detailLoadToken + 1;
    state.detailLoadToken = token;
    renderDetailChildren(parent, []);
    if (!parent || isPlayable(parent) || !state.client) {
      state.detailLoading = false;
      return global.Promise.resolve([]);
    }
    state.detailLoading = true;
    return invokeClient(['listItems', 'getItems', 'browse'], [parent, { limit: 60 }]).then(function (result) {
      if (token !== state.detailLoadToken || state.detailItem !== parent) { return []; }
      state.detailLoading = false;
      var items = resultArray(result, ['items', 'Items', 'children']).slice(0, 60);
      renderDetailChildren(parent, items);
      if (!items.length && state.detailPendingFocus) {
        state.detailPendingFocus = null;
        global.setTimeout(function () { focusInitial(refs.detailPrimaryAction); }, 0);
      }
      return items;
    }).catch(function () {
      if (token === state.detailLoadToken) {
        state.detailLoading = false;
        renderDetailChildren(parent, []);
        state.detailPendingFocus = null;
        global.setTimeout(function () { focusInitial(refs.detailPrimaryAction); }, 0);
      }
      return [];
    });
  }

  function entityProgress(entity) {
    var progress = number(firstValue(entity, ['progress', 'progressFraction', 'playedFraction'], NaN), NaN);
    var resume = number(firstValue(entity, ['resumePosition', 'viewOffset', 'PlaybackPositionTicks'], 0), 0);
    var duration = number(firstValue(entity, ['duration', 'durationMs', 'RunTimeTicks'], 0), 0);
    if (isFinite(progress)) {
      return clamp(progress > 1 ? progress / 100 : progress, 0, 1);
    }
    if (resume > 0 && duration > 0) {
      return clamp(resume / duration, 0, 1);
    }
    return 0;
  }

  function entityPlayed(entity) {
    return Boolean(firstValue(entity, ['played', 'watched', 'isPlayed', 'Played'], false)) ||
      number(firstValue(entity, ['viewCount', 'ViewCount'], 0), 0) > 0;
  }

  function completedBadgeLabel(entity) {
    var type = entityType(entity);
    if (type === 'episode') { return entityPlayed(entity) ? '시청 완료' : ''; }
    if (type !== 'show' && type !== 'series' && type !== 'season') { return ''; }
    var total = number(entity && entity.episodeCount, -1);
    var watched = entity && entity.watchedEpisodeCount;
    var unplayed = entity && entity.unplayedEpisodeCount;
    // Unknown/empty counts are not proof that every episode was watched.
    if (total <= 0) { return ''; }
    if (unplayed != null) { return number(unplayed, -1) === 0 ? '전체 시청 완료' : ''; }
    return watched != null && number(watched, -1) >= total ? '전체 시청 완료' : '';
  }

  function applyPlayedBadge(card, entity) {
    if (!card) { return; }
    var label = completedBadgeLabel(entity);
    var existing = card.querySelector('.played-badge');
    if (!label) {
      if (existing) { card.removeChild(existing); }
      card.removeAttribute('data-played');
      card.setAttribute('aria-label', entityTitle(entity));
      return;
    }
    if (existing) { return; }
    var playedBadge = global.document.createElement('span');
    playedBadge.className = 'played-badge';
    playedBadge.textContent = '✓';
    playedBadge.setAttribute('aria-label', label);
    card.appendChild(playedBadge);
    card.setAttribute('data-played', 'true');
    card.setAttribute('aria-label', entityTitle(entity) + ', ' + label);
  }

  function updateEpisodeCompletion(items, episode) {
    if (entityType(episode) !== 'episode') { return; }
    var id = entityId(episode);
    var wasPlayed = entityPlayed(episode) || items.some(function (item) { return entityId(item) === id && entityPlayed(item); });
    items.forEach(function (item) {
      if (entityId(item) === id) { item.played = true; item.progress = 1; }
      if (wasPlayed || !item || [episode.parentId, episode.seriesId].indexOf(entityId(item)) < 0) { return; }
      if (item.watchedEpisodeCount != null) {
        item.watchedEpisodeCount = Math.min(number(item.episodeCount, Infinity), number(item.watchedEpisodeCount, 0) + 1);
      }
      if (item.unplayedEpisodeCount != null) { item.unplayedEpisodeCount = Math.max(0, number(item.unplayedEpisodeCount, 0) - 1); }
    });
    episode.played = true;
    episode.progress = 1;
  }

  function markEntityPlayed(entity) {
    var id = entityId(entity);
    if (!id) { return; }
    var knownItems = [];
    [state.items, state.searchItems, state.detailChildren, [state.detailItem], state.detailStack.map(function (entry) { return entry.item; })].concat(state.homeRows.map(function (row) { return row.items || []; })).forEach(function (items) {
      items.forEach(function (item) { if (item && knownItems.indexOf(item) < 0) { knownItems.push(item); } });
    });
    updateEpisodeCompletion(knownItems, entity);
    // Keep playback state current even for titles whose posters have no badge.
    knownItems.forEach(function (item) {
      if (entityId(item) === id) { item.played = true; item.progress = 1; }
    });
    entity.played = true;
    entity.progress = 1;
    all('.media-card[data-entity-id]').forEach(function (card) {
      knownItems.some(function (item) {
        if (card.getAttribute('data-entity-id') !== entityId(item)) { return false; }
        applyPlayedBadge(card, item);
        return true;
      });
    });
  }

  function loadCardArtwork(art) {
    if (!art || typeof art.getAttribute !== 'function') {
      return false;
    }
    var image = art.getAttribute('data-art-url');
    if (!image) {
      return false;
    }
    art.setAttribute('data-art-loaded-url', image);
    art.removeAttribute('data-art-url');
    if (state.client && state.connection && state.connection.type === 'shyni' && typeof state.client.fetchImage === 'function') {
      state.client.fetchImage(image).then(function (resolved) {
        if (!resolved || art.getAttribute('data-art-loaded-url') !== image) {
          if (resolved && /^blob:/i.test(resolved) && global.URL && global.URL.revokeObjectURL) { global.URL.revokeObjectURL(resolved); }
          return;
        }
        if (/^blob:/i.test(resolved)) { art.setAttribute('data-art-blob', resolved); }
        art.style.backgroundImage = 'url("' + resolved.replace(/"/g, '%22') + '")';
      }).catch(function () {});
    } else {
      art.style.backgroundImage = 'url("' + image.replace(/"/g, '%22') + '")';
    }
    if (artworkObserver && typeof artworkObserver.unobserve === 'function') {
      artworkObserver.unobserve(art);
    }
    return true;
  }

  function observeCardArtwork(art) {
    if (!art) {
      return;
    }
    if (typeof global.IntersectionObserver !== 'function') {
      scheduleVisibleArtwork();
      return;
    }
    if (!artworkObserver) {
      artworkObserver = new global.IntersectionObserver(function (entries) {
        if (entries.some(function (entry) { return entry.isIntersecting || entry.intersectionRatio > 0; })) { scheduleVisibleArtwork(); }
      }, { rootMargin: '64px 0px' });
    }
    artworkObserver.observe(art);
  }

  function scheduleVisibleArtwork() {
    if (state.artworkTimer) { global.clearTimeout(state.artworkTimer); }
    state.artworkTimer = global.setTimeout(loadVisibleArtwork, 180);
  }

  function loadVisibleArtwork() {
    state.artworkTimer = null;
    if (!global.document || state.currentModal || (refs.playerView && !refs.playerView.classList.contains('hidden'))) { return; }
    var scope = byId(state.currentView);
    if (!scope) { return; }
    var visible = [];
    var clipNodes = [];
    var clipRects = [];
    // Read geometry as one batch, then start a small batch of image decodes.
    all('.artwork[data-art-url]', scope).forEach(function (art) {
      var rect = art.getBoundingClientRect();
      var top = Math.max(0, rect.top);
      var bottom = Math.min(global.innerHeight, rect.bottom);
      var left = Math.max(0, rect.left);
      var right = Math.min(global.innerWidth, rect.right);
      var parent = art.parentNode;
      if (rect.width <= 0 || rect.height <= 0) { return; }
      while (parent && parent !== scope) {
        if (parent.getAttribute && (parent.getAttribute('data-nav-scroll') === 'true' || parent.id === 'home-hubs')) {
          var index = clipNodes.indexOf(parent);
          if (index < 0) { index = clipNodes.length; clipNodes.push(parent); clipRects.push(parent.getBoundingClientRect()); }
          var clip = clipRects[index];
          top = Math.max(top, clip.top); bottom = Math.min(bottom, clip.bottom);
          left = Math.max(left, clip.left); right = Math.min(right, clip.right);
        }
        parent = parent.parentNode;
      }
      if (bottom > top && right > left) { visible.push(art); }
    });
    visible.slice(0, 8).forEach(loadCardArtwork);
    if (visible.length > 8) { scheduleVisibleArtwork(); }
  }

  function releaseHiddenArtwork() {
    // Keep the card/route data for instant Back navigation, not decoded bitmaps.
    all('.view.hidden .artwork[data-art-loaded-url]').forEach(function (art) {
      art.setAttribute('data-art-url', art.getAttribute('data-art-loaded-url'));
      art.removeAttribute('data-art-loaded-url');
      art.style.backgroundImage = '';
      if (art.getAttribute('data-art-blob') && global.URL && global.URL.revokeObjectURL) { global.URL.revokeObjectURL(art.getAttribute('data-art-blob')); }
      art.removeAttribute('data-art-blob');
    });
    if (state.currentView !== 'home-view') { setBackdrop(refs.homeBackdrop, ''); }
    if (state.currentView !== 'items-view') { setBackdrop(refs.itemsBackdrop, ''); }
    if (state.currentView !== 'detail-view') { setBackdrop(refs.detailBackdrop, ''); }
  }

  function queueCardVisuals(button, entity) {
    var view = state.currentView;
    if (state.cardVisualTimer) { global.clearTimeout(state.cardVisualTimer); }
    scheduleVisibleArtwork();
    state.cardVisualTimer = global.setTimeout(function () {
      state.cardVisualTimer = null;
      if (state.currentView !== view || global.document.activeElement !== button || !refs.playerView.classList.contains('hidden')) { return; }
      if (view === 'home-view') { renderHomeSpotlight(entity); }
      else if (view === 'items-view') { renderItemsBackdrop(entity); }
    }, 120);
  }

  function createCard(entity, index, kind, deferArtwork, layoutOverride) {
    var button = global.document.createElement('button');
    var art = global.document.createElement('span');
    var copy = global.document.createElement('span');
    var titleNode = global.document.createElement('span');
    var metaNode = global.document.createElement('span');
    var progress = entityProgress(entity);
    var cardType = entityType(entity);
    var cardLayout = '';
    var image;

    if (kind === 'home-item' || kind === 'detail-child') {
      if (progress > 0 && progress < 0.98) {
        cardLayout = 'card-wide';
      } else if (cardType === 'show' || cardType === 'series' || cardType === 'season' || cardType === 'movie') {
        cardLayout = 'card-tall';
      } else if (cardType === 'artist' || cardType === 'album' || cardType === 'audio' || cardType === 'photo') {
        cardLayout = 'card-square';
      } else {
        cardLayout = 'card-wide';
      }
    }
    if (layoutOverride) {
      cardLayout = layoutOverride;
    }
    image = cardLayout === 'card-wide' ? entityBackdrop(entity) : entityImage(entity);

    button.type = 'button';
    button.className = 'media-card focusable' + (cardLayout ? ' ' + cardLayout : '');
    button.tabIndex = 0;
    button.setAttribute('data-card-kind', kind);
    button.setAttribute('data-card-index', String(index));
    button.setAttribute('data-entity-id', entityId(entity));
    button.setAttribute('aria-label', entityTitle(entity));

    art.className = 'artwork';
    if (image) {
      art.setAttribute('data-art-url', image);
      if (deferArtwork) {
        observeCardArtwork(art);
      }
    }
    copy.className = 'card-copy';
    titleNode.className = 'card-title';
    titleNode.textContent = entityTitle(entity);
    metaNode.className = 'card-meta';
    metaNode.textContent = kind === 'library' ? entityTypeLabel(entity) : entityMetaText(entity);
    copy.appendChild(titleNode);
    copy.appendChild(metaNode);
    button.appendChild(art);
    button.appendChild(copy);

    if (completedBadgeLabel(entity)) { applyPlayedBadge(button, entity); }

    if (progress > 0 && progress < 0.98) {
      var bar = global.document.createElement('span');
      var fill = global.document.createElement('span');
      bar.className = 'progress-bar';
      fill.style.width = Math.round(progress * 100) + '%';
      bar.appendChild(fill);
      button.appendChild(bar);
    }
    button.addEventListener('focus', function () {
      if (state.currentView === 'home-view') {
        activateHomeSectionForCard(button);
      }
      queueCardVisuals(button, entity);
    }, false);
    // Pointer and D-pad use the same focus event; hover must not move a rail
    // underneath the focused card when a stationary Magic Remote cursor is hit.
    return button;
  }

  function applyLibraryOrder(libraries) {
    var order = storageGet(STORAGE.libraryOrder, []);
    if (!Array.isArray(order) || !order.length) {
      return libraries.slice();
    }
    return libraries.slice().sort(function (left, right) {
      var leftIndex = order.indexOf(entityId(left));
      var rightIndex = order.indexOf(entityId(right));
      if (leftIndex < 0) { leftIndex = 100000; }
      if (rightIndex < 0) { rightIndex = 100000; }
      return leftIndex - rightIndex;
    });
  }

  function renderCardGrid(grid, entities, kind) {
    if (state.remote) { state.remote.invalidateLayout(); }
    var eagerCount = eagerArtworkCount(kind);
    var eagerArtwork = [];
    var fragment = global.document.createDocumentFragment();
    if (artworkObserver && typeof artworkObserver.unobserve === 'function' && typeof grid.querySelectorAll === 'function') {
      all('.artwork[data-art-url]', grid).forEach(function (art) {
        artworkObserver.unobserve(art);
      });
    }
    grid.innerHTML = '';
    entities.forEach(function (entity, index) {
      var card = createCard(entity, index, kind, index >= eagerCount);
      if (index < eagerCount && card.firstChild && card.firstChild.getAttribute('data-art-url')) {
        eagerArtwork.push(card.firstChild);
      }
      fragment.appendChild(card);
    });
    grid.appendChild(fragment);
    global.setTimeout(function () {
      eagerArtwork.forEach(loadCardArtwork);
    }, 0);
  }

  function renderLibraries() {
    renderCardGrid(refs.libraryBrowseGrid, state.libraries, 'library');
    renderSideLibraries();
    if (!state.libraries.length) {
      refs.libraryBrowseGrid.textContent = '라이브러리가 없습니다.';
    } else {
      renderHomeSpotlight(state.libraries[0]);
    }
    global.setTimeout(focusInitial, 0);
  }

  function appendHomePreviewRow(row, rowIndex) {
    var section = global.document.createElement('section');
    var heading = global.document.createElement('div');
    var titleWrap = global.document.createElement('div');
    var icon = global.document.createElement('span');
    var titleNode = global.document.createElement('h2');
    var rail = global.document.createElement('div');
    var eagerArtwork = [];
    section.className = 'home-preview-rail';
    section.setAttribute('data-home-section', 'true');
    section.setAttribute('data-home-row-index', String(rowIndex));
    heading.className = 'home-hub-heading';
    heading.classList.add('rail-heading');
    titleWrap.className = 'home-hub-title-wrap';
    icon.className = 'home-hub-icon';
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = row.kind === 'continue' ? '▶' : '✦';
    titleNode.textContent = row.title || entityTitle(row.library);
    rail.className = 'card-rail home-item-rail';
    rail.setAttribute('data-nav-layout', 'rail');
    rail.setAttribute('data-nav-scroll', 'true');
    rail.setAttribute('data-nav-left', '#nav-home');
    rail.setAttribute('aria-label', titleNode.textContent);
    titleWrap.appendChild(icon);
    titleWrap.appendChild(titleNode);
    heading.appendChild(titleWrap);
    section.appendChild(heading);
    row.items.forEach(function (item, itemIndex) {
      var card = createCard(item, itemIndex, 'home-item', itemIndex >= 6, row.kind === 'continue' ? 'card-wide' : '');
      card.setAttribute('data-home-row', String(rowIndex));
      if (itemIndex === 0) {
        card.setAttribute('data-nav-left', '#nav-home');
      }
      if (itemIndex < 6 && card.firstChild && card.firstChild.getAttribute('data-art-url')) {
        eagerArtwork.push(card.firstChild);
      }
      rail.appendChild(card);
    });
    section.appendChild(rail);
    refs.homePreviewRows.appendChild(section);
    global.setTimeout(function () { eagerArtwork.forEach(loadCardArtwork); }, 0);
  }

  function renderHomePreviewRows() {
    var active = global.document.activeElement;
    var hadPreviewFocus = active && active.getAttribute && active.getAttribute('data-card-kind') === 'home-item';
    var previousEntityId = hadPreviewFocus ? text(active.getAttribute('data-entity-id')) : '';
    var previousRowIndex = hadPreviewFocus ? number(active.getAttribute('data-home-row'), 0) : 0;
    var previousCardIndex = hadPreviewFocus ? number(active.getAttribute('data-card-index'), 0) : 0;
    var shouldMoveInitialFocus = state.currentView === 'home-view' &&
      (!active || active === global.document.body);
    var firstHomeCard;
    var focusTarget = null;
    if (artworkObserver && typeof artworkObserver.unobserve === 'function') {
      all('.artwork[data-art-url]', refs.homePreviewRows).forEach(function (art) { artworkObserver.unobserve(art); });
    }
    refs.homePreviewRows.innerHTML = '';
    state.homeRows.forEach(appendHomePreviewRow);
    wireHomeSectionNavigation();
    firstHomeCard = refs.homePreviewRows.querySelector('.media-card');
    if (hadPreviewFocus && previousEntityId) {
      all('.media-card[data-entity-id]', refs.homePreviewRows).some(function (card) {
        if (card.getAttribute('data-entity-id') === previousEntityId) {
          focusTarget = card;
          return true;
        }
        return false;
      });
    }
    if (hadPreviewFocus && !focusTarget) {
      var previousRow = refs.homePreviewRows.querySelector('[data-home-row-index="' + previousRowIndex + '"]');
      var previousRowCards = previousRow ? all('.media-card', previousRow) : [];
      if (previousRowCards.length) {
        focusTarget = previousRowCards[Math.min(previousCardIndex, previousRowCards.length - 1)];
      }
    }
    focusTarget = focusTarget || firstHomeCard;
    if (firstHomeCard) {
      activateHomeSectionForCard(focusTarget);
      if (shouldMoveInitialFocus || hadPreviewFocus) {
        global.setTimeout(function () { focusInitial(focusTarget); }, 0);
      }
    }
  }

  function preferredHomeLibraries() {
    var visual = state.libraries.filter(function (library) {
      var type = entityType(library);
      var collectionType = text(library && (library.collectionType || library.CollectionType)).toLowerCase();
      return type !== 'artist' && type !== 'album' && type !== 'audio' && collectionType !== 'music' && collectionType !== 'books';
    });
    return visual.length ? visual : state.libraries;
  }

  function homeHubRowKind(hub) {
    var explicit = text(hub && hub.kind).toLowerCase();
    var value = explicit + ' ' + text(hub && (hub.identifier || hub.id)) + ' ' + text(hub && hub.title);
    value = value.toLowerCase();
    if (explicit === 'continue' || /continue|on[ -]?deck|next[ -]?up|resume/.test(value)) { return 'continue'; }
    if (explicit === 'recent' || /recent|latest|newly[ -]?added|최근|새로\s*추가/.test(value)) { return 'recent'; }
    return explicit || 'recommendation';
  }

  function localizedHomeHubTitle(hub, kind) {
    var title = text(hub && hub.title);
    var match;
    if (kind === 'continue') { return '계속 시청'; }
    if (kind !== 'recent') { return title || '추천 콘텐츠'; }
    match = /^recently added in (.+)$/i.exec(title);
    if (match) { return match[1] + '에 최근에 추가됨'; }
    match = /^(?:recently added|latest)\s+(.+)$/i.exec(title);
    if (match) { return match[1] + ' · 최근에 추가됨'; }
    return /^(?:recently added|latest)$/i.test(title) || !title ? '최근에 추가됨' : title;
  }

  function normalizeServerHomeRows(result, limit) {
    var hubs = resultArray(result, ['hubs', 'rows', 'items']);
    var continueItems = [];
    var continueIds = {};
    var rows = [];
    limit = limit || 14;
    hubs.forEach(function (hub) {
      if (homeHubRowKind(hub) !== 'continue') { return; }
      resultArray(hub, ['items', 'Items', 'children']).forEach(function (item) {
        var id = entityId(item) || entityTitle(item);
        if (!id || continueIds[id] || continueItems.length >= limit) { return; }
        continueIds[id] = true;
        continueItems.push(item);
      });
    });
    if (continueItems.length) {
      rows.push({ kind: 'continue', title: '계속 시청', items: continueItems });
    }
    hubs.forEach(function (hub) {
      var kind = homeHubRowKind(hub);
      var rowIds = {};
      var items;
      if (kind === 'continue') { return; }
      items = resultArray(hub, ['items', 'Items', 'children']).filter(function (item) {
        var id = entityId(item) || entityTitle(item);
        if (!id || rowIds[id] || (kind !== 'recent' && continueIds[id])) { return false; }
        rowIds[id] = true;
        return true;
      }).slice(0, limit);
      if (items.length) {
        rows.push({
          id: text(hub.id || hub.identifier),
          kind: kind,
          title: localizedHomeHubTitle(hub, kind),
          items: items
        });
      }
    });
    return rows;
  }

  function commitHomeRows(token, rows) {
    var spotlightItem;
    if (token !== state.homeLoadToken || !state.client) { return state.homeRows; }
    state.homeRows = rows || [];
    renderHomePreviewRows();
    spotlightItem = state.homeRows.length && state.homeRows[0].items.length ? state.homeRows[0].items[0] : null;
    if (spotlightItem) {
      var active = global.document.activeElement;
      if (!active || !active.getAttribute || !active.getAttribute('data-card-kind')) {
        renderHomeSpotlight(spotlightItem);
      }
    }
    return state.homeRows;
  }

  function composeFallbackHomeRows(loadedRows, libraries, limit) {
    var continueItems = [];
    var continueIds = {};
    var rows = [];
    limit = limit || 14;
    loadedRows.forEach(function (row) {
      var recentItems = [];
      row.items.forEach(function (item) {
        var progress = entityProgress(item);
        var id = entityId(item) || entityTitle(item);
        if (progress > 0 && progress < 0.98 && !continueIds[id]) {
          continueIds[id] = true;
          continueItems.push(item);
        }
        recentItems.push(item);
      });
      if (recentItems.length) {
        rows.push({
          library: row.library,
          kind: 'recent',
          title: entityTitle(row.library) + '에 최근에 추가됨',
          items: recentItems.slice(0, limit)
        });
      }
    });
    if (continueItems.length) {
      rows.unshift({
        library: libraries[0],
        kind: 'continue',
        title: '계속 시청',
        items: continueItems.slice(0, limit)
      });
    }
    return rows;
  }

  function mergeHomeRows(primaryRows, fallbackRows) {
    var continueRow = null;
    var recentRows = [];
    var otherRows = [];
    var recentKeys = {};

    function append(row, includeOther) {
      var kind;
      var key;
      if (!row || !row.items || !row.items.length) { return; }
      kind = homeHubRowKind(row);
      if (kind === 'continue') {
        if (!continueRow) { continueRow = row; }
        return;
      }
      if (kind === 'recent') {
        key = text(row.title).toLowerCase() || (row.library ? libraryKey(row.library) : '');
        if (!recentKeys[key]) {
          recentKeys[key] = true;
          recentRows.push(row);
        }
        return;
      }
      if (includeOther) { otherRows.push(row); }
    }

    (primaryRows || []).forEach(function (row) { append(row, true); });
    (fallbackRows || []).forEach(function (row) { append(row, false); });
    return (continueRow ? [continueRow] : []).concat(recentRows, otherRows);
  }

  function needsHomeRecentFallback(rows, libraries) {
    return Boolean(libraries && libraries.length) && !(rows || []).some(function (row) {
      return homeHubRowKind(row) === 'recent';
    });
  }

  function loadFallbackHomeRows(libraries, token, primaryRows) {
    return global.Promise.all(libraries.map(function (library) {
      return invokeClient(['listItems', 'getItems', 'browse'], [library, {
        limit: 14,
        recursive: true,
        recent: true,
        sortBy: 'DateCreated,SortName,ProductionYear',
        sortOrder: 'Descending,Descending,Descending'
      }]).then(function (result) {
        return {
          library: library,
          items: resultArray(result, ['items', 'Items', 'children']).slice(0, 14)
        };
      }).catch(function () {
        // A single unavailable library must not prevent the rest of Home from loading.
        return { library: library, items: [] };
      });
    })).then(function (loadedRows) {
      if (token !== state.homeLoadToken || !state.client) { return state.homeRows; }
      return commitHomeRows(token, mergeHomeRows(
        primaryRows,
        composeFallbackHomeRows(loadedRows, libraries, 14)
      ));
    });
  }

  function loadHomePreviewRows() {
    var token = state.homeLoadToken + 1;
    var libraries = preferredHomeLibraries();
    state.homeLoadToken = token;
    if (!state.client) {
      state.homeRows = [];
      renderHomePreviewRows();
      return global.Promise.resolve(state.homeRows);
    }
    return invokeClient(['listHomeHubs', 'getHomeHubs'], [{ limit: 14, libraries: libraries }], true).catch(function () {
      // Older or restricted servers fall back to recent library browsing.
      return null;
    }).then(function (result) {
      var rows;
      if (token !== state.homeLoadToken || !state.client) { return state.homeRows; }
      rows = normalizeServerHomeRows(result, 14);
      if (rows.length && !needsHomeRecentFallback(rows, libraries)) { return commitHomeRows(token, rows); }
      if (!libraries.length) { return commitHomeRows(token, rows); }
      return loadFallbackHomeRows(libraries, token, rows);
    });
  }

  function wireSideNavigationItems(items) {
    var entries = (items || []).filter(function (item) {
      return item && typeof item.setAttribute === 'function';
    });
    entries.forEach(function (item, index) {
      if (!item.id) {
        item.id = 'side-navigation-entry-' + index;
      }
    });
    entries.forEach(function (item, index) {
      var previous = entries[index > 0 ? index - 1 : index];
      var next = entries[index < entries.length - 1 ? index + 1 : index];
      item.setAttribute('data-nav-up', '#' + previous.id);
      item.setAttribute('data-nav-down', '#' + next.id);
      item.setAttribute('data-nav-left', '#' + item.id);
    });
    return entries;
  }

  function nextSideNavigationItem(items, current, code) {
    var entries = (items || []).filter(Boolean);
    var currentIndex = entries.indexOf(current);
    if (currentIndex < 0) { return null; }
    if (code === 37) { return current; }
    if (code === 38) { return entries[Math.max(0, currentIndex - 1)]; }
    if (code === 40) { return entries[Math.min(entries.length - 1, currentIndex + 1)]; }
    return null;
  }

  function sideNavigationFocusItems() {
    var libraryButtons = refs.sideLibraryItems ? all('.side-library-entry', refs.sideLibraryItems) : [];
    return wireSideNavigationItems(
      [refs.navRefresh, refs.navHome, refs.navSearch, refs.navLibraries].concat(libraryButtons, [refs.navSettings])
    );
  }

  function handleSideNavigationDirection(code) {
    var current = global.document.activeElement;
    var next;
    if (!refs.sideNavigation || !current || !refs.sideNavigation.contains(current)) {
      return false;
    }
    if (state.currentModal || !refs.playerView.classList.contains('hidden')) { return false; }
    if (code !== 37 && code !== 38 && code !== 40) { return false; }
    var direction = code === 37 ? 'left' : code === 38 ? 'up' : 'down';
    next = global.document.querySelector(current.getAttribute('data-nav-' + direction));
    if (!next) { return false; }
    if (state.remote && state.remote.focus(next, 'dpad-sidebar')) {
      return true;
    }
    if (typeof next.focus === 'function') {
      next.focus();
      return true;
    }
    return false;
  }

  function renderSideLibraries() {
    var fragment;
    if (!refs.sideLibraryItems) { return; }
    refs.sideLibraryItems.innerHTML = '';
    fragment = global.document.createDocumentFragment();
    state.libraries.forEach(function (library, index) {
      var button = global.document.createElement('button');
      var icon = global.document.createElement('span');
      var label = global.document.createElement('span');
      var type = entityType(library);
      button.type = 'button';
      button.id = 'side-library-entry-' + index;
      button.className = 'focusable side-navigation-item side-library-entry';
      button.tabIndex = 0;
      button.setAttribute('data-card-kind', 'nav-library');
      button.setAttribute('data-card-index', String(index));
      button.setAttribute('data-library-id', libraryKey(library));
      button.setAttribute('data-nav-right', '#item-grid .media-card');
      button.setAttribute('aria-label', entityTitle(library));
      icon.className = 'side-navigation-icon';
      if (type === 'artist' || type === 'album' || type === 'audio') {
        icon.innerHTML = '<svg class="ui-symbol" viewBox="0 0 24 24" aria-hidden="true"><path d="M9 18V5l10-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="16" cy="16" r="3"></circle></svg>';
      } else if (type === 'photo') {
        icon.innerHTML = '<svg class="ui-symbol" viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"></rect><circle cx="9" cy="9" r="2"></circle><path d="m4 17 5-5 4 4 2-2 5 5"></path></svg>';
      } else {
        icon.innerHTML = '<svg class="ui-symbol" viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"></rect><path d="m10 8 6 4-6 4z"></path></svg>';
      }
      label.className = 'side-navigation-label';
      label.textContent = entityTitle(library);
      button.appendChild(icon);
      button.appendChild(label);
      fragment.appendChild(button);
    });
    refs.sideLibraryItems.appendChild(fragment);
    sideNavigationFocusItems();
    updateSideNavigation(state.currentView);
  }

  function sortItemsForView(items) {
    return items.slice().sort(function (left, right) {
      var order = entityTitle(left).localeCompare(entityTitle(right));
      return state.sortAscending ? order : -order;
    });
  }

  function renderItems(title, breadcrumb, focusDescriptor) {
    refs.itemsTitle.textContent = title || '항목';
    refs.breadcrumb.textContent = breadcrumb || '라이브러리';
    renderCardGrid(refs.itemGrid, state.items, 'item');
    refs.emptyState.classList.toggle('hidden', state.items.length !== 0);
    // Remote reads actual rendered rows, including flex layouts on webOS 3.x.
    renderItemsBackdrop(state.items[0] || null);
    showView('items-view', resolveFocusDescriptor(focusDescriptor, refs.itemGrid));
  }

  function syncLibraryModeTabs(mode) {
    state.libraryMode = mode || 'browse';
    all('[data-library-mode]').forEach(function (tab) {
      var selected = tab.getAttribute('data-library-mode') === state.libraryMode;
      tab.classList.toggle('selected', selected);
      if (selected) { tab.setAttribute('aria-current', 'page'); } else { tab.removeAttribute('aria-current'); }
    });
  }

  function libraryModeTitle(mode) {
    if (mode === 'recommendations') { return '추천'; }
    if (mode === 'collections') { return '컬렉션'; }
    if (mode === 'playlists') { return '재생목록'; }
    return '탐색';
  }

  function loadLibraryMode(mode) {
    var sourceView = state.currentView;
    var rootRoute = state.routeStack.length ? state.routeStack[0] : null;
    var parent = rootRoute && rootRoute.container;
    if (!parent && sourceView === 'items-view' && state.activeLibraryId) {
      state.libraries.some(function (library) {
        if (libraryKey(library) === state.activeLibraryId) { parent = library; return true; }
        return false;
      });
    }
    syncLibraryModeTabs(mode);
    if (mode === 'browse') {
      if (parent) {
        state.routeStack = [];
        browse(parent, entityTitle(parent)).catch(function () {});
      } else {
        renderLibraries();
        showView('libraries-view');
      }
      return;
    }
    var token = state.browseLoadToken + 1;
    state.browseLoadToken = token;
    setNetworkStatus('불러오는 중', false);
    invokeClient(['listLibraryMode'], [parent, mode, { limit: 180 }]).then(function (result) {
      if (token !== state.browseLoadToken) { return; }
      var items = sortItemsForView(resultArray(result, ['items', 'Items', 'children']));
      state.items = items;
      state.routeStack = [{
        container: parent,
        title: libraryModeTitle(mode),
        breadcrumb: parent ? entityTitle(parent) : '라이브러리',
        items: items,
        mode: mode,
        returnFocus: captureFocusDescriptor(global.document.activeElement)
      }];
      renderItems(libraryModeTitle(mode), parent ? entityTitle(parent) : '라이브러리');
      syncLibraryModeTabs(mode);
      setNetworkStatus('연결됨', false);
    }).catch(function (error) {
      if (token !== state.browseLoadToken) { return; }
      setNetworkStatus('불러오기 실패', true);
      showToast(errorMessage(error), 4500);
    });
  }

  function renderSearchItems() {
    renderCardGrid(refs.searchGrid, state.searchItems, 'search-item');
    refs.searchEmpty.classList.toggle('hidden', state.searchItems.length > 0);
    if (state.searchItems.length) {
      refs.searchEmpty.textContent = '';
      all('.media-card', refs.searchGrid).forEach(function (card, index) {
        if (index % (global.innerWidth <= 900 ? 3 : global.innerWidth <= 1400 ? 8 : 9) === 0) {
          card.setAttribute('data-nav-left', '#nav-search');
        }
      });
    }
  }

  function submitSearch() {
    var query = text(refs.searchInput.value).trim();
    if (!query) {
      state.searchItems = [];
      refs.searchEmpty.textContent = '검색어를 입력하세요.';
      renderSearchItems();
      focusInitial(refs.searchInput);
      return;
    }
    var token = state.browseLoadToken + 1;
    state.browseLoadToken = token;
    refs.searchEmpty.classList.remove('hidden');
    refs.searchEmpty.textContent = '검색 중…';
    setNetworkStatus('검색 중', false);
    invokeClient(['searchItems', 'search'], [query, { limit: 120 }]).then(function (result) {
      if (token !== state.browseLoadToken) { return; }
      state.searchItems = resultArray(result, ['items', 'Items']).slice(0, 120);
      refs.searchEmpty.textContent = state.searchItems.length ? '' : '검색 결과가 없습니다.';
      renderSearchItems();
      setNetworkStatus('연결됨', false);
      global.setTimeout(function () { focusInitial(state.searchItems.length ? refs.searchGrid.querySelector('.media-card') : refs.searchInput); }, 0);
    }).catch(function (error) {
      if (token !== state.browseLoadToken) { return; }
      state.searchItems = [];
      refs.searchEmpty.textContent = errorMessage(error);
      refs.searchEmpty.classList.remove('hidden');
      setNetworkStatus('검색 실패', true);
    });
  }

  function connectionAttemptIsCurrent(attemptToken, client, connection) {
    return attemptToken === state.connectionAttemptToken && state.client === client && state.connection === connection;
  }

  function testConnection(client, connection, attemptToken) {
    return invokeClientOn(client, ['testConnection', 'connect', 'test'], [{ persist: false }]).then(function (result) {
      result = result || {};
      if (!connectionAttemptIsCurrent(attemptToken, client, connection)) {
        return null;
      }
      if (result.userId && !connection.userId) {
        connection.userId = result.userId;
      }
      if (result.serverName) {
        connection.serverName = result.serverName;
      }
      return result;
    });
  }

  function persistConnection(connection) {
    var api = apiNamespace();
    storageSet(STORAGE.connection, connection);
    if (api && typeof api.setActiveConnection === 'function') {
      api.setActiveConnection(connection);
    } else if (api && typeof api.saveConnection === 'function') {
      api.saveConnection(connection);
    }
  }

  function savedConnection() {
    var saved = storageGet(STORAGE.connection, null);
    var api = apiNamespace();
    if ((!saved || !(saved.token || saved.accessToken || saved.apiKey)) && api && typeof api.getActiveConnection === 'function') {
      saved = api.getActiveConnection();
    }
    return saved;
  }

  function loadLibraries(attemptToken, expectedClient, expectedConnection) {
    state.playLoadToken += 1;
    var clientAtRequest = expectedClient || state.client;
    var connectionAtRequest = expectedConnection || state.connection;
    var token = state.browseLoadToken + 1;
    state.browseLoadToken = token;
    setNetworkStatus('불러오는 중', false);
    return invokeClient(['listLibraries', 'getLibraries', 'libraries'], []).then(function (result) {
      if (
        token !== state.browseLoadToken ||
        state.client !== clientAtRequest ||
        state.connection !== connectionAtRequest ||
        (typeof attemptToken === 'number' && !connectionAttemptIsCurrent(attemptToken, clientAtRequest, connectionAtRequest))
      ) { return null; }
      state.libraries = applyLibraryOrder(resultArray(result, ['libraries', 'items', 'Items']));
      persistConnection(connectionAtRequest);
      renderLibraries();
      showView('home-view');
      setNetworkStatus('연결됨', false);
      loadHomePreviewRows();
      return state.libraries;
    }).catch(function (error) {
      if (
        token !== state.browseLoadToken ||
        state.client !== clientAtRequest ||
        state.connection !== connectionAtRequest ||
        (typeof attemptToken === 'number' && !connectionAttemptIsCurrent(attemptToken, clientAtRequest, connectionAtRequest))
      ) { return null; }
      setNetworkStatus('연결 오류', true);
      showView('setup-view');
      refs.connectionError.textContent = errorMessage(error);
      throw error;
    });
  }

  function connectionConfigWithoutCredentials(config) {
    var kind = config.kind || config.type || config.serverType || 'plex';
    var serverUrl = normalizeServerUrl(config.url || config.baseUrl || config.serverUrl);
    var token = config.token || config.accessToken || config.apiKey || '';
    return {
      kind: kind,
      type: kind,
      serverType: kind,
      url: serverUrl,
      baseUrl: serverUrl,
      serverUrl: serverUrl,
      token: token,
      accessToken: token,
      apiKey: token,
      userId: config.userId || '',
      clientId: config.clientId || config.clientIdentifier || storageGet('shyniflex.webos.device-id', ''),
      clientIdentifier: config.clientIdentifier || config.clientId || storageGet('shyniflex.webos.device-id', ''),
      serverName: config.serverName || config.name || '',
      serverId: config.serverId || '',
      authSource: config.authSource || 'token'
    };
  }

  function connect(config, skipTest, attemptToken) {
    var connection;
    var client;
    if (typeof attemptToken !== 'number') {
      attemptToken = state.connectionAttemptToken + 1;
      state.connectionAttemptToken = attemptToken;
    }
    if (attemptToken !== state.connectionAttemptToken) {
      return global.Promise.resolve(null);
    }
    connection = connectionConfigWithoutCredentials(config);
    client = createClient(connection);
    state.connection = connection;
    state.client = client;
    refs.connectionError.textContent = '';
    setNetworkStatus('연결 확인', false);
    return (skipTest ? global.Promise.resolve({}) : testConnection(client, connection, attemptToken)).then(function (result) {
      if (result === null || !connectionAttemptIsCurrent(attemptToken, client, connection)) {
        return null;
      }
      return loadLibraries(attemptToken, client, connection);
    });
  }

  function browse(container, title, replaceRoute, returnFocusOverride) {
    var originFocus = returnFocusOverride || captureFocusDescriptor(global.document.activeElement);
    var existingRoute = replaceRoute && state.routeStack.length ? state.routeStack[state.routeStack.length - 1] : null;
    var clientAtRequest = state.client;
    var token = state.browseLoadToken + 1;
    state.browseLoadToken = token;
    setNetworkStatus('불러오는 중', false);
    return invokeClient(['listItems', 'getItems', 'browse'], [container]).then(function (result) {
      if (token !== state.browseLoadToken || state.client !== clientAtRequest) { return []; }
      var items = sortItemsForView(resultArray(result, ['items', 'Items', 'children']));
      var route = {
        container: container,
        title: title || entityTitle(container),
        breadcrumb: existingRoute ? existingRoute.breadcrumb : state.routeStack.length ? state.routeStack[state.routeStack.length - 1].title : '라이브러리',
        items: items,
        returnFocus: existingRoute ? existingRoute.returnFocus : originFocus
      };
      if (replaceRoute && state.routeStack.length) {
        state.routeStack[state.routeStack.length - 1] = route;
      } else {
        state.routeStack.push(route);
      }
      state.items = items;
      renderItems(route.title, route.breadcrumb);
      setNetworkStatus('연결됨', false);
      return items;
    }).catch(function (error) {
      if (token !== state.browseLoadToken || state.client !== clientAtRequest) { return []; }
      setNetworkStatus('불러오기 실패', true);
      showToast(errorMessage(error), 4500);
      throw error;
    });
  }

  function openLibrary(library) {
    state.activeLibraryId = libraryKey(library);
    state.libraryReturnView = state.currentView === 'libraries-view' ? 'libraries-view' : 'home-view';
    state.sortAscending = true;
    if (refs.itemsSort) { refs.itemsSort.textContent = '제목순'; }
    state.routeStack = [];
    state.items = [];
    syncLibraryModeTabs('browse');
    return browse(library, entityTitle(library));
  }

  function openDetail(item) {
    state.browseLoadToken += 1;
    state.playLoadToken += 1;
    if (state.client) { setNetworkStatus('연결됨', false); }
    if (state.currentView === 'detail-view' && state.detailItem) {
      state.detailStack.push({ item: state.detailItem, focus: captureFocusDescriptor(global.document.activeElement) });
    } else {
      state.detailReturnView = state.currentView;
      state.detailReturnFocus = captureFocusDescriptor(global.document.activeElement);
      state.detailStack = [];
    }
    state.detailItem = item;
    renderDetail(item);
    showView('detail-view', refs.detailPrimaryAction);
    loadDetailChildren(item);
  }

  function closeDetail() {
    state.detailLoadToken += 1;
    state.playLoadToken += 1;
    state.detailLoading = false;
    if (state.detailStack.length) {
      var parent = state.detailStack.pop();
      state.detailItem = parent.item;
      state.detailPendingFocus = parent.focus;
      renderDetail(parent.item);
      showView('detail-view', refs.detailPrimaryAction);
      loadDetailChildren(parent.item);
      return true;
    }
    var returnView = state.detailReturnView || 'items-view';
    var returnFocus = resolveFocusDescriptor(state.detailReturnFocus, byId(returnView));
    state.detailItem = null;
    state.detailChildren = [];
    state.detailStack = [];
    showView(returnView, returnFocus);
    return true;
  }

  function navigateBack() {
    state.browseLoadToken += 1;
    state.playLoadToken += 1;
    if (state.client) { setNetworkStatus('연결됨', false); }
    if (state.routeStack.length <= 1) {
      var rootRoute = state.routeStack.length ? state.routeStack[0] : null;
      var rootScope = state.libraryReturnView === 'libraries-view' ? refs.libraryBrowseGrid : refs.homePreviewRows;
      var rootFocus = rootRoute ? resolveFocusDescriptor(rootRoute.returnFocus, rootScope) : null;
      state.routeStack = [];
      state.items = [];
      showView(state.libraryReturnView || 'home-view', rootFocus);
      return true;
    }
    var leavingRoute = state.routeStack.pop();
    var route = state.routeStack[state.routeStack.length - 1];
    state.items = route.items || [];
    renderItems(route.title, route.breadcrumb, leavingRoute.returnFocus);
    return true;
  }

  function supportedSubtitle(track) {
    if (!track || track.textSubtitle === false) { return false; }
    var format = text(track.format || track.codec).toLowerCase();
    var url = text(track.url || track.deliveryUrl || track.key);
    if (!url || /pgs|dvdsub|dvbsub|vobsub/.test(format)) { return false; }
    return ['vtt', 'webvtt', 'srt', 'subrip'].indexOf(format) >= 0 || /\.(vtt|srt)(?:$|[?#])/i.test(url);
  }

  function normalizePlayback(result, item) {
    result = result || {};
    var sources = result.sources || {};
    var sourceList = Array.isArray(sources) ? sources : [];
    var directSource = null;
    var hlsSource = null;
    sourceList.forEach(function (source) {
      if (!directSource && source.kind === 'direct') { directSource = source; }
      if (!hlsSource && source.kind === 'hls') { hlsSource = source; }
    });
    var subtitleList = Array.isArray(result.subtitles) ? result.subtitles : [];
    var preferredSubtitle = null;
    subtitleList.forEach(function (subtitle) {
      if (!supportedSubtitle(subtitle)) { return; }
      if (!preferredSubtitle || subtitle.selected || subtitle.default || subtitle.forced) {
        preferredSubtitle = subtitle;
      }
    });
    var subtitleUrl = firstValue(result, ['subtitleUrl', 'defaultSubtitleUrl'], '');
    if (subtitleUrl && !supportedSubtitle({ url: subtitleUrl })) { subtitleUrl = ''; }
    if (!subtitleUrl && preferredSubtitle) {
      subtitleUrl = firstValue(preferredSubtitle, ['url', 'deliveryUrl', 'key'], '');
      if (state.client && typeof state.client.subtitleUrl === 'function') {
        try { subtitleUrl = state.client.subtitleUrl(preferredSubtitle) || subtitleUrl; } catch (ignore) { /* Keep direct URL. */ }
      }
    }
    var startTime = number(firstValue(result, ['startTime', 'resumeSeconds', 'positionSeconds'], NaN), NaN);
    if (!isFinite(startTime)) {
      startTime = number(firstValue(result, ['positionMs', 'resumePositionMs', 'viewOffset'], 0), 0) / 1000;
    }
    var hlsUrl = firstValue(result, ['hlsUrl', 'fallbackUrl', 'transcodeUrl'], hlsSource ? hlsSource.url : firstValue(sources, ['hlsUrl', 'fallbackUrl'], ''));
    var directUrl = firstValue(result, ['directUrl', 'url', 'src'], directSource ? directSource.url : firstValue(sources, ['directUrl', 'url'], ''));
    if (!directSource && (directUrl === hlsUrl || /\.m3u8(?:$|[?#])/i.test(directUrl))) { hlsUrl = hlsUrl || directUrl; directUrl = ''; }
    return {
      directUrl: directUrl,
      hlsUrl: hlsUrl,
      type: text(firstValue(result, ['mimeType', 'type'], directSource ? directSource.mimeType : '')),
      startTime: Math.max(0, startTime),
      positionOffset: Math.max(0, number(result.streamStartPositionMs, 0) / 1000),
      durationHint: Math.max(0, number(result.durationMs, 0) / 1000),
      subtitleUrl: subtitleUrl,
      subtitles: subtitleList,
      selectedSubtitle: preferredSubtitle,
      session: result.session || result.playSession || result,
      item: item,
      title: entityTitle(item)
    };
  }

  function formatTime(seconds) {
    seconds = Math.max(0, Math.floor(number(seconds, 0)));
    var hours = Math.floor(seconds / 3600);
    var minutes = Math.floor((seconds % 3600) / 60);
    var remaining = seconds % 60;
    function two(value) { return value < 10 ? '0' + value : String(value); }
    return hours > 0 ? hours + ':' + two(minutes) + ':' + two(remaining) : two(minutes) + ':' + two(remaining);
  }

  function updateProgressUi(progress) {
    var offset = state.playback ? number(state.playback.positionOffset, 0) : 0;
    var position = Math.max(0, number(progress.position, 0) + offset);
    var duration = state.playback && state.playback.durationHint > 0
      ? state.playback.durationHint
      : Math.max(0, number(progress.duration, 0) + offset);
    refs.currentTime.textContent = formatTime(position);
    refs.duration.textContent = formatTime(duration);
    refs.timeline.value = String(Math.round(clamp(duration > 0 ? position / duration : progress.percent, 0, 1) * 1000));
    if (state.playerState === 'buffering' && !progress.paused && number(progress.position, 0) > 0 && refs.video.readyState >= 3) {
      state.playerState = 'playing';
      refs.playerView.setAttribute('data-playback-state', 'playing');
      setNetworkStatus('재생 중', false);
      setPlayerError('');
      showOsd(false);
    }
    if (state.playback && shouldReportPlaybackProgress(state.playbackStartReported, state.playerState)) {
      reportPlayback(progress.paused ? 'paused' : 'playing', progress, false);
    }
  }

  function reportPlayback(playState, progress, force) {
    if (!state.playback || !state.client) {
      return global.Promise.resolve(null);
    }
    var now = Date.now();
    if (!force && now - state.lastProgressReport < 10000) {
      return global.Promise.resolve(null);
    }
    state.lastProgressReport = now;
    var playbackAtCall = state.playback;
    var isInitialPlayingReport = playState === 'playing' && !state.playbackStartReported;
    progress = progress || state.player.getProgress();
    var positionOffset = state.playback ? number(state.playback.positionOffset, 0) : 0;
    var durationHint = state.playback ? number(state.playback.durationHint, 0) : 0;
    var overrides = {
      positionMs: Math.round((number(progress.position, 0) + positionOffset) * 1000),
      durationMs: Math.round((durationHint > 0 ? durationHint : number(progress.duration, 0) + positionOffset) * 1000),
      isPaused: playState === 'paused',
      playMethod: state.playback.session && state.playback.session.playMethod,
      eventName: progress.eventName || (playState === 'paused' ? 'Pause' : 'TimeUpdate')
    };
    var methods = playState === 'stopped'
      ? ['reportStopped', 'stopPlayback', 'reportProgress']
      : isInitialPlayingReport
        ? ['reportPlaying', 'reportProgress', 'updateProgress']
        : ['reportProgress', 'updateProgress', 'reportPlayback'];
    if (isInitialPlayingReport) {
      state.playbackStartReported = true;
    }
    return invokeClient(methods, [state.playback.session, overrides], true).catch(function () {
      if (isInitialPlayingReport && state.playback === playbackAtCall) {
        state.playbackStartReported = false;
      }
      // Playback must not stop because a progress heartbeat failed.
    });
  }

  function playbackQualityLabel(quality) {
    if (quality === 'compatible') { return '호환 재생 (최대 1080p)'; }
    if (quality === 'fullhd') { return 'Full HD (최대 1080p)'; }
    if (quality === 'hd') { return 'HD (최대 720p)'; }
    return '자동 (TV 권장)';
  }

  function updatePlaybackSettingsUi() {
    var settings = state.playbackSettings;
    var rewindLabel = settings.seekSeconds + '초 뒤로';
    var forwardLabel = settings.seekSeconds + '초 앞으로';
    var rewindValue;
    var forwardValue;
    if (refs.playbackSettingsSummary) {
      refs.playbackSettingsSummary.textContent = playbackQualityLabel(settings.quality) +
        ' · ' + settings.seekSeconds + '초 이동 · 이어보기 ' + (settings.resume ? '켬' : '끔') +
        ' · ' + (settings.videoFit === 'cover' ? '화면 채우기' : '영상 전체 보기');
    }
    if (refs.playerRewind) {
      refs.playerRewind.setAttribute('aria-label', rewindLabel);
      rewindValue = refs.playerRewind.querySelector('small');
      if (rewindValue) { rewindValue.textContent = String(settings.seekSeconds); }
    }
    if (refs.playerForward) {
      refs.playerForward.setAttribute('aria-label', forwardLabel);
      forwardValue = refs.playerForward.querySelector('small');
      if (forwardValue) { forwardValue.textContent = String(settings.seekSeconds); }
    }
  }

  function syncPlaybackSettingsForm() {
    if (refs.playbackQuality) { refs.playbackQuality.value = state.playbackSettings.quality; }
    if (refs.playbackSeekSeconds) { refs.playbackSeekSeconds.value = String(state.playbackSettings.seekSeconds); }
    if (refs.playbackResume) { refs.playbackResume.value = state.playbackSettings.resume ? 'on' : 'off'; }
    if (refs.playbackRewindOnResume) { refs.playbackRewindOnResume.value = String(state.playbackSettings.rewindOnResume); }
    if (refs.playbackVideoFit) { refs.playbackVideoFit.value = state.playbackSettings.videoFit; }
  }

  function applyPlaybackSettings(value, persist) {
    state.playbackSettings = normalizePlaybackSettings(value);
    if (state.player) { state.player.seekStep = state.playbackSettings.seekSeconds; }
    applyVideoFit(state.playbackSettings.videoFit, Boolean(persist));
    syncPlaybackSettingsForm();
    updatePlaybackSettingsUi();
    if (persist) { storageSet(STORAGE.playback, state.playbackSettings); }
  }

  function restorePlaybackSettings() {
    var saved = storageGet(STORAGE.playback, {});
    if (!saved || typeof saved !== 'object') { saved = {}; }
    if (!saved.videoFit) { saved.videoFit = storageGet(STORAGE.videoFit, PLAYBACK_DEFAULTS.videoFit); }
    applyPlaybackSettings(saved, false);
  }

  function savePlaybackSettings() {
    applyPlaybackSettings({
      quality: refs.playbackQuality.value,
      seekSeconds: number(refs.playbackSeekSeconds.value, PLAYBACK_DEFAULTS.seekSeconds),
      resume: refs.playbackResume.value !== 'off',
      rewindOnResume: number(refs.playbackRewindOnResume.value, PLAYBACK_DEFAULTS.rewindOnResume),
      videoFit: refs.playbackVideoFit.value
    }, true);
    showView('settings-view', refs.playbackSettingsButton);
    showToast('재생 환경설정을 저장했습니다.');
  }

  function applyVideoFit(mode, persist) {
    mode = mode === 'cover' ? 'cover' : 'contain';
    state.videoFitMode = mode;
    if (state.playbackSettings) { state.playbackSettings.videoFit = mode; }
    refs.video.style.objectFit = mode;
    if (refs.playerFit) {
      refs.playerFit.textContent = mode === 'cover' ? '화면 맞춤' : '화면 채우기';
      refs.playerFit.setAttribute('aria-label', mode === 'cover' ? '영상 전체가 보이도록 맞추기' : '화면을 채우도록 확대하기');
      refs.playerFit.setAttribute('aria-pressed', mode === 'cover' ? 'true' : 'false');
    }
    if (persist) {
      storageSet(STORAGE.videoFit, mode);
      if (state.playbackSettings) { storageSet(STORAGE.playback, state.playbackSettings); }
    }
    if (refs.playbackVideoFit) { refs.playbackVideoFit.value = mode; }
    updatePlaybackSettingsUi();
  }

  function cycleVideoFit() {
    var next = state.videoFitMode === 'cover' ? 'contain' : 'cover';
    applyVideoFit(next, true);
    showToast(next === 'cover' ? '화면을 채웠습니다.' : '영상 전체가 보이도록 맞췄습니다.');
  }

  function showOsd(keepVisible) {
    refs.playerOsd.classList.add('visible');
    if (state.osdTimer) {
      global.clearTimeout(state.osdTimer);
    }
    if (!keepVisible) {
      state.osdTimer = global.setTimeout(function () {
        if (!state.currentModal && !state.subtitles.adjustmentMode) {
          refs.playerOsd.classList.remove('visible');
        }
      }, 4500);
    }
  }

  function setPlayerError(message) {
    refs.playerMessage.textContent = message || '';
    refs.playerMessage.classList.toggle('hidden', !message);
    if (message) { showOsd(true); }
  }

  function playItem(item) {
    if (state.playerState === 'resolving') { return global.Promise.resolve(null); }
    var fromBeginning = arguments[1] === true;
    var forceCompatibility = arguments[2] === true;
    var preparedSession = null;
    var clientAtRequest = state.client;
    var token = state.playLoadToken + 1;
    state.playLoadToken = token;
    if (refs.playerView.classList.contains('hidden')) {
      state.playerReturnFocus = global.document.activeElement;
    }
    setNetworkStatus('', false);
    state.playerState = 'resolving';
    state.remote.stopRepeating();
    setPlayerError('');
    refs.playerTitle.textContent = entityTitle(item);
    refs.playbackMode.textContent = '';
    refs.playerView.classList.remove('hidden');
    refs.appHeader.classList.add('hidden');
    refs.content.classList.add('hidden');
    releaseBrowseArtwork();
    focusInitial(refs.playerClose);
    var options = playbackOptionsForItem(item, forceCompatibility);
    if (fromBeginning) { options.startTimeSeconds = 0; }
    return invokeClient(['resolvePlayback', 'getPlayback', 'playback'], [item, options]).then(function (result) {
      if (token !== state.playLoadToken || state.client !== clientAtRequest) {
        if (clientAtRequest.stopTranscode) { clientAtRequest.stopTranscode(result); }
        return null;
      }
      state.pendingPlayback = result;
      preparedSession = result;
      if (options.compatibility && clientAtRequest.preparePlayback) {
        return clientAtRequest.preparePlayback(result);
      }
      return result;
    }).then(function (result) {
      if (!result || token !== state.playLoadToken || state.client !== clientAtRequest) {
        if (preparedSession && clientAtRequest.stopTranscode) { clientAtRequest.stopTranscode(preparedSession); }
        return null;
      }
      state.pendingPlayback = null;
      var playback = normalizePlayback(result, item);
      if (!playback.directUrl && !playback.hlsUrl) {
        throw new Error('이 TV에서 재생할 주소를 서버가 제공하지 않았습니다.');
      }
      state.playback = playback;
      state.lastProgressReport = 0;
      state.playbackStartReported = false;
      state.playerState = 'loading';
      refs.playerTitle.textContent = playback.title;
      refs.playbackMode.textContent = result.deliveryMode === 'hls-mpegts' ? 'Shyni TV 호환 HLS' :
        result.playMethod === 'Transcode' ? 'Shyni 호환 변환 · 1080p' :
        (result.playMethod === 'DirectStream' ? '호환 스트리밍 · 영상 원본 유지' : '직접 재생');
      refs.playerView.classList.remove('hidden');
      refs.appHeader.classList.add('hidden');
      refs.content.classList.add('hidden');
      releaseBrowseArtwork();
      state.subtitles.clear();
      state.subtitles.setPlaybackOffset(playback.positionOffset);
      renderSubtitleTracks(playback);
      if (playback.subtitleUrl && state.subtitles.enabled) {
        state.subtitles.loadFromUrl(playback.subtitleUrl).then(function (loaded) {
          if (token !== state.playLoadToken || state.playback !== playback) { return; }
          if (!loaded) { showToast('기본 자막을 불러오지 못했습니다.'); }
        });
      }
      state.player.load({
        directUrl: playback.directUrl,
        hlsUrl: playback.hlsUrl,
        type: playback.type,
        startTime: fromBeginning ? 0 : playbackStartTimeForSettings(playback.startTime, state.playbackSettings),
        autoplay: true
      });
      showOsd(false);
      global.setTimeout(function () { focusInitial(refs.playerToggle); }, 0);
      setNetworkStatus('', false);
    }).catch(function (error) {
      if (token !== state.playLoadToken || state.client !== clientAtRequest) {
        if (preparedSession && clientAtRequest.stopTranscode) { clientAtRequest.stopTranscode(preparedSession); }
        return null;
      }
      if (state.pendingPlayback && clientAtRequest.stopTranscode) { clientAtRequest.stopTranscode(state.pendingPlayback); }
      state.pendingPlayback = null;
      setNetworkStatus('재생 실패', true);
      state.playerState = 'error';
      setPlayerError(errorMessage(error));
      refs.playerView.classList.remove('hidden');
      refs.appHeader.classList.add('hidden');
      refs.content.classList.add('hidden');
      showOsd(true);
      global.setTimeout(function () { focusInitial(refs.playerClose); }, 0);
      throw error;
    });
  }

  function closePlayer(completed) {
    state.playLoadToken += 1;
    if (state.client && state.client.stopTranscode) {
      state.client.stopTranscode(state.pendingPlayback || (state.playback && state.playback.session));
    }
    state.pendingPlayback = null;
    var returnFocus = state.playerReturnFocus;
    var stopReport = null;
    var completedSession = state.playback && state.playback.session;
    var completedItem = state.playback && state.playback.item;
    if (completed && completedItem) { markEntityPlayed(completedItem); }
    if (state.playback) {
      stopReport = reportPlayback('stopped', state.player.getProgress(), true);
    }
    state.playback = null;
    state.playbackStartReported = false;
    state.playerState = 'idle';
    if (typeof state.player.unload === 'function') {
      state.player.unload();
    } else {
      state.player.pause();
      refs.video.removeAttribute('src');
      try { refs.video.load(); } catch (ignore) { /* Older engines may reject load after teardown. */ }
    }
    state.subtitles.clear();
    state.subtitles.setAdjustmentMode(false);
    refs.playerView.classList.add('hidden');
    refs.appHeader.classList.remove('hidden');
    refs.content.classList.remove('hidden');
    scheduleVisibleArtwork();
    if (state.currentView === 'detail-view') { setBackdrop(refs.detailBackdrop, entityBackdrop(state.detailItem)); }
    refs.playerOsd.classList.add('visible');
    state.playerReturnFocus = null;
    setNetworkStatus('연결됨', false);
    global.setTimeout(function () { focusInitial(returnFocus); }, 0);
    if (stopReport && typeof stopReport.then === 'function') {
      stopReport.then(function () {
        if (completed && completedSession && state.client) {
          return invokeClient(['reportCompleted', 'markPlayed'], [completedSession], true);
        }
        return null;
      }).then(function () {
        return loadHomePreviewRows();
      }).catch(function () {
        // A temporary sync failure must not keep the player open.
      });
    }
    return true;
  }

  function openModal(id) {
    state.modalReturnFocus = global.document.activeElement;
    all('.modal').forEach(function (modal) {
      modal.classList.toggle('hidden', modal.id !== id);
    });
    refs.modalBackdrop.classList.remove('hidden');
    state.currentModal = id;
    if (id === 'subtitle-modal') {
      syncSubtitlePreview();
      var controls = all('.focusable', byId(id));
      controls.forEach(function (control, index) { if (!control.id) { control.id = 'subtitle-control-' + index; } });
      controls.forEach(function (control, index) {
        control.setAttribute('data-nav-up', '#' + controls[Math.max(0, index - 1)].id);
        control.setAttribute('data-nav-down', '#' + controls[Math.min(controls.length - 1, index + 1)].id);
      });
    }
    showOsd(true);
    global.setTimeout(focusInitial, 0);
  }

  function closeModal() {
    var returnFocus = state.modalReturnFocus;
    refs.modalBackdrop.classList.add('hidden');
    all('.modal').forEach(function (modal) { modal.classList.add('hidden'); });
    state.currentModal = null;
    state.modalReturnFocus = null;
    if (!refs.playerView.classList.contains('hidden')) {
      showOsd(false);
    }
    global.setTimeout(function () { focusInitial(returnFocus); }, 0);
    return true;
  }

  function renderReorderList() {
    state.pendingLibraryOrder = state.libraries.slice();
    refs.reorderList.innerHTML = '';
    state.pendingLibraryOrder.forEach(function (library, index) {
      var row = global.document.createElement('div');
      var label = global.document.createElement('span');
      var up = global.document.createElement('button');
      var down = global.document.createElement('button');
      row.className = 'reorder-row';
      label.textContent = entityTitle(library);
      up.type = 'button';
      up.className = 'focusable';
      up.tabIndex = 0;
      up.textContent = '↑';
      up.setAttribute('data-reorder-index', String(index));
      up.setAttribute('data-reorder-direction', '-1');
      down.type = 'button';
      down.className = 'focusable';
      down.tabIndex = 0;
      down.textContent = '↓';
      down.setAttribute('data-reorder-index', String(index));
      down.setAttribute('data-reorder-direction', '1');
      row.appendChild(label);
      row.appendChild(up);
      row.appendChild(down);
      refs.reorderList.appendChild(row);
    });
  }

  function moveLibrary(index, direction) {
    var target = index + direction;
    if (index < 0 || target < 0 || index >= state.pendingLibraryOrder.length || target >= state.pendingLibraryOrder.length) {
      return;
    }
    var moving = state.pendingLibraryOrder.splice(index, 1)[0];
    state.pendingLibraryOrder.splice(target, 0, moving);
    refs.reorderList.innerHTML = '';
    state.pendingLibraryOrder.forEach(function (library, nextIndex) {
      var row = global.document.createElement('div');
      row.className = 'reorder-row';
      var label = global.document.createElement('span');
      label.textContent = entityTitle(library);
      row.appendChild(label);
      [-1, 1].forEach(function (nextDirection) {
        var button = global.document.createElement('button');
        button.type = 'button';
        button.className = 'focusable';
        button.tabIndex = 0;
        button.textContent = nextDirection < 0 ? '↑' : '↓';
        button.setAttribute('data-reorder-index', String(nextIndex));
        button.setAttribute('data-reorder-direction', String(nextDirection));
        row.appendChild(button);
      });
      refs.reorderList.appendChild(row);
    });
    var nextFocus = refs.reorderList.querySelector('[data-reorder-index="' + target + '"][data-reorder-direction="' + direction + '"]');
    if (nextFocus) { nextFocus.focus(); }
  }

  function releaseBrowseArtwork() {
    [state.artworkTimer, state.cardVisualTimer, state.backdropTimer].forEach(function (timer) { if (timer) { global.clearTimeout(timer); } });
    state.artworkTimer = state.cardVisualTimer = state.backdropTimer = null;
    all('.artwork[data-art-loaded-url]').forEach(function (art) {
      art.setAttribute('data-art-url', art.getAttribute('data-art-loaded-url'));
      art.removeAttribute('data-art-loaded-url');
      art.style.backgroundImage = '';
      if (art.getAttribute('data-art-blob') && global.URL && global.URL.revokeObjectURL) { global.URL.revokeObjectURL(art.getAttribute('data-art-blob')); }
      art.removeAttribute('data-art-blob');
    });
    [refs.homeBackdrop, refs.itemsBackdrop, refs.detailBackdrop].forEach(function (element) { if (element) { setBackdrop(element, ''); } });
  }

  function renderSubtitleTracks(playback) {
    refs.subtitleTrack.innerHTML = '';
    var off = global.document.createElement('option');
    off.value = 'off'; off.textContent = '자막 끄기'; refs.subtitleTrack.appendChild(off);
    (playback.subtitles || []).forEach(function (track, index) {
      var option = global.document.createElement('option');
      option.value = String(index); option.disabled = !supportedSubtitle(track);
      option.textContent = text(track.title || track.language, '자막 ' + (index + 1)) + (option.disabled ? ' (SRT/VTT 필요)' : '');
      refs.subtitleTrack.appendChild(option);
    });
    var selected = (playback.subtitles || []).indexOf(playback.selectedSubtitle);
    refs.subtitleTrack.value = state.subtitles.enabled && selected >= 0 ? String(selected) : 'off';
    refs.subtitleTrackStatus.textContent = 'SRT/VTT 텍스트 자막만 직접 표시합니다. 이미지 자막은 TV 보호를 위해 불러오지 않습니다.';
  }

  function selectSubtitleTrack() {
    state.subtitles.clear();
    state.subtitles.setPlaybackOffset(state.playback ? state.playback.positionOffset : 0);
    var track = state.playback && (state.playback.subtitles || [])[number(refs.subtitleTrack.value, -1)];
    state.subtitles.setStyle({ enabled: Boolean(track && supportedSubtitle(track)) });
    persistSubtitleSettings();
    if (!state.subtitles.enabled) { return; }
    var playback = state.playback;
    state.subtitles.loadFromUrl(track.url || track.deliveryUrl || track.key).then(function (loaded) {
      if (state.playback === playback && !loaded) { showToast('자막을 불러오지 못했습니다. SRT/VTT 형식과 2MB 이하 크기를 확인해 주세요.'); }
    });
  }

  function syncSubtitlePreview() {
    if (!refs.subtitlePreview || !state.subtitles) { return; }
    var settings = state.subtitles.getSettings();
    var vertical = settings.mode === 'vertical-rl';
    refs.subtitlePreview.setAttribute('data-writing-mode', settings.mode);
    refs.subtitlePreview.style.fontFamily = settings.fontFamily;
    refs.subtitlePreview.style.fontSize = Math.min(settings.fontSize, 42) + 'px';
    refs.subtitlePreview.style.lineHeight = String(settings.lineSpacing);
    refs.subtitlePreview.textContent = vertical ? '자막 미리보기\n가나다 ABC' : '자막 미리보기 · 가나다 ABC';
    refs.subtitleSizeValue.textContent = Math.round(settings.fontSize) + 'px';
    refs.subtitleOffsetValue.textContent = (settings.timeOffset > 0 ? '+' : '') + settings.timeOffset.toFixed(1) + '초';
    refs.subtitleDirectionValue.textContent = vertical ? '세로쓰기' : '가로쓰기';
  }

  function persistSubtitleSettings() {
    var settings = state.subtitles.getSettings();
    storageSet(STORAGE.subtitle, settings);
    refs.subtitleX.value = String(Math.round(settings.x));
    refs.subtitleY.value = String(Math.round(settings.y));
    syncSubtitlePreview();
  }

  function applySubtitleControls() {
    var previous = state.subtitles.getSettings();
    var nextMode = refs.subtitleOrientation.value === 'vertical' ? 'vertical-rl' : 'horizontal';
    var nextX = number(refs.subtitleX.value, 50);
    var nextY = number(refs.subtitleY.value, 82);
    if (nextMode !== previous.mode) {
      if (nextMode === 'vertical-rl' && nextY >= 75 && nextX >= 45 && nextX <= 55) {
        nextX = 82;
        nextY = 50;
      } else if (nextMode === 'horizontal' && nextX >= 75 && nextY >= 45 && nextY <= 55) {
        nextX = 50;
        nextY = 82;
      }
      refs.subtitleX.value = String(nextX);
      refs.subtitleY.value = String(nextY);
    }
    var settings = {
      mode: nextMode,
      x: nextX,
      y: nextY,
      fontSize: number(refs.subtitleSize.value, 46),
      lineSpacing: number(refs.subtitleLineHeight.value, 125) / 100,
      timeOffset: number(refs.subtitleOffset.value, 0)
    };
    state.subtitles.setStyle(settings);
    persistSubtitleSettings();
  }

  function applySubtitleFontControl() {
    var previousFamily = state.subtitles.getSettings().fontFamily;
    var selectedPreset = refs.subtitleFontFamily.value;
    if (selectedPreset !== 'external') {
      state.subtitles.setFontFamily(subtitleFontFamilyForPreset(selectedPreset));
      persistSubtitleSettings();
      return;
    }
    state.subtitles.restoreFont('shyniflex-user-font').then(function (loaded) {
      if (loaded) {
        refs.subtitleFontFamily.value = 'external';
        persistSubtitleSettings();
        return;
      }
      refs.subtitleFontFamily.value = subtitleFontPresetForFamily(previousFamily);
      showToast('저장된 외부 폰트가 없습니다. 먼저 폰트 파일을 선택하세요.');
    }).catch(function () {
      refs.subtitleFontFamily.value = subtitleFontPresetForFamily(previousFamily);
      showToast('저장된 외부 폰트를 불러오지 못했습니다.');
    });
  }

  function restoreSubtitleSettings() {
    var saved = storageGet(STORAGE.subtitle, {});
    var migratedPosition = false;
    if (saved.mode === 'vertical-rl' && number(saved.y, 50) >= 75 && number(saved.x, 50) >= 45 && number(saved.x, 50) <= 55) {
      saved.x = 82;
      saved.y = 50;
      migratedPosition = true;
    }
    if (!saved.fontFamily) {
      saved.fontFamily = SUBTITLE_FONT.defaultFont;
    }
    state.subtitles.setStyle(saved);
    var actual = state.subtitles.getSettings();
    if (migratedPosition) {
      storageSet(STORAGE.subtitle, actual);
    }
    refs.subtitleOrientation.value = actual.mode === 'vertical-rl' ? 'vertical' : 'horizontal';
    refs.subtitleX.value = String(Math.round(actual.x));
    refs.subtitleY.value = String(Math.round(actual.y));
    refs.subtitleSize.value = String(Math.round(actual.fontSize));
    refs.subtitleLineHeight.value = String(Math.round(actual.lineSpacing * 100));
    refs.subtitleFontFamily.value = subtitleFontPresetForFamily(actual.fontFamily);
    refs.subtitleOffset.value = String(actual.timeOffset);
    syncSubtitlePreview();
    if (refs.subtitleFontFamily.value === 'external') {
      state.subtitles.restoreFont('shyniflex-user-font').then(function (loaded) {
        if (loaded) { return; }
        state.subtitles.setFontFamily(SUBTITLE_FONT.cinema);
        refs.subtitleFontFamily.value = 'cinema';
        persistSubtitleSettings();
      }).catch(function () {
        state.subtitles.setFontFamily(SUBTITLE_FONT.cinema);
        refs.subtitleFontFamily.value = 'cinema';
        persistSubtitleSettings();
      });
    }
  }

  function handleAction(action, target) {
    switch (action) {
      case 'select-plex':
        beginMediaLogin('shyni');
        return;
      case 'select-jellyfin':
        beginMediaLogin('jellyfin');
        return;
      case 'select-emby':
        beginMediaLogin('emby');
        return;
      case 'open-token-login':
        state.authFlowToken += 1;
        cancelAuthPoll();
        state.authBusy = false;
        refs.connectionError.textContent = '';
        showAuthStep('token-login-step', refs.serverKind);
        return;
      case 'auth-back':
        beginMediaLogin('shyni');
        return;
      case 'change-media-server':
        state.authFlowToken += 1;
        cancelAuthPoll();
        state.mediaServer = null;
        state.quickConnectAvailable = false;
        state.quickConnect = null;
        state.authBusy = false;
        refs.serverPassword.value = '';
        refs.connectionError.textContent = '';
        showAuthStep('media-login-step', refs.serverUrl);
        return;
      case 'toggle-password-visibility':
        var revealPassword = refs.serverPassword.type === 'password';
        refs.serverPassword.type = revealPassword ? 'text' : 'password';
        refs.passwordVisibility.textContent = revealPassword ? '숨김' : '표시';
        refs.passwordVisibility.setAttribute('aria-pressed', revealPassword ? 'true' : 'false');
        focusInitial(refs.serverPassword);
        return;
      case 'start-quick-connect':
        startQuickConnect();
        return;
      case 'cancel-quick-connect':
        cancelQuickConnect('');
        return;
      case 'retry-plex-login':
        beginMediaLogin('shyni');
        return;
      case 'show-home':
        state.browseLoadToken += 1;
        state.playLoadToken += 1;
        if (state.client) { setNetworkStatus('연결됨', false); }
        showView('home-view');
        return;
      case 'show-libraries':
        state.browseLoadToken += 1;
        state.playLoadToken += 1;
        if (state.client) { setNetworkStatus('연결됨', false); }
        syncLibraryModeTabs('browse');
        showView('libraries-view');
        return;
      case 'show-search':
        state.browseLoadToken += 1;
        state.playLoadToken += 1;
        if (state.client) { setNetworkStatus('연결됨', false); }
        showView('search-view', refs.searchInput);
        return;
      case 'library-mode':
        loadLibraryMode(text(target && target.getAttribute('data-library-mode'), 'browse'));
        return;
      case 'open-settings':
        state.browseLoadToken += 1;
        state.playLoadToken += 1;
        if (state.client) { setNetworkStatus('연결됨', false); }
        if (state.currentView !== 'settings-view' && state.currentView !== 'playback-settings-view') {
          state.settingsReturnView = state.currentView === 'setup-view' ? 'home-view' : state.currentView;
          state.settingsReturnFocus = captureFocusDescriptor(global.document.activeElement);
        }
        showView('settings-view');
        return;
      case 'open-playback-settings':
        syncPlaybackSettingsForm();
        showView('playback-settings-view', refs.playbackQuality);
        return;
      case 'cancel-playback-settings':
        showView('settings-view', refs.playbackSettingsButton);
        return;
      case 'save-playback-settings':
        savePlaybackSettings();
        return;
      case 'close-modal':
        closeModal();
        return;
      case 'go-back':
        navigateBack();
        return;
      case 'close-detail':
        closeDetail();
        return;
      case 'play-detail':
        if (!state.detailItem) { return; }
        if (isPlayable(state.detailItem)) {
          playItem(state.detailItem).catch(function () {});
        } else {
          var firstDetailChild = refs.detailChildRail.querySelector('.media-card');
          if (firstDetailChild) {
            focusInitial(firstDetailChild);
            return;
          }
          if (state.detailLoading) {
            showToast('항목을 불러오는 중입니다.');
            return;
          }
          var detailContainer = state.detailItem;
          var detailOriginFocus = state.detailStack.length ? state.detailStack[state.detailStack.length - 1].focus : state.detailReturnFocus;
          if (state.detailReturnView === 'home-view') {
            state.routeStack = [];
            state.libraryReturnView = 'home-view';
          }
          state.detailLoadToken += 1;
          state.detailItem = null;
          state.detailStack = [];
          browse(detailContainer, entityTitle(detailContainer), false, detailOriginFocus).catch(function () {});
        }
        return;
      case 'refresh-current':
        if (state.currentView === 'search-view') {
          submitSearch();
        } else if (state.currentView === 'items-view' && state.routeStack.length && state.routeStack[state.routeStack.length - 1].mode) {
          loadLibraryMode(state.routeStack[state.routeStack.length - 1].mode);
        } else if (state.currentView === 'items-view' && state.routeStack.length) {
          var currentRoute = state.routeStack[state.routeStack.length - 1];
          browse(currentRoute.container, currentRoute.title, true).catch(function () {});
        } else {
          var requestedView = state.currentView === 'libraries-view' ? 'libraries-view' : 'home-view';
          loadLibraries().then(function (libraries) {
            if (libraries) { showView(requestedView); }
          }).catch(function () {});
        }
        return;
      case 'toggle-sort':
        var sortFocus = captureFocusDescriptor(global.document.activeElement);
        state.sortAscending = !state.sortAscending;
        state.items = sortItemsForView(state.items);
        if (state.routeStack.length) {
          state.routeStack[state.routeStack.length - 1].items = state.items.slice();
        }
        if (refs.itemsSort) {
          refs.itemsSort.textContent = state.sortAscending ? '제목순' : '제목 역순';
        }
        renderItems(refs.itemsTitle.textContent, refs.breadcrumb.textContent, sortFocus);
        return;
      case 'disconnect':
        var disconnectConnection = state.connection;
        var disconnectClient = state.client;
        state.connectionAttemptToken += 1;
        if (
          disconnectConnection &&
          (disconnectConnection.authSource === 'password' || disconnectConnection.authSource === 'quick_connect') &&
          disconnectConnection.kind !== 'plex'
        ) {
          invokeClientOn(disconnectClient, ['logout', 'signOut'], [], true).catch(function () {
            // The local connection is removed even when the server is offline.
          });
        }
        storageRemove(STORAGE.connection);
        var api = apiNamespace();
        if (api && typeof api.clearConnections === 'function') {
          api.clearConnections();
        }
        state.client = null;
        state.connection = null;
        state.libraries = [];
        state.activeLibraryId = '';
        state.items = [];
        state.searchItems = [];
        state.homeRows = [];
        state.routeStack = [];
        state.detailItem = null;
        state.detailChildren = [];
        state.detailStack = [];
        state.detailPendingFocus = null;
        state.detailLoadToken += 1;
        state.detailLoading = false;
        state.homeLoadToken += 1;
        state.browseLoadToken += 1;
        state.playLoadToken += 1;
        if (artworkObserver && typeof artworkObserver.disconnect === 'function') {
          artworkObserver.disconnect();
          artworkObserver = null;
        }
        clearSensitiveAuthInputs();
        state.authBusy = false;
        state.mediaServer = null;
        state.quickConnectAvailable = false;
        state.plexToken = '';
        state.plexServers = [];
        cancelAuthPoll();
        if (state.currentModal) { closeModal(); }
        showView('setup-view');
        setNetworkStatus('연결 필요', false);
        beginMediaLogin('shyni');
        return;
      case 'reorder-libraries':
        renderReorderList();
        openModal('reorder-modal');
        return;
      case 'save-library-order':
        state.libraries = state.pendingLibraryOrder.slice();
        storageSet(STORAGE.libraryOrder, state.libraries.map(entityId));
        renderLibraries();
        loadHomePreviewRows();
        closeModal();
        showToast('라이브러리 순서를 저장했습니다.');
        return;
      case 'open-subtitles':
        openModal('subtitle-modal');
        return;
      case 'play-from-start':
        if (state.detailItem && isPlayable(state.detailItem)) { playItem(state.detailItem, true).catch(function () {}); }
        return;
      case 'retry-saved-connection':
        retrySavedConnection();
        return;
      case 'change-saved-connection':
        byId('saved-connection-actions').classList.add('hidden');
        refs.plexQr.parentNode.classList.remove('hidden');
        beginMediaLogin('shyni');
        return;
      case 'reset-subtitles':
        state.subtitles.setStyle({ mode: 'horizontal', x: 50, y: 82, fontSize: 46, lineSpacing: 1.25, timeOffset: 0, fontFamily: SUBTITLE_FONT.defaultFont });
        persistSubtitleSettings();
        restoreSubtitleSettings();
        return;
      case 'load-subtitle-url':
        if (!refs.subtitleUrl.value) { return; }
        state.subtitles.loadFromUrl(refs.subtitleUrl.value).then(function (loaded) {
          if (loaded) { state.subtitles.setStyle({ enabled: true }); persistSubtitleSettings(); }
          showToast(loaded ? '자막을 적용했습니다.' : '자막을 불러오지 못했습니다.');
          if (loaded) { closeModal(); }
        });
        return;
      case 'load-font-url':
        if (!refs.fontUrl.value) { return; }
        state.subtitles.loadFontFromUrl('Shyni External', refs.fontUrl.value).then(function (loaded) {
          showToast(loaded ? '외부 폰트를 적용했습니다.' : '폰트를 불러오지 못했습니다.');
          if (loaded) {
            refs.subtitleFontFamily.value = 'external';
            persistSubtitleSettings();
          }
        });
        return;
      case 'toggle-subtitle-move':
        closeModal();
        state.subtitles.setAdjustmentMode(true);
        refs.playerOsd.classList.remove('visible');
        showToast('방향키 또는 드래그로 자막 위치를 옮기고 OK를 누르세요.', 4500);
        return;
      default:
        return;
    }
  }

  function handleClick(event) {
    var target = event.target;
    while (target && target !== global.document && !target.getAttribute('data-action') && !target.getAttribute('data-player-action') && !target.getAttribute('data-card-kind') && !target.getAttribute('data-reorder-index')) {
      target = target.parentNode;
    }
    if (!target || target === global.document) { return; }

    var action = target.getAttribute('data-action');
    if (action) {
      handleAction(action, target);
      return;
    }
    var playerAction = target.getAttribute('data-player-action');
    if (playerAction) {
      showOsd(false);
      if (playerAction === 'toggle') { state.player.toggle(); }
      if (playerAction === 'rewind') { state.player.seekBy(-state.player.seekStep); }
      if (playerAction === 'forward') { state.player.seekBy(state.player.seekStep); }
      if (playerAction === 'fit') { cycleVideoFit(); }
      if (playerAction === 'close') { closePlayer(); }
      return;
    }
    var cardKind = target.getAttribute('data-card-kind');
    if (cardKind === 'nav-library') {
      var navigationLibrary = state.libraries[number(target.getAttribute('data-card-index'), -1)];
      if (navigationLibrary) { openLibrary(navigationLibrary).catch(function () {}); }
      return;
    }
    if (cardKind === 'library') {
      var library = state.libraries[number(target.getAttribute('data-card-index'), -1)];
      if (library) { openLibrary(library).catch(function () {}); }
      return;
    }
    if (cardKind === 'item') {
      var item = state.items[number(target.getAttribute('data-card-index'), -1)];
      if (!item) { return; }
      openDetail(item);
      return;
    }
    if (cardKind === 'search-item') {
      var searchItem = state.searchItems[number(target.getAttribute('data-card-index'), -1)];
      if (searchItem) { openDetail(searchItem); }
      return;
    }
    if (cardKind === 'home-item') {
      var row = state.homeRows[number(target.getAttribute('data-home-row'), -1)];
      var homeItem = row && row.items[number(target.getAttribute('data-card-index'), -1)];
      if (homeItem) { openDetail(homeItem); }
      return;
    }
    if (cardKind === 'detail-child') {
      var detailChild = state.detailChildren[number(target.getAttribute('data-card-index'), -1)];
      if (detailChild) { openDetail(detailChild); }
      return;
    }
    if (target.hasAttribute('data-reorder-index')) {
      moveLibrary(number(target.getAttribute('data-reorder-index'), -1), number(target.getAttribute('data-reorder-direction'), 0));
    }
  }

  function handleBack() {
    if (state.currentModal) {
      return closeModal();
    }
    if (!refs.playerView.classList.contains('hidden')) {
      return closePlayer();
    }
    if (state.currentView === 'setup-view' && state.authStep === 'quick-connect-step') {
      if (state.authBusy) { return true; }
      cancelQuickConnect('');
      return true;
    }
    if (state.currentView === 'setup-view' && state.authStep !== 'provider-choice-step') {
      returnToProvider();
      return true;
    }
    if (state.currentView === 'detail-view') {
      return closeDetail();
    }
    if (state.currentView === 'playback-settings-view') {
      showView('settings-view', refs.playbackSettingsButton);
      return true;
    }
    if (state.currentView === 'settings-view') {
      showView(state.settingsReturnView || 'home-view', resolveFocusDescriptor(state.settingsReturnFocus, byId(state.settingsReturnView)));
      return true;
    }
    if (state.currentView === 'items-view') {
      return navigateBack();
    }
    if (state.currentView === 'libraries-view') {
      showView('home-view');
      return true;
    }
    if (state.currentView === 'search-view') {
      showView('home-view');
      return true;
    }
    return false;
  }

  function isRoot() {
    return !state.currentModal && refs.playerView.classList.contains('hidden') && (
      state.currentView === 'home-view' ||
      state.currentView === 'search-view' ||
      (state.currentView === 'setup-view' && state.authStep === 'provider-choice-step')
    );
  }

  function bindTvNavigation() {
    sideNavigationFocusItems();
    global.document.addEventListener('scroll', scheduleVisibleArtwork, true);
    all('.page-content').forEach(function (page) { page.setAttribute('data-nav-scroll', 'true'); });
    if (state.remote) {
      state.remote.addKeyHandler(function (event, code) {
        return handleSideNavigationDirection(code);
      }, true);
    }
    global.document.addEventListener('focusin', function (event) {
      if (refs.sideNavigation) {
        refs.sideNavigation.classList.toggle('expanded', refs.sideNavigation.contains(event.target));
      }
    }, false);
  }

  var AUTH_STEP_IDS = [
    'provider-choice-step',
    'media-login-step',
    'quick-connect-step',
    'plex-login-step',
    'plex-server-step',
    'token-login-step'
  ];

  function setAuthenticationControlState(control, enabled, required) {
    if (!control) { return; }
    control.disabled = !enabled;
    control.required = Boolean(enabled && required);
    control.setAttribute('tabindex', enabled ? '0' : '-1');
  }

  function cancelAuthPoll() {
    if (state.authPollTimer) {
      global.clearTimeout(state.authPollTimer);
      state.authPollTimer = null;
    }
  }

  function syncLoginField(input, label, indicator, emptyText) {
    var filled = Boolean(input && text(input.value).length);
    if (label) { label.classList.toggle('has-value', filled); }
    if (indicator) { indicator.textContent = filled ? '입력됨 ✓' : (emptyText || '입력 대기'); }
  }

  function refreshLoginFieldStates() {
    syncLoginField(refs.serverUrl, refs.serverUrlField, refs.serverUrlState);
    syncLoginField(refs.serverUsername, refs.serverUsernameField, refs.serverUsernameState);
    syncLoginField(refs.serverPassword, refs.serverPasswordField, refs.serverPasswordState);
    syncLoginField(refs.serverTotp, refs.serverTotpField, refs.serverTotpState);
    syncLoginField(refs.plexServerUrl, refs.plexServerUrlField, refs.plexServerUrlState, '선택 사항');
    syncLoginField(refs.tokenServerUrl, refs.tokenServerUrlField, refs.tokenServerUrlState);
    syncLoginField(refs.serverToken, refs.serverTokenField, refs.serverTokenState);
  }

  function updateAuthenticationHelp() {
    var insecure = state.mediaServer && /^http:\/\//i.test(state.mediaServer.baseUrl || '');
    refs.authHelp.classList.toggle('warning', Boolean(insecure));
    refs.authHelp.textContent = insecure
      ? 'HTTP 연결에서는 로그인 정보가 암호화되지 않습니다. 신뢰하는 홈 네트워크에서만 사용하고 가능하면 HTTPS를 사용하세요.'
      : '비밀번호는 저장하지 않으며 로그인 후 발급된 토큰만 이 TV에 저장합니다.';
  }

  function syncMediaLoginPanels() {
    var resolved = Boolean(state.mediaServer);
    var kind = state.authKind === 'emby' ? 'emby' : state.authKind === 'jellyfin' ? 'jellyfin' : 'shyni';
    refs.mediaUrlPanel.classList.toggle('hidden', resolved);
    refs.mediaCredentialsPanel.classList.toggle('hidden', !resolved);
    refs.mediaCredentialsPanel.setAttribute('aria-hidden', resolved ? 'false' : 'true');
    refs.mediaLoginTitle.textContent = (kind === 'emby' ? 'Emby' : kind === 'jellyfin' ? 'Jellyfin' : 'Shyni') + ' 연결';
    refs.mediaBackendBadge.className = 'provider-badge ' + (kind === 'emby' ? 'emby-badge' : kind === 'jellyfin' ? 'jellyfin-badge' : 'shyni-badge');
    refs.mediaBackendBadge.textContent = kind === 'emby' ? 'E' : kind === 'jellyfin' ? 'J' : 'S';
    refs.serverPasswordField.classList.toggle('hidden', Boolean(state.shyniChallenge));
    if (refs.serverTotpField) { refs.serverTotpField.classList.toggle('hidden', !state.shyniChallenge); }
    refs.quickConnectButton.classList.toggle('hidden', !state.quickConnectAvailable || kind !== 'jellyfin');
    if (resolved) {
      refs.detectedServerName.textContent = state.mediaServer.serverName || state.mediaServer.name || '서버 확인됨';
      refs.detectedServerMeta.textContent = (kind === 'emby' ? 'Emby' : kind === 'jellyfin' ? 'Jellyfin' : 'Shyni') +
        (state.mediaServer.version ? ' ' + state.mediaServer.version : '') + ' · ' + state.mediaServer.baseUrl;
      refs.serverUrl.value = state.mediaServer.baseUrl;
    }
    updateAuthenticationHelp();
    refreshLoginFieldStates();
  }

  function syncTokenLoginControls() {
    var showUserId = text(refs.serverKind.value).toLowerCase() !== 'plex';
    refs.userIdField.classList.toggle('hidden', !showUserId);
    if (state.authStep === 'token-login-step' && !state.authBusy) {
      setAuthenticationControlState(refs.serverUserId, showUserId, false);
    } else {
      setAuthenticationControlState(refs.serverUserId, false, false);
    }
  }

  function syncAuthControls() {
    var setup = byId('setup-view');
    all('button,input,select', setup).forEach(function (control) {
      setAuthenticationControlState(control, false, false);
    });
    if (state.authStep === 'provider-choice-step') {
      if (state.authBusy) { return; }
      [refs.providerPlex, refs.providerJellyfin, refs.providerEmby, refs.openTokenLogin].forEach(function (control) {
        setAuthenticationControlState(control, true, false);
      });
      return;
    }
    if (state.authStep === 'media-login-step') {
      setAuthenticationControlState(refs.mediaLoginBack, true, false);
      if (state.authBusy) { return; }
      if (!state.mediaServer) {
        setAuthenticationControlState(refs.serverUrl, true, true);
        setAuthenticationControlState(refs.findServer, true, false);
      } else {
        setAuthenticationControlState(refs.changeMediaServer, true, false);
        setAuthenticationControlState(refs.serverUsername, true, true);
        setAuthenticationControlState(refs.serverPassword, true, false);
        setAuthenticationControlState(refs.passwordVisibility, true, false);
        if (refs.serverTotp) { setAuthenticationControlState(refs.serverTotp, Boolean(state.shyniChallenge), false); }
        setAuthenticationControlState(refs.connectionSubmit, true, false);
        if (!refs.quickConnectButton.classList.contains('hidden')) {
          setAuthenticationControlState(refs.quickConnectButton, true, false);
        }
      }
      return;
    }
    if (state.authStep === 'quick-connect-step') {
      setAuthenticationControlState(refs.quickConnectCancel, true, false);
      return;
    }
    if (state.authStep === 'plex-login-step') {
      setAuthenticationControlState(refs.plexLoginBack, true, false);
      if (!state.authBusy && !byId('saved-connection-actions').classList.contains('hidden')) {
        all('button', byId('saved-connection-actions')).forEach(function (button) {
          setAuthenticationControlState(button, true, false);
        });
      }
      if (!state.authBusy && !refs.plexLoginRetry.classList.contains('hidden')) {
        setAuthenticationControlState(refs.plexLoginRetry, true, false);
      }
      return;
    }
    if (state.authStep === 'plex-server-step') {
      setAuthenticationControlState(refs.plexServerBack, true, false);
      if (state.authBusy) { return; }
      all('.plex-server-option', refs.plexServerList).forEach(function (control) {
        setAuthenticationControlState(control, true, false);
      });
      setAuthenticationControlState(refs.plexServerUrl, true, false);
      setAuthenticationControlState(refs.plexServerSubmit, true, false);
      return;
    }
    if (state.authStep === 'token-login-step') {
      setAuthenticationControlState(refs.tokenLoginBack, true, false);
      if (state.authBusy) { return; }
      setAuthenticationControlState(refs.serverKind, true, false);
      setAuthenticationControlState(refs.tokenServerUrl, true, true);
      setAuthenticationControlState(refs.serverToken, true, true);
      setAuthenticationControlState(refs.tokenSubmit, true, false);
      syncTokenLoginControls();
    }
  }

  function showAuthStep(stepId, preferredFocus) {
    AUTH_STEP_IDS.forEach(function (id) {
      var step = byId(id);
      var active = id === stepId;
      step.classList.toggle('hidden', !active);
      step.setAttribute('aria-hidden', active ? 'false' : 'true');
    });
    state.authStep = stepId;
    refs.loginHeading.classList.toggle('hidden', stepId !== 'provider-choice-step');
    if (stepId === 'media-login-step') { syncMediaLoginPanels(); }
    syncAuthControls();
    global.setTimeout(function () { focusInitial(preferredFocus); }, 0);
  }

  function setConnectionFormBusy(busy) {
    state.authBusy = Boolean(busy);
    refs.findServer.textContent = busy && state.authStep === 'media-login-step' && !state.mediaServer ? '서버 확인 중…' : '서버 찾기';
    refs.connectionSubmit.textContent = busy && state.authStep === 'media-login-step' && state.mediaServer ? '로그인 중…' : '로그인';
    refs.tokenSubmit.textContent = busy && state.authStep === 'token-login-step' ? '연결 중…' : '연결하고 시작';
    refs.plexServerSubmit.textContent = busy && state.authStep === 'plex-server-step' ? '연결 중…' : '입력한 주소로 연결';
    syncAuthControls();
  }

  function clearSensitiveAuthInputs() {
    refs.serverUsername.value = '';
    refs.serverPassword.value = '';
    if (refs.serverTotp) { refs.serverTotp.value = ''; }
    state.shyniChallenge = '';
    refs.serverPassword.type = 'password';
    refs.passwordVisibility.textContent = '표시';
    refs.passwordVisibility.setAttribute('aria-pressed', 'false');
    refs.serverToken.value = '';
    refs.serverUserId.value = '';
    state.quickConnect = null;
    refreshLoginFieldStates();
  }

  function returnToProvider() {
    state.authFlowToken += 1;
    state.connectionAttemptToken += 1;
    state.browseLoadToken += 1;
    cancelAuthPoll();
    state.client = null;
    state.connection = null;
    state.authKind = '';
    state.mediaServer = null;
    state.quickConnectAvailable = false;
    state.plexPin = null;
    state.plexToken = '';
    state.plexServers = [];
    state.authBusy = false;
    refs.connectionError.textContent = '';
    refs.plexQr.textContent = '';
    refs.plexServerList.innerHTML = '';
    clearSensitiveAuthInputs();
    beginMediaLogin('shyni');
    setNetworkStatus('연결 필요', false);
  }

  function beginMediaLogin(kind) {
    state.authFlowToken += 1;
    cancelAuthPoll();
    state.authKind = kind === 'emby' ? 'emby' : kind === 'jellyfin' ? 'jellyfin' : 'shyni';
    state.mediaServer = null;
    state.quickConnectAvailable = false;
    state.quickConnect = null;
    state.shyniChallenge = '';
    state.authBusy = false;
    refs.connectionError.textContent = '';
    refs.serverUsername.value = '';
    refs.serverPassword.value = '';
    refs.serverUrl.placeholder = state.authKind === 'shyni' ? 'https://shyni.example.com' : '192.168.0.10:8096';
    showAuthStep('media-login-step', refs.serverUrl);
  }

  function renderPlexQr(url) {
    refs.plexQr.innerHTML = '';
    try {
      if (typeof global.qrcode === 'function') {
        var qr = global.qrcode(0, 'M');
        qr.addData(url);
        qr.make();
        refs.plexQr.innerHTML = qr.createSvgTag(5, 0);
        return;
      }
    } catch (ignore) {
      // The large code and plex.tv/link remain a complete fallback.
    }
    refs.plexQr.textContent = 'plex.tv/link';
  }

  function showPlexRetry(message) {
    cancelAuthPoll();
    state.authBusy = false;
    refs.plexLoginStatus.textContent = message;
    refs.plexLoginRetry.classList.remove('hidden');
    refs.connectionError.textContent = message;
    syncAuthControls();
    focusInitial(refs.plexLoginRetry);
  }

  function renderPlexServers(servers) {
    refs.plexServerList.innerHTML = '';
    servers.forEach(function (server, index) {
      var connection = server.connections && server.connections[0];
      if (!connection) { return; }
      var button = global.document.createElement('button');
      var copy = global.document.createElement('span');
      var name = global.document.createElement('strong');
      var address = global.document.createElement('small');
      var arrow = global.document.createElement('b');
      button.type = 'button';
      button.className = 'focusable plex-server-option';
      button.setAttribute('data-plex-server-index', String(index));
      button.setAttribute('tabindex', '-1');
      button.disabled = true;
      name.textContent = server.name || 'Plex Media Server';
      address.textContent = connection.uri + (connection.local ? ' · 로컬' : connection.relay ? ' · Relay' : ' · 원격');
      arrow.textContent = '›';
      copy.appendChild(name);
      copy.appendChild(address);
      button.appendChild(copy);
      button.appendChild(arrow);
      refs.plexServerList.appendChild(button);
    });
    if (servers.length && servers[0].connections && servers[0].connections[0]) {
      refs.plexServerUrl.value = servers[0].connections[0].uri;
    }
    refreshLoginFieldStates();
  }

  function finishPlexPin(flowToken, token) {
    if (flowToken !== state.authFlowToken) { return; }
    cancelAuthPoll();
    state.plexToken = token;
    refs.plexLoginStatus.textContent = '승인 완료 · Plex 서버를 불러오는 중입니다…';
    setNetworkStatus('서버 찾는 중', false);
    invokeAuthenticationApi('getPlexResources', {
      token: token,
      clientIdentifier: state.plexPin && state.plexPin.clientIdentifier
    }).then(function (servers) {
      if (flowToken !== state.authFlowToken) { return; }
      state.plexServers = Array.isArray(servers) ? servers : [];
      renderPlexServers(state.plexServers);
      if (state.plexServers.length) {
        connectPlexServersAutomatically(flowToken, state.plexServers);
        return;
      }
      showPlexServerFallback('자동으로 찾은 Plex 서버가 없습니다. 서버 주소를 직접 입력하세요.');
    }).catch(function () {
      if (flowToken !== state.authFlowToken) { return; }
      state.plexServers = [];
      renderPlexServers([]);
      state.authBusy = false;
      showAuthStep('plex-server-step', refs.plexServerUrl);
      setNetworkStatus('서버 주소 필요', false);
      refs.connectionError.textContent = 'Plex 서버 목록을 불러오지 못했습니다. 서버 주소를 직접 입력하세요.';
    });
  }

  function schedulePlexPinPoll(flowToken) {
    cancelAuthPoll();
    if (flowToken !== state.authFlowToken || !state.plexPin) { return; }
    if (Date.now() >= state.authDeadline) {
      showPlexRetry('Plex 인증 코드가 만료되었습니다. 새 코드를 만들어 주세요.');
      return;
    }
    state.authPollTimer = global.setTimeout(function () {
      state.authPollTimer = null;
      invokeAuthenticationApi('checkPlexPin', state.plexPin).then(function (result) {
        if (flowToken !== state.authFlowToken) { return; }
        if (result && result.claimed && result.token) {
          finishPlexPin(flowToken, result.token);
          return;
        }
        state.authPollDelay = Math.min(5000, Math.max(1500, state.authPollDelay * 1.5));
        schedulePlexPinPoll(flowToken);
      }).catch(function (error) {
        if (flowToken !== state.authFlowToken) { return; }
        if (error && (error.code === 'network_error' || error.code === 'timeout') && Date.now() < state.authDeadline) {
          state.authPollDelay = Math.min(5000, state.authPollDelay * 2);
          schedulePlexPinPoll(flowToken);
          return;
        }
        showPlexRetry(errorMessage(error));
      });
    }, state.authPollDelay);
  }

  function startPlexLogin() {
    byId('saved-connection-actions').classList.add('hidden');
    refs.plexQr.parentNode.classList.remove('hidden');
    byId('plex-login-title').textContent = 'Plex 계정에서 TV 코드를 승인하세요';
    var flowToken = state.authFlowToken + 1;
    state.authFlowToken = flowToken;
    state.connectionAttemptToken += 1;
    state.browseLoadToken += 1;
    state.client = null;
    state.connection = null;
    state.authKind = 'plex';
    state.plexPin = null;
    state.plexToken = '';
    state.plexServers = [];
    state.authPollDelay = 1500;
    state.authBusy = true;
    cancelAuthPoll();
    refs.connectionError.textContent = '';
    refs.plexLoginCode.textContent = '----';
    refs.plexLoginStatus.textContent = '인증 코드를 만드는 중입니다…';
    refs.plexLoginRetry.classList.add('hidden');
    refs.plexQr.textContent = '';
    showAuthStep('plex-login-step', refs.plexLoginBack);
    setNetworkStatus('Plex 로그인', false);
    invokeAuthenticationApi('createPlexPin', {}).then(function (pin) {
      var parsedExpiry;
      if (flowToken !== state.authFlowToken) { return; }
      state.plexPin = pin;
      state.authBusy = false;
      refs.plexLoginCode.textContent = pin.code;
      refs.plexLoginStatus.textContent = '휴대폰이나 PC에서 plex.tv/link를 열어 코드를 입력하세요.';
      renderPlexQr(pin.authUrl);
      parsedExpiry = Date.parse(pin.expiresAt || '');
      state.authDeadline = isFinite(parsedExpiry) ? Math.min(parsedExpiry, Date.now() + 120000) : Date.now() + 120000;
      syncAuthControls();
      schedulePlexPinPoll(flowToken);
    }).catch(function (error) {
      if (flowToken !== state.authFlowToken) { return; }
      showPlexRetry(errorMessage(error));
    });
  }

  function plexConnectionCandidates(servers) {
    var candidates = [];
    var groups = [true, false];
    groups.forEach(function (owned) {
      (Array.isArray(servers) ? servers : []).forEach(function (server) {
        var token;
        if (Boolean(server && server.owned) !== owned) { return; }
        token = server && (server.accessToken || state.plexToken);
        if (!token) { return; }
        (server && Array.isArray(server.connections) ? server.connections : []).forEach(function (connection) {
          if (!connection || !/^https?:\/\//i.test(text(connection.uri))) { return; }
          candidates.push({
            kind: 'plex',
            url: connection.uri,
            token: token,
            clientIdentifier: state.plexPin && state.plexPin.clientIdentifier,
            serverName: server.name,
            serverId: server.clientIdentifier,
            authSource: 'plex_pin'
          });
        });
      });
    });
    return candidates;
  }

  function connectPlexCandidates(candidates, attemptToken) {
    function connectAt(index, lastError) {
      if (attemptToken !== state.connectionAttemptToken) {
        return global.Promise.resolve(null);
      }
      if (index >= candidates.length) {
        return global.Promise.reject(lastError || new Error('Plex 서버에 연결할 수 없습니다.'));
      }
      setNetworkStatus('자동 연결 ' + (index + 1) + '/' + candidates.length, false);
      return connect(candidates[index], false, attemptToken).then(function (result) {
        if (result === null) { return null; }
        return { result: result, candidate: candidates[index] };
      }).catch(function (error) {
        if (attemptToken !== state.connectionAttemptToken) { return null; }
        return connectAt(index + 1, error);
      });
    }
    return connectAt(0, null);
  }

  function showPlexServerFallback(message) {
    state.authBusy = false;
    renderPlexServers(state.plexServers);
    showAuthStep('plex-server-step', refs.plexServerList.querySelector('.plex-server-option') || refs.plexServerUrl);
    setNetworkStatus('서버 주소 필요', false);
    refs.connectionError.textContent = message;
  }

  function connectPlexServersAutomatically(flowToken, servers) {
    var candidates = plexConnectionCandidates(servers);
    var attemptToken;
    if (flowToken !== state.authFlowToken) { return global.Promise.resolve(null); }
    if (!candidates.length) {
      showPlexServerFallback('자동으로 연결할 Plex 서버 주소가 없습니다. 서버 주소를 직접 입력하세요.');
      return global.Promise.resolve(null);
    }
    attemptToken = state.connectionAttemptToken + 1;
    state.connectionAttemptToken = attemptToken;
    state.authBusy = true;
    refs.connectionError.textContent = '';
    refs.plexLoginStatus.textContent = '승인 완료 · Plex 서버에 자동 연결 중입니다…';
    syncAuthControls();
    return connectPlexCandidates(candidates, attemptToken).then(function (outcome) {
      if (flowToken !== state.authFlowToken || attemptToken !== state.connectionAttemptToken || outcome === null) {
        return null;
      }
      state.authBusy = false;
      state.plexToken = '';
      state.plexServers = [];
      return outcome.result;
    }).catch(function (error) {
      if (flowToken !== state.authFlowToken || attemptToken !== state.connectionAttemptToken) { return null; }
      state.client = null;
      state.connection = null;
      state.browseLoadToken += 1;
      showPlexServerFallback('자동 연결에 실패했습니다. 서버를 선택하거나 주소를 직접 입력하세요. ' + errorMessage(error));
      return null;
    });
  }

  function connectPlexServer(server) {
    var candidates = plexConnectionCandidates([server]);
    var attemptToken;
    if (!candidates.length || state.authBusy) { return global.Promise.resolve(null); }
    attemptToken = state.connectionAttemptToken + 1;
    state.connectionAttemptToken = attemptToken;
    refs.connectionError.textContent = '';
    setConnectionFormBusy(true);
    setNetworkStatus('연결 확인', false);
    return connectPlexCandidates(candidates, attemptToken).then(function (outcome) {
      if (attemptToken !== state.connectionAttemptToken || outcome === null) { return null; }
      setConnectionFormBusy(false);
      state.plexToken = '';
      state.plexServers = [];
      return outcome.result;
    }).catch(function (error) {
      if (attemptToken !== state.connectionAttemptToken) { return null; }
      setConnectionFormBusy(false);
      showAuthStep('plex-server-step', refs.plexServerUrl);
      refs.connectionError.textContent = errorMessage(error);
      return null;
    });
  }

  function probeSelectedMediaServer() {
    var flowToken = state.authFlowToken;
    if (state.authBusy) { return; }
    refs.connectionError.textContent = '';
    setConnectionFormBusy(true);
    setNetworkStatus('서버 확인 중', false);
    invokeAuthenticationApi('probeMediaBrowser', {
      type: state.authKind,
      url: refs.serverUrl.value
    }).then(function (server) {
      if (flowToken !== state.authFlowToken) { return; }
      state.mediaServer = server;
      state.authKind = server.type === 'emby' ? 'emby' : server.type === 'jellyfin' ? 'jellyfin' : 'shyni';
      state.quickConnectAvailable = false;
      setConnectionFormBusy(false);
      showAuthStep('media-login-step', refs.serverUsername);
      setNetworkStatus('서버 확인됨', false);
      if (state.authKind !== 'jellyfin') { return; }
      invokeAuthenticationApi('isQuickConnectEnabled', {
        type: state.authKind,
        baseUrl: server.baseUrl
      }).then(function (enabled) {
        if (flowToken !== state.authFlowToken || state.mediaServer !== server) { return; }
        state.quickConnectAvailable = Boolean(enabled);
        syncMediaLoginPanels();
        syncAuthControls();
        if (enabled) { startQuickConnect(); }
      }).catch(function () {
        // Username/password remains available when capability detection fails.
      });
    }).catch(function (error) {
      if (flowToken !== state.authFlowToken) { return; }
      setConnectionFormBusy(false);
      setNetworkStatus('서버 확인 실패', true);
      refs.connectionError.textContent = errorMessage(error);
      focusInitial(refs.serverUrl);
    });
  }

  function cancelQuickConnect(message) {
    state.authFlowToken += 1;
    state.connectionAttemptToken += 1;
    cancelAuthPoll();
    state.quickConnect = null;
    state.authBusy = false;
    showAuthStep('media-login-step', refs.serverUsername);
    setNetworkStatus('계정 로그인', false);
    refs.connectionError.textContent = message || '';
  }

  function finishQuickConnect(flowToken) {
    var server = state.mediaServer;
    state.authBusy = true;
    refs.quickConnectStatus.textContent = '승인 완료 · 로그인 중입니다…';
    syncAuthControls();
    setNetworkStatus('로그인 중', false);
    invokeAuthenticationApi('authenticateWithQuickConnect', {
      type: 'jellyfin',
      baseUrl: server.baseUrl,
      secret: state.quickConnect.secret,
      clientIdentifier: state.quickConnect.clientIdentifier,
      serverName: server.serverName,
      serverId: server.serverId
    }).then(function (config) {
      var attemptToken;
      if (flowToken !== state.authFlowToken) { return null; }
      attemptToken = state.connectionAttemptToken + 1;
      state.connectionAttemptToken = attemptToken;
      return connect(config, false, attemptToken).then(function (result) {
        if (result !== null) {
          state.quickConnect = null;
          clearSensitiveAuthInputs();
        }
        return result;
      });
    }).catch(function (error) {
      if (flowToken !== state.authFlowToken) { return; }
      cancelQuickConnect(errorMessage(error));
    });
  }

  function scheduleQuickConnectPoll(flowToken) {
    cancelAuthPoll();
    if (flowToken !== state.authFlowToken || !state.quickConnect) { return; }
    if (Date.now() >= state.authDeadline) {
      cancelQuickConnect('Quick Connect 코드가 만료되었습니다. 다시 시도하세요.');
      return;
    }
    state.authPollTimer = global.setTimeout(function () {
      state.authPollTimer = null;
      invokeAuthenticationApi('checkQuickConnect', state.quickConnect).then(function (result) {
        if (flowToken !== state.authFlowToken) { return; }
        if (result && result.expired) {
          cancelQuickConnect('Quick Connect 코드가 만료되었습니다. 다시 시도하세요.');
          return;
        }
        if (result && result.authenticated) {
          finishQuickConnect(flowToken);
          return;
        }
        scheduleQuickConnectPoll(flowToken);
      }).catch(function (error) {
        if (flowToken !== state.authFlowToken) { return; }
        if (error && (error.code === 'network_error' || error.code === 'timeout') && Date.now() < state.authDeadline) {
          scheduleQuickConnectPoll(flowToken);
          return;
        }
        cancelQuickConnect(errorMessage(error));
      });
    }, 2000);
  }

  function startQuickConnect() {
    var server = state.mediaServer;
    var flowToken = state.authFlowToken;
    if (!server || state.authKind !== 'jellyfin' || state.authBusy) { return; }
    refs.connectionError.textContent = '';
    setConnectionFormBusy(true);
    setNetworkStatus('Quick Connect', false);
    invokeAuthenticationApi('initiateQuickConnect', {
      type: 'jellyfin',
      baseUrl: server.baseUrl
    }).then(function (quickConnect) {
      if (flowToken !== state.authFlowToken) { return; }
      state.quickConnect = quickConnect;
      state.authDeadline = Date.now() + 300000;
      state.authBusy = false;
      refs.quickConnectCode.textContent = quickConnect.code;
      refs.quickConnectStatus.textContent = 'Jellyfin의 Quick Connect 화면에서 이 코드를 승인하세요.';
      showAuthStep('quick-connect-step', refs.quickConnectCancel);
      scheduleQuickConnectPoll(flowToken);
    }).catch(function (error) {
      if (flowToken !== state.authFlowToken) { return; }
      setConnectionFormBusy(false);
      showAuthStep('media-login-step', refs.serverUsername);
      refs.connectionError.textContent = errorMessage(error);
    });
  }

  function submitMediaCredentials() {
    var flowToken = state.authFlowToken;
    var server = state.mediaServer;
    var password = refs.serverPassword.value;
    var attemptToken;
    if (!server) {
      probeSelectedMediaServer();
      return;
    }
    if (!state.shyniChallenge && !text(refs.serverUsername.value).trim()) {
      refs.connectionError.textContent = '사용자 아이디를 입력하세요.';
      focusInitial(refs.serverUsername);
      return;
    }
    attemptToken = state.connectionAttemptToken + 1;
    state.connectionAttemptToken = attemptToken;
    refs.connectionError.textContent = '';
    setConnectionFormBusy(true);
    setNetworkStatus('로그인 중', false);
    var authRequest = state.shyniChallenge ? invokeAuthenticationApi('completeShyniTotp', {
      kind: 'shyni',
      url: server.baseUrl,
      challenge: state.shyniChallenge,
      code: refs.serverTotp && refs.serverTotp.value,
      serverName: server.serverName
    }) : authenticateByName({
      kind: state.authKind,
      url: server.baseUrl,
      username: refs.serverUsername.value,
      password: password,
      serverName: server.serverName,
      serverId: server.serverId
    });
    authRequest.then(function (config) {
      if (flowToken !== state.authFlowToken || attemptToken !== state.connectionAttemptToken) { return null; }
      if (config && config.requiresTotp) {
        state.shyniChallenge = config.challenge;
        setConnectionFormBusy(false);
        refs.serverPasswordField.classList.add('hidden');
        if (refs.serverTotpField) { refs.serverTotpField.classList.remove('hidden'); }
        refs.connectionError.textContent = '인증 앱의 2단계 인증 코드를 입력하세요.';
        syncAuthControls();
        focusInitial(refs.serverTotp);
        return null;
      }
      state.shyniChallenge = '';
      return connect(config, false, attemptToken);
    }).then(function (result) {
      if (typeof result !== 'undefined' && result !== null && flowToken === state.authFlowToken) {
        refs.serverPassword.value = '';
        if (refs.serverTotp) { refs.serverTotp.value = ''; }
        password = '';
        refreshLoginFieldStates();
      }
    }).catch(function (error) {
      password = '';
      if (flowToken !== state.authFlowToken || attemptToken !== state.connectionAttemptToken) { return; }
      setConnectionFormBusy(false);
      showAuthStep('media-login-step', state.shyniChallenge && refs.serverTotp ? refs.serverTotp : refs.serverPassword);
      setNetworkStatus('로그인 실패', true);
      refs.connectionError.textContent = errorMessage(error);
    });
  }

  function submitTokenConnection() {
    var attemptToken;
    if (state.authBusy) { return; }
    if (!text(refs.tokenServerUrl.value).trim() || !text(refs.serverToken.value)) {
      refs.connectionError.textContent = '서버 주소와 토큰/API 키를 입력하세요.';
      focusInitial(!refs.tokenServerUrl.value ? refs.tokenServerUrl : refs.serverToken);
      return;
    }
    attemptToken = state.connectionAttemptToken + 1;
    state.connectionAttemptToken = attemptToken;
    refs.connectionError.textContent = '';
    setConnectionFormBusy(true);
    connect({
      kind: refs.serverKind.value,
      url: refs.tokenServerUrl.value,
      token: refs.serverToken.value,
      userId: refs.serverUserId.value,
      authSource: 'token'
    }, false, attemptToken).then(function (result) {
      if (typeof result !== 'undefined' && result !== null) { refs.serverToken.value = ''; }
    }).catch(function (error) {
      if (attemptToken !== state.connectionAttemptToken) { return; }
      setConnectionFormBusy(false);
      showAuthStep('token-login-step', refs.serverToken);
      setNetworkStatus('연결 오류', true);
      refs.connectionError.textContent = errorMessage(error);
    });
  }

  function bindForms() {
    [
      [refs.serverUrl, refs.serverUrlField, refs.serverUrlState],
      [refs.serverUsername, refs.serverUsernameField, refs.serverUsernameState],
      [refs.serverPassword, refs.serverPasswordField, refs.serverPasswordState],
      [refs.serverTotp, refs.serverTotpField, refs.serverTotpState],
      [refs.plexServerUrl, refs.plexServerUrlField, refs.plexServerUrlState, '선택 사항'],
      [refs.tokenServerUrl, refs.tokenServerUrlField, refs.tokenServerUrlState],
      [refs.serverToken, refs.serverTokenField, refs.serverTokenState]
    ].forEach(function (entry) {
      entry[0].addEventListener('input', function () {
        syncLoginField(entry[0], entry[1], entry[2], entry[3]);
      });
    });
    refs.serverKind.addEventListener('change', function () {
      syncTokenLoginControls();
      syncAuthControls();
    });
    refs.serverUsername.addEventListener('keydown', function (event) {
      if ((event.key === 'Enter' || event.keyCode === 13) && !state.authBusy) {
        event.preventDefault();
        focusInitial(refs.serverPassword);
      }
    });
    refs.mediaLoginForm.addEventListener('submit', function (event) {
      event.preventDefault();
      if (state.authBusy) { return; }
      if (state.mediaServer) { submitMediaCredentials(); } else { probeSelectedMediaServer(); }
    });
    refs.tokenLoginForm.addEventListener('submit', function (event) {
      event.preventDefault();
      submitTokenConnection();
    });
    refs.plexServerForm.addEventListener('submit', function (event) {
      event.preventDefault();
      if (!text(refs.plexServerUrl.value).trim()) {
        refs.connectionError.textContent = 'Plex 서버 주소를 입력하세요.';
        focusInitial(refs.plexServerUrl);
        return;
      }
      connectPlexServer({
        name: 'Plex Media Server',
        accessToken: state.plexToken,
        clientIdentifier: '',
        connections: [{ uri: normalizeServerUrl(refs.plexServerUrl.value), local: true, relay: false }]
      });
    });
    refs.searchForm.addEventListener('submit', function (event) {
      event.preventDefault();
      submitSearch();
    });
    refs.plexServerList.addEventListener('click', function (event) {
      var target = event.target;
      while (target && target !== refs.plexServerList && !target.hasAttribute('data-plex-server-index')) {
        target = target.parentNode;
      }
      if (!target || target === refs.plexServerList) { return; }
      connectPlexServer(state.plexServers[number(target.getAttribute('data-plex-server-index'), -1)]);
    });
    refreshLoginFieldStates();
    refs.timeline.addEventListener('change', function () {
      var progress = state.player.getProgress();
      var offset = state.playback ? number(state.playback.positionOffset, 0) : 0;
      var duration = state.playback && state.playback.durationHint > 0
        ? state.playback.durationHint
        : progress.duration + offset;
      if (duration > 0) {
        state.player.seekTo(Math.max(0, duration * number(refs.timeline.value, 0) / 1000 - offset));
      }
      showOsd(false);
    });

    [refs.subtitleOrientation, refs.subtitleSize, refs.subtitleX, refs.subtitleY, refs.subtitleLineHeight, refs.subtitleOffset].forEach(function (control) {
      control.addEventListener('input', applySubtitleControls);
      control.addEventListener('change', applySubtitleControls);
    });
    refs.subtitleFontFamily.addEventListener('change', applySubtitleFontControl);
    refs.subtitleTrack.addEventListener('change', selectSubtitleTrack);
    refs.subtitleFile.addEventListener('change', function () {
      var file = refs.subtitleFile.files && refs.subtitleFile.files[0];
      if (!file) { return; }
      state.subtitles.loadFromFile(file).then(function (loaded) {
        if (loaded) { state.subtitles.setStyle({ enabled: true }); persistSubtitleSettings(); }
        showToast(loaded ? '외부 자막을 적용했습니다.' : '자막 파일을 읽지 못했습니다.');
        if (loaded) { closeModal(); }
      });
    });
    refs.fontFile.addEventListener('change', function () {
      var file = refs.fontFile.files && refs.fontFile.files[0];
      if (!file) { return; }
      state.subtitles.loadFontFromFile(file, 'Shyni External', 'shyniflex-user-font').then(function (loaded) {
        showToast(loaded ? '외부 폰트를 저장하고 적용했습니다.' : '폰트 파일을 읽지 못했습니다.');
        if (loaded) {
          refs.subtitleFontFamily.value = 'external';
          persistSubtitleSettings();
        }
      });
    });
  }

  function bindPlaybackLifecycle() {
    global.document.addEventListener('visibilitychange', function () {
      if (!state.playback) {
        if (!global.document.hidden && state.client && state.currentView !== 'setup-view') { loadHomePreviewRows(); }
        return;
      }
      var progress = state.player.getProgress();
      if (global.document.hidden) {
        progress.eventName = 'Pause';
        reportPlayback('paused', progress, true);
        state.player.pause();
        state.subtitles.cancelLoad();
      } else if (!progress.paused) {
        progress.eventName = 'Unpause';
        reportPlayback('playing', progress, true);
        loadHomePreviewRows();
      }
    }, false);
    if (typeof global.addEventListener === 'function') {
      global.addEventListener('pagehide', function () {
        if (state.playback) {
          reportPlayback('stopped', state.player.getProgress(), true);
          state.player.unload();
          state.subtitles.clear();
        }
      }, false);
    }
  }

  function cacheRefs() {
    refs.appHeader = byId('app-header');
    refs.setupView = byId('setup-view');
    refs.content = byId('content');
    refs.settingsButton = byId('settings-button');
    refs.sideNavigation = byId('side-navigation');
    refs.navHome = byId('nav-home');
    refs.navSearch = byId('nav-search');
    refs.navLibraries = byId('nav-libraries');
    refs.navRefresh = byId('nav-refresh');
    refs.navSettings = byId('nav-settings');
    refs.sideLibraryItems = byId('side-library-items');
    refs.networkStatus = byId('network-status');
    refs.systemClock = byId('system-clock');
    refs.deviceCapability = byId('device-capability');
    refs.toast = byId('toast');
    refs.loginHeading = byId('login-heading');
    refs.connectionError = byId('connection-error');
    refs.providerPlex = byId('provider-plex');
    refs.providerJellyfin = byId('provider-jellyfin');
    refs.providerEmby = byId('provider-emby');
    refs.openTokenLogin = byId('open-token-login');
    refs.mediaLoginBack = byId('media-login-back');
    refs.mediaLoginForm = byId('media-login-form');
    refs.mediaLoginTitle = byId('media-login-title');
    refs.mediaBackendBadge = byId('media-backend-badge');
    refs.mediaUrlPanel = byId('media-url-panel');
    refs.mediaCredentialsPanel = byId('media-credentials-panel');
    refs.serverKind = byId('server-kind');
    refs.serverUrl = byId('server-url');
    refs.serverUrlField = byId('server-url-field');
    refs.serverUrlState = byId('server-url-state');
    refs.findServer = byId('find-server');
    refs.detectedServerName = byId('detected-server-name');
    refs.detectedServerMeta = byId('detected-server-meta');
    refs.changeMediaServer = byId('change-media-server');
    refs.authHelp = byId('auth-help');
    refs.serverUsername = byId('server-username');
    refs.serverUsernameField = byId('server-username-field');
    refs.serverUsernameState = byId('server-username-state');
    refs.serverPassword = byId('server-password');
    refs.serverPasswordField = byId('server-password-field');
    refs.serverPasswordState = byId('server-password-state');
    refs.passwordVisibility = byId('password-visibility');
    refs.serverTotp = byId('server-totp');
    refs.serverTotpField = byId('server-totp-field');
    refs.serverTotpState = byId('server-totp-state');
    refs.connectionSubmit = byId('connection-submit');
    refs.quickConnectButton = byId('quick-connect-button');
    refs.quickConnectCode = byId('quick-connect-code');
    refs.quickConnectStatus = byId('quick-connect-status');
    refs.quickConnectCancel = byId('quick-connect-cancel');
    refs.plexLoginBack = byId('plex-login-back');
    refs.plexLoginCode = byId('plex-login-code');
    refs.plexLoginStatus = byId('plex-login-status');
    refs.plexLoginRetry = byId('plex-login-retry');
    refs.plexQr = byId('plex-qr');
    refs.plexServerBack = byId('plex-server-back');
    refs.plexServerList = byId('plex-server-list');
    refs.plexServerForm = byId('plex-server-form');
    refs.plexServerUrl = byId('plex-server-url');
    refs.plexServerUrlField = byId('plex-server-url-field');
    refs.plexServerUrlState = byId('plex-server-url-state');
    refs.plexServerSubmit = byId('plex-server-submit');
    refs.tokenLoginBack = byId('token-login-back');
    refs.tokenLoginForm = byId('token-login-form');
    refs.tokenServerUrl = byId('token-server-url');
    refs.tokenServerUrlField = byId('token-server-url-field');
    refs.tokenServerUrlState = byId('token-server-url-state');
    refs.serverToken = byId('server-token');
    refs.serverTokenField = byId('server-token-field');
    refs.serverTokenState = byId('server-token-state');
    refs.serverUserId = byId('server-user-id');
    refs.tokenSubmit = byId('token-submit');
    refs.userIdField = byId('user-id-field');
    refs.libraryBrowseGrid = byId('library-browse-grid');
    refs.searchForm = byId('search-form');
    refs.searchInput = byId('search-input');
    refs.searchGrid = byId('search-grid');
    refs.searchEmpty = byId('search-empty');
    refs.homeHubs = byId('home-hubs');
    refs.homePreviewRows = byId('home-preview-rows');
    refs.homeBackdrop = byId('home-backdrop');
    refs.homeHeroTitle = byId('home-hero-title');
    refs.homeHeroMeta = byId('home-hero-meta');
    refs.homeHeroSummary = byId('home-hero-summary');
    refs.itemGrid = byId('item-grid');
    refs.itemsBackdrop = byId('items-backdrop');
    refs.itemsSort = byId('items-sort');
    refs.itemsTitle = byId('items-title');
    refs.breadcrumb = byId('breadcrumb');
    refs.emptyState = byId('empty-state');
    refs.detailBackdrop = byId('detail-backdrop');
    refs.detailTitle = byId('detail-title');
    refs.detailMeta = byId('detail-meta');
    refs.detailSummary = byId('detail-summary');
    refs.detailPrimaryAction = byId('detail-primary-action');
    refs.detailRestart = byId('detail-restart');
    refs.detailRelated = byId('detail-related');
    refs.detailRelatedTitle = byId('detail-related-title');
    refs.detailChildRail = byId('detail-child-rail');
    refs.playerView = byId('player-view');
    refs.video = byId('video');
    refs.playerMessage = byId('player-message');
    refs.playerOsd = byId('player-osd');
    refs.playerClose = byId('player-close');
    refs.playerRewind = byId('player-rewind');
    refs.playerToggle = byId('player-toggle');
    refs.playerForward = byId('player-forward');
    refs.playerFit = byId('player-fit');
    refs.playerTitle = byId('player-title');
    refs.playbackMode = byId('playback-mode');
    refs.currentTime = byId('current-time');
    refs.duration = byId('duration');
    refs.timeline = byId('timeline');
    refs.playbackSettingsButton = byId('playback-settings-button');
    refs.playbackSettingsSummary = byId('playback-settings-summary');
    refs.playbackQuality = byId('playback-quality');
    refs.playbackSeekSeconds = byId('playback-seek-seconds');
    refs.playbackResume = byId('playback-resume');
    refs.playbackRewindOnResume = byId('playback-rewind-on-resume');
    refs.playbackVideoFit = byId('playback-video-fit');
    refs.modalBackdrop = byId('modal-backdrop');
    refs.reorderList = byId('reorder-list');
    refs.subtitleUrl = byId('subtitle-url');
    refs.subtitleFile = byId('subtitle-file');
    refs.fontFile = byId('font-file');
    refs.fontUrl = byId('font-url');
    refs.subtitleFontFamily = byId('subtitle-font-family');
    refs.subtitleOrientation = byId('subtitle-orientation');
    refs.subtitleSize = byId('subtitle-size');
    refs.subtitleX = byId('subtitle-x');
    refs.subtitleY = byId('subtitle-y');
    refs.subtitleLineHeight = byId('subtitle-line-height');
    refs.subtitleTrack = byId('subtitle-track');
    refs.subtitleTrackStatus = byId('subtitle-track-status');
    refs.subtitlePreview = byId('subtitle-preview');
    refs.subtitleSizeValue = byId('subtitle-size-value');
    refs.subtitleDirectionValue = byId('subtitle-direction-value');
    refs.subtitleOffset = byId('subtitle-offset');
    refs.subtitleOffsetValue = byId('subtitle-offset-value');
  }

  function initializeControllers() {
    if (!global.ShyniFlexWebOS || !global.ShyniFlexWebOS.Player || !global.ShyniFlexWebOS.Subtitles || !global.ShyniFlexWebOS.Remote) {
      throw new Error('webOS 재생 모듈을 불러오지 못했습니다.');
    }
    state.player = new global.ShyniFlexWebOS.Player(refs.video, {
      seekStep: state.playbackSettings.seekSeconds,
      startupTimeout: 12000,
      onProgress: updateProgressUi,
      onError: function (error) {
        var failedPlayback = state.playback;
        var failedSession = failedPlayback && failedPlayback.session;
        if (failedSession && failedSession.serverType === 'shyni' &&
            failedSession.playMethod === 'DirectPlay' && !failedSession.compatibilityFallbackStarted) {
          failedSession.compatibilityFallbackStarted = true;
          setPlayerError('');
          setNetworkStatus('', false);
          state.playerState = 'idle';
          playItem(failedPlayback.item, false, true).catch(function () {});
          return;
        }
        if (state.subtitles) { state.subtitles.clear(); }
        setPlayerError('재생 오류: ' + errorMessage(error));
        setNetworkStatus('재생 오류', true);
      },
      onStateChange: function (playState, details) {
        if (playState === 'starting') {
          return;
        }
        if (refs.playerToggle) {
          refs.playerToggle.setAttribute('data-state', playState === 'playing' ? 'playing' : 'paused');
          refs.playerToggle.setAttribute('aria-label', playState === 'playing' ? '일시정지' : '재생');
        }
        var previousPlayerState = state.playerState;
        state.playerState = playState;
        refs.playerView.setAttribute('data-playback-state', playState);
        // HLS segment boundaries can briefly emit `waiting` on older webOS TVs.
        // Keep transient buffering silent so the status message does not flicker.
        if (state.playback && state.playback.session && (details.attempt === 'hls-native' || details.attempt === 'hls-js')) {
          if (state.playback.session.playMethod === 'DirectPlay') { state.playback.session.playMethod = 'Transcode'; }
          refs.playbackMode.textContent = state.playback.session.deliveryMode === 'hls-mpegts' ? 'Shyni TV 호환 HLS' :
            state.playback.session.playMethod === 'DirectStream' ? '호환 스트리밍 · 영상 원본 유지' : '호환 변환 · H.264/AAC';
        }
        if (playState === 'playing') {
          setNetworkStatus('재생 중', false);
          setPlayerError('');
          showOsd(false);
          if (!state.playbackStartReported || previousPlayerState === 'paused') {
            details.progress.eventName = previousPlayerState === 'paused' ? 'Unpause' : 'TimeUpdate';
            reportPlayback('playing', details.progress, true);
          }
        }
        if (playState === 'paused') {
          showOsd(true);
          reportPlayback('paused', details.progress, true);
        }
      },
      onEnded: function () {
        closePlayer(true);
      }
    });
    state.subtitles = new global.ShyniFlexWebOS.Subtitles(refs.video, {
      container: refs.playerView,
      overlay: byId('subtitle-overlay'),
      mode: 'horizontal',
      x: 50,
      y: 82,
      fontSize: 46,
      lineSpacing: 1.25,
      fallbackFont: 'Shyni Hangul, Arial, sans-serif',
      fontFamily: SUBTITLE_FONT.defaultFont,
      onPositionChange: persistSubtitleSettings,
      onAdjustmentModeChange: function (enabled) {
        if (!enabled) {
          persistSubtitleSettings();
          showOsd(false);
          showToast('자막 위치를 저장했습니다.');
        }
      }
    });
    state.remote = new global.ShyniFlexWebOS.Remote({
      root: global.document,
      player: state.player,
      subtitles: state.subtitles,
      repeatNavigation: true,
      canRepeatNavigation: function (element) {
        return Boolean(element && !state.currentModal && refs.playerView.classList.contains('hidden') &&
          (element.getAttribute('data-card-kind') || refs.sideNavigation.contains(element)));
      },
      isRoot: isRoot,
      onBack: handleBack,
      onNavigateBack: handleBack,
      getFocusRoot: function () {
        if (state.currentModal) { return byId(state.currentModal); }
        if (!refs.playerView.classList.contains('hidden')) { return refs.playerView; }
        return byId(state.currentView);
      },
      allowSidebar: function () {
        return !state.currentModal && refs.playerView.classList.contains('hidden') && state.currentView !== 'setup-view';
      },
      onUnhandledKey: function (code) {
        if (!refs.playerView.classList.contains('hidden') && (code === 13 || code === 37 || code === 38 || code === 39 || code === 40)) {
          showOsd(false);
          focusInitial();
          return true;
        }
        return false;
      }
    });
    state.remote.addKeyHandler(function (event, code) {
      var isPlayerOpen = !refs.playerView.classList.contains('hidden');
      var isDirectionOrOk = code === 13 || code === 37 || code === 38 || code === 39 || code === 40;
      if (isPlayerOpen && !state.currentModal && !state.subtitles.adjustmentMode && isDirectionOrOk && !refs.playerOsd.classList.contains('visible')) {
        showOsd(false);
        global.setTimeout(focusInitial, 0);
        return true;
      }
      return false;
    }, true);
  }

  function start() {
    cacheRefs();
    detectDeviceCapabilities();
    updateSystemClock();
    global.setInterval(updateSystemClock, 30000);
    initializeControllers();
    restorePlaybackSettings();
    bindForms();
    bindTvNavigation();
    bindPlaybackLifecycle();
    global.document.addEventListener('click', handleClick, false);
    restoreSubtitleSettings();
    setNetworkStatus('연결 필요', false);

    var saved = savedConnection();
    if (saved && (saved.url || saved.baseUrl || saved.serverUrl) && (saved.token || saved.accessToken || saved.apiKey)) {
      var startupAttemptToken = state.connectionAttemptToken + 1;
      state.connectionAttemptToken = startupAttemptToken;
      setConnectionFormBusy(true);
    refs.plexLoginStatus.textContent = '저장된 Shyni 서버에 연결 중입니다…';
      showAuthStep('plex-login-step', refs.plexLoginBack);
      refs.plexQr.parentNode.classList.add('hidden');
      refs.plexLoginRetry.classList.add('hidden');
      byId('saved-connection-actions').classList.add('hidden');
      byId('plex-login-title').textContent = '로그인 유지 중';
      connect(saved, shouldSkipSavedConnectionTest(saved), startupAttemptToken).then(function (result) {
        if (startupAttemptToken !== state.connectionAttemptToken || result === null) { return; }
        setConnectionFormBusy(false);
      }).catch(function (error) {
        if (startupAttemptToken !== state.connectionAttemptToken) { return; }
        showSavedConnectionRetry(error);
      });
    } else {
      showView('setup-view');
      beginMediaLogin('shyni');
    }
  }

  function showSavedConnectionRetry(error) {
    setConnectionFormBusy(false);
    showView('setup-view');
    showAuthStep('plex-login-step', byId('saved-connection-retry'));
    refs.plexQr.parentNode.classList.add('hidden');
    refs.plexLoginRetry.classList.add('hidden');
    byId('saved-connection-actions').classList.remove('hidden');
    syncAuthControls();
    byId('plex-login-title').textContent = '저장된 연결 확인';
    refs.plexLoginStatus.textContent = '로그인은 유지되어 있습니다. 서버 연결을 확인한 뒤 다시 시도하세요.';
    refs.connectionError.textContent = error && (error.status === 401 || error.status === 403)
      ? 'Shyni 로그인 또는 서버 접근 권한을 확인해 주세요. 필요한 경우 다른 계정으로 연결할 수 있습니다.'
      : '미디어 서버에 연결하지 못했습니다.' + (error && error.status ? ' (HTTP ' + error.status + ')' : '');
    focusInitial(byId('saved-connection-retry'));
  }

  function retrySavedConnection() {
    var saved = savedConnection();
    if (!saved || state.authBusy) { return; }
    setConnectionFormBusy(true);
    refs.connectionError.textContent = '';
    refs.plexLoginStatus.textContent = '저장된 서버에 다시 연결하는 중입니다…';
    var attempt = state.connectionAttemptToken + 1;
    state.connectionAttemptToken = attempt;
    connect(saved, shouldSkipSavedConnectionTest(saved), attempt).then(function (result) {
      if (state.connectionAttemptToken !== attempt || result === null) { return; }
      setConnectionFormBusy(false);
      byId('saved-connection-actions').classList.add('hidden');
    }).catch(function (error) { if (state.connectionAttemptToken === attempt) { showSavedConnectionRetry(error); } });
  }

  function safeStart() {
    try {
      start();
      if (global.ShyniFlexBoot && typeof global.ShyniFlexBoot.ready === 'function') {
        global.ShyniFlexBoot.ready();
      }
    } catch (error) {
      if (refs.setupView && refs.setupView.classList) {
        refs.setupView.classList.remove('hidden');
      }
      if (global.ShyniFlexBoot && typeof global.ShyniFlexBoot.fail === 'function') {
        global.ShyniFlexBoot.fail(error);
        return;
      }
      throw error;
    }
  }

  global.ShyniFlexWebOSApp = {
    version: APP_VERSION,
    normalizeServerUrl: normalizeServerUrl,
    shouldSkipSavedConnectionTest: shouldSkipSavedConnectionTest,
    supportsPasswordAuthentication: supportsPasswordAuthentication,
    authenticationModeForServer: authenticationModeForServer,
    connectionConfigWithoutCredentials: connectionConfigWithoutCredentials,
    savedConnection: savedConnection,
    eagerArtworkCount: eagerArtworkCount,
    shouldReportPlaybackProgress: shouldReportPlaybackProgress,
    loadCardArtwork: loadCardArtwork,
    renderCardGrid: renderCardGrid,
    subtitleFontFamilyForPreset: subtitleFontFamilyForPreset,
    subtitleFontPresetForFamily: subtitleFontPresetForFamily,
    normalizePlaybackSettings: normalizePlaybackSettings,
    playbackStartTimeForSettings: playbackStartTimeForSettings,
    playbackOptionsForCapabilities: playbackOptionsForCapabilities,
    isLegacyTv: isLegacyTv,
    supportedSubtitle: supportedSubtitle,
    normalizePlayback: normalizePlayback,
    applyUhdUiClass: applyUhdUiClass,
    applyDeviceCapabilities: applyDeviceCapabilities,
    detectDeviceCapabilities: detectDeviceCapabilities,
    entityId: entityId,
    entityTitle: entityTitle,
    entityProgress: entityProgress,
    entityPlayed: entityPlayed,
    completedBadgeLabel: completedBadgeLabel,
    updateEpisodeCompletion: updateEpisodeCompletion,
    homeHubRowKind: homeHubRowKind,
    normalizeServerHomeRows: normalizeServerHomeRows,
    composeFallbackHomeRows: composeFallbackHomeRows,
    mergeHomeRows: mergeHomeRows,
    needsHomeRecentFallback: needsHomeRecentFallback,
    wireSideNavigationItems: wireSideNavigationItems,
    nextSideNavigationItem: nextSideNavigationItem,
    start: start
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = global.ShyniFlexWebOSApp;
  }
  if (!global.document) {
    return;
  }
  if (global.document.readyState === 'loading') {
    global.document.addEventListener('DOMContentLoaded', safeStart, false);
  } else {
    safeStart();
  }
})(typeof window !== 'undefined' ? window : this);
