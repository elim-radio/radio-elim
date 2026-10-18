const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/native-audio-bridge.js'), 'utf8');

function fixture({ content = false, pageId = 'page-a', websiteLoaded = true } = {}) {
    const messages = [], timers = new Map(), observers = [], windowEvents = {}, documentEvents = {};
    let nextTimer = 0, updates = 0;
    const main = { children: content ? [{}] : [] };
    const document = {
        readyState: 'loading', hidden: false, documentElement: {},
        getElementById: id => id === 'main-content' ? main : null,
        querySelector: () => null,
        addEventListener: (name, fn) => { documentEvents[name] = fn; }
    };
    const window = {
        ElimNative: { postMessage: value => messages.push(JSON.parse(value)) },
        addEventListener: (name, fn) => { windowEvents[name] = fn; }
    };
    window.top = window;
    const context = vm.createContext({
        window, document, navigator: {}, URLSearchParams,
        location: { origin: 'https://elim-radio.github.io', pathname: '/radio-elim/', search: `?_app_reload=${pageId}` },
        setTimeout(fn) { const id = ++nextTimer; timers.set(id, fn); return id; },
        clearTimeout(id) { timers.delete(id); },
        MutationObserver: class {
            constructor(fn) { this.fn = fn; this.active = false; observers.push(this); }
            observe() { this.active = true; }
            disconnect() { this.active = false; }
        },
        listFor: websiteLoaded ? () => [] : undefined,
        syncPlayingUI() {}, updateNowPlayingInfo() {}, updatePositionState() {},
        updatePlayerProgress() { updates++; }, writeSaved() {}, showMessage() {},
        currentEpisode: null, currentRoute: 'home', currentEpisodeTab: 'radio',
        currentSpeed: 1, isPlaying: false, positions: {}
    });
    vm.runInContext(source, context);
    return {
        messages, context, window, document, observers,
        get updates() { return updates; },
        domReady() { document.readyState = 'interactive'; windowEvents.DOMContentLoaded(); },
        flush() { const queued = [...timers.values()]; timers.clear(); queued.forEach(fn => fn()); },
        render() { main.children = [{}]; observers.filter(o => o.active).forEach(o => o.fn()); },
        hide() { windowEvents.pagehide(); },
        sync() { documentEvents.visibilitychange(); }
    };
}

test('waits for website rendering even after DOMContentLoaded and bridge initialization', () => {
    const page = fixture();
    page.domReady();
    assert.equal(page.messages.length, 0);
    page.flush();
    assert.equal(page.window.__elimNativeReady, true);
    assert.equal(page.window.__elimIsPageReady(), false);
    assert.equal(page.messages.length, 0);
    page.render();
    assert.deepEqual(page.messages, [{ type: 'ready', pageId: 'page-a' }]);
    assert.equal(page.window.__elimIsPageReady(), true);
    // External assets never finished: document is still only interactive.
    assert.equal(page.document.readyState, 'interactive');
    assert.equal(page.observers[0].active, false);
});

test('defers readiness until all DOMContentLoaded handlers have had a chance to render', () => {
    const page = fixture({ content: true });
    page.domReady();
    assert.equal(page.messages.length, 0);
    page.flush();
    assert.equal(page.messages.length, 1);
    page.render();
    assert.equal(page.messages.length, 1);
});

test('does not report a ready page if the website script failed to load', () => {
    const page = fixture({ content: true, websiteLoaded: false });
    page.domReady(); page.flush(); page.render(); page.sync();
    assert.equal(page.messages.length, 0);
    assert.ok(!page.window.__elimIsPageReady());
});

test('ignores native updates from a previous page and identifies outgoing messages', () => {
    const page = fixture({ content: true, pageId: 'page-b' });
    page.domReady(); page.flush();
    const state = { pageId: 'page-a', item: null, paused: true, speed: 1, position: 0, duration: 0 };
    page.window.__elimNativeApply(state);
    assert.equal(page.updates, 0);
    page.window.__elimNativeApply({ ...state, pageId: 'page-b' });
    assert.equal(page.updates, 1);
    page.window.audioEl.pause(); page.sync();
    assert.deepEqual(page.messages.map(m => m.pageId), ['page-b', 'page-b', 'page-b']);
});

test('leaving before initialization cancels the pending ready notification', () => {
    const page = fixture({ content: true });
    page.domReady(); page.hide(); page.flush();
    assert.equal(page.messages.length, 0);
});

test('leaving while waiting for content disconnects the readiness observer', () => {
    const page = fixture();
    page.domReady(); page.flush(); page.hide(); page.render();
    assert.equal(page.messages.length, 0);
});
