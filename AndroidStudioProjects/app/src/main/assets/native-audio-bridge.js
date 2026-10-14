/* Android-only adapter for the existing GitHub website. Audio lives in MediaSessionService. */
(() => {
    'use strict';
    if (window !== window.top || location.origin !== 'https://elim-radio.github.io' ||
        !(location.pathname === '/radio-elim' || location.pathname.startsWith('/radio-elim/')) ||
        !window.ElimNative || window.__elimNativeInstalled) return;
    const pageId = new URLSearchParams(location.search).get('_app_reload');
    if (!pageId) return;
    window.__elimNativeInstalled = true;
    window.__elimPageId = pageId;
    const send = (type, args = {}) => window.ElimNative.postMessage(JSON.stringify({type, ...args, pageId}));
    let src = '', position = 0, duration = 0, speed = 1, paused = true, ended = false;
    let error = null, pendingId = null, currentId = null, snapshot = null, lastError = null;
    let ready = false, savedAt = 0, closing = false;
    let lastUiKey = '', lastSavedKey = '';
    const attributes = {};
    const facade = {
        preload: 'metadata', title: '',
        get src() { return src; },
        set src(value) { src = String(value || ''); },
        get currentTime() { return position; },
        set currentTime(value) {
            if (Number.isFinite(Number(value)) && Number(value) >= 0) {
                position = Number(value); send('seek', {position});
            }
        },
        get duration() { return duration || NaN; },
        get playbackRate() { return speed; },
        set playbackRate(value) {
            speed = [0.75, 1, 1.25, 1.5, 1.75, 2].includes(Number(value)) ? Number(value) : 1;
            // playEpisode also sets this before play; do not alter an old track while switching.
            if (ready && currentId && typeof currentEpisode !== 'undefined' && currentEpisode?.id === currentId)
                send('speed', {speed});
        },
        get paused() { return paused; },
        get ended() { return ended; },
        get error() { return error; },
        get readyState() { return duration > 0 ? 4 : 0; },
        setAttribute(name, value) { if (name === 'src') this.src = value; else attributes[name] = String(value); },
        getAttribute(name) { return name === 'src' ? src : attributes[name] ?? null; },
        removeAttribute(name) { if (name === 'src') src = ''; else delete attributes[name]; },
        load() {
            if (!src) {
                closing = true; pendingId = null; currentId = null;
                position = 0; duration = 0; paused = true; ended = false; error = null;
                send('stop');
            }
        },
        play() {
            if (!ready || typeof currentEpisode === 'undefined' || !currentEpisode)
                return Promise.reject(new Error('No episode selected'));
            const tab = currentEpisodeTab;
            const queue = listFor(tab).slice().sort((a, b) => sortAscending
                ? a.date.localeCompare(b.date) || a.num - b.num
                : b.date.localeCompare(a.date) || b.num - a.num)
                .map(ep => ({id: ep.id, title: ep.title, src: ep.src, tab,
                    date: ep.date, youtubeId: ep.youtubeId || ''}));
            pendingId = currentEpisode.id; closing = false; error = null; lastError = null;
            send('play', {id: pendingId, queue, speed: currentSpeed,
                position: Number(positions[pendingId]) || 0});
            return Promise.resolve();
        },
        pause() { send('pause'); }
    };
    window.Audio = function Audio() { return facade; };
    window.audioEl = facade;

    function apply(state) {
        if (state.pageId !== pageId) return;
        if (!ready) { snapshot = state; return; }
        if (closing && state.item) return;
        if (!state.item) closing = false;
        if (pendingId && state.item?.id !== pendingId && !state.error) return;
        if (state.item?.id === pendingId || state.error) pendingId = null;
        const changed = currentId !== (state.item?.id || null);
        currentId = state.item?.id || null;
        src = state.item?.src || '';
        position = Number(state.position) || 0;
        duration = Number(state.duration) || 0;
        speed = Number(state.speed) || 1;
        paused = state.paused; ended = state.ended;
        error = state.error ? {message: state.error} : null;
        isPlaying = !paused;
        currentSpeed = speed;
        if (state.item) {
            currentEpisodeTab = state.item.tab;
            currentEpisode = listFor(currentEpisodeTab).find(ep => ep.id === currentId) || state.item;
            document.title = state.item.title;
            const saveKey = `${currentId}|${Math.floor(position)}|${speed}|${ended}`;
            if (saveKey !== lastSavedKey && (Date.now() - savedAt >= 5000 || paused || changed || ended)) {
                if (ended) delete positions[currentId]; else positions[currentId] = position;
                writeSaved('elim.positions.v2', positions);
                writeSaved('elim.speed.v2', speed);
                savedAt = Date.now();
                lastSavedKey = saveKey;
            }
        } else { currentEpisode = null; document.title = '라디오 엘림'; }
        // Never invoke HTML onended/onloadedmetadata: the service handles queue and resume position.
        if (changed && currentRoute === 'player') {
            if (currentEpisode) render(); else navigate('home');
        }
        const uiKey = `${currentId}|${paused}|${currentRoute}`;
        if (uiKey !== lastUiKey || changed) { syncPlayingUI(); lastUiKey = uiKey; }
        updatePlayerProgress();
        const select = document.querySelector('select[onchange*="setSpeed"]');
        if (select) select.value = String(speed);
        if (state.error && state.error !== lastError) showMessage(state.error);
        lastError = state.error;
    }
    window.__elimNativeApply = apply;
    let readySent = false, readinessObserver = null, initializationTimer = null;
    function pageIsReady() {
        const main = document.getElementById('main-content');
        return ready && document.readyState !== 'loading' && !!main && main.children.length > 0;
    }
    window.__elimIsPageReady = pageIsReady;
    function notifyPageReady() {
        if (readySent || !pageIsReady()) return;
        readySent = true;
        readinessObserver?.disconnect();
        send('ready');
    }
    function initialize() {
            if (typeof listFor !== 'function' || typeof syncPlayingUI !== 'function') return;
            updateNowPlayingInfo = ep => { if (ep) document.title = ep.title; };
            updatePositionState = () => {};
            if (navigator.mediaSession) {
                navigator.mediaSession.metadata = null;
                navigator.mediaSession.playbackState = 'none';
            }
            ready = true;
            window.__elimNativeReady = true;
            // Rendering may finish after DOMContentLoaded. Wait for actual content,
            // not for remote thumbnails/fonts or the window load event.
            readinessObserver = new MutationObserver(notifyPageReady);
            readinessObserver.observe(document.documentElement, {childList: true, subtree: true});
            if (snapshot) { const initial = snapshot; snapshot = null; apply(initial); }
            notifyPageReady();
    }
    window.addEventListener('DOMContentLoaded', () => {
        // The injected bridge's listener runs before the website's render listener.
        initializationTimer = setTimeout(initialize, 0);
    }, {once: true});
    window.addEventListener('pagehide', () => {
        clearTimeout(initializationTimer);
        readinessObserver?.disconnect();
    });
    document.addEventListener('visibilitychange', () => {
        if (!document.hidden && readySent) send('sync');
    });
})();
