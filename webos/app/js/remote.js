(function (global) {
  'use strict';

  var KEY = {
    OK: 13,
    LEFT: 37,
    UP: 38,
    RIGHT: 39,
    DOWN: 40,
    PAUSE: 19,
    REWIND: 412,
    STOP: 413,
    PLAY: 415,
    FAST_FORWARD: 417,
    BACK: 461
  };
  var DIRECTION = {};
  DIRECTION[KEY.LEFT] = 'left';
  DIRECTION[KEY.UP] = 'up';
  DIRECTION[KEY.RIGHT] = 'right';
  DIRECTION[KEY.DOWN] = 'down';

  var DEFAULT_FOCUS_SELECTOR = [
    '[data-focusable]:not([data-focusable="false"])',
    'button:not([disabled])',
    'a[href]',
    'input:not([disabled])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])'
  ].join(',');

  function resolveElement(value, root) {
    if (typeof value === 'string' && root && typeof root.querySelector === 'function') {
      try {
        return root.querySelector(value);
      } catch (ignore) {
        return null;
      }
    }
    return value || null;
  }

  function keyCode(eventOrCode) {
    if (typeof eventOrCode === 'number') {
      return eventOrCode;
    }
    if (!eventOrCode) {
      return 0;
    }
    if (eventOrCode.keyCode || eventOrCode.which) {
      return eventOrCode.keyCode || eventOrCode.which;
    }
    var names = {
      ArrowLeft: KEY.LEFT,
      ArrowUp: KEY.UP,
      ArrowRight: KEY.RIGHT,
      ArrowDown: KEY.DOWN,
      Enter: KEY.OK,
      MediaPlay: KEY.PLAY,
      MediaPause: KEY.PAUSE,
      MediaStop: KEY.STOP,
      MediaTrackNext: KEY.FAST_FORWARD,
      MediaTrackPrevious: KEY.REWIND
    };
    return names[eventOrCode.key] || 0;
  }

  function dispatchControlEvent(element, name, document) {
    if (!element || typeof element.dispatchEvent !== 'function') {
      return;
    }
    var event;
    try {
      event = new global.Event(name, { bubbles: true });
    } catch (ignore) {
      if (!document || typeof document.createEvent !== 'function') {
        return;
      }
      event = document.createEvent('Event');
      event.initEvent(name, true, false);
    }
    element.dispatchEvent(event);
  }

  function adjustRange(element, code, document) {
    if (!element || String(element.nodeName || '').toLowerCase() !== 'input') {
      return false;
    }
    var type = typeof element.getAttribute === 'function'
      ? element.getAttribute('type')
      : element.type;
    if (String(type || '').toLowerCase() !== 'range' || (code !== KEY.LEFT && code !== KEY.RIGHT)) {
      return false;
    }
    var minimum = element.min === '' || typeof element.min === 'undefined' ? 0 : Number(element.min);
    var maximum = element.max === '' || typeof element.max === 'undefined' ? 100 : Number(element.max);
    var step = element.step === '' || typeof element.step === 'undefined' ? 1 : Number(element.step);
    var current = Number(element.value);
    minimum = isFinite(minimum) ? minimum : 0;
    maximum = isFinite(maximum) ? maximum : 100;
    step = isFinite(step) && step > 0 ? step : 1;
    current = isFinite(current) ? current : minimum;
    element.value = String(Math.max(minimum, Math.min(maximum, current + (code === KEY.RIGHT ? step : -step))));
    dispatchControlEvent(element, 'input', document);
    dispatchControlEvent(element, 'change', document);
    return true;
  }

  function rectOf(element) {
    if (!element || typeof element.getBoundingClientRect !== 'function') {
      return null;
    }
    var rect = element.getBoundingClientRect();
    var width = typeof rect.width === 'number' ? rect.width : rect.right - rect.left;
    var height = typeof rect.height === 'number' ? rect.height : rect.bottom - rect.top;
    if (!isFinite(width) || !isFinite(height) || width <= 0 || height <= 0) {
      return null;
    }
    return {
      left: rect.left,
      right: rect.right,
      top: rect.top,
      bottom: rect.bottom,
      width: width,
      height: height,
      centerX: rect.left + width / 2,
      centerY: rect.top + height / 2
    };
  }

  function isVisible(element) {
    if (!element || element.disabled || element.hidden) {
      return false;
    }
    if (
      typeof element.getAttribute === 'function' &&
      (element.getAttribute('aria-disabled') === 'true' ||
        element.getAttribute('aria-hidden') === 'true')
    ) {
      return false;
    }
    return Boolean(rectOf(element));
  }

  function perpendicularOverlap(current, candidate, direction) {
    if (direction === 'left' || direction === 'right') {
      return Math.max(
        0,
        Math.min(current.bottom, candidate.bottom) -
          Math.max(current.top, candidate.top)
      );
    }
    return Math.max(
      0,
      Math.min(current.right, candidate.right) -
        Math.max(current.left, candidate.left)
    );
  }

  function scoreCandidate(current, candidate, direction) {
    var primary;
    var secondary;
    if (direction === 'left') {
      primary = current.centerX - candidate.centerX;
      secondary = Math.abs(candidate.centerY - current.centerY);
    } else if (direction === 'right') {
      primary = candidate.centerX - current.centerX;
      secondary = Math.abs(candidate.centerY - current.centerY);
    } else if (direction === 'up') {
      primary = current.centerY - candidate.centerY;
      secondary = Math.abs(candidate.centerX - current.centerX);
    } else {
      primary = candidate.centerY - current.centerY;
      secondary = Math.abs(candidate.centerX - current.centerX);
    }

    if (primary <= 0) {
      return Infinity;
    }

    var overlap = perpendicularOverlap(current, candidate, direction);
    var alignmentPenalty = overlap > 0 ? 0 : secondary * secondary * 4;
    var anglePenalty = (secondary / Math.max(primary, 1)) * 100;
    return primary * primary + alignmentPenalty + anglePenalty;
  }

  function findNext(elements, currentElement, direction) {
    var current = rectOf(currentElement);
    var best = null;
    var bestScore = Infinity;
    var i;
    if (!current) {
      return elements.length ? elements[0] : null;
    }
    for (i = 0; i < elements.length; i += 1) {
      if (elements[i] === currentElement || !isVisible(elements[i])) {
        continue;
      }
      var candidate = rectOf(elements[i]);
      // A horizontal key must not jump diagonally into another row.
      if ((direction === 'left' || direction === 'right') &&
          perpendicularOverlap(current, candidate, direction) <= 0) {
        continue;
      }
      var score = scoreCandidate(current, candidate, direction);
      if (score < bestScore) {
        best = elements[i];
        bestScore = score;
      }
    }
    return best;
  }

  function adjustSelect(element, code, document) {
    if (!element || String(element.nodeName || '').toLowerCase() !== 'select' ||
        (code !== KEY.LEFT && code !== KEY.RIGHT && code !== KEY.OK)) { return false; }
    var options = element.options || [];
    var step = code === KEY.LEFT ? -1 : 1;
    var next = element.selectedIndex + step;
    while (next >= 0 && next < options.length && options[next].disabled) { next += step; }
    if (next >= 0 && next < options.length) {
      element.selectedIndex = next;
      dispatchControlEvent(element, 'change', document);
    }
    return true;
  }

  function isInside(element, selector) {
    return Boolean(closest(element, selector));
  }

  function closest(element, selector) {
    var current = element;
    while (current) {
      var matcher = current.matches || current.matchesSelector || current.webkitMatchesSelector || current.msMatchesSelector;
      if (typeof matcher === 'function') {
        try {
          if (matcher.call(current, selector)) { return current; }
        } catch (ignore) {
          return null;
        }
      }
      current = current.parentNode;
    }
    return null;
  }

  function buildRows(cards, rail) {
    var rows = [];
    var rowTop = null;
    cards.forEach(function (card) {
      // offsetTop is independent of scrolling and focus outlines/transforms.
      var top = card.offsetTop;
      if (!rows.length || (!rail && Math.abs(top - rowTop) > 4)) {
        rows.push([]);
        rowTop = top;
      }
      rows[rows.length - 1].push(card);
    });
    return rows;
  }

  function rowNeighbour(rows, current, direction) {
    var r;
    for (r = 0; r < rows.length; r += 1) {
      var c = rows[r].indexOf(current);
      if (c < 0) { continue; }
      if (direction === 'left') { return rows[r][c - 1] || null; }
      if (direction === 'right') { return rows[r][c + 1] || null; }
      var nextRow = rows[r + (direction === 'up' ? -1 : 1)];
      return nextRow ? nextRow[Math.min(c, nextRow.length - 1)] : null;
    }
    return null;
  }

  function Remote(options) {
    if (!(this instanceof Remote)) {
      return new Remote(options);
    }
    this.options = options || {};
    this.document = this.options.document || global.document;
    if (!this.document) {
      throw new Error('ShyniFlexWebOS.Remote requires a document.');
    }
    this.root = resolveElement(this.options.root, this.document) || this.document;
    this.keyTarget = this.options.keyTarget || this.document;
    this.focusSelector = this.options.focusSelector || DEFAULT_FOCUS_SELECTOR;
    this.player = this.options.player || null;
    this.keyHandlers = [];
    this.started = false;
    this._focused = null;
    this._lastContentFocus = null;
    this._layoutCache = null;
    this._pointerPosition = null;
    this._heldDirection = null;
    this._repeatTimer = null;
    this._boundKeyDown = this.handleKeyDown.bind(this);
    this._boundKeyUp = this.handleKeyUp.bind(this);
    this._boundBlur = this.stopRepeating.bind(this);
    this._boundVisibility = this._handleVisibility.bind(this);
    this._boundPointerFocus = this._handlePointerFocus.bind(this);
    this._boundFocusIn = this._handleFocusIn.bind(this);

    if (this.options.subtitles) {
      var subtitleController = this.options.subtitles;
      this.addKeyHandler(function (event, code) {
        return subtitleController.handleRemoteKey(code, event);
      });
    }
    if (this.options.autoStart !== false) {
      this.start();
    }
  }

  Remote.KEY = KEY;
  Remote.DEFAULT_FOCUS_SELECTOR = DEFAULT_FOCUS_SELECTOR;
  Remote.scoreCandidate = scoreCandidate;
  Remote.findNext = findNext;
  Remote.isInside = isInside;
  Remote.adjustRange = adjustRange;
  Remote.adjustSelect = adjustSelect;
  Remote.buildRows = buildRows;
  Remote.rowNeighbour = rowNeighbour;

  Remote.prototype.start = function () {
    if (this.started || !this.keyTarget || typeof this.keyTarget.addEventListener !== 'function') {
      return this;
    }
    this.started = true;
    this.keyTarget.addEventListener('keydown', this._boundKeyDown, true);
    this.keyTarget.addEventListener('keyup', this._boundKeyUp, true);
    this.document.addEventListener('visibilitychange', this._boundVisibility, true);
    if (typeof global.addEventListener === 'function') { global.addEventListener('blur', this._boundBlur, true); }
    if (this.root && typeof this.root.addEventListener === 'function') {
      this.root.addEventListener('mousedown', this._boundPointerFocus, true);
      this.root.addEventListener('mousemove', this._boundPointerFocus, true);
      this.root.addEventListener('focusin', this._boundFocusIn, true);
    }
    return this;
  };

  Remote.prototype.stop = function () {
    this.stopRepeating();
    if (!this.started) {
      return this;
    }
    this.started = false;
    this.keyTarget.removeEventListener('keydown', this._boundKeyDown, true);
    this.keyTarget.removeEventListener('keyup', this._boundKeyUp, true);
    this.document.removeEventListener('visibilitychange', this._boundVisibility, true);
    if (typeof global.removeEventListener === 'function') { global.removeEventListener('blur', this._boundBlur, true); }
    if (this.root && typeof this.root.removeEventListener === 'function') {
      this.root.removeEventListener('mousedown', this._boundPointerFocus, true);
      this.root.removeEventListener('mousemove', this._boundPointerFocus, true);
      this.root.removeEventListener('focusin', this._boundFocusIn, true);
    }
    return this;
  };

  Remote.prototype.setPlayer = function (player) {
    this.player = player || null;
    return this;
  };

  Remote.prototype.addKeyHandler = function (handler, first) {
    var self = this;
    if (typeof handler !== 'function') {
      return function () {};
    }
    if (first) {
      this.keyHandlers.unshift(handler);
    } else {
      this.keyHandlers.push(handler);
    }
    return function () {
      self.removeKeyHandler(handler);
    };
  };

  Remote.prototype.removeKeyHandler = function (handler) {
    var index = this.keyHandlers.indexOf(handler);
    if (index >= 0) {
      this.keyHandlers.splice(index, 1);
      return true;
    }
    return false;
  };

  Remote.prototype._matches = function (element, selector) {
    if (!element || element === this.root) {
      return false;
    }
    var matcher =
      element.matches ||
      element.matchesSelector ||
      element.webkitMatchesSelector ||
      element.msMatchesSelector;
    if (!matcher) {
      return false;
    }
    try {
      return matcher.call(element, selector);
    } catch (ignore) {
      return false;
    }
  };

  Remote.prototype._closestFocusable = function (element) {
    var current = element;
    while (current && current !== this.root && current !== this.document) {
      if (this._matches(current, this.focusSelector) && isVisible(current)) {
        return current;
      }
      current = current.parentNode;
    }
    return null;
  };

  Remote.prototype._handlePointerFocus = function (event) {
    // Scrolling beneath an idle Magic Remote cursor emits mouseover events.
    // Only real cursor movement (or a click) can switch away from D-pad mode.
    if (event.type !== 'mousedown') {
      var position = { x: event.clientX, y: event.clientY };
      var previous = this._pointerPosition;
      if (!previous) { this._pointerPosition = position; return; }
      if (Math.abs(position.x - previous.x) + Math.abs(position.y - previous.y) < 6) { return; }
      this._pointerPosition = position;
    }
    this.stopRepeating();
    var focusable = this._closestFocusable(event.target);
    if (this._allowedTarget(focusable) && this.document.activeElement !== focusable) {
      this.focus(focusable, 'pointer');
    }
  };

  Remote.prototype._handleFocusIn = function (event) {
    var element = event.target;
    if (this._focused && this._focused !== element) {
      this._focused.removeAttribute('data-tv-focused');
    }
    this._focused = element;
    if (element && typeof element.setAttribute === 'function') {
      element.setAttribute('data-tv-focused', 'true');
    }
  };

  Remote.prototype._focusScope = function () {
    return typeof this.options.getFocusRoot === 'function'
      ? this.options.getFocusRoot() || this.root : this.root;
  };

  Remote.prototype._allowedTarget = function (element, alreadyFocused) {
    var scope = this._focusScope();
    return Boolean(element && !element.disabled && !element.hidden && (alreadyFocused || isVisible(element)) &&
      (!scope.contains || scope.contains(element) ||
        (typeof this.options.allowSidebar === 'function' && this.options.allowSidebar() &&
          isInside(element, '.side-navigation'))));
  };

  Remote.prototype.getFocusableElements = function () {
    if (!this.root || typeof this.root.querySelectorAll !== 'function') {
      return [];
    }
    var nodes;
    try {
      nodes = this._focusScope().querySelectorAll(this.focusSelector);
    } catch (ignore) {
      return [];
    }
    return Array.prototype.filter.call(nodes, isVisible);
  };

  Remote.prototype.getCurrentFocus = function () {
    var active = this.document.activeElement;
    if (this._allowedTarget(active, true) && this._matches(active, this.focusSelector)) {
      return active;
    }
    return null;
  };

  Remote.prototype.focus = function (element, source) {
    if (!element || typeof element.focus !== 'function' || !isVisible(element)) {
      return false;
    }
    if (this.document.activeElement === element) { return true; }
    var previous = this.document.activeElement;
    if (previous && !isInside(previous, '.side-navigation') && isInside(element, '.side-navigation')) {
      this._lastContentFocus = previous;
    }
    try { element.focus({ preventScroll: true }); } catch (ignore) { element.focus(); }
    if (this._focused !== element) { this._handleFocusIn({ target: element }); }
    this._revealFocus(element);
    if (typeof this.options.onFocusChange === 'function') {
      this.options.onFocusChange(element, source || 'api');
    }
    return true;
  };

  Remote.prototype._revealFocus = function (element) {
    var parent = element.parentNode;
    while (parent && parent !== this.root) {
      if (parent.getAttribute && parent.getAttribute('data-nav-scroll') === 'true') {
        var itemRect = rectOf(element);
        var bounds = rectOf(parent);
        if (itemRect && bounds) {
          if (parent.scrollWidth > parent.clientWidth) {
            if (itemRect.left < bounds.left + 10) { parent.scrollLeft -= bounds.left + 10 - itemRect.left; }
            else if (itemRect.right > bounds.right - 10) { parent.scrollLeft += itemRect.right - bounds.right + 10; }
          }
          if (parent.scrollHeight > parent.clientHeight) {
            if (itemRect.top < bounds.top + 10) { parent.scrollTop -= bounds.top + 10 - itemRect.top; }
            else if (itemRect.bottom > bounds.bottom - 10) { parent.scrollTop += itemRect.bottom - bounds.bottom + 10; }
          }
        }
      }
      parent = parent.parentNode;
    }
  };

  Remote.prototype.focusInitial = function () {
    var initial = resolveElement(this.options.initialFocus, this.root);
    if (initial && this.focus(initial, 'initial')) {
      return initial;
    }
    var elements = this.getFocusableElements();
    if (elements.length) {
      this.focus(elements[0], 'initial');
      return elements[0];
    }
    return null;
  };

  Remote.prototype._explicitTarget = function (current, direction) {
    if (!current || typeof current.getAttribute !== 'function') {
      return null;
    }
    var selector =
      current.getAttribute('data-nav-' + direction) ||
      current.getAttribute('data-focus-' + direction);
    var target = resolveElement(selector, this.root);
    return this._allowedTarget(target) ? target : null;
  };

  Remote.prototype._groupTarget = function (current, direction) {
    var group = closest(current, '[data-nav-layout]');
    if (!group) { return null; }
    var cache = this._layoutCache;
    var children = group.children;
    var width = group.clientWidth;
    if (!cache || cache.group !== group || cache.width !== width ||
        cache.count !== children.length || cache.first !== children[0] ||
        cache.last !== children[children.length - 1]) {
      var layout = group.getAttribute('data-nav-layout');
      var cards = Array.prototype.filter.call(group.querySelectorAll(this.focusSelector), isVisible);
      cache = { group: group, width: width, count: children.length,
        first: children[0], last: children[children.length - 1],
        rows: layout === 'column' ? cards.map(function (card) { return [card]; }) : buildRows(cards, layout === 'rail'),
        column: null, lastTarget: null };
      this._layoutCache = cache;
    }
    var neighbour = null;
    cache.rows.some(function (row, rowIndex) {
      var column = row.indexOf(current);
      if (column < 0) { return false; }
      if (cache.column === null || cache.lastTarget !== current) { cache.column = column; }
      if (direction === 'up' || direction === 'down') {
        var targetRow = cache.rows[rowIndex + (direction === 'up' ? -1 : 1)];
        neighbour = targetRow ? targetRow[Math.min(cache.column, targetRow.length - 1)] : null;
      } else {
        var nextColumn = column + (direction === 'left' ? -1 : 1);
        neighbour = row[nextColumn] || null;
        if (neighbour) { cache.column = nextColumn; }
      }
      return true;
    });
    cache.lastTarget = neighbour || current;
    // At an edge, stay put unless the group explicitly names an exit.
    return neighbour || this._explicitTarget(group, direction) || current;
  };

  Remote.prototype.invalidateLayout = function () {
    this._layoutCache = null;
  };

  Remote.prototype.moveFocus = function (direction, current) {
    current = current || this.getCurrentFocus();
    if (!current) {
      var initialElements = this.getFocusableElements();
      if (!initialElements.length) { return null; }
      this.focus(initialElements[0], 'dpad');
      return initialElements[0];
    }
    var currentInSidebar = isInside(current, '.side-navigation');
    if (currentInSidebar && direction === 'right') {
      var remembered = this._allowedTarget(this._lastContentFocus) ? this._lastContentFocus : null;
      var content = remembered || this._explicitTarget(current, direction) || this.getFocusableElements()[0];
      if (content) { this.focus(content, 'dpad'); }
      return content || current;
    }
    var explicit = this._explicitTarget(current, direction);
    var structured = explicit || this._groupTarget(current, direction);
    if (structured) {
      this.focus(structured, 'dpad');
      return structured;
    }
    var elements = this.getFocusableElements();
    var sameRegion = elements.filter(function (element) {
      return isInside(element, '.side-navigation') === currentInSidebar;
    });
    var next = findNext(sameRegion, current, direction);
    // The sidebar is a separate navigation region. It may only be crossed
    // horizontally, never by an imprecise up/down geometry match.
    if (!next && direction === 'left' && !currentInSidebar &&
        typeof this.options.allowSidebar === 'function' && this.options.allowSidebar()) {
      next = this.root.querySelector('.side-navigation-item.selected') || this.root.querySelector('#nav-home');
    }
    if (next) {
      this.focus(next, 'dpad');
    }
    return next;
  };

  Remote.prototype._isRoot = function () {
    if (typeof this.options.isRoot === 'function') {
      return Boolean(this.options.isRoot());
    }
    if (typeof this.options.isRoot === 'boolean') {
      return this.options.isRoot;
    }
    if (typeof this.options.routeDepth === 'number') {
      return this.options.routeDepth <= 0;
    }
    return !global.history || global.history.length <= 1;
  };

  Remote.prototype._platformBack = function () {
    if (
      global.webOS &&
      typeof global.webOS.platformBack === 'function'
    ) {
      global.webOS.platformBack();
      return true;
    }
    if (global.history && typeof global.history.back === 'function') {
      global.history.back();
      return true;
    }
    return false;
  };

  Remote.prototype._handleBack = function (event) {
    if (typeof this.options.onBack === 'function' && this.options.onBack(event) === true) {
      return true;
    }
    if (this._isRoot()) {
      return this._platformBack();
    }
    if (typeof this.options.onNavigateBack === 'function') {
      this.options.onNavigateBack(event);
      return true;
    }
    if (global.history && typeof global.history.back === 'function') {
      global.history.back();
      return true;
    }
    return false;
  };

  Remote.prototype._consume = function (event) {
    if (!event) {
      return;
    }
    if (typeof event.preventDefault === 'function') {
      event.preventDefault();
    }
    if (typeof event.stopPropagation === 'function') {
      event.stopPropagation();
    }
  };

  Remote.prototype.stopRepeating = function () {
    if (this._repeatTimer !== null) { global.clearTimeout(this._repeatTimer); }
    this._repeatTimer = null;
    this._heldDirection = null;
  };

  Remote.prototype.handleKeyUp = function (event) {
    if (this._heldDirection && (!keyCode(event) || keyCode(event) === this._heldDirection.code)) { this.stopRepeating(); }
  };

  Remote.prototype._handleVisibility = function () {
    if (this.document.hidden) { this.stopRepeating(); }
  };

  Remote.prototype._startRepeating = function (code) {
    var self = this;
    if (!this.options.repeatNavigation || typeof this.options.canRepeatNavigation !== 'function' ||
        !this.options.canRepeatNavigation(this.document.activeElement)) { return; }
    this._heldDirection = { code: code, scope: this._focusScope(), lastEventAt: Date.now() };
    this._repeatTimer = global.setTimeout(function () { self._repeatDirection(); }, 220);
  };

  Remote.prototype._repeatDirection = function () {
    var held = this._heldDirection;
    var self = this;
    this._repeatTimer = null;
    // A lost keyup may never leave an unbounded navigation loop behind.
    if (!held || this.document.hidden || this._focusScope() !== held.scope || Date.now() - held.lastEventAt > 600 ||
        !this.options.canRepeatNavigation(this.document.activeElement)) { this.stopRepeating(); return; }
    var current = this.getCurrentFocus();
    if (!current) { this.stopRepeating(); return; }
    var direction = DIRECTION[held.code];
    var next = this._explicitTarget(current, direction) || this._groupTarget(current, direction);
    // Holding a key stops at a content boundary. A fresh press can enter the sidebar/header.
    if (!next || next === current || (!isInside(current, '.side-navigation') && !isInside(next, '[data-nav-layout]'))) { return; }
    this.focus(next, 'dpad-repeat');
    this._repeatTimer = global.setTimeout(function () { self._repeatDirection(); }, 95);
  };

  Remote.prototype.handleKeyDown = function (eventOrCode) {
    var event = typeof eventOrCode === 'number' ? null : eventOrCode;
    var code = keyCode(eventOrCode);
    if (event && this._heldDirection && this._heldDirection.code === code &&
        this._heldDirection.scope === this._focusScope() && this.options.canRepeatNavigation(this.document.activeElement)) {
      this._heldDirection.lastEventAt = Date.now();
      this._consume(event);
      return true;
    }
    this.stopRepeating();
    var handlers = this.keyHandlers.slice();
    var handled = false;
    var i;

    for (i = 0; i < handlers.length; i += 1) {
      if (handlers[i](event, code) === true) {
        handled = true;
        break;
      }
    }

    var active = !handled && (DIRECTION[code] || code === KEY.OK) ? this.getCurrentFocus() : null;
    if (!handled && adjustSelect(active, code, this.document)) {
      handled = true;
    } else if (!handled && (code === KEY.LEFT || code === KEY.RIGHT) && adjustRange(active, code, this.document)) {
      handled = true;
    } else if (!handled && DIRECTION[code]) {
      this.moveFocus(DIRECTION[code], active);
      // Always consume D-pad navigation so a missing neighbour cannot scroll
      // the document or move focus into browser chrome.
      handled = true;
    } else if (!handled && code === KEY.OK) {
      var current = active || this.focusInitial();
      if (current) {
        if (typeof this.options.onOk === 'function') {
          handled = this.options.onOk(current, event) === true;
        }
        if (!handled && typeof current.click === 'function') {
          current.click();
          handled = true;
        }
      }
    } else if (!handled && code === KEY.BACK) {
      handled = this._handleBack(event);
    } else if (
      !handled &&
      (code === KEY.PLAY ||
        code === KEY.PAUSE ||
        code === KEY.FAST_FORWARD ||
        code === KEY.REWIND ||
        code === KEY.STOP)
    ) {
      if (typeof this.options.onMediaKey === 'function') {
        handled = this.options.onMediaKey(code, event) === true;
      }
      if (
        !handled &&
        this.player &&
        typeof this.player.handleRemoteKey === 'function'
      ) {
        handled = this.player.handleRemoteKey(code);
      }
    }

    if (!handled && typeof this.options.onUnhandledKey === 'function') {
      handled = this.options.onUnhandledKey(code, event) === true;
    }
    if (handled) {
      this._consume(event);
      if (event && DIRECTION[code]) { this._startRepeating(code); }
    }
    return handled;
  };

  Remote.prototype.destroy = function () {
    this.stop();
    this.keyHandlers = [];
    this._layoutCache = null;
    this._lastContentFocus = null;
    this._focused = null;
  };

  var namespace = global.ShyniFlexWebOS || {};
  namespace.Remote = Remote;
  namespace.RemoteKeys = KEY;
  global.ShyniFlexWebOS = namespace;
  global.ShyniFlexRemote = Remote;
})(
  typeof window !== 'undefined'
    ? window
    : typeof global !== 'undefined'
      ? global
      : this
);
