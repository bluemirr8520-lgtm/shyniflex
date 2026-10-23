'use strict';

var http = require('node:http');
var fs = require('node:fs');
var path = require('node:path');
var url = require('node:url');

var root = path.resolve(__dirname, '../app');
var port = Number(process.env.SHYNIFLEX_MOCK_PORT || 9393);

function json(response, status, value) {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Device-Id'
  });
  response.end(JSON.stringify(value));
}

function poster(response, title, wide) {
  var width = wide ? 640 : 400;
  var height = wide ? 360 : 600;
  var safe = String(title).replace(/[&<>"']/g, '');
  var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="' + width + '" height="' + height + '" viewBox="0 0 ' + width + ' ' + height + '">' +
    '<defs><linearGradient id="g" x2="1" y2="1"><stop stop-color="#183b52"/><stop offset="1" stop-color="#10251f"/></linearGradient></defs>' +
    '<rect width="100%" height="100%" fill="url(#g)"/><circle cx="' + (width * 0.72) + '" cy="' + (height * 0.28) + '" r="' + (height * 0.16) + '" fill="#6ce6c2" opacity=".9"/>' +
    '<path d="M' + (width * 0.68) + ' ' + (height * 0.20) + 'v' + (height * 0.16) + 'l' + (height * 0.12) + ' -' + (height * 0.08) + 'z" fill="#0b1220"/>' +
    '<text x="32" y="' + (height - 52) + '" fill="white" font-size="34" font-family="sans-serif">' + safe + '</text></svg>';
  response.writeHead(200, { 'Content-Type': 'image/svg+xml', 'Access-Control-Allow-Origin': '*' });
  response.end(svg);
}

function movie(id, title, viewItemId) {
  return {
    id: id, title: title, type: 'movie', view_item_id: viewItemId, part_id: id + 1000,
    duration_ms: 7200000, year: 2026, summary: title + '의 ShyniFlex webOS 화면 검증용 소개입니다.',
    poster_url: '/mock/poster/' + id, thumb_url: '/mock/wide/' + id, art_url: '/mock/wide/' + id,
    added_at: '2026-10-' + String(20 - (id % 9)).padStart(2, '0') + 'T12:00:00+09:00'
  };
}

function show(id, title, viewItemId) {
  return {
    id: id, title: title, type: 'show', view_item_id: viewItemId, episode_count: 8, year: 2026,
    summary: title + '의 시즌과 회차를 TV 리모컨으로 탐색합니다.',
    poster_url: '/mock/poster/' + id, art_url: '/mock/wide/' + id,
    latest_ep: '2026-10-' + String(20 - (id % 9)).padStart(2, '0') + 'T12:00:00+09:00'
  };
}

var movies = Array.from({ length: 18 }, function (_, index) { return movie(101 + index, '영화 ' + (index + 1), 2001 + index); });
var shows = Array.from({ length: 12 }, function (_, index) { return show(301 + index, '시리즈 ' + (index + 1), 3001 + index); });

var server = http.createServer(function (request, response) {
  var parsed = url.parse(request.url, true);
  var pathname = decodeURIComponent(parsed.pathname);
  if (request.method === 'OPTIONS') {
    response.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Device-Id', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' });
    response.end();
    return;
  }
  if (pathname === '/auth/me') {
    if (!request.headers.authorization) { json(response, 401, { detail: 'login required' }); }
    else { json(response, 200, { user: { username: 'webos-qa' } }); }
    return;
  }
  if (pathname === '/auth/login') { json(response, 200, { token: 'qa-token', user: { username: 'webos-qa' } }); return; }
  if (pathname === '/auth/logout') { json(response, 200, { ok: true }); return; }
  if (pathname === '/libraries') { json(response, 200, [{ id: 8, name: '영화', type: 'movie' }, { id: 9, name: 'TV 프로그램', type: 'tv' }]); return; }
  if (pathname === '/continue-watching') {
    json(response, 200, { items: [Object.assign({}, movies[0], { library_view_id: 8, metadata_item_id: movies[0].id, position_ms: 1800000 }),
      Object.assign({}, movie(402, '이어보는 회차', 4402), { type: 'episode', show_title: '시리즈 1', subtitle: '시즌 1 · 2화', library_view_id: 9, metadata_item_id: 402, position_ms: 900000 })] });
    return;
  }
  if (pathname === '/libraries/8/movies') { json(response, 200, movies); return; }
  if (pathname === '/libraries/9/shows') { json(response, 200, shows); return; }
  if (/^\/libraries\/(8|9)\/search$/.test(pathname)) {
    var source = pathname.indexOf('/8/') >= 0 ? movies : shows;
    var q = String(parsed.query.q || '').toLowerCase();
    json(response, 200, source.filter(function (item) { return item.title.toLowerCase().indexOf(q) >= 0; }));
    return;
  }
  if (/^\/libraries\/9\/shows\/\d+\/seasons$/.test(pathname)) {
    json(response, 200, [{ id: 701, title: '시즌 1', type: 'season', season_number: 1, episode_count: 8, poster_url: '/mock/poster/701' }]);
    return;
  }
  if (/^\/libraries\/9\/shows\/\d+\/seasons\/701\/episodes$/.test(pathname)) {
    json(response, 200, Array.from({ length: 8 }, function (_, index) {
      return { id: 801 + index, title: (index + 1) + '화', type: 'episode', show_id: 301, view_item_id: 5001 + index,
        part_id: 6001 + index, season_number: 1, episode_number: index + 1, duration_ms: 3000000,
        thumb_url: '/mock/wide/' + (801 + index), summary: '회차 ' + (index + 1) + ' 소개' };
    }));
    return;
  }
  if (pathname === '/playback/progress' && request.method === 'GET') {
    var values = {};
    String(parsed.query.item_ids || '').split(',').filter(Boolean).forEach(function (id, index) {
      values[id] = { metadata_item_id: Number(id), completed: index === 0 && Number(id) >= 800, position_ms: index === 1 ? 600000 : 0, duration_ms: Number(id) >= 800 ? 3000000 : 7200000 };
    });
    json(response, 200, values);
    return;
  }
  if (pathname === '/playback/progress' && request.method === 'POST') { json(response, 200, { ok: true }); return; }
  var imageMatch = pathname.match(/^\/mock\/(poster|wide)\/(\d+)$/);
  if (imageMatch) { poster(response, (imageMatch[1] === 'poster' ? '포스터 ' : '회차 ') + imageMatch[2], imageMatch[1] === 'wide'); return; }

  var candidate = pathname === '/' ? '/index.html' : pathname;
  var local = path.resolve(root, '.' + candidate);
  if (local.indexOf(root) !== 0 || !fs.existsSync(local) || !fs.statSync(local).isFile()) { json(response, 404, { detail: 'not found' }); return; }
  var ext = path.extname(local).toLowerCase();
  var mime = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.ttf': 'font/ttf' }[ext] || 'application/octet-stream';
  response.writeHead(200, { 'Content-Type': mime });
  fs.createReadStream(local).pipe(response);
});

server.listen(port, '127.0.0.1', function () {
  process.stdout.write('ShyniFlex mock server listening on http://127.0.0.1:' + port + '\n');
});
