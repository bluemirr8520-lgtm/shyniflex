(function (global) {
  'use strict';

  var APP_VERSION = '1.0.1';
  var PRODUCT = 'ShyniFlex webOS';
  var PLEX_TV_URL = 'https://plex.tv';
  var PLEX_AUTH_URL = 'https://plex.tv/link/';
  var CONNECTIONS_KEY = 'shyniflex.webos.connections.v1';
  var ACTIVE_KEY = 'shyniflex.webos.active-connection.v1';
  var CLIENT_KEY = 'shyniflex.webos.device-id';
  var memoryStore = {};

  function ApiError(message, status, path, code) {
    this.name = 'ApiError';
    this.message = message || 'Media server request failed';
    this.status = Number(status) || 0;
    this.path = safePath(path);
    this.code = code || 'request_failed';
    if (Error.captureStackTrace) {
      Error.captureStackTrace(this, ApiError);
    }
  }
  ApiError.prototype = Object.create(Error.prototype);
  ApiError.prototype.constructor = ApiError;

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

  function bool(value) {
    return value === true || value === 1 || value === '1' || value === 'true';
  }

  function array(value) {
    if (Array.isArray(value)) { return value; }
    if (value === null || typeof value === 'undefined') { return []; }
    return [value];
  }

  function first(source, keys, fallback) {
    var i;
    source = source || {};
    for (i = 0; i < keys.length; i += 1) {
      if (source[keys[i]] !== null && typeof source[keys[i]] !== 'undefined') {
        return source[keys[i]];
      }
    }
    return fallback;
  }

  function randomId() {
    var seed = String(Date.now()) + String(Math.random()).slice(2);
    return 'shyniflex-webos-' + seed.slice(0, 8) + '-' + seed.slice(8, 20);
  }

  function localGet(key, fallback) {
    try {
      if (global.localStorage) {
        var raw = global.localStorage.getItem(key);
        return raw ? JSON.parse(raw) : fallback;
      }
    } catch (ignore) {
      // Tests and privacy-restricted engines use the in-memory fallback.
    }
    return Object.prototype.hasOwnProperty.call(memoryStore, key) ? memoryStore[key] : fallback;
  }

  function localSet(key, value) {
    try {
      if (global.localStorage) {
        global.localStorage.setItem(key, JSON.stringify(value));
        return;
      }
    } catch (ignore) {
      // Preserve the value for this process if persistent storage is unavailable.
    }
    memoryStore[key] = value;
  }

  function localRemove(key) {
    try {
      if (global.localStorage) { global.localStorage.removeItem(key); }
    } catch (ignore) {
      // The memory copy is still cleared below.
    }
    delete memoryStore[key];
  }

  function clientIdentifier() {
    var existing = localGet(CLIENT_KEY, '');
    if (existing) { return String(existing); }
    var created = randomId();
    localSet(CLIENT_KEY, created);
    return created;
  }

  function trimServerUrl(value) {
    var result = text(value).trim();
    while (result.length > 1 && result.charAt(result.length - 1) === '/') {
      result = result.slice(0, -1);
    }
    if (!/^https?:\/\//i.test(result)) {
      throw new ApiError('Server URL must begin with http:// or https://', 0, result, 'invalid_server_url');
    }
    return result;
  }

  function normalizeConnection(input) {
    input = input || {};
    var type = text(input.type || input.kind || input.serverType).toLowerCase();
    if (['shyni', 'plex', 'jellyfin', 'emby'].indexOf(type) < 0) {
      throw new ApiError('지원하지 않는 서버 종류입니다.', 0, '', 'invalid_server_type');
    }
    var token = text(input.token || input.accessToken || input.apiKey);
    if (!token) {
      throw new ApiError('An access token or API key is required', 0, '', 'missing_token');
    }
    var baseUrl = trimServerUrl(input.baseUrl || input.url || input.serverUrl);
    return {
      id: text(input.id, type + '-' + baseUrl.replace(/[^a-z0-9]+/gi, '-').toLowerCase()),
      name: text(input.name || input.serverName, baseUrl),
      type: type,
      baseUrl: baseUrl,
      url: baseUrl,
      token: token,
      userId: text(input.userId),
      clientIdentifier: text(input.clientIdentifier || input.clientId, clientIdentifier())
    };
  }

  function mediaBrowserAuthorization(type, identifier, accessToken) {
    var value = 'MediaBrowser Client="' + encodeURIComponent(PRODUCT) + '", Device="' +
      encodeURIComponent('LG webOS TV') + '", DeviceId="' + encodeURIComponent(identifier) +
      '", Version="' + encodeURIComponent(APP_VERSION) + '"';
    if (accessToken) {
      value += ', Token="' + encodeURIComponent(accessToken) + '"';
    }
    return value;
  }

  function anonymousJsonRequest(method, url, options) {
    options = options || {};
    if (!global.XMLHttpRequest || !global.Promise) {
      return global.Promise.reject(
        new ApiError('이 TV의 웹 엔진은 서버 로그인을 지원하지 않습니다.', 0, url, 'unsupported_engine')
      );
    }
    return new global.Promise(function (resolve, reject) {
      var xhr = new global.XMLHttpRequest();
      var headers = options.headers || {};
      var completed = false;
      var body = null;
      function fail(message, code) {
        if (completed) { return; }
        completed = true;
        reject(new ApiError(message, 0, url, code));
      }
      xhr.open(method || 'GET', url, true);
      xhr.timeout = options.timeout || 12000;
      Object.keys(headers).forEach(function (key) {
        try { xhr.setRequestHeader(key, headers[key]); } catch (ignore) { /* Old engines may block optional headers. */ }
      });
      xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4 || completed) { return; }
        if (!xhr.status) {
          fail(options.networkMessage || '서버에 연결할 수 없습니다.', 'network_error');
          return;
        }
        completed = true;
        resolve({
          status: Number(xhr.status) || 0,
          data: parseBody(xhr.responseText || '', xhr.getResponseHeader('content-type') || ''),
          raw: xhr.responseText || ''
        });
      };
      xhr.onerror = function () {
        fail(options.networkMessage || '서버에 연결할 수 없습니다.', 'network_error');
      };
      xhr.ontimeout = function () {
        fail(options.timeoutMessage || '서버 응답 시간이 초과되었습니다.', 'timeout');
      };
      if (Object.prototype.hasOwnProperty.call(options, 'body')) {
        body = options.body;
        if (body !== null && typeof body !== 'string') {
          body = JSON.stringify(body);
        }
      }
      try {
        xhr.send(body);
      } catch (sendError) {
        fail(options.sendMessage || '서버 요청을 보내지 못했습니다.', 'send_failed');
      }
    });
  }

  function trimCandidateUrl(value) {
    var result = text(value).trim();
    while (result.length > 1 && result.charAt(result.length - 1) === '/') {
      result = result.slice(0, -1);
    }
    return result;
  }

  function buildMediaBrowserCandidates(value, type) {
    var values = text(value).split(/[\r\n,;]+/);
    var candidates = [];
    var seen = {};
    var requestedType = text(type, 'jellyfin').toLowerCase() === 'emby' ? 'emby' : 'jellyfin';
    function add(candidate) {
      candidate = trimCandidateUrl(candidate);
      if (!candidate || seen[candidate.toLowerCase()]) { return; }
      if (!/^https?:\/\//i.test(candidate)) { return; }
      seen[candidate.toLowerCase()] = true;
      candidates.push(candidate);
    }
    values.forEach(function (entry) {
      var raw = text(entry).trim().replace(/^\/\//, '');
      var slash;
      var host;
      var path;
      var hasPort;
      if (!raw) { return; }
      if (/^https?:\/\//i.test(raw)) {
        add(raw);
        return;
      }
      slash = raw.indexOf('/');
      host = slash >= 0 ? raw.slice(0, slash) : raw;
      path = slash >= 0 ? raw.slice(slash) : '';
      hasPort = /:\d+$/.test(host) || /^\[[^\]]+\]:\d+$/.test(host);
      if (hasPort) {
        add('http://' + host + path);
        add('https://' + host + path);
        return;
      }
      add('http://' + host + ':8096' + path);
      add('https://' + host + path);
      if (requestedType === 'emby') {
        add('https://' + host + ':8920' + path);
      }
      add('https://' + host + ':8096' + path);
      add('http://' + host + path);
    });
    return candidates;
  }

  function mediaBrowserType(data, requestedType) {
    var product = text(data && (data.ProductName || data.productName)).toLowerCase();
    if (product.indexOf('emby') >= 0) { return 'emby'; }
    if (product.indexOf('jellyfin') >= 0) { return 'jellyfin'; }
    if (data && Object.prototype.hasOwnProperty.call(data, 'RemoteAddresses')) { return 'emby'; }
    return requestedType === 'emby' ? 'emby' : 'jellyfin';
  }

  function probeMediaBrowserCandidate(baseUrl, requestedType) {
    var path = '/System/Info/Public';
    return anonymousJsonRequest('GET', joinUrl(baseUrl, path), {
      headers: { Accept: 'application/json' },
      timeout: 8000,
      networkMessage: '미디어 서버에 연결할 수 없습니다.',
      timeoutMessage: '미디어 서버 확인 시간이 초과되었습니다.'
    }).then(function (response) {
      var data = response.data;
      var serverId;
      var serverName;
      var detectedType;
      if (response.status < 200 || response.status >= 300) {
        throw new ApiError('서버 확인 요청이 HTTP ' + response.status + ' 오류로 거부되었습니다.', response.status, path, 'probe_http_error');
      }
      if (!data || typeof data !== 'object' || Array.isArray(data)) {
        throw new ApiError('서버 확인 응답이 올바른 JSON 형식이 아닙니다.', 0, path, 'invalid_probe_response');
      }
      serverId = text(data.Id || data.id).trim();
      serverName = text(data.ServerName || data.serverName || data.LocalAddress || data.localAddress).trim();
      if (!serverId || !serverName) {
        throw new ApiError('서버 확인 응답에 서버 ID 또는 이름이 없습니다.', 0, path, 'invalid_server_identity');
      }
      detectedType = mediaBrowserType(data, requestedType);
      return {
        type: detectedType,
        kind: detectedType,
        baseUrl: baseUrl,
        url: baseUrl,
        name: serverName,
        serverName: serverName,
        serverId: serverId,
        version: text(data.Version || data.version),
        productName: text(data.ProductName || data.productName, detectedType === 'emby' ? 'Emby' : 'Jellyfin'),
        raw: data
      };
    });
  }

  function probeMediaBrowser(input) {
    input = input || {};
    var requestedType = text(input.type || input.kind || input.serverType, 'jellyfin').toLowerCase();
    if (requestedType === 'shyni') {
      var shyniBase;
      try { shyniBase = trimServerUrl(input.url || input.baseUrl || input.serverUrl); }
      catch (shyniValidationError) { return global.Promise.reject(shyniValidationError); }
      return anonymousJsonRequest('GET', joinUrl(shyniBase, '/auth/me'), {
        headers: { Accept: 'application/json', 'X-Device-Id': clientIdentifier() },
        timeout: 10000,
        networkMessage: 'Shyni 서버에 연결할 수 없습니다. 주소와 CORS 설정을 확인하세요.'
      }).then(function (response) {
        if ([200, 401, 403].indexOf(response.status) < 0) {
          throw new ApiError('Shyni 서버 확인 요청이 거부되었습니다. (HTTP ' + response.status + ')', response.status, '/auth/me', 'probe_http_error');
        }
        return { type: 'shyni', kind: 'shyni', baseUrl: shyniBase, url: shyniBase,
          name: 'Shyni 서버', serverName: 'Shyni 서버', serverId: shyniBase };
      });
    }
    var candidates = buildMediaBrowserCandidates(input.url || input.baseUrl || input.serverUrl, requestedType);
    if (requestedType !== 'jellyfin' && requestedType !== 'emby') {
      return global.Promise.reject(new ApiError('Jellyfin 또는 Emby 서버를 선택하세요.', 0, '', 'invalid_server_type'));
    }
    if (!candidates.length) {
      return global.Promise.reject(new ApiError('서버 주소를 입력하세요.', 0, '', 'invalid_server_url'));
    }
    return new global.Promise(function (resolve, reject) {
      var settled = false;
      var remaining = candidates.length;
      var lastError = null;
      candidates.forEach(function (candidate) {
        probeMediaBrowserCandidate(candidate, requestedType).then(function (result) {
          if (settled) { return; }
          settled = true;
          resolve(result);
        }).catch(function (error) {
          lastError = error;
          remaining -= 1;
          if (!settled && remaining === 0) {
            settled = true;
            reject(new ApiError(
              '서버를 찾을 수 없습니다. 주소와 서버의 원격 접속 설정을 확인하세요.',
              lastError && lastError.status,
              '',
              'server_probe_failed'
            ));
          }
        });
      });
    });
  }

  function mediaBrowserAnonymousHeaders(type, identifier) {
    var authorization = mediaBrowserAuthorization(type, identifier);
    return {
      Accept: 'application/json',
      Authorization: authorization,
      'X-Emby-Authorization': authorization
    };
  }

  function quickConnectInput(input) {
    input = input || {};
    var type = text(input.type || input.kind || input.serverType, 'jellyfin').toLowerCase();
    return {
      type: type,
      baseUrl: trimServerUrl(input.baseUrl || input.url || input.serverUrl),
      clientIdentifier: text(input.clientIdentifier || input.clientId, clientIdentifier()),
      secret: text(input.secret)
    };
  }

  function isQuickConnectEnabled(input) {
    var config;
    try { config = quickConnectInput(input); } catch (error) { return global.Promise.resolve(false); }
    if (config.type !== 'jellyfin') { return global.Promise.resolve(false); }
    return anonymousJsonRequest('GET', joinUrl(config.baseUrl, '/QuickConnect/Enabled'), {
      headers: mediaBrowserAnonymousHeaders(config.type, config.clientIdentifier),
      timeout: 8000
    }).then(function (response) {
      return response.status >= 200 && response.status < 300 && response.data === true;
    }).catch(function () { return false; });
  }

  function initiateQuickConnect(input) {
    var config;
    try { config = quickConnectInput(input); } catch (error) { return global.Promise.reject(error); }
    if (config.type !== 'jellyfin') {
      return global.Promise.reject(new ApiError('Quick Connect는 Jellyfin에서만 지원됩니다.', 0, '', 'quick_connect_not_supported'));
    }
    var path = '/QuickConnect/Initiate';
    var headers = mediaBrowserAnonymousHeaders(config.type, config.clientIdentifier);
    function validate(response) {
      var data = response.data;
      var code;
      var secret;
      if (response.status < 200 || response.status >= 300) {
        throw new ApiError('Quick Connect 시작 요청이 거부되었습니다.', response.status, path, 'quick_connect_initiate_failed');
      }
      code = text(data && (data.Code || data.code)).trim();
      secret = text(data && (data.Secret || data.secret));
      if (!code || !secret) {
        throw new ApiError('Quick Connect 응답에 코드가 없습니다.', 0, path, 'invalid_quick_connect_response');
      }
      return {
        type: config.type,
        baseUrl: config.baseUrl,
        clientIdentifier: config.clientIdentifier,
        code: code,
        secret: secret
      };
    }
    return anonymousJsonRequest('GET', joinUrl(config.baseUrl, path), {
      headers: headers,
      timeout: 10000
    }).then(function (response) {
      if (response.status === 405) {
        return anonymousJsonRequest('POST', joinUrl(config.baseUrl, path), {
          headers: headers,
          body: null,
          timeout: 10000
        }).then(validate);
      }
      return validate(response);
    });
  }

  function checkQuickConnect(input) {
    var config;
    try { config = quickConnectInput(input); } catch (error) { return global.Promise.reject(error); }
    if (config.type !== 'jellyfin' || !config.secret) {
      return global.Promise.reject(new ApiError('Quick Connect 요청 정보가 없습니다.', 0, '', 'missing_quick_connect_secret'));
    }
    var path = '/QuickConnect/Connect';
    var url = appendQuery(joinUrl(config.baseUrl, path), { secret: config.secret });
    return anonymousJsonRequest('GET', url, {
      headers: mediaBrowserAnonymousHeaders(config.type, config.clientIdentifier),
      timeout: 10000
    }).then(function (response) {
      if (response.status === 404 || response.status === 410) {
        return { authenticated: false, expired: true };
      }
      if (response.status === 401 || response.status === 403) {
        throw new ApiError('Quick Connect 승인이 거부되었습니다.', response.status, path, 'quick_connect_rejected');
      }
      if (response.status < 200 || response.status >= 300) {
        throw new ApiError('Quick Connect 확인 요청이 실패했습니다.', response.status, path, 'quick_connect_poll_failed');
      }
      return { authenticated: Boolean(response.data && (response.data.Authenticated || response.data.authenticated)), expired: false };
    });
  }

  function authenticateWithQuickConnect(input) {
    input = input || {};
    var config;
    try { config = quickConnectInput(input); } catch (error) { return global.Promise.reject(error); }
    if (config.type !== 'jellyfin' || !config.secret) {
      return global.Promise.reject(new ApiError('Quick Connect 요청 정보가 없습니다.', 0, '', 'missing_quick_connect_secret'));
    }
    var path = '/Users/AuthenticateWithQuickConnect';
    var headers = mediaBrowserAnonymousHeaders(config.type, config.clientIdentifier);
    headers['Content-Type'] = 'application/json';
    return anonymousJsonRequest('POST', joinUrl(config.baseUrl, path), {
      headers: headers,
      body: { Secret: config.secret },
      timeout: 15000
    }).then(function (response) {
      var data = response.data;
      var token;
      var user;
      var userId;
      var userName;
      if (response.status === 400 || response.status === 401 || response.status === 403) {
        throw new ApiError('Quick Connect 로그인이 거부되었습니다.', response.status, path, 'quick_connect_rejected');
      }
      if (response.status < 200 || response.status >= 300) {
        throw new ApiError('Quick Connect 로그인 요청이 실패했습니다.', response.status, path, 'quick_connect_auth_failed');
      }
      token = text(data && (data.AccessToken || data.accessToken || data.access_token));
      user = data && (data.User || data.user);
      userId = text(user && (user.Id || user.id));
      userName = text(user && (user.Name || user.name));
      if (!token || !userId || !userName) {
        throw new ApiError('Quick Connect 로그인 응답이 올바르지 않습니다.', 0, path, 'invalid_auth_response');
      }
      return {
        id: config.type + '-' + config.baseUrl.replace(/[^a-z0-9]+/gi, '-').toLowerCase(),
        kind: config.type,
        type: config.type,
        serverType: config.type,
        name: text(input.serverName, config.baseUrl),
        serverName: text(input.serverName, config.baseUrl),
        baseUrl: config.baseUrl,
        url: config.baseUrl,
        serverUrl: config.baseUrl,
        token: token,
        accessToken: token,
        apiKey: token,
        userId: userId,
        userName: userName,
        serverId: text(input.serverId),
        clientIdentifier: config.clientIdentifier,
        clientId: config.clientIdentifier,
        authSource: 'quick_connect'
      };
    });
  }

  function plexHeaders(identifier, token) {
    var headers = {
      Accept: 'application/json',
      'X-Plex-Product': PRODUCT,
      'X-Plex-Version': APP_VERSION,
      'X-Plex-Platform': 'webOS',
      'X-Plex-Device': 'LG TV',
      'X-Plex-Device-Name': 'LG webOS TV',
      'X-Plex-Client-Identifier': identifier
    };
    if (token) { headers['X-Plex-Token'] = token; }
    return headers;
  }

  function getPlexAuthUrl(code) {
    return PLEX_AUTH_URL + '?pin=' + encodeURIComponent(text(code));
  }

  function createPlexPin(input) {
    input = input || {};
    var identifier = text(input.clientIdentifier || input.clientId, clientIdentifier());
    var path = '/api/v2/pins';
    var url = PLEX_TV_URL + path;
    return anonymousJsonRequest('POST', url, {
      headers: plexHeaders(identifier),
      body: null,
      timeout: 15000,
      networkMessage: 'Plex 로그인 서버에 연결할 수 없습니다.'
    }).then(function (response) {
      var data = response.data;
      var id;
      var code;
      if (response.status < 200 || response.status >= 300) {
        throw new ApiError('Plex 인증 코드 요청이 거부되었습니다.', response.status, path, 'plex_pin_create_failed');
      }
      id = text(data && (data.id || data.Id));
      code = text(data && (data.code || data.Code)).trim();
      if (!id || !/^[A-Za-z0-9]{4}$/.test(code)) {
        throw new ApiError('Plex 인증 코드 응답이 올바르지 않습니다.', 0, path, 'invalid_plex_pin_response');
      }
      code = code.toUpperCase();
      return {
        id: id,
        code: code,
        expiresAt: text(data && (data.expiresAt || data.expires_at)),
        clientIdentifier: identifier,
        authUrl: getPlexAuthUrl(code)
      };
    });
  }

  function checkPlexPin(input) {
    input = input || {};
    var id = text(input.id).trim();
    var identifier = text(input.clientIdentifier || input.clientId, clientIdentifier());
    if (!id) {
      return global.Promise.reject(new ApiError('Plex 인증 코드 정보가 없습니다.', 0, '', 'missing_plex_pin'));
    }
    var path = '/api/v2/pins/' + encodeURIComponent(id);
    return anonymousJsonRequest('GET', PLEX_TV_URL + path, {
      headers: plexHeaders(identifier),
      timeout: 12000,
      networkMessage: 'Plex 승인 상태를 확인할 수 없습니다.'
    }).then(function (response) {
      var token;
      if (response.status === 404 || response.status === 410) {
        throw new ApiError('Plex 인증 코드가 만료되었습니다.', response.status, path, 'plex_pin_expired');
      }
      if (response.status === 401 || response.status === 403) {
        throw new ApiError('Plex 로그인이 거부되었습니다.', response.status, path, 'plex_pin_rejected');
      }
      if (response.status < 200 || response.status >= 300) {
        throw new ApiError('Plex 승인 상태 확인이 실패했습니다.', response.status, path, 'plex_pin_poll_failed');
      }
      token = text(response.data && (response.data.authToken || response.data.auth_token));
      return { claimed: Boolean(token), token: token };
    });
  }

  function getPlexResources(input) {
    input = input || {};
    var token = text(input.token || input.accessToken);
    var identifier = text(input.clientIdentifier || input.clientId, clientIdentifier());
    if (!token) {
      return global.Promise.reject(new ApiError('Plex 로그인 토큰이 없습니다.', 0, '', 'missing_token'));
    }
    var path = '/api/v2/resources';
    var url = appendQuery(PLEX_TV_URL + path, { includeHttps: 1, includeRelay: 1, includeIPv6: 1 });
    return anonymousJsonRequest('GET', url, {
      headers: plexHeaders(identifier, token),
      timeout: 20000,
      networkMessage: 'Plex 서버 목록을 불러올 수 없습니다.'
    }).then(function (response) {
      if (response.status < 200 || response.status >= 300) {
        throw new ApiError('Plex 서버 목록 요청이 거부되었습니다.', response.status, path, 'plex_resources_failed');
      }
      if (!Array.isArray(response.data)) {
        throw new ApiError('Plex 서버 목록 응답이 올바르지 않습니다.', 0, path, 'invalid_plex_resources');
      }
      return response.data.filter(function (resource) {
        return text(resource && resource.provides).split(',').indexOf('server') >= 0;
      }).map(function (resource) {
        var connections = array(resource.connections).map(function (connection) {
          return {
            uri: trimCandidateUrl(connection && connection.uri),
            local: bool(connection && connection.local),
            relay: bool(connection && connection.relay),
            protocol: text(connection && connection.protocol)
          };
        }).filter(function (connection) { return /^https?:\/\//i.test(connection.uri); });
        connections.sort(function (left, right) {
          function score(connection) {
            return (connection.local ? 0 : 20) + (connection.relay ? 40 : 0) + (/^https:/i.test(connection.uri) ? 0 : 2);
          }
          return score(left) - score(right);
        });
        return {
          name: text(resource.name, 'Plex Media Server'),
          clientIdentifier: text(resource.clientIdentifier),
          owned: bool(resource.owned),
          accessToken: text(resource.accessToken, token),
          connections: connections
        };
      }).filter(function (resource) { return resource.connections.length > 0; });
    });
  }

  function shyniAuthResult(input, baseUrl, identifier, data) {
    data = data || {};
    if (bool(data.require_totp)) {
      var challenge = text(data.challenge_token).trim();
      if (!challenge) { throw new ApiError('서버의 2단계 인증 정보가 올바르지 않습니다.', 0, '/auth/login', 'invalid_totp_challenge'); }
      return { requiresTotp: true, challenge: challenge, baseUrl: baseUrl, clientIdentifier: identifier };
    }
    var token = text(data.token).trim();
    if (!token || token.indexOf('cookie:') === 0) {
      throw new ApiError('webOS 앱에는 JSON 로그인 토큰을 반환하는 Shyni 서버가 필요합니다.', 0, '/auth/login', 'missing_webos_token');
    }
    var user = data.user || data;
    return {
      id: 'shyni-' + baseUrl.replace(/[^a-z0-9]+/gi, '-').toLowerCase(),
      kind: 'shyni', type: 'shyni', serverType: 'shyni',
      name: text(input.serverName, baseUrl), serverName: text(input.serverName, baseUrl),
      baseUrl: baseUrl, url: baseUrl, serverUrl: baseUrl,
      token: token, accessToken: token, apiKey: token,
      userId: text(user.id || user.user_id || user.username || user.name),
      userName: text(user.username || user.name, '사용자'),
      clientIdentifier: identifier, clientId: identifier, authSource: 'password'
    };
  }

  function shyniAnonymousPost(baseUrl, path, body, input) {
    return anonymousJsonRequest('POST', joinUrl(baseUrl, path), {
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-Device-Id': input.clientIdentifier },
      body: body,
      timeout: 30000,
      networkMessage: 'Shyni 서버에 연결할 수 없습니다. 주소와 CORS 설정을 확인하세요.',
      timeoutMessage: 'Shyni 서버 로그인 시간이 초과되었습니다.'
    }).then(function (response) {
      var data = response.data || {};
      var detail = data && data.detail;
      var message = typeof detail === 'string' ? detail : detail && (detail.message || detail.reason_text);
      if (response.status < 200 || response.status >= 300) {
        throw new ApiError(message || (response.status === 401 ? '아이디 또는 비밀번호가 올바르지 않습니다.' :
          '로그인 요청이 거부되었습니다. (HTTP ' + response.status + ')'), response.status, path, 'shyni_login_failed');
      }
      return shyniAuthResult(input, baseUrl, input.clientIdentifier, data);
    });
  }

  function authenticateShyni(input) {
    input = input || {};
    var baseUrl;
    var username = text(input.username || input.userName).trim();
    var identifier = text(input.clientIdentifier || input.clientId, clientIdentifier());
    try { baseUrl = trimServerUrl(input.baseUrl || input.url || input.serverUrl); }
    catch (error) { return global.Promise.reject(error); }
    if (!username) { return global.Promise.reject(new ApiError('사용자 아이디를 입력하세요.', 0, '', 'missing_username')); }
    input.clientIdentifier = identifier;
    return shyniAnonymousPost(baseUrl, '/auth/login', {
      username: username,
      password: text(input.password),
      app_name: 'shyniflex-webos',
      device_name: 'LG webOS TV'
    }, input);
  }

  function completeShyniTotp(input) {
    input = input || {};
    var baseUrl;
    var identifier = text(input.clientIdentifier || input.clientId, clientIdentifier());
    try { baseUrl = trimServerUrl(input.baseUrl || input.url || input.serverUrl); }
    catch (error) { return global.Promise.reject(error); }
    var challenge = text(input.challenge).trim();
    var code = text(input.code).trim();
    if (!challenge || !/^\d{6,8}$/.test(code)) {
      return global.Promise.reject(new ApiError('2단계 인증 코드를 확인해 주세요.', 0, '', 'invalid_totp_code'));
    }
    input.clientIdentifier = identifier;
    return shyniAnonymousPost(baseUrl, '/auth/login/totp', {
      challenge_token: challenge,
      code: code,
      trust_device: false
    }, input);
  }

  function authenticateByName(input) {
    input = input || {};
    var type = text(input.type || input.kind || input.serverType).toLowerCase();
    var baseUrl;
    var username;
    var password;
    var identifier;

    if (type === 'shyni') { return authenticateShyni(input); }
    try {
      if (type !== 'jellyfin' && type !== 'emby') {
        throw new ApiError(
          '아이디와 비밀번호 접속은 Jellyfin과 Emby 서버에서 지원됩니다.',
          0,
          '',
          'credentials_not_supported'
        );
      }
      baseUrl = trimServerUrl(input.baseUrl || input.url || input.serverUrl);
      username = text(input.username || input.userName).trim();
      if (!username) {
        throw new ApiError('사용자 아이디를 입력하세요.', 0, '', 'missing_username');
      }
      // Passwordless Jellyfin/Emby users still authenticate with an empty Pw.
      password = text(input.password);
      identifier = text(input.clientIdentifier || input.clientId, clientIdentifier());
    } catch (validationError) {
      return global.Promise.reject(validationError);
    }

    if (!global.XMLHttpRequest || !global.Promise) {
      return global.Promise.reject(
        new ApiError('이 TV의 웹 엔진은 서버 로그인을 지원하지 않습니다.', 0, '', 'unsupported_engine')
      );
    }

    return new global.Promise(function (resolve, reject) {
      var path = '/Users/AuthenticateByName';
      var url = joinUrl(baseUrl, path);
      var authorization = mediaBrowserAuthorization(type, identifier);
      var xhr = new global.XMLHttpRequest();
      xhr.open('POST', url, true);
      xhr.timeout = 30000;
      try {
        xhr.setRequestHeader('Accept', 'application/json');
        xhr.setRequestHeader('Content-Type', 'application/json');
        xhr.setRequestHeader('Authorization', authorization);
        xhr.setRequestHeader('X-Emby-Authorization', authorization);
      } catch (headerError) {
        reject(new ApiError('서버 로그인 헤더를 설정하지 못했습니다.', 0, path, 'header_setup_failed'));
        return;
      }
      xhr.onreadystatechange = function () {
        var data;
        var token;
        var user;
        var userId;
        if (xhr.readyState !== 4) { return; }
        if (xhr.status === 400 || xhr.status === 401 || xhr.status === 403) {
          reject(new ApiError('아이디 또는 비밀번호가 올바르지 않습니다.', xhr.status, path, 'invalid_credentials'));
          return;
        }
        if (xhr.status < 200 || xhr.status >= 300) {
          reject(new ApiError('로그인 요청이 HTTP ' + xhr.status + ' 오류로 거부되었습니다.', xhr.status, path, 'http_error'));
          return;
        }
        data = parseBody(xhr.responseText || '', xhr.getResponseHeader('content-type') || '');
        token = text(data && (data.AccessToken || data.accessToken || data.access_token));
        user = data && (data.User || data.user);
        userId = text(user && (user.Id || user.id));
        if (!token || !userId) {
          reject(new ApiError('로그인 응답에 액세스 토큰 또는 사용자 ID가 없습니다.', 0, path, 'invalid_auth_response'));
          return;
        }
        resolve({
          id: type + '-' + baseUrl.replace(/[^a-z0-9]+/gi, '-').toLowerCase(),
          kind: type,
          type: type,
          serverType: type,
          name: text(input.serverName, baseUrl),
          serverName: text(input.serverName, baseUrl),
          baseUrl: baseUrl,
          url: baseUrl,
          serverUrl: baseUrl,
          token: token,
          accessToken: token,
          apiKey: token,
          userId: userId,
          userName: text(user && (user.Name || user.name)),
          serverId: text(input.serverId || data && (data.ServerId || data.serverId)),
          clientIdentifier: identifier,
          clientId: identifier,
          authSource: 'password'
        });
      };
      xhr.onerror = function () {
        reject(new ApiError(
          '로그인 서버에 연결할 수 없습니다. 서버 주소와 CORS 설정을 확인하세요.',
          0,
          path,
          'network_error'
        ));
      };
      xhr.ontimeout = function () {
        reject(new ApiError('서버 로그인 시간이 초과되었습니다.', 0, path, 'timeout'));
      };
      try {
        xhr.send(JSON.stringify({ Username: username, Pw: password }));
      } catch (sendError) {
        reject(new ApiError('서버 로그인 요청을 보내지 못했습니다.', 0, path, 'send_failed'));
      }
    });
  }

  function saveConnection(input) {
    var connection = normalizeConnection(input);
    var connections = listConnections();
    var updated = false;
    connections = connections.map(function (existing) {
      if (existing.id === connection.id) {
        updated = true;
        return connection;
      }
      return existing;
    });
    if (!updated) { connections.push(connection); }
    localSet(CONNECTIONS_KEY, connections);
    return connection;
  }

  function listConnections() {
    var values = localGet(CONNECTIONS_KEY, []);
    return Array.isArray(values) ? values.slice() : [];
  }

  function getConnection(id) {
    var values = listConnections();
    var i;
    for (i = 0; i < values.length; i += 1) {
      if (values[i].id === id) { return values[i]; }
    }
    return null;
  }

  function removeConnection(id) {
    var before = listConnections();
    var after = before.filter(function (connection) { return connection.id !== id; });
    localSet(CONNECTIONS_KEY, after);
    if (localGet(ACTIVE_KEY, '') === id) { localRemove(ACTIVE_KEY); }
    return after.length !== before.length;
  }

  function clearConnections() {
    localRemove(CONNECTIONS_KEY);
    localRemove(ACTIVE_KEY);
  }

  function resolveConnection(value) {
    if (typeof value === 'string') {
      var found = getConnection(value);
      if (!found) { throw new ApiError('Saved connection was not found', 0, '', 'connection_not_found'); }
      return normalizeConnection(found);
    }
    return normalizeConnection(value);
  }

  function setActiveConnection(value) {
    var connection = typeof value === 'string' ? resolveConnection(value) : saveConnection(value);
    localSet(ACTIVE_KEY, connection.id);
    return connection;
  }

  function getActiveConnection() {
    var id = localGet(ACTIVE_KEY, '');
    return id ? getConnection(id) : null;
  }

  function safePath(value) {
    var raw = text(value);
    var question = raw.indexOf('?');
    return question >= 0 ? raw.slice(0, question) : raw;
  }

  function encodeQuery(parameters) {
    var parts = [];
    function encode(value) {
      return encodeURIComponent(String(value)).replace(/[!'()*]/g, function (character) {
        return '%' + character.charCodeAt(0).toString(16).toUpperCase();
      });
    }
    Object.keys(parameters || {}).forEach(function (key) {
      var value = parameters[key];
      if (value === null || typeof value === 'undefined' || value === '') { return; }
      array(value).forEach(function (entry) {
        parts.push(encode(key) + '=' + encode(entry));
      });
    });
    return parts.join('&');
  }

  function appendQuery(url, parameters) {
    var query = encodeQuery(parameters);
    if (!query) { return url; }
    return url + (url.indexOf('?') >= 0 ? '&' : '?') + query;
  }

  function joinUrl(baseUrl, path) {
    path = text(path);
    if (/^https?:\/\//i.test(path)) { return path; }
    return baseUrl + (path.charAt(0) === '/' ? path : '/' + path);
  }

  function sameOrigin(left, right) {
    try {
      var LeftUrl = global.URL || URL;
      var a = new LeftUrl(left);
      var b = new LeftUrl(right);
      return a.protocol === b.protocol && a.hostname === b.hostname && a.port === b.port;
    } catch (ignore) {
      return left.indexOf(right + '/') === 0 || left === right;
    }
  }

  function tokenizedUrl(connection, path, extra) {
    if (!path) { return null; }
    var absolute = joinUrl(connection.baseUrl, path);
    if (!sameOrigin(absolute, connection.baseUrl)) {
      return absolute;
    }
    var parameters = {};
    Object.keys(extra || {}).forEach(function (key) { parameters[key] = extra[key]; });
    if (connection.type === 'plex') {
      parameters['X-Plex-Token'] = connection.token;
    } else if (connection.type !== 'shyni') {
      parameters.api_key = connection.token;
    }
    return appendQuery(absolute, parameters);
  }

  function requestHeaders(connection, accept) {
    var headers = { Accept: accept || 'application/json' };
    if (connection.type === 'plex') {
      headers['X-Plex-Token'] = connection.token;
      headers['X-Plex-Product'] = PRODUCT;
      headers['X-Plex-Version'] = APP_VERSION;
      headers['X-Plex-Client-Identifier'] = connection.clientIdentifier;
      headers['X-Plex-Platform'] = 'webOS';
    } else if (connection.type === 'shyni') {
      headers.Authorization = 'Bearer ' + connection.token;
      headers['X-Device-Id'] = connection.clientIdentifier;
    } else {
      var authorization = mediaBrowserAuthorization(connection.type, connection.clientIdentifier, connection.token);
      headers['X-Emby-Token'] = connection.token;
      headers.Authorization = authorization;
      headers['X-Emby-Authorization'] = authorization;
    }
    return headers;
  }

  function parseBody(raw, contentType) {
    if (!raw) { return null; }
    if (/json/i.test(contentType || '') || /^[\s\r\n]*[\[{]/.test(raw)) {
      try { return JSON.parse(raw); } catch (ignore) { /* Try XML below. */ }
    }
    if (global.DOMParser) {
      try { return new global.DOMParser().parseFromString(raw, 'application/xml'); } catch (ignoreXml) { /* Fall through. */ }
    }
    return raw;
  }

  function request(connection, method, path, options) {
    options = options || {};
    if (!global.XMLHttpRequest || !global.Promise) {
      return global.Promise.reject(new ApiError('This web engine does not provide XMLHttpRequest', 0, path, 'unsupported_engine'));
    }
    var url = appendQuery(joinUrl(connection.baseUrl, path), options.query || {});
    var headers = requestHeaders(connection, options.accept);
    Object.keys(options.headers || {}).forEach(function (key) { headers[key] = options.headers[key]; });
    return new global.Promise(function (resolve, reject) {
      var xhr = new global.XMLHttpRequest();
      xhr.open(method || 'GET', url, true);
      xhr.timeout = options.timeout || 30000;
      if (options.maxResponseBytes) {
        xhr.onprogress = function (event) {
          if (event.loaded > options.maxResponseBytes) {
            xhr.abort();
            reject(new ApiError('서버 응답이 허용 크기를 초과했습니다.', 0, '', 'response_too_large'));
          }
        };
      }
      Object.keys(headers).forEach(function (key) {
        try { xhr.setRequestHeader(key, headers[key]); } catch (ignore) { /* Some old engines block optional headers. */ }
      });
      xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4) { return; }
        if (xhr.status >= 200 && xhr.status < 300) {
          if (options.maxResponseBytes && (xhr.responseText || '').length > options.maxResponseBytes) {
            reject(new ApiError('서버 응답이 허용 크기를 초과했습니다.', 0, '', 'response_too_large'));
            return;
          }
          resolve(options.rawText
            ? (options.responseInfo ? { body: xhr.responseText || '', url: xhr.responseURL || url } : xhr.responseText || '')
            : parseBody(xhr.responseText || '', xhr.getResponseHeader('content-type') || ''));
          return;
        }
        if (xhr.status === 0) {
          reject(new ApiError(
            '미디어 서버에 연결할 수 없습니다. 서버 실행 상태, 주소 및 CORS 설정을 확인하세요.',
            0,
            path,
            'network_error'
          ));
          return;
        }
        var errorBody = parseBody(xhr.responseText || '', xhr.getResponseHeader('content-type') || '');
        var detail = errorBody && errorBody.detail;
        var detailText = typeof detail === 'string' ? detail : detail && (detail.message || detail.reason_text);
        reject(new ApiError(detailText || '서버 요청이 실패했습니다. (HTTP ' + xhr.status + ')', xhr.status, path, 'http_error'));
      };
      xhr.onerror = function () {
        reject(new ApiError('Could not reach the media server. Check its URL and CORS settings.', 0, path, 'network_error'));
      };
      xhr.ontimeout = function () {
        reject(new ApiError('Media server request timed out', 0, path, 'timeout'));
      };
      var body = null;
      if (typeof options.body !== 'undefined' && options.body !== null) {
        headers['Content-Type'] = headers['Content-Type'] || 'application/json';
        try { xhr.setRequestHeader('Content-Type', headers['Content-Type']); } catch (ignoreContentType) { /* Continue. */ }
        body = headers['Content-Type'].indexOf('json') >= 0 ? JSON.stringify(options.body) : options.body;
      }
      try { xhr.send(body); } catch (error) {
        reject(new ApiError('Media server request could not be sent', 0, path, 'send_failed'));
      }
    });
  }

  function xmlElements(documentOrNode, tagName) {
    if (!documentOrNode || typeof documentOrNode.getElementsByTagName !== 'function') { return []; }
    return Array.prototype.slice.call(documentOrNode.getElementsByTagName(tagName));
  }

  function attr(node, name, fallback) {
    if (!node || typeof node.getAttribute !== 'function') { return fallback; }
    var value = node.getAttribute(name);
    return value === null ? fallback : value;
  }

  function plexContainer(data) {
    return data && data.MediaContainer ? data.MediaContainer : data;
  }

  function plexJsonEntries(data) {
    var container = plexContainer(data) || {};
    return array(container.Metadata).concat(array(container.Directory)).concat(array(container.Video));
  }

  function plexStreamFromJson(connection, stream) {
    var key = first(stream, ['key', 'Key'], '');
    return {
      id: text(first(stream, ['id', 'ID'], key)),
      type: 'subtitle',
      language: text(first(stream, ['language', 'languageCode'], '')),
      title: text(first(stream, ['displayTitle', 'title', 'language'], '자막')),
      selected: bool(stream.selected),
      default: bool(stream.default),
      forced: bool(stream.forced),
      url: key ? tokenizedUrl(connection, key) : null,
      key: key,
      format: text(first(stream, ['format', 'codec'], ''))
    };
  }

  function mediaMimeType(container, audio) {
    var name = text(container).toLowerCase();
    var types = audio ? { mp3: 'audio/mpeg', aac: 'audio/aac', m4a: 'audio/mp4', flac: 'audio/flac', ogg: 'audio/ogg' }
      : { mp4: 'video/mp4', m4v: 'video/mp4', mov: 'video/quicktime', mkv: 'video/x-matroska', ts: 'video/mp2t', mpegts: 'video/mp2t', m2ts: 'video/mp2t', webm: 'video/webm' };
    return types[name] || '';
  }

  function plexPlaybackInfo(media, part) {
    var streams = array(part.Stream);
    var video = streams.filter(function (stream) { return number(stream.streamType, 0) === 1; })[0] || {};
    var audios = streams.filter(function (stream) { return number(stream.streamType, 0) === 2; });
    return {
      container: text(part.container || media.container).toLowerCase(),
      videoCodec: text(video.codec || media.videoCodec).toLowerCase(),
      width: number(video.width || media.width, 0),
      height: number(video.height || media.height, 0),
      bitrate: number(media.bitrate, 0),
      bitDepth: number(video.bitDepth, 0),
      frameRate: number(video.frameRate, 0),
      profile: text(video.profile || media.videoProfile).toLowerCase(),
      level: number(video.level, 0),
      audioTracks: audios.map(function (stream) {
        return { codec: text(stream.codec).toLowerCase(), channels: number(stream.channels, 0), profile: text(stream.profile).toLowerCase() };
      }),
      embeddedSubtitles: streams.some(function (stream) { return number(stream.streamType, 0) === 3 && !bool(stream.external); }),
      complete: Boolean(video.codec && audios.length)
    };
  }

  function plexItemFromJson(connection, item) {
    var media = array(item.Media)[0] || {};
    var part = array(media.Part)[0] || {};
    var streams = array(part.Stream).filter(function (stream) { return number(stream.streamType, 0) === 3; }).map(function (stream) { return plexStreamFromJson(connection, stream); });
    var type = text(item.type, 'directory').toLowerCase();
    var id = text(item.ratingKey || item.key);
    var playable = type === 'movie' || type === 'episode' || type === 'video' || type === 'clip';
    return {
      id: id,
      key: id,
      title: text(item.title || item.name, '제목 없음'),
      type: type,
      year: item.year,
      summary: text(item.summary),
      isContainer: !playable,
      drillable: !playable,
      playable: playable,
      drillKey: id,
      imageUrl: tokenizedUrl(connection, item.thumb || item.art, { width: 280, height: 420, minSize: 1 }),
      backdropUrl: tokenizedUrl(connection, item.art || item.thumb, { width: 960, height: 540, minSize: 1 }),
      durationMs: number(item.duration, 0),
      resumePositionMs: number(item.viewOffset, 0),
      progress: number(item.duration, 0) > 0 ? number(item.viewOffset, 0) / number(item.duration, 1) : 0,
      played: number(item.viewCount, 0) > 0 || Boolean(item.viewedAt),
      episodeCount: item.leafCount == null ? null : number(item.leafCount, -1),
      watchedEpisodeCount: item.viewedLeafCount == null ? null : number(item.viewedLeafCount, -1),
      parentId: text(item.parentRatingKey),
      seriesId: text(item.grandparentRatingKey),
      partKey: text(part.key),
      mediaIndex: 0,
      partIndex: 0,
      mimeType: mediaMimeType(part.container || media.container),
      playbackInfo: plexPlaybackInfo(media, part),
      subtitles: streams
    };
  }

  function plexStreamFromXml(connection, node) {
    var key = attr(node, 'key', '');
    return {
      id: attr(node, 'id', key),
      type: 'subtitle',
      language: attr(node, 'languageCode', attr(node, 'language', '')),
      title: attr(node, 'displayTitle', attr(node, 'title', attr(node, 'language', '자막'))),
      selected: bool(attr(node, 'selected', '0')),
      default: bool(attr(node, 'default', '0')),
      forced: bool(attr(node, 'forced', '0')),
      url: key ? tokenizedUrl(connection, key) : null,
      key: key,
      format: attr(node, 'format', attr(node, 'codec', ''))
    };
  }

  function plexItemFromXml(connection, node) {
    var type = text(attr(node, 'type', node.tagName === 'Video' ? 'video' : 'directory')).toLowerCase();
    var id = attr(node, 'ratingKey', attr(node, 'key', ''));
    var playable = node.tagName === 'Video' || type === 'movie' || type === 'episode' || type === 'video' || type === 'clip';
    var parts = xmlElements(node, 'Part');
    var part = parts[0] || null;
    var media = xmlElements(node, 'Media')[0];
    function attributes(element) {
      var result = {};
      if (element && element.attributes) {
        for (var i = 0; i < element.attributes.length; i += 1) {
          result[element.attributes[i].name] = element.attributes[i].value;
        }
      }
      return result;
    }
    var partData = attributes(part);
    partData.Stream = xmlElements(part, 'Stream').map(attributes);
    var streams = part ? xmlElements(part, 'Stream').filter(function (stream) { return number(attr(stream, 'streamType', 0), 0) === 3; }).map(function (stream) { return plexStreamFromXml(connection, stream); }) : [];
    var duration = number(attr(node, 'duration', 0), 0);
    var resume = number(attr(node, 'viewOffset', 0), 0);
    return {
      id: id,
      key: id,
      title: attr(node, 'title', attr(node, 'name', '제목 없음')),
      type: type,
      year: attr(node, 'year', ''),
      summary: attr(node, 'summary', ''),
      isContainer: !playable,
      drillable: !playable,
      playable: playable,
      drillKey: id,
      imageUrl: tokenizedUrl(connection, attr(node, 'thumb', attr(node, 'art', '')), { width: 280, height: 420, minSize: 1 }),
      backdropUrl: tokenizedUrl(connection, attr(node, 'art', attr(node, 'thumb', '')), { width: 960, height: 540, minSize: 1 }),
      durationMs: duration,
      resumePositionMs: resume,
      progress: duration > 0 ? resume / duration : 0,
      played: number(attr(node, 'viewCount', 0), 0) > 0 || Boolean(attr(node, 'viewedAt', '')),
      episodeCount: attr(node, 'leafCount', '') === '' ? null : number(attr(node, 'leafCount', -1), -1),
      watchedEpisodeCount: attr(node, 'viewedLeafCount', '') === '' ? null : number(attr(node, 'viewedLeafCount', -1), -1),
      parentId: attr(node, 'parentRatingKey', ''),
      seriesId: attr(node, 'grandparentRatingKey', ''),
      partKey: part ? attr(part, 'key', '') : '',
      mimeType: part ? mediaMimeType(attr(part, 'container', '')) : '',
      playbackInfo: plexPlaybackInfo(attributes(media), partData),
      subtitles: streams
    };
  }

  function parsePlexItems(connection, data) {
    if (data && data.nodeType) {
      return xmlElements(data, 'Video').concat(xmlElements(data, 'Directory')).filter(function (node) {
        return node.parentNode && (node.parentNode.nodeType === 9 || node.parentNode.tagName === 'MediaContainer');
      }).map(function (node) { return plexItemFromXml(connection, node); });
    }
    return plexJsonEntries(data).map(function (entry) { return plexItemFromJson(connection, entry); });
  }

  function parsePlexLibraries(connection, data) {
    var values;
    if (data && data.nodeType) {
      values = xmlElements(data, 'Directory').map(function (node) {
        return {
          id: attr(node, 'key', ''),
          key: attr(node, 'key', ''),
          title: attr(node, 'title', '라이브러리'),
          type: attr(node, 'type', 'library'),
          isContainer: true,
          drillable: true,
          playable: false,
          library: true,
          imageUrl: tokenizedUrl(connection, attr(node, 'thumb', attr(node, 'art', '')), { width: 480, height: 270, minSize: 1 })
        };
      });
    } else {
      var container = plexContainer(data) || {};
      values = array(container.Directory).map(function (entry) {
        return {
          id: text(entry.key),
          key: text(entry.key),
          title: text(entry.title, '라이브러리'),
          type: text(entry.type, 'library'),
          isContainer: true,
          drillable: true,
          playable: false,
          library: true,
          imageUrl: tokenizedUrl(connection, entry.thumb || entry.art, { width: 480, height: 270, minSize: 1 })
        };
      });
    }
    return values;
  }

  function jellyImageUrl(connection, item, options) {
    options = options || {};
    var id = typeof item === 'string' ? item : text(item.Id || item.id);
    if (!id) { return null; }
    return tokenizedUrl(connection, '/Items/' + encodeURIComponent(id) + '/Images/Primary', {
      fillWidth: options.width || 280,
      fillHeight: options.height || 420,
      quality: options.quality || 90
    });
  }

  function jellySubtitle(connection, itemId, mediaSourceId, stream) {
    var delivery = text(stream.DeliveryUrl || stream.deliveryUrl);
    var codec = text(stream.Codec).toLowerCase();
    var textSubtitle = stream.IsTextSubtitle !== false && ['pgs', 'pgssub', 'dvdsub', 'dvbsub', 'vobsub'].indexOf(codec) < 0;
    if (!delivery && textSubtitle && stream.Index !== null && typeof stream.Index !== 'undefined') {
      delivery = '/Videos/' + encodeURIComponent(itemId) + '/' + encodeURIComponent(mediaSourceId) + '/Subtitles/' + encodeURIComponent(stream.Index) + '/Stream.vtt';
    }
    return {
      id: text(stream.Index),
      index: number(stream.Index, -1),
      type: 'subtitle',
      title: text(stream.DisplayTitle || stream.Title || stream.Language, '자막'),
      language: text(stream.Language),
      selected: bool(stream.IsDefault),
      default: bool(stream.IsDefault),
      forced: bool(stream.IsForced),
      format: text(stream.Codec),
      textSubtitle: textSubtitle,
      url: delivery ? tokenizedUrl(connection, delivery) : null,
      deliveryUrl: delivery
    };
  }

  function jellyItem(connection, item) {
    var type = text(item.Type, 'folder').toLowerCase();
    var playable = bool(item.IsFolder) ? false : ['movie', 'episode', 'video', 'audio', 'musicvideo', 'livetvchannel'].indexOf(type) >= 0;
    var userData = item.UserData || {};
    var durationTicks = number(item.RunTimeTicks, 0);
    var resumeTicks = number(userData.PlaybackPositionTicks, 0);
    return {
      id: text(item.Id),
      key: text(item.Id),
      title: text(item.Name, '제목 없음'),
      type: type,
      collectionType: text(item.CollectionType),
      year: item.ProductionYear,
      summary: text(item.Overview),
      isContainer: bool(item.IsFolder) || !playable,
      drillable: bool(item.IsFolder) || !playable,
      playable: playable,
      drillKey: text(item.Id),
      imageUrl: jellyImageUrl(connection, item),
      backdropUrl: item.BackdropImageTags && item.BackdropImageTags.length ? tokenizedUrl(connection, '/Items/' + encodeURIComponent(item.Id) + '/Images/Backdrop/0', { maxWidth: 960, quality: 84 }) : null,
      durationMs: durationTicks / 10000,
      resumePositionMs: resumeTicks / 10000,
      progress: durationTicks > 0 ? resumeTicks / durationTicks : 0,
      played: bool(userData.Played),
      episodeCount: item.RecursiveItemCount == null ? null : number(item.RecursiveItemCount, -1),
      unplayedEpisodeCount: userData.UnplayedItemCount == null ? null : number(userData.UnplayedItemCount, -1),
      parentId: text(item.SeasonId || item.ParentId),
      seriesId: text(item.SeriesId)
    };
  }

  function shyniRows(value) {
    var keys = Array.prototype.slice.call(arguments, 1);
    var i;
    if (Array.isArray(value)) { return value; }
    value = value || {};
    for (i = 0; i < keys.length; i += 1) {
      if (Array.isArray(value[keys[i]])) { return value[keys[i]]; }
    }
    if (Array.isArray(value.items)) { return value.items; }
    if (Array.isArray(value.results)) { return value.results; }
    return [];
  }

  function shyniAbsolute(connection, value) {
    if (!value) { return ''; }
    return /^https?:\/\//i.test(value) ? value : joinUrl(connection.baseUrl, value);
  }

  function shyniType(value, fallback) {
    value = text(value, fallback || 'movie').toLowerCase();
    if (value === '1') { return 'movie'; }
    if (value === '2' || value === 'tv') { return 'show'; }
    if (value === '3') { return 'season'; }
    if (value === '4') { return 'episode'; }
    return value;
  }

  function shyniMedia(connection, row, libraryId, fallbackType, previous) {
    row = row || {};
    previous = previous || {};
    var type = shyniType(first(row, ['item_type', 'type', 'metadata_type'], fallbackType), fallbackType);
    var id = number(first(row, ['metadata_item_id', 'id'], previous.id), 0);
    var duration = number(first(row, ['duration_ms'], 0), 0);
    if (!duration) { duration = number(first(row, ['duration_sec'], 0), 0) * 1000; }
    duration = duration || number(previous.durationMs, 0);
    var position = Math.max(0, number(first(row, ['position_ms'], previous.resumePositionMs), 0));
    var watched = bool(first(row, ['completed', 'watched'], false)) || text(row.state).toLowerCase() === 'completed';
    var poster = first(row, ['thumb_url', 'poster_url', 'show_thumb_url'], previous.imageUrl);
    var backdrop = first(row, ['art_url', 'backdrop_url', 'show_art_url'], previous.backdropUrl);
    var seasonNumber = first(row, ['season_number', 'logical_season_no', 'base_season_no'], null);
    var episodeNumber = first(row, ['episode_number'], null);
    if (type === 'season' && seasonNumber === null) { seasonNumber = first(row, ['index_'], null); }
    if (type === 'episode' && episodeNumber === null) { episodeNumber = first(row, ['index_'], null); }
    var playable = type === 'movie' || type === 'episode';
    return {
      id: String(id), key: String(id), libraryId: String(libraryId || first(row, ['library_view_id'], previous.libraryId) || ''),
      viewItemId: String(first(row, ['view_item_id'], previous.viewItemId) || ''),
      showId: String(first(row, ['show_id'], previous.showId) || ''),
      partId: String(first(row, ['part_id'], previous.partId) || ''),
      title: text(first(row, ['title', 'name'], previous.title), type === 'season' ? '시즌 ' + text(seasonNumber) : '제목 없음'),
      subtitle: text(first(row, ['subtitle'], previous.subtitle)),
      showTitle: text(first(row, ['show_title'], previous.showTitle)),
      type: type,
      collectionType: type === 'show' ? 'tv' : type,
      summary: text(first(row, ['summary'], previous.summary)),
      year: first(row, ['year', 'production_year'], previous.year),
      seasonNumber: seasonNumber,
      episodeNumber: episodeNumber,
      durationMs: duration,
      resumePositionMs: watched ? 0 : position,
      progress: duration > 0 ? (watched ? 1 : Math.min(1, position / duration)) : 0,
      played: watched,
      watched: watched,
      isContainer: !playable,
      drillable: !playable,
      playable: playable,
      imageUrl: shyniAbsolute(connection, poster),
      posterUrl: shyniAbsolute(connection, poster),
      backdropUrl: shyniAbsolute(connection, backdrop),
      thumbnailUrl: shyniAbsolute(connection, first(row, ['thumb_url'], poster)),
      episodeCount: first(row, ['episode_count'], previous.episodeCount),
      addedAt: text(first(row, ['latest_ep', 'latest_episode_added_at', 'added_at'], previous.addedAt)),
      raw: row
    };
  }

  function shyniLibrary(row) {
    row = row || {};
    var type = text(row.type || row.content_type).toLowerCase();
    if (type === 'movies') { type = 'movie'; }
    if (type === 'shows' || type === 'show') { type = 'tv'; }
    if (['movie', 'tv'].indexOf(type) < 0 || bool(row.remote)) { return null; }
    var id = number(row.id || row.library_view_id, 0);
    if (!id) { return null; }
    return { id: String(id), key: String(id), libraryId: String(id), title: text(row.name || row.title, '라이브러리 ' + id),
      type: 'library', collectionType: type, library: true, playable: false, isContainer: true, drillable: true };
  }

  function shyniProgressFor(value, id) {
    var candidate;
    var rows;
    if (value && !Array.isArray(value) && typeof value === 'object') {
      candidate = value[String(id)] || value.progress && value.progress[String(id)];
      if (candidate) { return candidate; }
      if (number(value.metadata_item_id || value.item_id || value.id, 0) === number(id, -1)) { return value; }
    }
    rows = shyniRows(value, 'progress');
    for (var i = 0; i < rows.length; i += 1) {
      if (number(rows[i].metadata_item_id || rows[i].item_id || rows[i].id, 0) === number(id, -1)) { return rows[i]; }
    }
    return null;
  }

  function shyniApplyProgress(item, row) {
    if (!row) { return item; }
    var watched = bool(first(row, ['completed', 'watched'], false)) || text(row.state).toLowerCase() === 'completed';
    var position = Math.max(0, number(row.position_ms, 0));
    var duration = number(item.durationMs || row.duration_ms, 0);
    var copy = {};
    Object.keys(item).forEach(function (key) { copy[key] = item[key]; });
    copy.played = watched;
    copy.watched = watched;
    copy.resumePositionMs = watched ? 0 : position;
    copy.durationMs = duration;
    copy.progress = duration > 0 ? (watched ? 1 : Math.min(1, position / duration)) : 0;
    return copy;
  }

  function shyniWithProgress(connection, items) {
    var playable = items.filter(function (item) { return item.playable && item.id && item.libraryId; });
    var groups = {};
    playable.forEach(function (item) {
      groups[item.libraryId] = groups[item.libraryId] || [];
      groups[item.libraryId].push(item);
    });
    return global.Promise.all(Object.keys(groups).map(function (libraryId) {
      var ids = groups[libraryId].map(function (item) { return item.id; }).filter(function (id, index, allIds) { return allIds.indexOf(id) === index; }).slice(0, 100);
      if (!ids.length) { return null; }
      return request(connection, 'GET', '/playback/progress', { query: { item_ids: ids.join(','), library_view_id: libraryId } })
        .then(function (value) { return { libraryId: libraryId, value: value }; })
        .catch(function () { return null; });
    })).then(function (responses) {
      return items.map(function (item) {
        var response = responses.filter(function (entry) { return entry && entry.libraryId === item.libraryId; })[0];
        return response ? shyniApplyProgress(item, shyniProgressFor(response.value, item.id)) : item;
      });
    });
  }

  function shyniListLibraries(connection) {
    return request(connection, 'GET', '/libraries').then(function (value) {
      return shyniRows(value, 'libraries', 'views').map(shyniLibrary).filter(Boolean);
    });
  }

  function shyniListItems(connection, parent, options) {
    parent = parent || {};
    options = options || {};
    var limit = Math.min(180, Math.max(1, number(options.limit, 60)));
    var libraryId = text(parent.libraryId || parent.id);
    var path;
    var fallbackType;
    if (parent.type === 'library') {
      fallbackType = parent.collectionType === 'tv' ? 'show' : 'movie';
      path = '/libraries/' + encodeURIComponent(libraryId) + '/' + (fallbackType === 'show' ? 'shows?sort=title' : 'movies?sort=title');
    } else if (parent.type === 'show' || parent.type === 'series') {
      fallbackType = 'season';
      // The current Shyni server exposes its compact client season contract
      // under the mobile platform name. Keep that contract for webOS too.
      path = '/libraries/' + encodeURIComponent(libraryId) + '/shows/' + encodeURIComponent(parent.id) + '/seasons?platform=mobile';
    } else if (parent.type === 'season') {
      fallbackType = 'episode';
      if (!parent.showId) { return global.Promise.reject(new ApiError('시리즈 정보를 찾을 수 없습니다.', 0, '', 'missing_show_id')); }
      path = '/libraries/' + encodeURIComponent(libraryId) + '/shows/' + encodeURIComponent(parent.showId) + '/seasons/' + encodeURIComponent(parent.id) + '/episodes?limit=' + limit + '&offset=0';
    } else {
      return global.Promise.resolve({ items: [], total: 0, size: 0 });
    }
    return request(connection, 'GET', path, { maxResponseBytes: 16777216 }).then(function (value) {
      var items = shyniRows(value, 'items', 'results', 'episodes').slice(0, limit).map(function (row) {
        var item = shyniMedia(connection, row, libraryId, fallbackType);
        if (fallbackType === 'season') { item.showId = text(parent.id); }
        if (fallbackType === 'episode') { item.showId = text(parent.showId); }
        return item;
      });
      return shyniWithProgress(connection, items);
    }).then(function (items) { return { items: items, total: items.length, size: items.length }; });
  }

  function shyniHomeHubs(connection, options) {
    options = options || {};
    var limit = Math.max(1, number(options.limit, 14));
    var librariesPromise = Array.isArray(options.libraries) ? global.Promise.resolve(options.libraries) : shyniListLibraries(connection);
    var continuePromise = request(connection, 'GET', '/continue-watching', { query: { limit: 30 } }).then(function (value) {
      return shyniRows(value, 'items', 'results').map(function (row) {
        return shyniMedia(connection, row, number(row.library_view_id, 0), shyniType(row.item_type || row.type, 'movie'));
      }).filter(function (item) { return item.playable && item.libraryId; }).slice(0, limit);
    }).catch(function () { return []; });
    return global.Promise.all([librariesPromise, continuePromise]).then(function (base) {
      var libraries = base[0];
      var hubs = [];
      if (base[1].length) { hubs.push({ id: 'continue-watching', title: '이어보기', kind: 'continue', items: base[1] }); }
      return global.Promise.all(libraries.map(function (library) {
        var route = library.collectionType === 'tv' ? 'shows?sort=latest_episode&sort_dir=desc' : 'movies?sort=added&sort_dir=desc';
        return request(connection, 'GET', '/libraries/' + encodeURIComponent(library.id) + '/' + route, { maxResponseBytes: 16777216 })
          .then(function (value) {
            var kind = library.collectionType === 'tv' ? 'show' : 'movie';
            return shyniRows(value, 'items', 'results').slice(0, limit).map(function (row) { return shyniMedia(connection, row, library.id, kind); });
          }).catch(function () { return []; });
      })).then(function (recentGroups) {
        var recent = [];
        recentGroups.forEach(function (group) { recent = recent.concat(group); });
        recent.sort(function (a, b) { return text(b.addedAt).localeCompare(text(a.addedAt)); });
        recent = recent.slice(0, limit);
        if (!recent.length) { return hubs; }
        return shyniWithProgress(connection, recent).then(function (items) {
          hubs.push({ id: 'recently-added', title: '최근 추가됨', kind: 'recent', items: items });
          return hubs;
        });
      });
    });
  }

  function shyniSearch(connection, query, options) {
    query = text(query).trim();
    var limit = Math.min(180, Math.max(1, number(options && options.limit, 120)));
    if (!query) { return global.Promise.resolve({ items: [], total: 0, size: 0 }); }
    return shyniListLibraries(connection).then(function (libraries) {
      return global.Promise.all(libraries.map(function (library) {
        var metadataType = library.collectionType === 'tv' ? 2 : 1;
        return request(connection, 'GET', '/libraries/' + encodeURIComponent(library.id) + '/search', {
          query: { q: query, limit: 200, metadata_type: metadataType }, maxResponseBytes: 16777216
        }).then(function (value) {
          return shyniRows(value, 'items', 'results').map(function (row) {
            return shyniMedia(connection, row, library.id, metadataType === 2 ? 'show' : 'movie');
          });
        }).catch(function () { return []; });
      }));
    }).then(function (groups) {
      var values = [];
      groups.forEach(function (group) { values = values.concat(group); });
      values = uniqueItems(values, limit);
      return shyniWithProgress(connection, values);
    }).then(function (items) { return { items: items, total: items.length, size: items.length }; });
  }

  function shyniContextQuery(decision, item) {
    var context = text(decision.playback_context_token);
    if (context) { return { playback_context_token: context }; }
    var viewId = text(decision.library_view_id || item.libraryId);
    var viewItemId = text(decision.view_item_id || item.viewItemId);
    if (!viewId || !viewItemId) { throw new ApiError('재생 권한 정보를 받지 못했습니다.', 0, '', 'missing_playback_context'); }
    return { library_view_id: viewId, view_item_id: viewItemId };
  }

  function shyniSubtitle(connection, row, ticket, context) {
    row = row || {};
    var format = text(row.format).toLowerCase();
    if (row.playable === false || /pgs|hdmv|dvd|dvb|vobsub/.test(format)) { return null; }
    var id = number(row.id, 0);
    if (!id) { return null; }
    var query = {};
    Object.keys(context).forEach(function (key) { query[key] = context[key]; });
    query.ticket = ticket;
    return {
      id: String(id), title: text(row.label || row.lang_code, '자막 ' + id), language: text(row.lang_code),
      format: 'vtt', codec: 'vtt', textSubtitle: true, selected: bool(row.default), default: bool(row.default), forced: bool(row.forced),
      url: appendQuery(connection.baseUrl + '/subtitles/track/' + encodeURIComponent(id) + '.vtt', query)
    };
  }

  function shyniResolvePlayback(connection, item, options) {
    options = options || {};
    if (!item || !item.playable || !item.id || !item.libraryId || !item.viewItemId) {
      return global.Promise.reject(new ApiError('재생할 영화 또는 회차의 정보가 부족합니다.', 0, '', 'missing_item'));
    }
    var sessionId = randomId();
    var body = { metadata_item_id: number(item.id, 0), library_view_id: number(item.libraryId, 0),
      view_item_id: number(item.viewItemId, 0), platform: 'mobile', client_app: 'shyniflex-webos',
      device_id: connection.clientIdentifier, client_session_id: sessionId };
    if (item.partId) { body.part_id = number(item.partId, 0); }
    return request(connection, 'POST', '/playback/decision', { body: body, timeout: 30000 }).then(function (decision) {
      decision = decision || {};
      var selected = decision.selected || {};
      var partId = number(selected.part_id || item.partId, 0);
      if (!partId) { throw new ApiError(text(decision.reason_text, '재생할 파일이 없습니다.'), 0, '/playback/decision', 'no_media_source'); }
      var context = shyniContextQuery(decision, item);
      var compatibility = options.compatibility !== false;
      var ticketPath = compatibility ? '/playback/transcode-ticket/' + encodeURIComponent(partId) : '/stream-ticket/' + encodeURIComponent(partId);
      var ticketQuery = {};
      Object.keys(context).forEach(function (key) { ticketQuery[key] = context[key]; });
      if (compatibility) { ticketQuery.mode = 'video'; }
      return request(connection, 'POST', ticketPath, { query: ticketQuery, body: {}, timeout: 30000 }).then(function (ticketResponse) {
        var ticket = text(ticketResponse && ticketResponse.ticket);
        if (!ticket) { throw new ApiError('재생 티켓을 받지 못했습니다.', 0, ticketPath, 'missing_stream_ticket'); }
        var streamUrl = compatibility
          ? appendQuery(connection.baseUrl + '/transcode/' + encodeURIComponent(partId), { ticket: ticket, mode: 'video', max_height: Math.min(1080, number(options.maxVideoHeight, 1080)) })
          : appendQuery(connection.baseUrl + '/stream/' + encodeURIComponent(partId), { ticket: ticket });
        return request(connection, 'POST', '/stream-ticket/' + encodeURIComponent(partId), { query: context, body: {}, timeout: 30000 })
          .then(function (subtitleTicketResponse) {
            var subtitleTicket = text(subtitleTicketResponse && subtitleTicketResponse.ticket);
            if (!subtitleTicket) { return []; }
            return request(connection, 'GET', '/subtitles/' + encodeURIComponent(partId), { query: context }).then(function (subtitleResponse) {
              return shyniRows(subtitleResponse, 'subtitles').map(function (row) {
                return shyniSubtitle(connection, row, subtitleTicket, context);
              }).filter(Boolean);
            });
          }).catch(function () { return []; }).then(function (subtitles) {
            return request(connection, 'GET', '/playback/progress', { query: { item_ids: item.id, library_view_id: decision.library_view_id || item.libraryId } })
              .catch(function () { return null; }).then(function (progressResponse) {
                var progress = shyniProgressFor(progressResponse, item.id);
                var duration = number(selected.duration_ms, 0) || number(selected.duration_sec, 0) * 1000 || number(progress && progress.duration_ms, 0) || number(item.durationMs, 0);
                var watched = progress && (bool(progress.completed || progress.watched) || text(progress.state).toLowerCase() === 'completed');
                var resume = watched ? 0 : number(progress && progress.position_ms, number(item.resumePositionMs, 0));
                var requestedStart = Object.prototype.hasOwnProperty.call(options, 'startTimeSeconds')
                  ? Math.max(0, Math.round(number(options.startTimeSeconds, 0) * 1000))
                  : resume;
                return { serverType: 'shyni', itemId: item.id, libraryId: item.libraryId, viewItemId: item.viewItemId,
                  partId: String(partId), url: streamUrl, directUrl: streamUrl, hlsUrl: '', fallbackUrl: '',
                  sources: [{ kind: 'direct', url: streamUrl, mimeType: 'video/mp4', playMethod: compatibility ? 'Transcode' : 'DirectPlay' }],
                  mimeType: 'video/mp4', subtitles: subtitles, durationMs: duration, resumePositionMs: resume,
                  playMethod: compatibility ? 'Transcode' : 'DirectPlay', sessionId: sessionId,
                  progressToken: text(decision.playback_context_token), decision: compatibility ? 'video_transcode' : text(decision.decision, 'direct_play'),
                  transcodeTicket: compatibility ? ticket : '', requestedStartPositionMs: requestedStart,
                  streamStartPositionMs: 0, maxVideoHeight: Math.min(1080, number(options.maxVideoHeight, 1080)),
                  selectedAudioStreamIndex: number(decision.selected_audio_stream_index, -1),
                  sourceItem: item };
              });
          });
      });
    });
  }

  function shyniReport(connection, playback, state, overrides) {
    overrides = overrides || {};
    var normalizedState = state === 'progress' ? (overrides.isPaused ? 'paused' : 'playing') : state;
    if (normalizedState === 'completed') { normalizedState = 'completed_candidate'; }
    return request(connection, 'POST', '/playback/progress', { body: {
      part_id: number(playback.partId, 0), metadata_item_id: number(playback.itemId, 0),
      library_view_id: number(playback.libraryId, 0), view_item_id: number(playback.viewItemId, 0) || null,
      playback_context_token: playback.progressToken || null, client_session_id: playback.sessionId,
      position_ms: Math.max(0, Math.round(number(overrides.positionMs, 0))),
      duration_ms: Math.max(0, Math.round(number(overrides.durationMs || playback.durationMs, 0))) || null,
      state: normalizedState, completion_eligible: normalizedState === 'completed_candidate',
      device_id: connection.clientIdentifier, decision: playback.decision, delivery_mode: 'server_proxy'
    } }).then(function () { return { ok: true, state: normalizedState, itemId: playback.itemId }; });
  }

  function shyniFetchImage(connection, value) {
    var url = typeof value === 'string' ? value : value && (value.imageUrl || value.posterUrl || value.backdropUrl);
    if (!url) { return global.Promise.resolve(''); }
    url = shyniAbsolute(connection, url);
    if (!sameOrigin(url, connection.baseUrl)) { return global.Promise.resolve(url); }
    return new global.Promise(function (resolve, reject) {
      var xhr = new global.XMLHttpRequest();
      xhr.open('GET', url, true);
      xhr.timeout = 20000;
      xhr.responseType = 'blob';
      var headers = requestHeaders(connection, 'image/*');
      Object.keys(headers).forEach(function (key) { try { xhr.setRequestHeader(key, headers[key]); } catch (ignore) {} });
      xhr.onload = function () {
        if (xhr.status >= 200 && xhr.status < 300 && xhr.response && global.URL && global.URL.createObjectURL) {
          resolve(global.URL.createObjectURL(xhr.response));
        } else { reject(new ApiError('이미지를 불러오지 못했습니다.', xhr.status, url, 'image_failed')); }
      };
      xhr.onerror = function () { reject(new ApiError('이미지를 불러오지 못했습니다.', 0, url, 'image_failed')); };
      xhr.ontimeout = xhr.onerror;
      xhr.send(null);
    });
  }

  function requireUser(connection) {
    if (!connection.userId) {
      throw new ApiError('Jellyfin/Emby user ID is required for this operation', 0, '', 'missing_user_id');
    }
    return connection.userId;
  }

  function testConnection(connection) {
    if (connection.type === 'shyni') {
      return request(connection, 'GET', '/auth/me', { timeout: 10000 }).then(function (data) {
        var user = data && (data.user || data) || {};
        return { ok: true, type: 'shyni', serverName: connection.name || 'Shyni 서버',
          serverId: connection.baseUrl, userId: text(user.id || user.user_id || user.username || user.name),
          userName: text(user.username || user.name, '사용자') };
      });
    }
    if (connection.type === 'plex') {
      return request(connection, 'GET', '/identity', { accept: 'application/json', timeout: 8000 }).then(function (data) {
        var container = plexContainer(data) || {};
        if (data && data.nodeType) {
          var root = data.documentElement;
          container = {
            machineIdentifier: attr(root, 'machineIdentifier', ''),
            version: attr(root, 'version', ''),
            friendlyName: attr(root, 'friendlyName', connection.name)
          };
        }
        return {
          ok: true,
          type: 'plex',
          serverName: text(container.friendlyName || container.name, connection.name),
          serverId: text(container.machineIdentifier),
          version: text(container.version),
          userId: ''
        };
      });
    }
    return request(connection, 'GET', '/System/Info/Public').then(function (info) {
      if (connection.userId) {
        return request(connection, 'GET', '/Users/' + encodeURIComponent(connection.userId)).then(function (user) {
          return { info: info || {}, user: user || {} };
        });
      }
      if (connection.type === 'emby') {
        throw new ApiError('Emby requires a user ID', 0, '/Users', 'missing_user_id');
      }
      return request(connection, 'GET', '/Users/Me').then(function (user) {
        connection.userId = text(user && user.Id);
        return { info: info || {}, user: user || {} };
      });
    }).then(function (result) {
      return {
        ok: true,
        type: connection.type,
        serverName: text(result.info.ServerName, connection.name),
        serverId: text(result.info.Id),
        version: text(result.info.Version),
        userId: text(result.user.Id, connection.userId),
        userName: text(result.user.Name)
      };
    });
  }

  function listLibraries(connection) {
    if (connection.type === 'shyni') { return shyniListLibraries(connection); }
    if (connection.type === 'plex') {
      return request(connection, 'GET', '/library/sections', { accept: 'application/json' }).then(function (data) {
        return parsePlexLibraries(connection, data);
      });
    }
    return request(connection, 'GET', '/Users/' + encodeURIComponent(requireUser(connection)) + '/Views', {
      query: { api_key: connection.token }
    }).then(function (data) {
      return array(data && data.Items).map(function (item) {
        var normalized = jellyItem(connection, item);
        normalized.library = true;
        normalized.playable = false;
        normalized.isContainer = true;
        normalized.drillable = true;
        normalized.imageUrl = jellyImageUrl(connection, item, { width: 480, height: 270, quality: 82 });
        return normalized;
      });
    });
  }

  function homeHubKind(title, identifier) {
    var value = (text(identifier) + ' ' + text(title)).toLowerCase();
    if (/continue|on[ -]?deck|next[ -]?up|resume/.test(value)) { return 'continue'; }
    if (/recent|latest|newly[ -]?added/.test(value)) { return 'recent'; }
    return 'recommendation';
  }

  function plexHubItems(connection, hub) {
    if (!hub || !hub.nodeType) { return parsePlexItems(connection, hub); }
    return Array.prototype.slice.call(hub.childNodes || []).filter(function (node) {
      return node && (node.tagName === 'Video' || node.tagName === 'Directory');
    }).map(function (node) { return plexItemFromXml(connection, node); });
  }

  function parsePlexHomeHubs(connection, data, limit) {
    var hubs;
    if (data && data.nodeType) {
      hubs = xmlElements(data, 'Hub').map(function (node) {
        return {
          title: attr(node, 'title', attr(node, 'name', '추천')),
          identifier: attr(node, 'identifier', attr(node, 'hubIdentifier', attr(node, 'key', ''))),
          key: attr(node, 'key', ''),
          source: node
        };
      });
    } else {
      hubs = array((plexContainer(data) || {}).Hub).map(function (hub) {
        return {
          title: text(hub.title || hub.name, '추천'),
          identifier: text(hub.identifier || hub.hubIdentifier || hub.key),
          key: text(hub.key),
          source: hub
        };
      });
    }
    return hubs.map(function (hub, index) {
      var items = plexHubItems(connection, hub.source).slice(0, limit);
      return {
        id: hub.identifier || hub.key || 'home-hub-' + index,
        identifier: hub.identifier,
        title: hub.title,
        kind: homeHubKind(hub.title, hub.identifier),
        items: items
      };
    }).filter(function (hub) { return hub.items.length > 0; });
  }

  function mediaBrowserHomeItems(data) {
    return array(data && data.Items ? data.Items : data);
  }

  function recentLibraryHub(library, items) {
    var id = text(library && (library.id || library.key || library.Id));
    return {
      id: 'recent-library-' + id,
      identifier: 'recent-library-' + id,
      title: text(library && (library.title || library.Name), '라이브러리') + '에 최근에 추가됨',
      kind: 'recent',
      libraryId: id,
      items: items
    };
  }

  function homeLibraries(connection, provided) {
    return Array.isArray(provided) ? global.Promise.resolve(provided) : listLibraries(connection);
  }

  function listPlexLibraryRecentHubs(connection, limit, libraries) {
    return homeLibraries(connection, libraries).then(function (libraries) {
      return global.Promise.all(libraries.map(function (library) {
        return listItems(connection, library, { limit: limit, recent: true }).then(function (result) {
          return recentLibraryHub(library, array(result && result.items).slice(0, limit));
        }).catch(function () {
          return null;
        });
      }));
    }).then(function (hubs) {
      return hubs.filter(function (hub) { return hub && hub.items.length > 0; });
    });
  }

  function listMediaBrowserLibraryRecentHubs(connection, limit, libraries) {
    return homeLibraries(connection, libraries).then(function (libraries) {
      return global.Promise.all(libraries.map(function (library) {
        return request(connection, 'GET', '/Users/' + encodeURIComponent(requireUser(connection)) + '/Items/Latest', {
          query: {
            ParentId: library.id,
            Limit: limit,
            Recursive: 'true',
            Fields: 'Overview,PrimaryImageAspectRatio,UserData,RunTimeTicks,BackdropImageTags,RecursiveItemCount',
            IncludeItemTypes: 'Movie,Series,Episode,Video',
            EnableUserData: 'true',
            api_key: connection.token
          }
        }).then(function (data) {
          var items = mediaBrowserHomeItems(data).slice(0, limit).map(function (item) { return jellyItem(connection, item); });
          return recentLibraryHub(library, items);
        }).catch(function () {
          return null;
        });
      }));
    }).then(function (hubs) {
      return hubs.filter(function (hub) { return hub && hub.items.length > 0; });
    });
  }

  function listHomeHubs(connection, options) {
    options = options || {};
    if (connection.type === 'shyni') { return shyniHomeHubs(connection, options); }
    var limit = Math.max(1, number(options.limit || options.count, 20));
    if (connection.type === 'plex') {
      return global.Promise.all([
        request(connection, 'GET', '/hubs', {
          accept: 'application/json',
          query: { count: limit, includeGuids: 1 }
        }),
        listPlexLibraryRecentHubs(connection, limit, options.libraries)
      ]).then(function (results) {
        var serverHubs = parsePlexHomeHubs(connection, results[0], limit);
        var continueHubs = serverHubs.filter(function (hub) { return hub.kind === 'continue'; });
        var recentHubs = serverHubs.filter(function (hub) { return hub.kind === 'recent'; });
        var recommendationHubs = serverHubs.filter(function (hub) { return hub.kind === 'recommendation'; });
        return continueHubs.concat(results[1].length ? results[1] : recentHubs).concat(recommendationHubs);
      });
    }

    var userId = encodeURIComponent(requireUser(connection));
    var resumePath = connection.type === 'emby' ? '/Users/' + userId + '/Items/Resume' : '/UserItems/Resume';
    var query = {
      UserId: connection.userId,
      Limit: limit,
      Recursive: 'true',
      Fields: 'Overview,PrimaryImageAspectRatio,UserData,RunTimeTicks,BackdropImageTags,RecursiveItemCount',
      IncludeItemTypes: 'Movie,Series,Episode,Video',
      EnableTotalRecordCount: 'false',
      api_key: connection.token
    };
    return global.Promise.all([
      request(connection, 'GET', resumePath, { query: query }),
      listMediaBrowserLibraryRecentHubs(connection, limit, options.libraries)
    ]).then(function (results) {
      var resume = mediaBrowserHomeItems(results[0]);
      if (connection.type === 'emby') {
        resume = resume.filter(function (item) {
          return number(item && item.UserData && item.UserData.PlaybackPositionTicks, 0) > 0;
        });
      }
      var definitions = [
        { id: 'continue-watching', title: '계속 시청', kind: 'continue', values: resume }
      ];
      var hubs = definitions.map(function (definition) {
        return {
          id: definition.id,
          identifier: definition.id,
          title: definition.title,
          kind: definition.kind,
          items: definition.values.slice(0, limit).map(function (item) { return jellyItem(connection, item); })
        };
      }).filter(function (hub) { return hub.items.length > 0; });
      if (results[1].length) { return hubs.concat(results[1]); }
      return request(connection, 'GET', '/Users/' + userId + '/Items/Latest', { query: query }).then(function (data) {
        var items = mediaBrowserHomeItems(data).slice(0, limit).map(function (item) { return jellyItem(connection, item); });
        if (items.length) {
          hubs.push({
            id: 'recently-added',
            identifier: 'recently-added',
            title: '최근에 추가됨',
            kind: 'recent',
            items: items
          });
        }
        return hubs;
      });
    });
  }

  function uniqueItems(items, limit) {
    var seen = {};
    return array(items).filter(function (item) {
      var id = text(item && (item.id || item.key || item.Id || item.ratingKey));
      if (item && item.libraryId) { id = text(item.libraryId) + ':' + id; }
      if (!id || seen[id]) { return false; }
      seen[id] = true;
      return true;
    }).slice(0, Math.max(1, number(limit, 180)));
  }

  function plexHubResults(connection, data, limit) {
    var values = [];
    parsePlexHomeHubs(connection, data, limit).forEach(function (hub) {
      values = values.concat(hub.items);
    });
    return uniqueItems(values, limit);
  }

  function searchItems(connection, query, options) {
    options = options || {};
    query = text(query).trim();
    var limit = Math.min(180, Math.max(1, number(options.limit, 120)));
    if (!query) { return global.Promise.resolve({ items: [], total: 0, size: 0 }); }
    if (connection.type === 'shyni') { return shyniSearch(connection, query, options); }
    if (connection.type === 'plex') {
      return request(connection, 'GET', '/hubs/search', {
        accept: 'application/json',
        query: { query: query, limit: limit, includeCollections: 1, includeExternalMedia: 0 }
      }).then(function (data) {
        var items = plexHubResults(connection, data, limit);
        return { items: items, total: items.length, size: items.length };
      });
    }
    return request(connection, 'GET', '/Users/' + encodeURIComponent(requireUser(connection)) + '/Items', {
      query: {
        SearchTerm: query,
        Recursive: 'true',
        Limit: limit,
        SortBy: 'SortName',
        Fields: 'Overview,PrimaryImageAspectRatio,UserData,RunTimeTicks,BackdropImageTags,RecursiveItemCount',
        IncludeItemTypes: 'Movie,Series,Episode,Video,BoxSet,Playlist',
        EnableUserData: 'true',
        api_key: connection.token
      }
    }).then(function (data) {
      var items = array(data && data.Items).map(function (item) { return jellyItem(connection, item); });
      return { items: uniqueItems(items, limit), total: number(data && data.TotalRecordCount, items.length), size: items.length };
    });
  }

  function listLibraryMode(connection, parent, mode, options) {
    options = options || {};
    mode = text(mode, 'browse').toLowerCase();
    var limit = Math.min(180, Math.max(1, number(options.limit, 180)));
    var parentId = text(parent && (parent.id || parent.key || parent.Id));
    if (mode === 'browse' || connection.type === 'shyni') { return listItems(connection, parent, options); }
    if (connection.type === 'plex') {
      var path;
      var query = { 'X-Plex-Container-Start': 0, 'X-Plex-Container-Size': limit };
      if (mode === 'recommendations') {
        path = parentId ? '/hubs/sections/' + encodeURIComponent(parentId) : '/hubs';
        query.count = limit;
        return request(connection, 'GET', path, { accept: 'application/json', query: query }).then(function (data) {
          var items = plexHubResults(connection, data, limit);
          return { items: items, total: items.length, size: items.length };
        });
      }
      path = mode === 'playlists'
        ? '/playlists/all'
        : parentId
          ? '/library/sections/' + encodeURIComponent(parentId) + '/all'
          : '/library/collections';
      if (mode === 'collections' && parentId) { query.type = 18; }
      return request(connection, 'GET', path, { accept: 'application/json', query: query }).then(function (data) {
        var items = uniqueItems(parsePlexItems(connection, data), limit);
        return { items: items, total: items.length, size: items.length };
      });
    }
    if (mode === 'recommendations') {
      return request(connection, 'GET', '/Movies/Recommendations', {
        query: { UserId: requireUser(connection), ParentId: parentId, CategoryLimit: 6, ItemLimit: 30, Fields: 'Overview,UserData,RunTimeTicks,BackdropImageTags,RecursiveItemCount', api_key: connection.token }
      }).then(function (data) {
        var values = [];
        array(data).forEach(function (group) { values = values.concat(array(group && group.Items)); });
        var items = uniqueItems(values.map(function (item) { return jellyItem(connection, item); }), limit);
        return { items: items, total: items.length, size: items.length };
      }).catch(function () {
        return request(connection, 'GET', '/Users/' + encodeURIComponent(requireUser(connection)) + '/Items', {
          query: {
            ParentId: parentId,
            Recursive: 'true',
            Limit: limit,
            SortBy: 'CommunityRating,DateCreated',
            SortOrder: 'Descending',
            Fields: 'Overview,PrimaryImageAspectRatio,UserData,RunTimeTicks,BackdropImageTags,RecursiveItemCount',
            IncludeItemTypes: 'Movie,Series,Episode,Video',
            EnableUserData: 'true',
            api_key: connection.token
          }
        }).then(function (data) {
          var items = array(data && data.Items).map(function (item) { return jellyItem(connection, item); });
          return { items: uniqueItems(items, limit), total: number(data && data.TotalRecordCount, items.length), size: items.length };
        });
      });
    }
    return request(connection, 'GET', '/Users/' + encodeURIComponent(requireUser(connection)) + '/Items', {
      query: {
        ParentId: mode === 'collections' ? parentId : '',
        Recursive: 'true',
        Limit: limit,
        SortBy: 'SortName',
        Fields: 'Overview,PrimaryImageAspectRatio,UserData,RunTimeTicks,BackdropImageTags,RecursiveItemCount',
        IncludeItemTypes: mode === 'collections' ? 'BoxSet' : 'Playlist',
        EnableUserData: 'true',
        api_key: connection.token
      }
    }).then(function (data) {
      var items = array(data && data.Items).map(function (item) { return jellyItem(connection, item); });
      return { items: uniqueItems(items, limit), total: number(data && data.TotalRecordCount, items.length), size: items.length };
    });
  }

  function listItems(connection, parent, options) {
    options = options || {};
    if (connection.type === 'shyni') { return shyniListItems(connection, parent, options); }
    var parentId = typeof parent === 'string' ? parent : text(parent && (parent.id || parent.key || parent.Id));
    if (!parentId) { return global.Promise.reject(new ApiError('A parent library or item is required', 0, '', 'missing_parent')); }
    if (connection.type === 'plex') {
      var isLibrary = Boolean(parent && parent.library);
      var path = isLibrary ? '/library/sections/' + encodeURIComponent(parentId) + '/all' : '/library/metadata/' + encodeURIComponent(parentId) + '/children';
      var requestedSort = text(options.sortBy).toLowerCase();
      var recentSort = Boolean(options.recent) || requestedSort === 'datecreated' || requestedSort === 'addedat';
      return request(connection, 'GET', path, {
        accept: 'application/json',
        query: {
          'X-Plex-Container-Start': options.offset || options.start || 0,
          'X-Plex-Container-Size': options.limit || 180,
          sort: recentSort ? 'addedAt:desc' : ''
        }
      }).then(function (data) {
        var items = parsePlexItems(connection, data);
        var container = plexContainer(data) || {};
        return {
          items: items,
          total: number(container.totalSize || container.size, items.length),
          offset: number(container.offset, options.offset || 0),
          size: items.length
        };
      });
    }
    return request(connection, 'GET', '/Users/' + encodeURIComponent(requireUser(connection)) + '/Items', {
      query: {
        ParentId: parentId,
        StartIndex: options.offset || options.start || 0,
        Limit: options.limit || 180,
        Recursive: options.recursive ? 'true' : 'false',
        SortBy: options.sortBy || 'SortName',
        SortOrder: options.sortOrder || 'Ascending',
        Fields: 'Overview,PrimaryImageAspectRatio,UserData,RunTimeTicks,BackdropImageTags,RecursiveItemCount',
        IncludeItemTypes: options.includeItemTypes || '',
        EnableUserData: 'true',
        api_key: connection.token
      }
    }).then(function (data) {
      var items = array(data && data.Items).map(function (item) { return jellyItem(connection, item); });
      return {
        items: items,
        total: number(data && data.TotalRecordCount, items.length),
        offset: number(data && data.StartIndex, options.offset || 0),
        size: items.length
      };
    });
  }

  function plexMetadata(connection, itemId) {
    return request(connection, 'GET', '/library/metadata/' + encodeURIComponent(itemId), { accept: 'application/json' }).then(function (data) {
      var items = parsePlexItems(connection, data);
      if (!items.length) { throw new ApiError('Plex media metadata was empty', 0, '/library/metadata/' + itemId, 'empty_metadata'); }
      return items[0];
    });
  }

  function plexCanCopyVideo(info, options) {
    if (!info || !info.complete) { return false; }
    return info.videoCodec === 'h264' && info.bitDepth === 8 &&
      ['baseline', 'constrained baseline', 'main', 'high'].indexOf(info.profile) >= 0 &&
      info.level > 0 && info.level <= 42 && info.frameRate > 0 && info.frameRate <= 60 &&
      info.width > 0 && info.width <= (options.maxVideoWidth || 1920) &&
      info.height > 0 && info.height <= (options.maxVideoHeight || 1080) &&
      info.bitrate > 0 && info.bitrate <= (options.maxVideoBitrateKbps || 8000);
  }

  function plexCanDirectPlay(info, options) {
    // Raw containers with embedded subtitles/unknown streams stay off the old
    // TV decoder. Supported H.264 may still be safely remuxed through Plex.
    return plexCanCopyVideo(info, options) && ['mp4', 'm4v'].indexOf(info.container) >= 0 &&
      !info.embeddedSubtitles && info.audioTracks.length > 0 && info.audioTracks.every(function (track) {
        return track.codec === 'aac' && track.profile === 'lc' && track.channels > 0 && track.channels <= 2;
      });
  }

  function plexHlsProfile(options) {
    var clauses = ['add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mpegts&videoCodec=h264&audioCodec=aac)'];
    function limit(scope, codec, name, value) {
      clauses.push('add-limitation(scope=' + scope + '&scopeName=' + codec + '&type=upperBound&name=' + name + '&value=' + value + '&replace=true)');
    }
    limit('videoCodec', '*', 'video.width', options.maxVideoWidth || 1920);
    limit('videoCodec', '*', 'video.height', options.maxVideoHeight || 1080);
    limit('videoCodec', '*', 'video.bitrate', options.maxVideoBitrateKbps || options.maxBitrate || 20000);
    limit('videoAudioCodec', 'aac', 'audio.channels', options.compatibility ? 2 : 6);
    if (options.compatibility) {
      limit('videoCodec', 'h264', 'video.bitDepth', 8);
      limit('videoCodec', 'h264', 'video.level', 42);
      limit('videoCodec', 'h264', 'video.frameRate', 60);
    }
    return clauses.join('+');
  }

  function checkPlexDecision(data) {
    var container = plexContainer(data) || {};
    var media;
    var streams;
    if (data && data.nodeType) {
      var root = xmlElements(data, 'MediaContainer')[0];
      container = {};
      ['generalDecisionCode', 'transcodeDecisionCode', 'mdeDecisionCode'].forEach(function (key) { container[key] = attr(root, key, 0); });
      var node = xmlElements(data, 'Media')[0];
      media = node ? { container: attr(node, 'container', '') } : null;
      streams = xmlElements(node, 'Stream').map(function (stream) {
        return { streamType: attr(stream, 'streamType', 0), codec: attr(stream, 'codec', ''), decision: attr(stream, 'decision', '') };
      });
    } else {
      media = array((plexJsonEntries(data)[0] || {}).Media)[0];
      streams = array(media && array(media.Part)[0] && array(media.Part)[0].Stream);
    }
    var failure = ['generalDecisionCode', 'transcodeDecisionCode', 'mdeDecisionCode'].filter(function (key) { return number(container[key], 0) >= 2000; })[0];
    if (failure) {
      throw new ApiError('Plex가 호환 재생을 허용하지 않았습니다. 서버의 영상 변환 허용 설정을 확인해 주세요. (Plex ' + number(container[failure], 0) + ')', 0, '', 'plex_decision_refused');
    }
    if (!media || ['mpegts', 'ts'].indexOf(text(media.container).toLowerCase()) < 0) {
      throw new ApiError('Plex가 TV에 맞는 MPEG-TS 재생 형식을 제공하지 않았습니다.', 0, '', 'plex_container_mismatch');
    }
    var video = streams.filter(function (stream) { return number(stream.streamType, 0) === 1; })[0];
    var audio = streams.filter(function (stream) { return number(stream.streamType, 0) === 2; })[0];
    if (!video || text(video.codec).toLowerCase() !== 'h264' || (audio && text(audio.codec).toLowerCase() !== 'aac') ||
        streams.some(function (stream) { return number(stream.streamType, 0) === 3 && stream.decision === 'burn'; })) {
      throw new ApiError('Plex의 재생 응답이 TV의 H.264/AAC 호환 조건과 다릅니다.', 0, '', 'plex_codec_mismatch');
    }
    return video.decision === 'copy' && (!audio || audio.decision === 'copy') ? 'DirectStream' : 'Transcode';
  }

  function resolvePlexPlayback(connection, item, options) {
    options = options || {};
    var itemId = text(item.id || item.key || item.ratingKey);
    var needsDetails = !item.partKey || (options.adaptive && !(item.playbackInfo && item.playbackInfo.complete));
    var detailsPromise = needsDetails ? plexMetadata(connection, itemId) : global.Promise.resolve(item);
    return detailsPromise.then(function (details) {
      var directAllowed = !options.compatibility || (options.adaptive && plexCanDirectPlay(details.playbackInfo, options));
      var direct = details.partKey && directAllowed ? tokenizedUrl(connection, details.partKey) : null;
      var sessionId = randomId();
      var parameters = {
        hasMDE: 1,
        path: '/library/metadata/' + itemId,
        mediaIndex: details.mediaIndex || 0,
        partIndex: details.partIndex || 0,
        protocol: 'hls',
        fastSeek: 1,
        directPlay: 0,
        directStream: options.compatibility ? (options.adaptive && plexCanCopyVideo(details.playbackInfo, options) ? 1 : 0) : 1,
        directStreamAudio: 1,
        videoCodec: 'h264',
        audioCodec: 'aac',
        maxVideoBitrate: options.maxVideoBitrateKbps || options.maxBitrate || 20000,
        videoResolution: options.videoResolution || '',
        videoQuality: 100,
        subtitleSize: 100,
        subtitles: 'none',
        maxAudioChannels: options.compatibility ? 2 : 6,
        session: sessionId,
        'X-Plex-Product': PRODUCT,
        'X-Plex-Version': APP_VERSION,
        // Match Android Plezy's Generic + explicit profile handshake. webOS
        // alone is not a reliable PMS transcoder profile identifier.
        'X-Plex-Platform': 'Generic',
        'X-Plex-Client-Profile-Name': 'Generic',
        'X-Plex-Client-Profile-Extra': plexHlsProfile(options),
        'X-Plex-Session-Identifier': sessionId,
        'X-Plex-Client-Identifier': connection.clientIdentifier,
        'X-Plex-Token': connection.token
      };
      var hls = appendQuery(connection.baseUrl + '/video/:/transcode/universal/start.m3u8', parameters);
      var result = {
        serverType: 'plex',
        itemId: itemId,
        url: direct || hls,
        fallbackUrl: hls,
        sources: [
          direct ? { kind: 'direct', url: direct, mimeType: details.mimeType || '', playMethod: 'DirectPlay' } : null,
          { kind: 'hls', url: hls, mimeType: 'application/vnd.apple.mpegurl', playMethod: 'Transcode' }
        ].filter(Boolean),
        subtitles: details.subtitles || [],
        durationMs: number(details.durationMs || item.durationMs, 0),
        resumePositionMs: number(details.resumePositionMs || item.resumePositionMs, 0),
        playMethod: direct ? 'DirectPlay' : 'Transcode',
        sessionId: sessionId,
        ratingKey: itemId
      };
      if (direct) { return result; }
      // Check errors before handing a decoder an unopened stream. Never fall
      // back to an unsafe raw file when the server refuses conversion.
      return request(connection, 'GET', '/video/:/transcode/universal/decision', {
        query: parameters, headers: { 'X-Plex-Platform': 'Generic' }, accept: 'application/json', timeout: 15000
      }).then(function (data) {
        result.playMethod = checkPlexDecision(data);
        result.sources[0].playMethod = result.playMethod;
        return result;
      }).catch(function (error) {
        if (error && /^plex_/.test(error.code)) { throw error; }
        var status = number(error && error.status, 0);
        throw new ApiError(status ? 'Plex 재생 준비 요청이 거부되었습니다. (HTTP ' + status + ')' :
          'Plex 재생 준비 응답을 받지 못했습니다. 서버 연결 상태를 확인해 주세요.', status, '', 'plex_prepare_failed');
      });
    });
  }

  function videoResolutionCodecProfile(codec, maxWidth, maxHeight) {
    return {
      Type: 'Video',
      Codec: codec,
      Conditions: [
        { Condition: 'LessThanEqual', Property: 'Width', Value: String(maxWidth), IsRequired: false },
        { Condition: 'LessThanEqual', Property: 'Height', Value: String(maxHeight), IsRequired: false }
      ]
    };
  }

  function webOsDeviceProfile(options) {
    options = options || {};
    var maxWidth = number(options.maxVideoWidth, 1920);
    var maxHeight = number(options.maxVideoHeight, 1080);
    return {
      Name: PRODUCT,
      MaxStreamingBitrate: options.maxStreamingBitrate || options.maxBitrate || 120000000,
      MaxStaticBitrate: 120000000,
      MusicStreamingTranscodingBitrate: 192000,
      DirectPlayProfiles: options.compatibility ? [{ Container: 'mp3,aac,m4a,flac,ogg', Type: 'Audio' }] : [
        { Container: 'mp4,m4v,mov,mkv,ts,m2ts', Type: 'Video', VideoCodec: 'h264,hevc,vp9', AudioCodec: 'aac,mp3,ac3,eac3' },
        { Container: 'mp3,aac,m4a,flac,ogg', Type: 'Audio' }
      ],
      TranscodingProfiles: [
        { Container: 'ts', Type: 'Video', Protocol: 'hls', VideoCodec: 'h264', AudioCodec: 'aac', Context: 'Streaming', MaxAudioChannels: options.compatibility ? '2' : '6', EstimateContentLength: false, EnableMpegtsM2TsMode: false, TranscodeSeekInfo: 'Auto', CopyTimestamps: true }
      ],
      CodecProfiles: [
        videoResolutionCodecProfile('h264', maxWidth, maxHeight),
        videoResolutionCodecProfile('hevc', maxWidth, maxHeight),
        videoResolutionCodecProfile('vp9', maxWidth, maxHeight)
      ],
      SubtitleProfiles: [
        { Format: 'vtt', Method: 'External' },
        { Format: 'srt', Method: 'External' },
        { Format: 'ass', Method: 'Encode' },
        { Format: 'ssa', Method: 'Encode' },
        { Format: 'pgssub', Method: 'Encode' }
      ]
    };
  }

  function resolveJellyPlayback(connection, item, options) {
    options = options || {};
    var itemId = text(item.id || item.Id);
    var requestedStartSeconds = number(options.startTimeSeconds, NaN);
    var startTicks = isFinite(requestedStartSeconds)
      ? Math.round(Math.max(0, requestedStartSeconds) * 10000000)
      : Math.round(number(item.resumePositionMs, 0) * 10000);
    return request(connection, 'POST', '/Items/' + encodeURIComponent(itemId) + '/PlaybackInfo', {
      query: {
        UserId: requireUser(connection),
        StartTimeTicks: startTicks,
        IsPlayback: 'true',
        AutoOpenLiveStream: 'true',
        EnableDirectPlay: options.compatibility ? 'false' : 'true',
        EnableDirectStream: options.compatibility ? 'false' : 'true',
        AllowVideoStreamCopy: options.compatibility ? 'false' : 'true',
        AllowAudioStreamCopy: options.compatibility ? 'false' : 'true',
        SubtitleStreamIndex: -1,
        MaxStreamingBitrate: options.maxStreamingBitrate || options.maxBitrate || 120000000,
        api_key: connection.token
      },
      body: {
        UserId: connection.userId,
        StartTimeTicks: startTicks,
        IsPlayback: true,
        AutoOpenLiveStream: true,
        EnableDirectPlay: !options.compatibility,
        EnableDirectStream: !options.compatibility,
        AllowVideoStreamCopy: !options.compatibility,
        AllowAudioStreamCopy: !options.compatibility,
        MaxAudioChannels: options.compatibility ? 2 : 6,
        SubtitleStreamIndex: -1,
        DeviceProfile: webOsDeviceProfile(options)
      }
    }).then(function (data) {
      var mediaSource = array(data && data.MediaSources)[0];
      if (!mediaSource) { throw new ApiError('Server returned no playable media source', 0, '/PlaybackInfo', 'no_media_source'); }
      var playSessionId = text(data.PlaySessionId, randomId());
      var mediaSourceId = text(mediaSource.Id, itemId);
      var itemType = text(item.type || item.Type || item.raw && item.raw.Type).toLowerCase();
      var isAudio = itemType === 'audio' || itemType === 'musicvideoaudio';
      var supportsDirectValue = mediaSource.SupportsDirectPlay;
      var hasDirectPlayFlag = supportsDirectValue !== null && typeof supportsDirectValue !== 'undefined';
      var directPlayAllowed = (!options.compatibility || isAudio) && (!hasDirectPlayFlag || bool(supportsDirectValue));
      var directStreamPath = text(mediaSource.DirectStreamUrl);
      var directStreamAllowed = (!options.compatibility || isAudio) && bool(mediaSource.SupportsDirectStream) && Boolean(directStreamPath);
      var supportsTranscodingValue = mediaSource.SupportsTranscoding;
      var hasTranscodingFlag = supportsTranscodingValue !== null && typeof supportsTranscodingValue !== 'undefined';
      var transcodingAllowed = !hasTranscodingFlag || bool(supportsTranscodingValue);
      var staticDirectPath = isAudio
        ? '/Audio/' + encodeURIComponent(itemId) + '/stream'
        : '/Videos/' + encodeURIComponent(itemId) + '/stream';
      var directPath = directPlayAllowed
        ? directStreamPath || staticDirectPath
        : directStreamAllowed
          ? directStreamPath
          : '';
      var direct = directPath ? tokenizedUrl(connection, directPath, {
        Static: 'true',
        MediaSourceId: mediaSourceId,
        PlaySessionId: playSessionId,
        DeviceId: connection.clientIdentifier
      }) : '';
      var hlsPath = text(mediaSource.TranscodingUrl);
      var hls = isAudio || !transcodingAllowed
        ? ''
        : hlsPath
          ? tokenizedUrl(connection, hlsPath)
          : tokenizedUrl(connection, '/Videos/' + encodeURIComponent(itemId) + '/master.m3u8', {
              DeviceId: connection.clientIdentifier,
              MediaSourceId: mediaSourceId,
              PlaySessionId: playSessionId,
              VideoCodec: 'h264',
              AudioCodec: 'aac',
              MaxStreamingBitrate: options.maxStreamingBitrate || options.maxBitrate || 120000000,
              MaxWidth: number(options.maxVideoWidth, 1920),
              MaxHeight: number(options.maxVideoHeight, 1080),
              TranscodingMaxAudioChannels: options.compatibility ? 2 : 6,
              SubtitleStreamIndex: -1,
              RequireAvc: options.compatibility ? 'true' : 'false',
              AllowVideoStreamCopy: options.compatibility ? 'false' : 'true',
              AllowAudioStreamCopy: options.compatibility ? 'false' : 'true',
              SegmentContainer: 'ts'
            });
      if (!direct && !hls) {
        if (options.compatibility && !isAudio) {
          throw new ApiError('호환 재생에는 서버의 영상 변환 권한이 필요합니다. 원본 재생은 안전을 위해 시도하지 않았습니다.', 0, '/PlaybackInfo', 'transcoding_required');
        }
        directPath = staticDirectPath;
        direct = tokenizedUrl(connection, directPath, {
          Static: 'true',
          MediaSourceId: mediaSourceId,
          PlaySessionId: playSessionId,
          DeviceId: connection.clientIdentifier
        });
      }
      var playMethod = direct
        ? (!directPlayAllowed && directStreamAllowed ? 'DirectStream' : 'DirectPlay')
        : 'Transcode';
      var streams = array(mediaSource.MediaStreams);
      var subtitles = streams.filter(function (stream) { return text(stream.Type).toLowerCase() === 'subtitle'; }).map(function (stream) {
        return jellySubtitle(connection, itemId, mediaSourceId, stream);
      });
      var durationTicks = number(mediaSource.RunTimeTicks || item.raw && item.raw.RunTimeTicks, 0);
      return {
        serverType: connection.type,
        itemId: itemId,
        directUrl: direct,
        hlsUrl: hls,
        url: direct || hls,
        fallbackUrl: hls,
        sources: [
          direct ? { kind: 'direct', url: direct, mimeType: mediaMimeType(mediaSource.Container, isAudio), playMethod: playMethod } : null,
          hls ? { kind: 'hls', url: hls, mimeType: 'application/vnd.apple.mpegurl', playMethod: 'Transcode' } : null
        ].filter(Boolean),
        subtitles: subtitles,
        durationMs: durationTicks > 0 ? durationTicks / 10000 : number(item.durationMs, 0),
        resumePositionMs: number(item.resumePositionMs, 0),
        mediaSourceId: mediaSourceId,
        playSessionId: playSessionId,
        liveStreamId: text(mediaSource.LiveStreamId),
        playMethod: playMethod
      };
    });
  }

  function resolvePlayback(connection, item, options) {
    if (!item || !(item.id || item.key || item.Id)) {
      return global.Promise.reject(new ApiError('A playable item is required', 0, '', 'missing_item'));
    }
    if (connection.type === 'shyni') { return shyniResolvePlayback(connection, item, options); }
    return connection.type === 'plex'
      ? resolvePlexPlayback(connection, item, options)
      : resolveJellyPlayback(connection, item, options);
  }

  function imageUrl(connection, itemOrPath, options) {
    options = options || {};
    if (connection.type === 'shyni') {
      return typeof itemOrPath === 'string' ? shyniAbsolute(connection, itemOrPath) :
        itemOrPath && (itemOrPath.imageUrl || itemOrPath.posterUrl || itemOrPath.backdropUrl) || null;
    }
    if (typeof itemOrPath === 'string' && (itemOrPath.charAt(0) === '/' || /^https?:\/\//i.test(itemOrPath))) {
      return tokenizedUrl(connection, itemOrPath, options);
    }
    if (itemOrPath && itemOrPath.imageUrl) { return itemOrPath.imageUrl; }
    if (connection.type === 'plex') {
      var plexPath = itemOrPath && (itemOrPath.thumb || itemOrPath.art || itemOrPath.raw && (itemOrPath.raw.thumb || itemOrPath.raw.art));
      return plexPath ? tokenizedUrl(connection, plexPath, options) : null;
    }
    return jellyImageUrl(connection, itemOrPath, options);
  }

  function subtitleUrl(connection, descriptor, options) {
    var path = typeof descriptor === 'string' ? descriptor : descriptor && (descriptor.url || descriptor.deliveryUrl || descriptor.key);
    if (connection.type === 'shyni') { return path ? shyniAbsolute(connection, path) : null; }
    return path ? tokenizedUrl(connection, path, options) : null;
  }

  function reportPlex(connection, playback, state, overrides) {
    overrides = overrides || {};
    return request(connection, 'GET', '/:/timeline', {
      query: {
        ratingKey: playback.ratingKey || playback.itemId,
        key: '/library/metadata/' + (playback.ratingKey || playback.itemId),
        state: state,
        time: Math.max(0, Math.round(number(overrides.positionMs, 0))),
        duration: Math.max(0, Math.round(number(overrides.durationMs || playback.durationMs, 0))),
        'X-Plex-Client-Identifier': connection.clientIdentifier,
        'X-Plex-Token': connection.token
      }
    }).then(function () {
      return { ok: true, state: state, itemId: playback.itemId, positionMs: number(overrides.positionMs, 0) };
    });
  }

  function jellyProgressBody(connection, playback, overrides) {
    overrides = overrides || {};
    return {
      ItemId: playback.itemId,
      MediaSourceId: playback.mediaSourceId,
      PlaySessionId: playback.playSessionId,
      LiveStreamId: playback.liveStreamId || null,
      PlayMethod: overrides.playMethod || playback.playMethod || 'DirectPlay',
      PositionTicks: Math.max(0, Math.round(number(overrides.positionMs, 0) * 10000)),
      IsPaused: Boolean(overrides.isPaused),
      IsMuted: Boolean(overrides.isMuted),
      AudioStreamIndex: typeof overrides.audioStreamIndex === 'number' ? overrides.audioStreamIndex : null,
      SubtitleStreamIndex: typeof overrides.subtitleStreamIndex === 'number' ? overrides.subtitleStreamIndex : null,
      CanSeek: true,
      VolumeLevel: 100,
      RepeatMode: 'RepeatNone'
    };
  }

  function reportJelly(connection, playback, state, overrides) {
    var endpoint = state === 'playing' ? '/Sessions/Playing' : state === 'stopped' ? '/Sessions/Playing/Stopped' : '/Sessions/Playing/Progress';
    var body = jellyProgressBody(connection, playback, overrides);
    if (state !== 'playing' && state !== 'stopped') {
      body.EventName = overrides && overrides.eventName
        ? overrides.eventName
        : body.IsPaused ? 'Pause' : 'TimeUpdate';
    }
    return request(connection, 'POST', endpoint, {
      query: { api_key: connection.token },
      body: body
    }).then(function () {
      return { ok: true, state: state, itemId: playback.itemId, positionMs: number(overrides && overrides.positionMs, 0) };
    });
  }

  function report(connection, playback, state, overrides) {
    if (!playback || !playback.itemId) {
      return global.Promise.resolve({ ok: false, state: state, itemId: '', positionMs: 0 });
    }
    if (connection.type === 'shyni') { return shyniReport(connection, playback, state, overrides); }
    return connection.type === 'plex'
      ? reportPlex(connection, playback, state === 'progress' ? (overrides && overrides.isPaused ? 'paused' : 'playing') : state, overrides)
      : reportJelly(connection, playback, state === 'progress' ? 'progress' : state, overrides);
  }

  function plexPlaylistTarget(connection, playlistUrl, body) {
    var lines = text(body).replace(/^\uFEFF/, '').trim().split(/\r?\n/);
    if (lines[0] !== '#EXTM3U') {
      throw new ApiError('Plex 서버가 영상 재생 목록을 보내지 않았습니다.', 0, '', 'plex_manifest_invalid');
    }
    // Old LG HLS cannot handle separate audio/subtitle groups or fMP4.
    if (lines.some(function (line) { return /^#EXT-X-(MEDIA:|MAP:)/.test(line); })) {
      throw new ApiError('이 TV에서 지원하지 않는 분리형 HLS입니다. 호환 재생을 선택해 주세요.', 0, '', 'plex_manifest_unsupported');
    }
    var variant = '';
    for (var i = 0; i < lines.length; i += 1) {
      if (/^#EXT-X-STREAM-INF:/.test(lines[i])) {
        if (!/CODECS=/i.test(lines[i]) || /avc1/i.test(lines[i])) {
          for (var j = i + 1; j < lines.length; j += 1) {
            if (lines[j] && lines[j].charAt(0) !== '#') { variant = lines[j].trim(); break; }
          }
          break;
        }
      }
    }
    if (!variant) {
      if (!lines.some(function (line) { return /^#EXTINF:/.test(line); }) ||
          !lines.some(function (line) { return line.trim() && line.trim().charAt(0) !== '#'; })) {
        throw new ApiError('Plex 서버가 아직 재생할 영상 조각을 준비하지 못했습니다.', 0, '', 'plex_manifest_empty');
      }
      return playlistUrl;
    }
    var resolved;
    try { resolved = new global.URL(variant, playlistUrl).href; } catch (ignore) {
      resolved = /^https?:\/\//i.test(variant) ? variant :
        variant.charAt(0) === '/' ? connection.baseUrl + variant :
          safePath(playlistUrl).slice(0, safePath(playlistUrl).lastIndexOf('/') + 1) + variant;
    }
    if (!sameOrigin(resolved, connection.baseUrl)) {
      throw new ApiError('Plex 재생 목록의 서버 주소가 일치하지 않습니다.', 0, '', 'plex_manifest_origin');
    }
    return /[?&]X-Plex-Token=/.test(resolved) ? resolved : tokenizedUrl(connection, resolved);
  }

  function waitFor(milliseconds) {
    return new global.Promise(function (resolve) {
      global.setTimeout(resolve, Math.max(0, number(milliseconds, 0)));
    });
  }

  function shyniHlsManifestReady(body) {
    var lines = text(body).replace(/^\uFEFF/, '').trim().split(/\r?\n/);
    if (lines[0] !== '#EXTM3U') { return false; }
    if (lines.some(function (line) { return /^#EXT-X-(?:MAP:|MEDIA:)/.test(line); })) {
      throw new ApiError('이 TV에서 지원하지 않는 HLS 형식입니다.', 0, '', 'shyni_hls_unsupported');
    }
    return lines.some(function (line) { return /^#EXTINF:/.test(line); }) &&
      lines.some(function (line) { return line && line.charAt(0) !== '#'; });
  }

  function prepareShyniHls(connection, playback) {
    if (!playback || playback.playMethod !== 'Transcode' || !playback.partId || !playback.transcodeTicket) {
      return global.Promise.resolve(playback);
    }
    var context = {};
    var startMs = Math.max(0, number(playback.requestedStartPositionMs, playback.resumePositionMs));
    if (playback.progressToken) {
      context.playback_context_token = playback.progressToken;
    } else {
      context.library_view_id = playback.libraryId;
      context.view_item_id = playback.viewItemId;
    }
    context.mode = 'video';
    context.start_ms = Math.round(startMs);
    context.max_height = Math.min(1080, Math.max(480, number(playback.maxVideoHeight, 1080)));
    context.segment_format = 'mpegts';
    context.client_session_id = playback.sessionId;
    if (number(playback.selectedAudioStreamIndex, -1) >= 0) {
      context.audio_stream_index = number(playback.selectedAudioStreamIndex, -1);
    }
    return request(connection, 'POST', '/transcode/' + encodeURIComponent(playback.partId) + '/hls', {
      query: context, body: {}, timeout: 30000
    }).then(function (created) {
      created = created || {};
      var sessionId = text(created.session_id || created.sessionId);
      var playlist = text(created.playlist || created.playlist_url || created.url);
      if (!sessionId || !playlist) {
        throw new ApiError('TV용 HLS 재생 정보를 받지 못했습니다.', 0, '/transcode/hls', 'missing_hls_session');
      }
      playlist = shyniAbsolute(connection, playlist);
      if (!sameOrigin(playlist, connection.baseUrl)) {
        throw new ApiError('HLS 재생 서버 주소가 일치하지 않습니다.', 0, '', 'shyni_hls_origin');
      }
      playlist = appendQuery(playlist, { ticket: playback.transcodeTicket });
      var attempts = 0;
      function readManifest() {
        attempts += 1;
        return request(connection, 'GET', playlist, {
          rawText: true, responseInfo: true, maxResponseBytes: 262144,
          accept: 'application/vnd.apple.mpegurl', timeout: 12000
        }).then(function (response) {
          if (!sameOrigin(response.url, connection.baseUrl)) {
            throw new ApiError('HLS 재생 서버 주소가 일치하지 않습니다.', 0, '', 'shyni_hls_origin');
          }
          if (shyniHlsManifestReady(response.body)) { return response.url; }
          if (attempts >= 20) {
            throw new ApiError('TV용 영상 조각 준비 시간이 초과되었습니다.', 0, '', 'shyni_hls_empty');
          }
          return waitFor(500).then(readManifest);
        }).catch(function (error) {
          if (attempts >= 20 || (error && error.code === 'shyni_hls_unsupported')) { throw error; }
          return waitFor(500).then(readManifest);
        });
      }
      return readManifest().then(function (readyUrl) {
        playback.url = readyUrl;
        playback.directUrl = '';
        playback.hlsUrl = readyUrl;
        playback.fallbackUrl = readyUrl;
        playback.sources = [{ kind: 'hls', url: readyUrl,
          mimeType: 'application/vnd.apple.mpegurl', playMethod: 'Transcode' }];
        playback.mimeType = 'application/vnd.apple.mpegurl';
        playback.hlsSessionId = sessionId;
        playback.deliveryMode = 'hls-mpegts';
        playback.streamStartPositionMs = startMs;
        playback.resumePositionMs = 0;
        return playback;
      });
    }).catch(function (error) {
      if (error && /^shyni_hls_/.test(error.code || '')) { throw error; }
      throw new ApiError('Shyni TV 호환 영상을 준비하지 못했습니다.' +
        (error && error.status ? ' (HTTP ' + error.status + ')' : ''),
      number(error && error.status, 0), '', 'shyni_hls_prepare');
    });
  }

  function preparePlayback(connection, playback) {
    if (connection.type === 'shyni') { return prepareShyniHls(connection, playback); }
    if (!playback || connection.type !== 'plex' || playback.playMethod === 'DirectPlay') {
      return global.Promise.resolve(playback);
    }
    var source = playback.url;
    if (!sameOrigin(source, connection.baseUrl)) {
      return global.Promise.reject(new ApiError('Plex 재생 서버 주소가 일치하지 않습니다.', 0, '', 'plex_manifest_origin'));
    }
    function read(url) {
      return request(connection, 'GET', url, { rawText: true, responseInfo: true, maxResponseBytes: 262144,
        accept: 'application/vnd.apple.mpegurl', headers: { 'X-Plex-Platform': 'Generic' }, timeout: 20000 });
    }
    return read(source).then(function (response) {
      if (!sameOrigin(response.url, connection.baseUrl)) {
        throw new ApiError('Plex 재생 응답의 서버 주소가 일치하지 않습니다.', 0, '', 'plex_manifest_origin');
      }
      var target = plexPlaylistTarget(connection, response.url, response.body);
      if (target === response.url) { return target; }
      // Hand the TV the short media playlist, not a master with codec/profile
      // negotiation parameters. Confirm segments exist before starting its decoder.
      return read(target).then(function (response) {
        if (!sameOrigin(response.url, connection.baseUrl)) {
          throw new ApiError('Plex 영상 목록의 서버 주소가 일치하지 않습니다.', 0, '', 'plex_manifest_origin');
        }
        if (plexPlaylistTarget(connection, response.url, response.body) !== response.url) {
          throw new ApiError('Plex 재생 목록이 중첩되어 있습니다.', 0, '', 'plex_manifest_nested');
        }
        return response.url;
      });
    }).then(function (url) {
      url = /[?&]X-Plex-Token=/i.test(url) ? url : tokenizedUrl(connection, url);
      playback.url = url;
      playback.fallbackUrl = url;
      playback.sources.forEach(function (entry) { if (entry.kind === 'hls') { entry.url = url; } });
      return playback;
    }).catch(function (error) {
      if (error && /^plex_manifest_/.test(error.code)) { throw error; }
      throw new ApiError('Plex 영상 준비에 실패했습니다. 서버 변환 상태와 연결을 확인해 주세요.' +
        (error && error.status ? ' (HTTP ' + error.status + ')' : ''), number(error && error.status, 0), '', 'plex_manifest_request');
    });
  }

  function stopTranscode(connection, playback) {
    if (connection.type === 'shyni') {
      if (!playback || !playback.hlsSessionId) { return global.Promise.resolve(null); }
      return request(connection, 'DELETE', '/transcode/hls/' + encodeURIComponent(playback.hlsSessionId), {
        timeout: 5000
      }).catch(function () { return null; });
    }
    if (!playback || connection.type !== 'plex' || !playback.sessionId) { return global.Promise.resolve(null); }
    return request(connection, 'GET', '/video/:/transcode/universal/stop', {
      query: { session: playback.sessionId }, timeout: 5000
    }).catch(function () { return null; });
  }

  function reportCompleted(connection, playback) {
    if (!playback || !playback.itemId) {
      return global.Promise.resolve({ ok: false, skipped: true });
    }
    if (connection.type === 'shyni') {
      return shyniReport(connection, playback, 'completed', { positionMs: playback.durationMs, durationMs: playback.durationMs });
    }
    if (connection.type === 'plex') {
      return request(connection, 'GET', '/:/scrobble', {
        query: {
          key: playback.ratingKey || playback.itemId,
          identifier: 'com.plexapp.plugins.library',
          'X-Plex-Client-Identifier': connection.clientIdentifier,
          'X-Plex-Token': connection.token
        }
      }).then(function () {
        return { ok: true, completed: true, itemId: playback.itemId };
      });
    }
    return request(connection, 'POST', '/Users/' + encodeURIComponent(connection.userId) + '/PlayedItems/' + encodeURIComponent(playback.itemId), {
      query: { api_key: connection.token }
    }).then(function () {
      return { ok: true, completed: true, itemId: playback.itemId };
    });
  }

  function logout(connection) {
    if (connection.type === 'shyni') {
      return request(connection, 'POST', '/auth/logout', { body: {} }).catch(function () { return null; }).then(function () {
        return { ok: true, skipped: false };
      });
    }
    if (connection.type === 'plex') {
      return global.Promise.resolve({ ok: false, skipped: true });
    }
    return request(connection, 'POST', '/Sessions/Logout').then(function () {
      return { ok: true, skipped: false };
    });
  }

  function Client(connectionOrId) {
    if (!(this instanceof Client)) { return new Client(connectionOrId); }
    this.connection = resolveConnection(connectionOrId);
  }

  Client.prototype.testConnection = function (options) {
    var self = this;
    return testConnection(this.connection).then(function (result) {
      if (result.userId) { self.connection.userId = result.userId; }
      if (result.serverName) { self.connection.name = result.serverName; }
      if (!options || options.persist !== false) { saveConnection(self.connection); }
      return result;
    });
  };
  Client.prototype.listLibraries = function () { return listLibraries(this.connection); };
  Client.prototype.getLibraries = Client.prototype.listLibraries;
  Client.prototype.listHomeHubs = function (options) { return listHomeHubs(this.connection, options); };
  Client.prototype.listItems = function (parent, options) { return listItems(this.connection, parent, options); };
  Client.prototype.getItems = Client.prototype.listItems;
  Client.prototype.drillItems = Client.prototype.listItems;
  Client.prototype.searchItems = function (query, options) { return searchItems(this.connection, query, options); };
  Client.prototype.listLibraryMode = function (parent, mode, options) { return listLibraryMode(this.connection, parent, mode, options); };
  Client.prototype.resolvePlayback = function (item, options) { return resolvePlayback(this.connection, item, options); };
  Client.prototype.getPlayback = Client.prototype.resolvePlayback;
  Client.prototype.preparePlayback = function (playback) { return preparePlayback(this.connection, playback); };
  Client.prototype.stopTranscode = function (playback) { return stopTranscode(this.connection, playback); };
  Client.prototype.imageUrl = function (itemOrPath, options) { return imageUrl(this.connection, itemOrPath, options); };
  Client.prototype.fetchImage = function (itemOrPath) {
    return this.connection.type === 'shyni' ? shyniFetchImage(this.connection, itemOrPath) :
      global.Promise.resolve(imageUrl(this.connection, itemOrPath));
  };
  Client.prototype.subtitleUrl = function (descriptor, options) { return subtitleUrl(this.connection, descriptor, options); };
  Client.prototype.reportPlaying = function (playback, overrides) { return report(this.connection, playback, 'playing', overrides); };
  Client.prototype.reportProgress = function (playback, overrides) { return report(this.connection, playback, 'progress', overrides); };
  Client.prototype.updateProgress = Client.prototype.reportProgress;
  Client.prototype.reportStopped = function (playback, overrides) { return report(this.connection, playback, 'stopped', overrides); };
  Client.prototype.reportCompleted = function (playback) { return reportCompleted(this.connection, playback); };
  Client.prototype.markPlayed = Client.prototype.reportCompleted;
  Client.prototype.logout = function () { return logout(this.connection); };
  Client.prototype.signOut = Client.prototype.logout;

  function createClient(connectionOrId) { return new Client(connectionOrId); }

  var api = {
    version: APP_VERSION,
    ApiError: ApiError,
    Client: Client,
    createClient: createClient,
    normalizeConnection: normalizeConnection,
    buildMediaBrowserCandidates: buildMediaBrowserCandidates,
    probeMediaBrowser: probeMediaBrowser,
    authenticateByName: authenticateByName,
    completeShyniTotp: completeShyniTotp,
    isQuickConnectEnabled: isQuickConnectEnabled,
    initiateQuickConnect: initiateQuickConnect,
    checkQuickConnect: checkQuickConnect,
    authenticateWithQuickConnect: authenticateWithQuickConnect,
    getPlexAuthUrl: getPlexAuthUrl,
    createPlexPin: createPlexPin,
    checkPlexPin: checkPlexPin,
    getPlexResources: getPlexResources,
    saveConnection: saveConnection,
    listConnections: listConnections,
    getConnections: listConnections,
    getConnection: getConnection,
    removeConnection: removeConnection,
    clearConnections: clearConnections,
    setActiveConnection: setActiveConnection,
    getActiveConnection: getActiveConnection,
    testConnection: function (connection) { return testConnection(resolveConnection(connection)); },
    listLibraries: function (connection) { return listLibraries(resolveConnection(connection)); },
    listHomeHubs: function (connection, options) { return listHomeHubs(resolveConnection(connection), options); },
    listItems: function (connection, parent, options) { return listItems(resolveConnection(connection), parent, options); },
    drillItems: function (connection, parent, options) { return listItems(resolveConnection(connection), parent, options); },
    searchItems: function (connection, query, options) { return searchItems(resolveConnection(connection), query, options); },
    listLibraryMode: function (connection, parent, mode, options) { return listLibraryMode(resolveConnection(connection), parent, mode, options); },
    resolvePlayback: function (connection, item, options) { return resolvePlayback(resolveConnection(connection), item, options); },
    imageUrl: function (connection, itemOrPath, options) { return imageUrl(resolveConnection(connection), itemOrPath, options); },
    fetchImage: function (connection, itemOrPath) {
      connection = resolveConnection(connection);
      return connection.type === 'shyni' ? shyniFetchImage(connection, itemOrPath) : global.Promise.resolve(imageUrl(connection, itemOrPath));
    },
    subtitleUrl: function (connection, descriptor, options) { return subtitleUrl(resolveConnection(connection), descriptor, options); },
    reportPlaying: function (connection, playback, overrides) { return report(resolveConnection(connection), playback, 'playing', overrides); },
    reportProgress: function (connection, playback, overrides) { return report(resolveConnection(connection), playback, 'progress', overrides); },
    reportStopped: function (connection, playback, overrides) { return report(resolveConnection(connection), playback, 'stopped', overrides); },
    reportCompleted: function (connection, playback) { return reportCompleted(resolveConnection(connection), playback); },
    logout: function (connection) { return logout(resolveConnection(connection)); },
    helpers: {
      appendQuery: appendQuery,
      safePath: safePath,
      sameOrigin: sameOrigin,
      tokenizedUrl: function (connection, path, extra) { return tokenizedUrl(normalizeConnection(connection), path, extra); }
    }
  };

  global.ShyniFlexApi = api;
  global.ShyniFlexWebOS = global.ShyniFlexWebOS || {};
  global.ShyniFlexWebOS.Api = api;
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
