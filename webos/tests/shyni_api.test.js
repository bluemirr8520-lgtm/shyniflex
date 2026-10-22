'use strict';

var test = require('node:test');
var assert = require('node:assert/strict');

var queue = [];
var calls = [];

function response(status, body, headers) {
  queue.push({ status: status, body: body, headers: headers || { 'content-type': 'application/json' } });
}

function FakeXMLHttpRequest() {
  this.headers = {};
  this.readyState = 0;
  this.status = 0;
  this.responseText = '';
}

FakeXMLHttpRequest.prototype.open = function (method, url) {
  this.method = method;
  this.url = url;
};
FakeXMLHttpRequest.prototype.setRequestHeader = function (name, value) { this.headers[name] = value; };
FakeXMLHttpRequest.prototype.getResponseHeader = function (name) {
  var source = this.responseHeaders || {};
  return source[String(name).toLowerCase()] || source[name] || '';
};
FakeXMLHttpRequest.prototype.send = function (body) {
  var next = queue.shift();
  if (!next) { throw new Error('No fake response queued for ' + this.method + ' ' + this.url); }
  this.body = body;
  calls.push({ method: this.method, url: this.url, headers: this.headers, body: body });
  this.status = next.status;
  this.responseText = typeof next.body === 'string' ? next.body : JSON.stringify(next.body);
  this.responseHeaders = next.headers;
  this.readyState = 4;
  if (typeof this.onreadystatechange === 'function') { this.onreadystatechange(); }
  if (typeof this.onload === 'function') { this.onload(); }
};
FakeXMLHttpRequest.prototype.abort = function () {};

global.XMLHttpRequest = FakeXMLHttpRequest;
global.localStorage = {
  values: {},
  getItem: function (key) { return Object.prototype.hasOwnProperty.call(this.values, key) ? this.values[key] : null; },
  setItem: function (key, value) { this.values[key] = String(value); },
  removeItem: function (key) { delete this.values[key]; }
};

var api = require('../app/js/api.js');

function connection() {
  return {
    type: 'shyni', baseUrl: 'https://media.example.test', token: 'saved-token',
    clientIdentifier: 'webos-device', name: 'Home Shyni'
  };
}

test.beforeEach(function () {
  queue.length = 0;
  calls.length = 0;
  global.localStorage.values = {};
});

test('Shyni password and TOTP login return a persistent bearer connection', async function () {
  response(200, { require_totp: true, challenge_token: 'challenge-1' });
  var first = await api.authenticateByName({
    type: 'shyni', url: 'https://media.example.test', username: 'tv-user', password: 'secret',
    clientIdentifier: 'webos-device'
  });
  assert.equal(first.requiresTotp, true);
  assert.equal(first.challenge, 'challenge-1');
  assert.deepEqual(JSON.parse(calls[0].body), {
    username: 'tv-user', password: 'secret', app_name: 'shyniflex-webos', device_name: 'LG webOS TV'
  });

  response(200, { token: 'token-after-totp', user: { username: 'tv-user' } });
  var completed = await api.completeShyniTotp({
    url: 'https://media.example.test', challenge: first.challenge, code: '123456', clientIdentifier: 'webos-device'
  });
  assert.equal(completed.type, 'shyni');
  assert.equal(completed.token, 'token-after-totp');
  assert.equal(completed.userName, 'tv-user');
  assert.equal(calls[1].headers['X-Device-Id'], 'webos-device');
});

test('Shyni requests use bearer authentication and expose only video libraries', async function () {
  response(200, [
    { id: 8, name: '영화', type: 'movie' },
    { id: 9, name: 'TV', type: 'shows' },
    { id: 10, name: '음악', type: 'music' },
    { id: 11, name: '원격', type: 'movie', remote: true }
  ]);
  var libraries = await api.createClient(connection()).listLibraries();
  assert.deepEqual(libraries.map(function (item) { return [item.id, item.collectionType]; }), [['8', 'movie'], ['9', 'tv']]);
  assert.equal(calls[0].headers.Authorization, 'Bearer saved-token');
  assert.equal(calls[0].headers['X-Device-Id'], 'webos-device');
  assert.equal(calls[0].url, 'https://media.example.test/libraries');
});

test('season browsing returns a four-column-ready episode collection with watch state', async function () {
  response(200, [
    { id: 21, title: '첫 번째 회', item_type: 'episode', view_item_id: 501, part_id: 91, episode_number: 1, duration_ms: 3600000 },
    { id: 22, title: '두 번째 회', item_type: 'episode', view_item_id: 502, part_id: 92, episode_number: 2, duration_ms: 3600000 }
  ]);
  response(200, {
    '21': { metadata_item_id: 21, completed: true, position_ms: 3600000, duration_ms: 3600000 },
    '22': { metadata_item_id: 22, completed: false, position_ms: 900000, duration_ms: 3600000 }
  });
  var result = await api.createClient(connection()).listItems({
    id: '7', type: 'season', libraryId: '9', showId: '5'
  }, { limit: 60 });
  assert.equal(result.items.length, 2);
  assert.equal(result.items[0].played, true);
  assert.equal(result.items[0].resumePositionMs, 0);
  assert.equal(result.items[1].progress, 0.25);
  assert.equal(result.items[1].showId, '5');
  assert.match(calls[0].url, /\/libraries\/9\/shows\/5\/seasons\/7\/episodes\?limit=60&offset=0$/);
  assert.match(calls[1].url, /\/playback\/progress\?item_ids=21%2C22&library_view_id=9$/);
});

test('legacy webOS playback uses a Shyni video transcode ticket, scoped subtitles and progress', async function () {
  response(200, {
    decision: 'video_transcode', library_view_id: 8, view_item_id: 501,
    selected: { part_id: 90, duration_ms: 7200000 }
  });
  response(200, { ticket: 'transcode-ticket' });
  response(200, { ticket: 'subtitle-ticket' });
  response(200, { subtitles: [
    { id: 4, label: '한국어', lang_code: 'ko', format: 'ass', source: 'external', default: true },
    { id: 5, label: 'PGS', format: 'pgs', playable: false }
  ] });
  response(200, { '23': { metadata_item_id: 23, position_ms: 300000, duration_ms: 7200000 } });

  var client = api.createClient(connection());
  var playback = await client.resolvePlayback({
    id: '23', type: 'movie', playable: true, libraryId: '8', viewItemId: '501', partId: '90', title: '테스트 영화'
  }, { compatibility: true, maxVideoHeight: 1080 });

  assert.equal(playback.playMethod, 'Transcode');
  assert.equal(playback.url, 'https://media.example.test/transcode/90?ticket=transcode-ticket&mode=video&max_height=1080');
  assert.equal(playback.resumePositionMs, 300000);
  assert.equal(playback.subtitles.length, 1);
  assert.match(playback.subtitles[0].url, /\/subtitles\/track\/4\.vtt\?library_view_id=8&view_item_id=501&ticket=subtitle-ticket$/);
  assert.match(calls[1].url, /\/playback\/transcode-ticket\/90\?library_view_id=8&view_item_id=501&mode=video$/);
  assert.match(calls[2].url, /\/stream-ticket\/90\?library_view_id=8&view_item_id=501$/);

  response(200, { ok: true });
  await client.reportProgress(playback, { positionMs: 420000, durationMs: 7200000, isPaused: false });
  var progress = JSON.parse(calls[5].body);
  assert.equal(progress.state, 'playing');
  assert.equal(progress.position_ms, 420000);
  assert.equal(progress.delivery_mode, 'server_proxy');
});

test('completed playback is reported as a completion candidate', async function () {
  response(200, { ok: true });
  await api.createClient(connection()).reportCompleted({
    partId: '90', itemId: '23', libraryId: '8', viewItemId: '501', sessionId: 'session', durationMs: 3600000,
    decision: 'video_transcode'
  });
  var body = JSON.parse(calls[0].body);
  assert.equal(body.state, 'completed_candidate');
  assert.equal(body.completion_eligible, true);
  assert.equal(body.position_ms, 3600000);
});
