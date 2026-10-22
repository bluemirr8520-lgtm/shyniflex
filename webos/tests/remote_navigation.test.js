'use strict';

var test = require('node:test');
var assert = require('node:assert/strict');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');

function fixture(count, columns, clock) {
  var queries = 0;
  var groupQueries = 0;
  var nodes = [];
  var doc = { activeElement: null, addEventListener: function () {}, removeEventListener: function () {} };
  function node(id, classes, top, left, parent) {
    var element = {
      id: id, className: classes, offsetTop: top, offsetLeft: left, parentNode: parent || doc,
      children: [], attrs: {}, clientWidth: 960, clientHeight: 600,
      setAttribute: function (name, value) { this.attrs[name] = value; },
      getAttribute: function (name) { return this.attrs[name] || null; },
      removeAttribute: function (name) { delete this.attrs[name]; },
      matches: function (selector) {
        if (selector === '[data-nav-layout]') { return Boolean(this.attrs['data-nav-layout']); }
        if (selector === '.side-navigation') { return this.className === 'side-navigation'; }
        return this.className.indexOf('focusable') >= 0;
      },
      contains: function (other) {
        while (other) { if (other === this) { return true; } other = other.parentNode; }
        return false;
      },
      getBoundingClientRect: function () {
        return this.hidden ? { left: 0, right: 0, top: 0, bottom: 0 } :
          { left: this.offsetLeft, right: this.offsetLeft + 100, top: this.offsetTop, bottom: this.offsetTop + 140 };
      },
      focus: function () { doc.activeElement = this; },
      querySelectorAll: function () { return nodes.filter(function (n) { return n !== element && element.contains(n) && n.className.indexOf('focusable') >= 0; }); }
    };
    if (parent) { parent.children.push(element); }
    nodes.push(element);
    return element;
  }
  var sidebar = node('sidebar', 'side-navigation', 0, 0);
  var nav = node('nav-home', 'focusable side-navigation-item selected', 100, 0, sidebar);
  var scope = node('view', 'view', 0, 100);
  var header = node('header', 'focusable', 0, 100, scope);
  var group = node('grid', 'grid', 200, 100, scope);
  group.attrs['data-nav-layout'] = 'grid';
  group.attrs['data-nav-left'] = '#nav-home';
  group.attrs['data-nav-up'] = '#header';
  var cards = Array.from({ length: count }, function (_, i) {
    return node('card-' + i, 'media-card focusable', 200 + Math.floor(i / columns) * 160, 100 + (i % columns) * 120, group);
  });
  group.querySelectorAll = function () { groupQueries += 1; return cards; };
  doc.contains = function () { return true; };
  doc.querySelector = function (selector) {
    if (selector === '.side-navigation-item.selected') { return nav; }
    return nodes.find(function (n) { return '#' + n.id === selector; }) || null;
  };
  doc.querySelectorAll = function () { queries += 1; return nodes.filter(function (n) { return n.className.indexOf('focusable') >= 0; }); };
  var sandbox = { document: doc, console: console, Date: clock ? { now: function () { return clock.now; } } : Date,
    setTimeout: clock ? clock.set.bind(clock) : setTimeout, clearTimeout: clock ? clock.clear.bind(clock) : clearTimeout };
  sandbox.window = sandbox;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../app/js/remote.js'), 'utf8'), sandbox);
  var Remote = sandbox.ShyniFlexWebOS.Remote;
  var remote = new Remote({ document: doc, autoStart: false, getFocusRoot: function () { return scope; }, allowSidebar: function () { return true; } });
  return { doc: doc, scope: scope, group: group, cards: cards, nav: nav, header: header, remote: remote, Remote: Remote,
    queries: function () { return queries; }, groupQueries: function () { return groupQueries; } };
}

function fakeClock() {
  return { now: 0, nextId: 0, jobs: new Map(),
    set: function (fn, ms) { var id = ++this.nextId; this.jobs.set(id, { fn: fn, at: this.now + ms }); return id; },
    clear: function (id) { this.jobs.delete(id); },
    advance: function (ms) {
      var end = this.now + ms;
      while (true) {
        var next = Array.from(this.jobs.entries()).filter(function (entry) { return entry[1].at <= end; }).sort(function (a, b) { return a[1].at - b[1].at; })[0];
        if (!next) { break; }
        this.now = next[1].at; this.jobs.delete(next[0]); next[1].fn();
      }
      this.now = end;
    }
  };
}

function enableRepeat(f) {
  f.remote.options.repeatNavigation = true;
  f.remote.options.canRepeatNavigation = function () { return true; };
}

test('left/right edits subtitle select values while up/down navigates, without a native popup', function () {
  var f = fixture(4, 2);
  var select = f.cards[0]; select.nodeName = 'SELECT'; select.selectedIndex = 0;
  select.options = [{ value: 'horizontal' }, { value: 'unsupported', disabled: true }, { value: 'vertical' }];
  f.remote.focus(select);
  f.remote.handleKeyDown(39); assert.equal(select.selectedIndex, 2); assert.equal(f.doc.activeElement, select);
  f.remote.handleKeyDown(37); assert.equal(select.selectedIndex, 0); assert.equal(f.doc.activeElement, select);
  f.remote.handleKeyDown(40); assert.equal(f.doc.activeElement, f.cards[2]);
});

test('held arrows start after 220ms, repeat every 95ms, and keyup cancels immediately', function () {
  var clock = fakeClock(); var f = fixture(24, 12, clock); enableRepeat(f);
  f.remote.focus(f.cards[0]);
  f.remote.handleKeyDown({ keyCode: 39 });
  assert.equal(f.doc.activeElement, f.cards[1]);
  clock.advance(219); assert.equal(f.doc.activeElement, f.cards[1]);
  clock.advance(1); assert.equal(f.doc.activeElement, f.cards[2]);
  f.remote.handleKeyDown({ keyCode: 39, repeat: true });
  assert.equal(f.doc.activeElement, f.cards[2], 'native repeats must not duplicate timer movement');
  clock.advance(95); assert.equal(f.doc.activeElement, f.cards[3]);
  f.remote.handleKeyUp({ keyCode: 39 });
  clock.advance(1000); assert.equal(f.doc.activeElement, f.cards[3]);
  assert.equal(clock.jobs.size, 0);
});

test('a held arrow cannot leave a content grid for the sidebar; a fresh press can', function () {
  var clock = fakeClock(); var f = fixture(24, 6, clock); enableRepeat(f);
  f.remote.focus(f.cards[1]); f.remote.handleKeyDown({ keyCode: 37 });
  clock.advance(500);
  assert.equal(f.doc.activeElement, f.cards[0]);
  f.remote.handleKeyDown({ keyCode: 37, repeat: true });
  assert.equal(f.doc.activeElement, f.cards[0]);
  f.remote.handleKeyUp({ keyCode: 37 });
  f.remote.handleKeyDown({ keyCode: 37 });
  assert.equal(f.doc.activeElement, f.nav);
  f.remote.stopRepeating();
});

test('held movement stops on visibility loss, scope change, pointer input, or missing keyup', function () {
  ['visibility', 'scope', 'pointer', 'lost-keyup', 'stop', 'other-key'].forEach(function (mode) {
    var clock = fakeClock(); var f = fixture(180, 90, clock); enableRepeat(f);
    f.remote.focus(f.cards[0]); f.remote.handleKeyDown({ keyCode: 39 });
    if (mode === 'visibility') { f.doc.hidden = true; f.remote._handleVisibility(); }
    if (mode === 'scope') { f.remote.options.getFocusRoot = function () { return f.group; }; }
    if (mode === 'pointer') { f.remote._handlePointerFocus({ type: 'mousedown', target: f.cards[3] }); }
    if (mode === 'stop') { f.remote.stop(); }
    if (mode === 'other-key') { f.remote.handleKeyDown({ keyCode: 65 }); }
    clock.advance(1000);
    var stoppedAt = f.doc.activeElement;
    clock.advance(1000);
    assert.equal(f.doc.activeElement, stoppedAt, mode);
    assert.equal(f.remote._heldDirection, null, mode);
    assert.equal(clock.jobs.size, 0, mode);
  });
});

test('grid movement follows actual six/eight/nine-column rows and never wraps horizontally', function () {
  [3, 6, 8, 9].forEach(function (columns) {
    var f = fixture(columns * 3 + 2, columns);
    f.remote.focus(f.cards[columns - 1]);
    assert.equal(f.remote.moveFocus('right'), f.cards[columns - 1]);
    assert.equal(f.remote.moveFocus('down'), f.cards[2 * columns - 1]);
    assert.equal(f.remote.moveFocus('up'), f.cards[columns - 1]);
    f.remote.focus(f.cards[3 * columns - 1]);
    assert.equal(f.remote.moveFocus('down'), f.cards[3 * columns + 1]);
    assert.equal(f.remote.moveFocus('down'), f.cards[3 * columns + 1]);
    assert.equal(f.remote.moveFocus('left'), f.cards[3 * columns]);
    assert.equal(f.remote.moveFocus('left'), f.nav);
    assert.equal(f.remote.moveFocus('right'), f.cards[3 * columns], 'sidebar returns to the last content card');
  });
});

test('up exits to the header only from the first row; bottom stays in the grid', function () {
  var f = fixture(18, 6);
  f.remote.focus(f.cards[13]);
  assert.equal(f.remote.moveFocus('up'), f.cards[7]);
  assert.equal(f.remote.moveFocus('up'), f.cards[1]);
  assert.equal(f.remote.moveFocus('up'), f.header);
  f.remote.focus(f.cards[17]);
  assert.equal(f.remote.moveFocus('down'), f.cards[17]);
});

test('rapid D-pad presses stay immediate and reuse layout without a document-wide scan', function () {
  var f = fixture(180, 9);
  f.remote.focus(f.cards[0]);
  for (var i = 0; i < 80; i += 1) {
    f.remote.handleKeyDown({ keyCode: i % 2 ? 37 : 39, repeat: true });
    assert.equal(f.doc.activeElement, f.cards[i % 2 ? 0 : 1]);
  }
  assert.equal(f.queries(), 0);
  assert.equal(f.groupQueries(), 1, 'measure the group once, not 80 times');
  assert.equal(f.cards.filter(function (card) { return card.attrs['data-tv-focused'] === 'true'; }).length, 1);
});

test('cached rows are rebuilt when the grid width changes', function () {
  var f = fixture(24, 6);
  f.remote.focus(f.cards[0]);
  assert.equal(f.remote.moveFocus('down'), f.cards[6]);
  f.group.clientWidth = 1280;
  f.cards.forEach(function (card, i) { card.offsetTop = 200 + Math.floor(i / 8) * 160; });
  f.remote.focus(f.cards[0]);
  assert.equal(f.remote.moveFocus('down'), f.cards[8]);
  assert.equal(f.groupQueries(), 2);
});

test('cached rows are discarded when same-sized results replace DOM cards', function () {
  var f = fixture(12, 6);
  f.remote.focus(f.cards[0]);
  f.remote.moveFocus('right');
  var replacement = Object.assign({}, f.cards[0], { attrs: {} });
  f.cards[0] = replacement;
  f.group.children[0] = replacement;
  f.remote.focus(replacement);
  assert.equal(f.remote.moveFocus('right'), f.cards[1]);
  assert.equal(f.groupQueries(), 2);
});

test('stationary pointer events cannot steal D-pad focus; real movement and click can', function () {
  var f = fixture(12, 6);
  f.remote.focus(f.cards[0]);
  f.remote._handlePointerFocus({ type: 'mousemove', target: f.cards[4], clientX: 300, clientY: 300 });
  f.remote.handleKeyDown(39);
  f.remote._handlePointerFocus({ type: 'mousemove', target: f.cards[4], clientX: 301, clientY: 300 });
  assert.equal(f.doc.activeElement, f.cards[1]);
  f.remote._handlePointerFocus({ type: 'mousemove', target: f.cards[4], clientX: 320, clientY: 300 });
  assert.equal(f.doc.activeElement, f.cards[4]);
  f.remote._handlePointerFocus({ type: 'mousedown', target: f.cards[2] });
  assert.equal(f.doc.activeElement, f.cards[2]);
});

test('hidden or out-of-scope targets cannot leak focus out of a modal/player', function () {
  var f = fixture(12, 6);
  f.remote.options.getFocusRoot = function () { return f.group; };
  f.remote.options.allowSidebar = function () { return false; };
  f.remote.focus(f.cards[0]);
  f.cards[0].attrs['data-nav-up'] = '#header';
  assert.equal(f.remote.moveFocus('up'), f.cards[0]);
  assert.equal(f.remote.moveFocus('left'), f.cards[0]);
  f.remote._handlePointerFocus({ type: 'mousedown', target: f.nav });
  assert.equal(f.doc.activeElement, f.cards[0]);
});

test('home rail geometry never jumps diagonally when its horizontal row ends', function () {
  var f = fixture(12, 6);
  assert.equal(f.Remote.findNext([f.cards[5], f.cards[6]], f.cards[5], 'left'), null);
  f.group.attrs['data-nav-layout'] = 'rail';
  f.remote.focus(f.cards[5]);
  assert.equal(f.remote.moveFocus('right'), f.cards[6], 'rail order is independent of mixed poster sizes');
});

test('sidebar refresh is first in both visual and D-pad order, with stable focus styling', function () {
  var html = fs.readFileSync(path.join(__dirname, '../app/index.html'), 'utf8');
  var app = fs.readFileSync(path.join(__dirname, '../app/js/app.js'), 'utf8');
  var css = fs.readFileSync(path.join(__dirname, '../app/styles.css'), 'utf8');
  assert.ok(html.indexOf('id="nav-refresh"') < html.indexOf('id="nav-home"'));
  assert.match(app, /\[refs\.navRefresh, refs\.navHome, refs\.navSearch, refs\.navLibraries\]/);
  assert.match(css, /\.focusable\s*\{[^}]*transition: none;/);
  assert.match(css, /box-shadow: 0 0 0 7px #55dfb8,/);
  assert.match(css, /\.side-navigation\.expanded\s*\{\s*border-right-color: #6ce6c2;\s*\}/, 'focus must not change sidebar geometry');
  assert.doesNotMatch(app, /remote\.root\s*=/, 'scope changes must not detach pointer/focus listeners from their original root');
});

test('vertical grid navigation remembers its column through a short last row', function () {
  var f = fixture(20, 8);
  f.remote.focus(f.cards[15]);
  assert.equal(f.remote.moveFocus('down'), f.cards[19]);
  assert.equal(f.remote.moveFocus('up'), f.cards[15]);
  assert.equal(f.remote.moveFocus('up'), f.cards[7]);
});

test('column controls follow DOM order and do not jump diagonally', function () {
  var f = fixture(6, 2);
  f.group.attrs['data-nav-layout'] = 'column';
  f.remote.focus(f.cards[0]);
  assert.equal(f.remote.moveFocus('down'), f.cards[1]);
  assert.equal(f.remote.moveFocus('right'), f.cards[1]);
  assert.equal(f.remote.moveFocus('down'), f.cards[2]);
  f.remote.invalidateLayout();
  assert.equal(f.remote.moveFocus('up'), f.cards[1]);
  assert.equal(f.groupQueries(), 2);
});
