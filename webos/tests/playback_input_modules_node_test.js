'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');
var vm = require('vm');

var appJs = path.resolve(__dirname, '..', 'app', 'js');

function loadScripts(files, extras) {
  var sandbox = {
    console: console,
    Promise: Promise,
    Date: Date,
    Math: Math,
    Error: Error,
    ArrayBuffer: ArrayBuffer,
    isFinite: isFinite,
    setTimeout: setTimeout,
    clearTimeout: clearTimeout
  };
  Object.keys(extras || {}).forEach(function (key) {
    sandbox[key] = extras[key];
  });
  sandbox.window = sandbox;
  sandbox.global = sandbox;
  vm.createContext(sandbox);
  files.forEach(function (file) {
    vm.runInContext(
      fs.readFileSync(path.join(appJs, file), 'utf8'),
      sandbox,
      { filename: file }
    );
  });
  return sandbox;
}

function FakeElement(name, rect) {
  this.nodeName = String(name || 'div').toUpperCase();
  this.style = {};
  this.attributes = {};
  this.children = [];
  this.parentNode = null;
  this.firstChild = null;
  this.listeners = {};
  this.textContent = '';
  this.className = '';
  this.disabled = false;
  this.hidden = false;
  this.rect = rect || { left: 0, top: 0, right: 1000, bottom: 500 };
}

FakeElement.prototype.appendChild = function (child) {
  child.parentNode = this;
  this.children.push(child);
  this.firstChild = this.children[0] || null;
  return child;
};

FakeElement.prototype.removeChild = function (child) {
  var index = this.children.indexOf(child);
  if (index >= 0) {
    this.children.splice(index, 1);
  }
  child.parentNode = null;
  this.firstChild = this.children[0] || null;
  return child;
};

FakeElement.prototype.addEventListener = function (name, handler) {
  this.listeners[name] = this.listeners[name] || [];
  this.listeners[name].push(handler);
};

FakeElement.prototype.removeEventListener = function (name, handler) {
  var listeners = this.listeners[name] || [];
  var index = listeners.indexOf(handler);
  if (index >= 0) {
    listeners.splice(index, 1);
  }
};

FakeElement.prototype.dispatch = function (name, event) {
  (this.listeners[name] || []).slice().forEach(function (handler) {
    handler(event || {});
  });
};

FakeElement.prototype.setAttribute = function (name, value) {
  this.attributes[name] = String(value);
};

FakeElement.prototype.getAttribute = function (name) {
  return Object.prototype.hasOwnProperty.call(this.attributes, name)
    ? this.attributes[name]
    : null;
};

FakeElement.prototype.removeAttribute = function (name) {
  delete this.attributes[name];
  if (name === 'src') {
    this.src = '';
  }
};

FakeElement.prototype.getBoundingClientRect = function () {
  var rect = this.rect;
  return {
    left: rect.left,
    top: rect.top,
    right: rect.right,
    bottom: rect.bottom,
    width: rect.right - rect.left,
    height: rect.bottom - rect.top
  };
};

FakeElement.prototype.focus = function () {
  if (this.ownerDocument) {
    this.ownerDocument.activeElement = this;
  }
};

FakeElement.prototype.click = function () {
  this.clicks = (this.clicks || 0) + 1;
};

function FakeVideo(container) {
  FakeElement.call(this, 'video');
  this.parentNode = container;
  this.currentTime = 0;
  this.duration = 100;
  this.paused = true;
  this.ended = false;
  this.src = '';
  this.loadCount = 0;
  this.playCount = 0;
  this.pauseCount = 0;
  this.buffered = {
    length: 1,
    start: function () {
      return 0;
    },
    end: function () {
      return 60;
    }
  };
}

FakeVideo.prototype = Object.create(FakeElement.prototype);
FakeVideo.prototype.constructor = FakeVideo;
FakeVideo.prototype.load = function () {
  this.loadCount += 1;
};
FakeVideo.prototype.play = function () {
  this.paused = false;
  this.playCount += 1;
  return Promise.resolve();
};
FakeVideo.prototype.pause = function () {
  this.paused = true;
  this.pauseCount += 1;
};

function makeDocument() {
  var document = new FakeElement('document');
  document.activeElement = null;
  document.createElement = function (name) {
    var element = new FakeElement(name);
    element.ownerDocument = document;
    return element;
  };
  document.querySelector = function () {
    return null;
  };
  document.querySelectorAll = function () {
    return [];
  };
  return document;
}

function testSubtitleParsingAndPresentation() {
  var document = makeDocument();
  var sandbox = loadScripts(['subtitles.js'], {
    document: document,
    getComputedStyle: function () {
      return { position: 'relative' };
    }
  });
  var Subtitles = sandbox.ShyniFlexWebOS.Subtitles;
  var vtt = [
    'WEBVTT',
    '',
    'intro',
    '00:00:01.000 --> 00:00:03.250 align:center',
    '<i>안녕</i> (2026) &amp; ABC!',
    '',
    '00:03.500 --> 00:05.000',
    '둘째 줄<br>계속'
  ].join('\n');
  var srt = [
    '1',
    '00:00:01,500 --> 00:00:02,750',
    '첫째',
    '',
    '2',
    '00:00:03,000 --> 00:00:04,000',
    '둘째'
  ].join('\n');
  var vttCues = Subtitles.parseWebVTT(vtt);
  var srtCues = Subtitles.parseSRT(srt);
  assert.strictEqual(vttCues.length, 2);
  assert.strictEqual(vttCues[0].id, 'intro');
  assert.strictEqual(vttCues[0].start, 1);
  assert.strictEqual(vttCues[0].end, 3.25);
  assert.strictEqual(vttCues[0].text, '안녕 (2026) & ABC!');
  assert.strictEqual(vttCues[1].text, '둘째 줄\n계속');
  assert.strictEqual(srtCues.length, 2);
  assert.strictEqual(srtCues[0].start, 1.5);

  var container = new FakeElement('div');
  var video = new FakeVideo(container);
  var changes = [];
  var subtitles = new Subtitles(video, {
    container: container,
    mode: 'vertical-rl',
    x: 70,
    y: 45,
    onPositionChange: function (position, source) {
      changes.push({ position: position, source: source });
    }
  });
  subtitles.setCues(vttCues);
  assert.strictEqual(subtitles.update(2), '안녕 (2026) & ABC!');
  assert.strictEqual(subtitles.cueElement.textContent, '안녕 (2026) ＆ ABC！');
  assert.strictEqual(subtitles.cueElement.style.writingMode, 'vertical-rl');
  assert.strictEqual(subtitles.cueElement.style.textOrientation, 'upright');
  assert.strictEqual(subtitles.cueElement.style.textAlign, 'center');

  var symbolSource = '가격 $10 (50%) & A&B!\n[OK] #1';
  var symbolExpected = '가격 ＄10 (50％) ＆ A＆B！\n[OK] ＃1';
  subtitles.setCues([{ start: 0, end: 10, text: symbolSource, order: 0 }]);
  assert.strictEqual(subtitles.update(2), symbolSource);
  assert.strictEqual(subtitles.cueElement.textContent, symbolExpected);
  subtitles.setMode('horizontal');
  assert.strictEqual(subtitles.cueElement.textContent, symbolSource);
  subtitles.setMode('vertical-rl');
  assert.strictEqual(subtitles.cueElement.textContent, symbolExpected);

  subtitles.setAdjustmentMode(true);
  assert.strictEqual(subtitles.handleRemoteKey(39), true);
  assert.strictEqual(subtitles.x, 72);
  assert.strictEqual(subtitles.handleRemoteKey(40), true);
  assert.strictEqual(subtitles.y, 47);
  assert.strictEqual(changes[0].source, 'dpad');
  assert.strictEqual(subtitles.handleRemoteKey(13), true);
  assert.strictEqual(subtitles.adjustmentMode, false);

  subtitles.cueElement.dispatch('mousedown', {
    clientX: 250,
    clientY: 100,
    preventDefault: function () {}
  });
  document.dispatch('mousemove', {
    clientX: 800,
    clientY: 400,
    preventDefault: function () {}
  });
  document.dispatch('mouseup', {});
  assert.strictEqual(subtitles.x, 80);
  assert.strictEqual(subtitles.y, 80);
  assert.strictEqual(changes[changes.length - 1].source, 'pointer');

  return subtitles.loadFontFromUrl('Unavailable font', 'https://example.invalid/font.ttf')
    .then(function (loaded) {
      assert.strictEqual(loaded, false);
      assert.strictEqual(subtitles.fontFamily, 'sans-serif');
      subtitles.destroy();
    });
}

function testBundledFontActivation() {
  var document = makeDocument();
  var addedFonts = [];
  var createdFont = null;
  document.fonts = {
    add: function (font) {
      addedFonts.push(font);
    }
  };
  function FakeFontFace(family, source, descriptors) {
    this.family = family;
    this.source = source;
    this.descriptors = descriptors;
    createdFont = this;
  }
  FakeFontFace.prototype.load = function () {
    return Promise.resolve(this);
  };
  var sandbox = loadScripts(['subtitles.js'], {
    document: document,
    FontFace: FakeFontFace,
    getComputedStyle: function () {
      return { position: 'relative' };
    }
  });
  var container = new FakeElement('div');
  var video = new FakeVideo(container);
  var subtitles = new sandbox.ShyniFlexWebOS.Subtitles(video, {
    container: container,
    fallbackFont: 'sans-serif'
  });

  return subtitles.loadFontFromUrl('Shyni Custom', 'assets/custom.ttf')
    .then(function (loaded) {
      assert.strictEqual(loaded, true);
      assert.strictEqual(createdFont.family, 'Shyni Custom');
      assert.strictEqual(createdFont.source, 'url("assets/custom.ttf")');
      assert.strictEqual(addedFonts.length, 1);
      assert.strictEqual(subtitles.fontFamily, 'Shyni Custom');
      assert.strictEqual(subtitles.cueElement.style.fontFamily, '"Shyni Custom", sans-serif');
      subtitles.destroy();
    });
}

function testPlayerFallbackProgressAndMediaKeys() {
  var document = makeDocument();
  var sandbox = loadScripts(['player.js'], { document: document });
  var Player = sandbox.ShyniFlexWebOS.Player;
  var container = new FakeElement('div');
  var video = new FakeVideo(container);
  var errors = [];
  var progress = [];
  var player = new Player(video, {
    seekStep: 10,
    onError: function (error) {
      errors.push(error);
    },
    onProgress: function (value) {
      progress.push(value);
    }
  });

  player.load({
    directUrl: 'https://media.example/movie.mp4',
    hlsUrl: 'https://media.example/movie.m3u8'
  });
  assert.strictEqual(video.src, 'https://media.example/movie.mp4');
  video.error = { code: 4, message: 'unsupported' };
  video.dispatch('error', { type: 'error' });
  assert.strictEqual(video.src, 'https://media.example/movie.m3u8');
  assert.strictEqual(player._attempt, 'hls-native');
  video.dispatch('error', { type: 'error' });
  assert.strictEqual(errors.length, 1);
  assert.strictEqual(errors[0].attempt, 'hls-native');

  video.currentTime = 40;
  video.dispatch('timeupdate', { type: 'timeupdate' });
  assert.strictEqual(progress[progress.length - 1].position, 40);
  assert.strictEqual(progress[progress.length - 1].buffered, 60);
  assert.strictEqual(player.handleRemoteKey(415), true);
  assert.strictEqual(video.playCount, 1);
  assert.strictEqual(player.handleRemoteKey(417), true);
  assert.strictEqual(video.currentTime, 50);
  assert.strictEqual(player.handleRemoteKey(412), true);
  assert.strictEqual(video.currentTime, 40);
  assert.strictEqual(player.handleRemoteKey(19), true);
  assert.strictEqual(video.paused, true);
  assert.strictEqual(player.handleRemoteKey(413), true);
  assert.strictEqual(video.currentTime, 0);
  player.destroy();
}

function testPlayerAutoplayTimingAndUnload() {
  var document = makeDocument();
  var sandbox = loadScripts(['player.js'], { document: document });
  var Player = sandbox.ShyniFlexWebOS.Player;
  var states = [];
  var video = new FakeVideo(new FakeElement('div'));
  var player = new Player(video, {
    onStateChange: function (state) {
      states.push(state);
    }
  });

  player.load({
    directUrl: 'https://media.example/fast.mp4',
    type: 'video/mp4',
    startTime: 0,
    autoplay: true
  });
  assert.strictEqual(video.playCount, 1);
  video.dispatch('loadedmetadata', { type: 'loadedmetadata' });
  assert.strictEqual(video.playCount, 1);
  video.dispatch('play', { type: 'play' });
  video.dispatch('playing', { type: 'playing' });
  assert.strictEqual(states.indexOf('starting') >= 0, true);
  assert.strictEqual(states[states.length - 1], 'playing');
  assert.strictEqual(video.firstChild.nodeName, 'SOURCE');

  var hlsDestroyed = 0;
  player._hls = {
    destroy: function () {
      hlsDestroyed += 1;
    }
  };
  player.unload();
  assert.strictEqual(hlsDestroyed, 1);
  assert.strictEqual(video.firstChild, null);
  assert.strictEqual(video.src, '');
  assert.strictEqual(player._source, null);
  assert.strictEqual(player._activeUrl, '');
  assert.strictEqual(player._attempt, 'idle');
  player.destroy();

  var resumeVideo = new FakeVideo(new FakeElement('div'));
  var resumePlayer = new Player(resumeVideo);
  resumePlayer.load({
    directUrl: 'https://media.example/resume.mp4',
    startTime: 37,
    autoplay: true
  });
  assert.strictEqual(resumeVideo.playCount, 0);
  resumeVideo.dispatch('loadedmetadata', { type: 'loadedmetadata' });
  assert.strictEqual(resumeVideo.currentTime, 37);
  assert.strictEqual(resumeVideo.playCount, 1);
  resumePlayer.destroy();

  var manualVideo = new FakeVideo(new FakeElement('div'));
  var manualPlayer = new Player(manualVideo);
  manualPlayer.load({
    directUrl: 'https://media.example/manual.mp4',
    startTime: 0,
    autoplay: false
  });
  manualVideo.dispatch('loadedmetadata', { type: 'loadedmetadata' });
  assert.strictEqual(manualVideo.playCount, 0);
  manualPlayer.destroy();

  var hlsVideo = new FakeVideo(new FakeElement('div'));
  var hlsPlayer = new Player(hlsVideo);
  hlsPlayer.load({
    directUrl: '',
    hlsUrl: 'https://media.example/transcode.m3u8',
    autoplay: false
  });
  assert.strictEqual(hlsPlayer._attempt, 'hls-native');
  hlsPlayer.destroy();

  var fallbackVideo = new FakeVideo(new FakeElement('div'));
  var fallbackPlayer = new Player(fallbackVideo);
  fallbackPlayer.load({
    directUrl: 'https://media.example/resume-direct.mp4',
    hlsUrl: 'https://media.example/resume-fallback.m3u8',
    startTime: 37,
    autoplay: false
  });
  fallbackVideo.dispatch('loadedmetadata', { type: 'loadedmetadata' });
  assert.strictEqual(fallbackVideo.currentTime, 37);
  fallbackVideo.error = { code: 3, message: 'decoder failed' };
  fallbackVideo.dispatch('error', { type: 'error' });
  assert.strictEqual(fallbackPlayer._attempt, 'hls-native');
  assert.strictEqual(fallbackPlayer._pendingStartTime, 37);
  fallbackVideo.currentTime = 0;
  fallbackVideo.dispatch('loadedmetadata', { type: 'loadedmetadata' });
  assert.strictEqual(fallbackVideo.currentTime, 37);
  fallbackPlayer.destroy();

  var retrySeekVideo = new FakeVideo(new FakeElement('div'));
  var storedTime = 0;
  var rejectFirstSeek = true;
  Object.defineProperty(retrySeekVideo, 'currentTime', {
    configurable: true,
    get: function () {
      return storedTime;
    },
    set: function (value) {
      if (rejectFirstSeek) {
        rejectFirstSeek = false;
        throw new Error('seek not ready');
      }
      storedTime = value;
    }
  });
  var retrySeekPlayer = new Player(retrySeekVideo);
  retrySeekPlayer.load({
    directUrl: 'https://media.example/retry-seek.mp4',
    hlsUrl: 'https://media.example/retry-seek.m3u8',
    startTime: 42,
    autoplay: true
  });
  retrySeekVideo.dispatch('loadedmetadata', { type: 'loadedmetadata' });
  assert.strictEqual(retrySeekPlayer._pendingStartTime, 42);
  assert.strictEqual(retrySeekVideo.playCount, 1);
  retrySeekVideo.error = { code: 3, message: 'decoder failed before seek' };
  retrySeekVideo.dispatch('error', { type: 'error' });
  assert.strictEqual(retrySeekPlayer._attempt, 'hls-native');
  assert.strictEqual(retrySeekPlayer._pendingStartTime, 42);
  retrySeekVideo.dispatch('loadeddata', { type: 'loadeddata' });
  assert.strictEqual(retrySeekVideo.currentTime, 42);
  assert.strictEqual(retrySeekPlayer._pendingStartTime, 0);
  assert.strictEqual(retrySeekVideo.playCount, 2);
  retrySeekPlayer.destroy();
}

function testPlayerRetriesRejectedEarlyAutoplay() {
  var document = makeDocument();
  var sandbox = loadScripts(['player.js'], { document: document });
  var video = new FakeVideo(new FakeElement('div'));
  var errors = [];
  var attempts = 0;
  video.play = function () {
    attempts += 1;
    this.playCount += 1;
    if (attempts === 1) {
      this.paused = true;
      return Promise.reject(new Error('source not ready'));
    }
    this.paused = false;
    return Promise.resolve();
  };
  var player = new sandbox.ShyniFlexWebOS.Player(video, {
    onError: function (error) {
      errors.push(error);
    }
  });
  player.load({
    directUrl: 'https://media.example/retry.mp4',
    startTime: 0,
    autoplay: true
  });
  assert.strictEqual(video.playCount, 1);
  return new Promise(function (resolve) { setTimeout(resolve, 0); }).then(function () {
    video.dispatch('loadedmetadata', { type: 'loadedmetadata' });
    assert.strictEqual(video.playCount, 2);
    assert.strictEqual(errors.length, 0);
    player.destroy();
  });
}

function testPlayerUsesHlsJsAfterNativeHlsFailure() {
  var loadedUrls = [];
  function FakeHls() {
    this.onceHandlers = {};
  }
  FakeHls.Events = { MEDIA_ATTACHED: 'mediaAttached', ERROR: 'error' };
  FakeHls.isSupported = function () {
    return true;
  };
  FakeHls.prototype.on = function () {};
  FakeHls.prototype.once = function (name, callback) {
    this.onceHandlers[name] = callback;
  };
  FakeHls.prototype.attachMedia = function () {
    if (this.onceHandlers.mediaAttached) {
      this.onceHandlers.mediaAttached();
    }
  };
  FakeHls.prototype.loadSource = function (url) {
    loadedUrls.push(url);
  };
  FakeHls.prototype.destroy = function () {};

  var document = makeDocument();
  var sandbox = loadScripts(['player.js'], {
    document: document,
    Hls: FakeHls
  });
  var video = new FakeVideo(new FakeElement('div'));
  var player = new sandbox.ShyniFlexWebOS.Player(video);
  player.load('https://media.example/live.m3u8');
  video.error = { code: 4 };
  video.dispatch('error', { type: 'error' });
  assert.strictEqual(player._attempt, 'hls-js');
  assert.strictEqual(loadedUrls[0], 'https://media.example/live.m3u8');
  player.destroy();
}

function focusable(rect) {
  var element = new FakeElement('button', rect);
  element.getAttribute = function () {
    return null;
  };
  return element;
}

function testRemoteGeometryMediaAndBack() {
  var document = makeDocument();
  var platformBackCalls = 0;
  var sandbox = loadScripts(['remote.js'], {
    document: document,
    webOS: {
      platformBack: function () {
        platformBackCalls += 1;
      }
    },
    history: { length: 1, back: function () {} }
  });
  var Remote = sandbox.ShyniFlexWebOS.Remote;
  var current = focusable({ left: 100, top: 100, right: 200, bottom: 160 });
  var right = focusable({ left: 260, top: 105, right: 360, bottom: 165 });
  var diagonal = focusable({ left: 230, top: 300, right: 330, bottom: 360 });
  assert.strictEqual(Remote.findNext([current, right, diagonal], current, 'right'), right);

  var sidebar = new FakeElement('nav');
  sidebar.matches = function (selector) { return selector === '.side-navigation'; };
  var sidebarItem = focusable({ left: 0, top: 100, right: 48, bottom: 160 });
  sidebar.appendChild(sidebarItem);
  assert.strictEqual(Remote.isInside(sidebarItem, '.side-navigation'), true);
  assert.strictEqual(Remote.isInside(current, '.side-navigation'), false);

  var range = new FakeElement('input');
  range.attributes.type = 'range';
  range.min = '24';
  range.max = '84';
  range.step = '2';
  range.value = '46';
  assert.strictEqual(Remote.adjustRange(range, 39, document), true);
  assert.strictEqual(range.value, '48');
  assert.strictEqual(Remote.adjustRange(range, 37, document), true);
  assert.strictEqual(range.value, '46');

  var mediaCodes = [];
  var player = {
    handleRemoteKey: function (code) {
      mediaCodes.push(code);
      return true;
    }
  };
  var remote = new Remote({
    document: document,
    root: document,
    autoStart: false,
    isRoot: true,
    player: player
  });
  assert.strictEqual(remote.handleKeyDown(415), true);
  assert.strictEqual(mediaCodes[0], 415);
  assert.strictEqual(remote.handleKeyDown(461), true);
  assert.strictEqual(platformBackCalls, 1);

  var adjusted = 0;
  var remove = remote.addKeyHandler(function (event, code) {
    if (code === 37) {
      adjusted += 1;
      return true;
    }
    return false;
  }, true);
  assert.strictEqual(remote.handleKeyDown(37), true);
  assert.strictEqual(adjusted, 1);
  remove();
  remote.destroy();
}

Promise.resolve()
  .then(testSubtitleParsingAndPresentation)
  .then(testBundledFontActivation)
  .then(testPlayerRetriesRejectedEarlyAutoplay)
  .then(function () {
    testPlayerFallbackProgressAndMediaKeys();
    testPlayerAutoplayTimingAndUnload();
    testPlayerUsesHlsJsAfterNativeHlsFailure();
    testRemoteGeometryMediaAndBack();
    console.log('playback_input_modules_node_test: PASS');
  })
  .catch(function (error) {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
  });
