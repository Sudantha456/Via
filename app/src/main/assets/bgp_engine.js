/*
 * Via Background Play — injected playback engine
 * ----------------------------------------------
 * Injected into every page by com.via.bgplay.web.PlaybackInjector.
 *
 * Responsibilities
 *   1. Find the media element that is actually playing (video, audio, YouTube player,
 *      YouTube Music player, SoundCloud, Spotify web, any HTML5 <audio>/<video>).
 *   2. Report rich metadata to the native layer (title, artist, album, artwork,
 *      site name, duration, position, playing state, prev/next availability).
 *   3. Execute transport commands coming from the notification / lock screen /
 *      headset buttons (play, pause, next, previous, seek, stop).
 *   4. Keep playback alive when the page is hidden: the Page Visibility API and
 *      blur/focus signals are neutralised, and playback that was interrupted by the
 *      system is resumed.
 *
 * The script is ES5 only and every browser API is accessed defensively, because it
 * has to run on old Android System WebView builds as well as on current ones.
 *
 * Native <-> JS contract
 *   JS -> native : window.BgpBridge.postMessage(jsonString)
 *   native -> JS : window.__bgp.act(action, value) / window.__bgp.configure(cfg)
 */
(function () {
    'use strict';

    var W = window;
    var D = document;

    var CONFIG = __BGP_CONFIG__;

    if (W.__bgp && W.__bgp.alive) {
        // Page injected twice (SPA navigation, reload of the same document, ...).
        W.__bgp.configure(CONFIG);
        return;
    }

    var H = (function () {
        try { return (location.hostname || '').toLowerCase(); } catch (e) { return ''; }
    })();

    /* ------------------------------------------------------------------ *
     * 1. Site adapters
     * ------------------------------------------------------------------ */

    function firstText(selectors) {
        for (var i = 0; i < selectors.length; i++) {
            try {
                var el = D.querySelector(selectors[i]);
                if (el) {
                    var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
                    if (t) return t;
                }
            } catch (e) { /* invalid selector on old engines */ }
        }
        return '';
    }

    function metaContent(names) {
        for (var i = 0; i < names.length; i++) {
            try {
                var el = D.querySelector('meta[property="' + names[i] + '"],meta[name="' + names[i] + '"]');
                if (el) {
                    var c = (el.getAttribute('content') || '').trim();
                    if (c) return c;
                }
            } catch (e) { }
        }
        return '';
    }

    function backgroundImageUrl(selectors) {
        for (var i = 0; i < selectors.length; i++) {
            try {
                var el = D.querySelector(selectors[i]);
                if (!el) continue;
                var bg = W.getComputedStyle(el).backgroundImage || '';
                var m = bg.match(/url\(["']?(.*?)["']?\)/);
                if (m && m[1]) return m[1];
            } catch (e) { }
        }
        return '';
    }

    function imageUrl(selectors) {
        for (var i = 0; i < selectors.length; i++) {
            try {
                var el = D.querySelector(selectors[i]);
                if (!el) continue;
                var src = el.currentSrc || el.src || el.getAttribute('src') || '';
                if (src && src.indexOf('data:') !== 0) return src;
            } catch (e) { }
        }
        return '';
    }

    function isYouTubeDesktop() {
        return /(^|\.)youtube\.com$/.test(H) && H.indexOf('music.') !== 0 && H.indexOf('m.youtube') !== 0;
    }
    function isYouTubeMobile() {
        return H.indexOf('m.youtube.com') === 0 || H.indexOf('youtube.com') === 0 && H.indexOf('music.') !== 0;
    }
    function isYouTubeMusic() {
        return H.indexOf('music.youtube.com') !== -1;
    }

    var ADAPTERS = [
        {
            id: 'youtube-music',
            test: isYouTubeMusic,
            siteName: 'YouTube Music',
            title: ['ytmusic-player-bar .title', 'ytmusic-player-bar yt-formatted-string.title'],
            artist: ['ytmusic-player-bar .byline a:first-of-type', 'ytmusic-player-bar .byline', '#song-video ytmusic-player-bar .byline'],
            album: ['ytmusic-player-bar .byline a:nth-of-type(2)'],
            art: function () {
                return imageUrl(['ytmusic-player-bar img.image', 'ytmusic-player-bar img', '#song-image img']);
            },
            next: ['ytmusic-player-bar #next-button', 'ytmusic-player-bar .next-button', '#next-button'],
            prev: ['ytmusic-player-bar #previous-button', 'ytmusic-player-bar .previous-button', '#previous-button'],
            play: ['ytmusic-player-bar #play-pause-button', 'ytmusic-player-bar .play-pause-button'],
            stripTitle: function (t) { return t.replace(/\s*-\s*YouTube Music\s*$/i, ''); }
        },
        {
            id: 'youtube-mobile',
            test: isYouTubeMobile,
            siteName: 'YouTube',
            title: [
                'ytm-slim-video-information-renderer h1 .yt-core-attributed-string',
                '.slim-video-information-title .yt-core-attributed-string',
                'h1.slim-video-information-title',
                'ytm-watch h1'
            ],
            artist: [
                'ytm-slim-owner-renderer .yt-core-attributed-string',
                '.slim-owner-channel-name .yt-core-attributed-string',
                'ytm-slim-video-metadata-section-renderer .yt-core-attributed-string'
            ],
            album: [],
            art: function () {
                var og = metaContent(['og:image']);
                if (og) return og;
                return imageUrl(['ytm-slim-video-information-renderer img', '.slim-video-information-thumbnail img']);
            },
            next: ['.next-button', '.ytp-next-button', 'button.player-next-button'],
            prev: ['.previous-button', '.ytp-prev-button', 'button.player-prev-button'],
            play: ['.play-pause-button', '.ytp-play-button', 'button.player-play-pause-button'],
            stripTitle: function (t) { return t.replace(/\s*-\s*YouTube\s*$/i, ''); }
        },
        {
            id: 'youtube',
            test: isYouTubeDesktop,
            siteName: 'YouTube',
            title: [
                'ytd-watch-metadata h1 yt-formatted-string',
                '#container h1.title yt-formatted-string',
                'h1.ytd-watch-metadata',
                '.ytp-title-link'
            ],
            artist: [
                'ytd-video-owner-renderer #channel-name a',
                '#owner #channel-name a',
                '#upload-info #channel-name a',
                'ytd-channel-name a'
            ],
            album: [],
            art: function () {
                var og = metaContent(['og:image']);
                if (og) return og;
                var bg = backgroundImageUrl(['.ytp-cued-thumbnail-overlay-image', '.ytp-thumbnail-overlay-image']);
                if (bg) return bg;
                return imageUrl(['ytd-watch-metadata img', 'link[rel="image_src"]']);
            },
            next: ['.ytp-next-button', 'a.ytp-next-button', '.ytp-next-button.ytp-button'],
            prev: ['.ytp-prev-button', 'a.ytp-prev-button'],
            play: ['.ytp-play-button'],
            stripTitle: function (t) { return t.replace(/\s*-\s*YouTube\s*$/i, ''); }
        },
        {
            id: 'soundcloud',
            test: function () { return H.indexOf('soundcloud.com') !== -1; },
            siteName: 'SoundCloud',
            title: ['.playbackSoundBadge__titleLink span', '.playbackSoundBadge__titleLink'],
            artist: ['.playbackSoundBadge__lightLink', '.playbackSoundBadge__additional a'],
            album: [],
            art: function () {
                return backgroundImageUrl(['.playbackSoundBadge__avatar .image', '.playbackSoundBadge__avatar span']);
            },
            next: ['.skipControl__next', '.playControls__next'],
            prev: ['.skipControl__previous', '.playControls__prev'],
            play: ['.playControls__play'],
            stripTitle: function (t) { return t.replace(/^Current track:\s*/i, ''); }
        },
        {
            id: 'spotify',
            test: function () { return H.indexOf('open.spotify.com') !== -1; },
            siteName: 'Spotify',
            title: ['[data-testid="context-item-info-title"] a', '[data-testid="nowplaying-track-link"]'],
            artist: ['[data-testid="context-item-info-artist"] a', '[data-testid="context-item-info-artist"]'],
            album: [],
            art: function () { return imageUrl(['[data-testid="cover-art-image"]', '.now-playing img']); },
            next: ['[data-testid="control-button-skip-forward"]'],
            prev: ['[data-testid="control-button-skip-back"]'],
            play: ['[data-testid="control-button-playpause"]'],
            stripTitle: function (t) { return t; }
        },
        {
            id: 'bandcamp',
            test: function () { return H.indexOf('bandcamp.com') !== -1; },
            siteName: 'Bandcamp',
            title: ['.title_link .title', '.playback .title span'],
            artist: ['.title_link .artist', '.playback .artist span'],
            album: [],
            art: function () { return imageUrl(['.playlist .current .art img', '.playback .art img']); },
            next: ['.nextbutton'],
            prev: ['.prevbutton'],
            play: ['.playbutton'],
            stripTitle: function (t) { return t; }
        }
    ];

    var ADAPTER = (function () {
        for (var i = 0; i < ADAPTERS.length; i++) {
            try { if (ADAPTERS[i].test()) return ADAPTERS[i]; } catch (e) { }
        }
        return null;
    })();

    /* ------------------------------------------------------------------ *
     * 2. Page MediaSession hooks — capture what the site itself exposes
     * ------------------------------------------------------------------ */

    var pageHandlers = {};
    var originalMediaSession = null;
    var lastPageMetadata = null;

    (function hookMediaSession() {
        try {
            originalMediaSession = W.navigator.mediaSession || null;
            if (!originalMediaSession || !originalMediaSession.setActionHandler) return;
            var original = originalMediaSession.setActionHandler;
            originalMediaSession.setActionHandler = function (action, handler) {
                try { pageHandlers[action] = handler; } catch (e) { }
                try {
                    return original.call(originalMediaSession, action, handler);
                } catch (e) { }
            };
            // Keep a copy of the latest metadata the page published.
            try {
                var descriptor = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(originalMediaSession), 'metadata');
                if (descriptor && descriptor.get) {
                    Object.defineProperty(originalMediaSession, 'metadata', {
                        configurable: true,
                        get: function () {
                            var md = descriptor.get.call(originalMediaSession);
                            if (md) lastPageMetadata = md;
                            return md;
                        }
                    });
                }
            } catch (e) { }
        } catch (e) { }
    })();

    function pageMetadata() {
        var md = null;
        try { md = (W.navigator.mediaSession && W.navigator.mediaSession.metadata) || null; } catch (e) { }
        if (!md) md = lastPageMetadata;
        return md;
    }

    function artworkFromMetadata(md) {
        if (!md || !md.artwork || !md.artwork.length) return '';
        var best = '';
        var bestSize = -1;
        for (var i = 0; i < md.artwork.length; i++) {
            var art = md.artwork[i];
            if (!art || !art.src) continue;
            var size = 0;
            try {
                size = parseFloat(String(art.sizes || '').split('x')[0]) || 0;
            } catch (e) { }
            if (size > bestSize) {
                bestSize = size;
                best = art.src;
            }
        }
        return best;
    }

    function invokePageHandler(action) {
        var handler = pageHandlers[action];
        if (!handler) return false;
        try {
            handler({ action: action });
            return true;
        } catch (e) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ *
     * 3. Media discovery
     * ------------------------------------------------------------------ */

    var activeMedia = null;

    function collectMedia() {
        var list = [];
        try {
            var nodes = D.querySelectorAll('video,audio');
            for (var i = 0; i < nodes.length; i++) list.push(nodes[i]);
        } catch (e) { }
        try {
            var frames = D.querySelectorAll('iframe');
            for (var f = 0; f < frames.length; f++) {
                try {
                    var fd = frames[f].contentDocument;
                    if (!fd) continue;
                    var inner = fd.querySelectorAll('video,audio');
                    for (var j = 0; j < inner.length; j++) list.push(inner[j]);
                } catch (e2) { /* cross origin */ }
            }
        } catch (e) { }
        return list;
    }

    function durationOf(m) {
        try {
            var d = m.duration;
            if (!isFinite(d) || d <= 0) {
                try {
                    if (m.seekable && m.seekable.length > 0) {
                        var end = m.seekable.end(m.seekable.length - 1);
                        if (isFinite(end) && end > 0) d = end;
                    }
                } catch (e) { }
            }
            return isFinite(d) && d > 0 ? d : 0;
        } catch (e) {
            return 0;
        }
    }

    function scoreOf(m) {
        if (!m) return -1;
        var s = 0;
        try {
            if (!m.paused && !m.ended) s += 1000;
            if (m.currentTime > 0) s += 100;
            if (durationOf(m) > 0) s += 60;
            if (m.tagName === 'VIDEO') s += 20;
            var r = m.getBoundingClientRect();
            var area = (r.width || 0) * (r.height || 0);
            s += Math.min(area / 4000, 200);
            if (m.muted && m.volume === 0) s -= 40;
        } catch (e) { }
        return s;
    }

    function pickMedia() {
        var list = collectMedia();
        if (!list.length) {
            activeMedia = null;
            return null;
        }
        var best = null;
        var bestScore = -1;
        for (var i = 0; i < list.length; i++) {
            var s = scoreOf(list[i]);
            if (s > bestScore) {
                bestScore = s;
                best = list[i];
            }
        }
        // Sticky selection: keep the current element while it is still the loud one.
        if (activeMedia && best && activeMedia !== best) {
            var keep = false;
            try {
                keep = !activeMedia.paused && !activeMedia.ended && scoreOf(activeMedia) >= bestScore - 150;
            } catch (e) { }
            if (!keep) activeMedia = best;
        } else if (best) {
            activeMedia = best;
        }
        return activeMedia;
    }

    /* ------------------------------------------------------------------ *
     * 4. Metadata assembly
     * ------------------------------------------------------------------ */

    function prettyHost() {
        var host = H.replace(/^www\./, '').replace(/^(m|mobile|amp|touch)\./, '');
        if (!host) return '';
        var parts = host.split('.');
        var name = parts[0];
        // Handle two part suffixes such as co.uk / com.au.
        if (parts.length > 2 && /^(co|com|org|net|gov|ac)$/.test(parts[0])) {
            name = parts[1];
        }
        if (!name) return '';
        return name.charAt(0).toUpperCase() + name.slice(1);
    }

    function siteName() {
        if (ADAPTER && ADAPTER.siteName) return ADAPTER.siteName;
        var og = metaContent(['og:site_name', 'application-name', 'apple-mobile-web-app-title']);
        if (og) return og;
        return prettyHost();
    }

    function pageTitle() {
        var t = '';
        try { t = (D.title || '').replace(/\s+/g, ' ').trim(); } catch (e) { }
        if (ADAPTER && ADAPTER.stripTitle) t = ADAPTER.stripTitle(t);
        if (!t) t = metaContent(['og:title', 'twitter:title']);
        return t;
    }

    function buildMeta(m) {
        var md = pageMetadata();
        var title = '';
        var artist = '';
        var album = '';
        var artwork = '';

        if (ADAPTER) {
            title = firstText(ADAPTER.title || []);
            artist = firstText(ADAPTER.artist || []);
            album = firstText(ADAPTER.album || []);
        }

        if (!title && md && md.title) title = String(md.title).trim();
        if (!artist && md && md.artist) artist = String(md.artist).trim();
        if (!album && md && md.album) album = String(md.album).trim();

        if (!title) title = pageTitle();
        if (!artist) artist = metaContent(['og:site_name', 'author', 'twitter:site']);
        if (ADAPTER && ADAPTER.stripTitle && title) title = ADAPTER.stripTitle(title);

        artwork = artworkFromMetadata(md);
        if (!artwork && ADAPTER && ADAPTER.art) {
            try { artwork = ADAPTER.art() || ''; } catch (e) { }
        }
        if (!artwork) artwork = metaContent(['og:image', 'twitter:image', 'twitter:image:src']);
        if (!artwork) {
            try {
                if (m && m.poster) artwork = m.poster;
            } catch (e) { }
        }
        if (!artwork) {
            artwork = imageUrl(['link[rel="apple-touch-icon"]', 'link[rel="icon"]']);
        }
        if (artwork && artwork.indexOf('//') === 0) artwork = 'https:' + artwork;

        if (artist === title) artist = '';

        return {
            title: title || '',
            artist: artist || '',
            album: album || '',
            artwork: artwork || ''
        };
    }

    function canPrevNext() {
        if (!ADAPTER) return { prev: false, next: false };
        var prev = false;
        var next = false;
        try {
            for (var i = 0; i < (ADAPTER.prev || []).length; i++) {
                if (D.querySelector(ADAPTER.prev[i])) { prev = true; break; }
            }
        } catch (e) { }
        try {
            for (var j = 0; j < (ADAPTER.next || []).length; j++) {
                if (D.querySelector(ADAPTER.next[j])) { next = true; break; }
            }
        } catch (e) { }
        return {
            prev: prev || !!pageHandlers['previoustrack'],
            next: next || !!pageHandlers['nexttrack']
        };
    }

    function snapshot() {
        var m = pickMedia();
        var meta = buildMeta(m);
        var duration = 0;
        var position = 0;
        var playing = false;
        try {
            if (m) {
                duration = durationOf(m);
                position = isFinite(m.currentTime) ? m.currentTime : 0;
                playing = !m.paused && !m.ended;
            }
        } catch (e) { }
        var nav = canPrevNext();
        return {
            v: 1,
            title: meta.title,
            artist: meta.artist,
            album: meta.album,
            artwork: meta.artwork,
            siteName: siteName(),
            host: H,
            pageUrl: (function () { try { return location.href; } catch (e) { return ''; } })(),
            duration: duration,
            position: position,
            playing: playing,
            canPrev: nav.prev,
            canNext: nav.next,
            hasMedia: !!m,
            adapter: ADAPTER ? ADAPTER.id : 'generic',
            bgEnabled: !!CONFIG.bg
        };
    }

    /* ------------------------------------------------------------------ *
     * 5. Native bridge
     * ------------------------------------------------------------------ */

    var lastSent = 0;
    var lastPayload = '';
    var pendingSend = null;

    function send(force) {
        var payload;
        try {
            payload = JSON.stringify(snapshot());
        } catch (e) {
            return;
        }
        var now = Date.now();
        if (!force && payload === lastPayload && now - lastSent < 1500) return;
        if (!force && now - lastSent < 240) {
            if (!pendingSend) {
                pendingSend = W.setTimeout(function () {
                    pendingSend = null;
                    send(true);
                }, 260);
            }
            return;
        }
        lastSent = now;
        lastPayload = payload;
        try {
            if (W.BgpBridge && W.BgpBridge.postMessage) {
                W.BgpBridge.postMessage(payload);
            }
        } catch (e) { }
    }

    /* ------------------------------------------------------------------ *
     * 6. Visibility / focus neutralisation
     * ------------------------------------------------------------------ */

    var realHiddenGetter = null;
    var realVisibilityGetter = null;
    var spooferInstalled = false;

    function installVisibilitySpoofer() {
        if (spooferInstalled) return;
        spooferInstalled = true;
        try {
            var proto = D.constructor && D.constructor.prototype;
            var hiddenDesc = proto ? Object.getOwnPropertyDescriptor(proto, 'hidden') : null;
            if (hiddenDesc && hiddenDesc.get) realHiddenGetter = hiddenDesc.get;
            var visDesc = proto ? Object.getOwnPropertyDescriptor(proto, 'visibilityState') : null;
            if (visDesc && visDesc.get) realVisibilityGetter = visDesc.get;

            if (proto && hiddenDesc && hiddenDesc.get) {
                Object.defineProperty(proto, 'hidden', {
                    configurable: true,
                    get: function () {
                        if (CONFIG.bg) return false;
                        try { return realHiddenGetter.call(this); } catch (e) { return false; }
                    }
                });
            }
            if (proto && visDesc && visDesc.get) {
                Object.defineProperty(proto, 'visibilityState', {
                    configurable: true,
                    get: function () {
                        if (CONFIG.bg) return 'visible';
                        try { return realVisibilityGetter.call(this); } catch (e) { return 'visible'; }
                    }
                });
            }
        } catch (e) { }

        try {
            if ('webkitHidden' in D) {
                Object.defineProperty(D, 'webkitHidden', {
                    configurable: true,
                    get: function () { return CONFIG.bg ? false : D.hidden; }
                });
                Object.defineProperty(D, 'webkitVisibilityState', {
                    configurable: true,
                    get: function () { return CONFIG.bg ? 'visible' : D.visibilityState; }
                });
            }
        } catch (e) { }

        try {
            Object.defineProperty(D, 'hasFocus', {
                configurable: true,
                value: function () { return true; }
            });
        } catch (e) { }

        // Swallow the signals that make players pause themselves.
        var signals = ['visibilitychange', 'webkitvisibilitychange', 'mozvisibilitychange', 'blur'];
        for (var i = 0; i < signals.length; i++) {
            var type = signals[i];
            try {
                W.addEventListener(type, guard, true);
                D.addEventListener(type, guard, true);
            } catch (e) { }
        }
    }

    /**
     * YouTube and YouTube Music show a "tap to resume" / buffering overlay when they
     * think playback was interrupted. Purely cosmetic cleanup, best effort.
     */
    function hideBlockingOverlays() {
        if (!CONFIG.bg) return;
        var selectors = ['.ytp-pause-overlay', 'ytm-notification-action-renderer', '.ytp-autonav-endscreen-upnext-container'];
        for (var i = 0; i < selectors.length; i++) {
            try {
                var nodes = D.querySelectorAll(selectors[i]);
                for (var j = 0; j < nodes.length; j++) {
                    if (nodes[j].style) nodes[j].style.display = 'none';
                }
            } catch (e) { }
        }
    }

    function guard(ev) {
        if (CONFIG.bg) {
            try { ev.stopImmediatePropagation(); } catch (e) { }
        }
    }

    /* ------------------------------------------------------------------ *
     * 7. Playback supervision
     * ------------------------------------------------------------------ */

    var wantPlaying = false;
    var lastResumeAttempt = 0;

    function resumeIfNeeded() {
        if (!CONFIG.bg) return;
        var m = pickMedia();
        if (!m) return;
        var now = Date.now();
        if (now - lastResumeAttempt < 1200) return;
        lastResumeAttempt = now;
        try {
            if (m.paused && wantPlaying) {
                var p = m.play();
                if (p && p.catch) p.catch(function () { /* autoplay guard */ });
            }
        } catch (e) { }
    }

    function attachMediaListeners() {
        var list = collectMedia();
        for (var i = 0; i < list.length; i++) {
            (function (m) {
                if (m.__bgpBound) return;
                m.__bgpBound = true;
                var onPlay = function () {
                    wantPlaying = true;
                    activeMedia = m;
                    send(true);
                };
                var onPause = function () {
                    if (!m.ended) {
                        // A pause that we did not ask for while the page should keep
                        // playing means the platform interrupted us: resume shortly.
                        if (CONFIG.bg && wantPlaying) {
                            W.setTimeout(resumeIfNeeded, 900);
                        }
                    }
                    send(true);
                };
                var onTime = function () { send(false); };
                var onEnded = function () {
                    wantPlaying = false;
                    send(true);
                    if (CONFIG.bg && CONFIG.autoplay) {
                        W.setTimeout(function () {
                            var still = pickMedia();
                            if (still && !still.paused) return;
                            if (invokePageHandler('nexttrack')) return;
                            clickControl('next');
                        }, 1200);
                    }
                };
                try {
                    m.addEventListener('play', onPlay, true);
                    m.addEventListener('playing', onPlay, true);
                    m.addEventListener('pause', onPause, true);
                    m.addEventListener('ended', onEnded, true);
                    m.addEventListener('timeupdate', onTime, true);
                    m.addEventListener('loadedmetadata', function () { send(true); }, true);
                    m.addEventListener('durationchange', function () { send(true); }, true);
                } catch (e) { }
            })(list[i]);
        }
    }

    function clickControl(kind) {
        if (!ADAPTER) return false;
        var selectors = kind === 'next' ? (ADAPTER.next || []) : (ADAPTER.prev || []);
        for (var i = 0; i < selectors.length; i++) {
            try {
                var el = D.querySelector(selectors[i]);
                if (el) {
                    el.click();
                    return true;
                }
            } catch (e) { }
        }
        return false;
    }

    function clickPlay() {
        if (!ADAPTER) return false;
        var selectors = ADAPTER.play || [];
        for (var i = 0; i < selectors.length; i++) {
            try {
                var el = D.querySelector(selectors[i]);
                if (el) {
                    el.click();
                    return true;
                }
            } catch (e) { }
        }
        return false;
    }

    function seekTo(seconds) {
        var m = pickMedia();
        if (!m) return;
        try {
            if (m.seekable && m.seekable.length > 0) {
                var start = m.seekable.start(0);
                var end = m.seekable.end(m.seekable.length - 1);
                if (seconds < start) seconds = start;
                if (seconds > end) seconds = end;
            }
            m.currentTime = seconds;
        } catch (e) { }
        if (pageHandlers['seekto']) {
            try { pageHandlers['seekto']({ action: 'seekto', seekTime: seconds, fastSeek: false }); } catch (e) { }
        }
        send(true);
    }

    /* ------------------------------------------------------------------ *
     * 8. Public API used by the native side
     * ------------------------------------------------------------------ */

    function act(action, value) {
        var m = pickMedia();
        try {
            switch (action) {
                case 'play':
                    wantPlaying = true;
                    if (m && m.paused) {
                        var p = m.play();
                        if (p && p.catch) p.catch(function () { });
                    }
                    if (!m || m.paused) invokePageHandler('play');
                    if ((!m || m.paused) && CONFIG.appHidden) clickPlay();
                    break;
                case 'pause':
                    wantPlaying = false;
                    try { if (m && !m.paused) m.pause(); } catch (e) { }
                    invokePageHandler('pause');
                    break;
                case 'toggle':
                    if (m && !m.paused && !m.ended) {
                        act('pause');
                    } else {
                        act('play');
                    }
                    break;
                case 'next':
                    if (typeof value === 'number') {
                        // "next" inside a playlist of the generic player
                        seekTo(m ? (m.currentTime || 0) + value : 0);
                        break;
                    }
                    if (!invokePageHandler('nexttrack')) clickControl('next');
                    wantPlaying = true;
                    break;
                case 'prev':
                    if (!invokePageHandler('previoustrack')) clickControl('prev');
                    wantPlaying = true;
                    break;
                case 'seek':
                    seekTo(Number(value) || 0);
                    break;
                case 'mute':
                    try { if (m) m.muted = true; } catch (e) { }
                    break;
                case 'unmute':
                    try { if (m) m.muted = false; } catch (e) { }
                    break;
                case 'stop':
                    wantPlaying = false;
                    try { if (m) { m.pause(); m.currentTime = 0; } } catch (e) { }
                    invokePageHandler('pause');
                    break;
                case 'rate':
                    try { if (m) m.playbackRate = Number(value) || 1; } catch (e) { }
                    break;
                case 'report':
                    send(true);
                    return;
            }
        } catch (e) { }
        send(true);
    }

    function configure(newConfig) {
        if (!newConfig) return;
        var bgChanged = !CONFIG || CONFIG.bg !== newConfig.bg;
        CONFIG = newConfig;
        if (bgChanged) {
            // Late players (YouTube Music, SPAs) may have replaced the media element:
            // re-attach the listeners and the title watcher.
            attachMediaListeners();
            observeTitle();
            send(true);
        }
        if (!CONFIG.bg) {
            // Leaving background mode: if we are hidden the page may legitimately pause.
            wantPlaying = !!(activeMedia && !activeMedia.paused);
        }
        attachMediaListeners();
        send(true);
    }

    installVisibilitySpoofer();

    /* ------------------------------------------------------------------ *
     * 9. Boot
     * ------------------------------------------------------------------ */

    attachMediaListeners();

    try {
        W.setInterval(function () {
            attachMediaListeners();
            send(false);
        }, 1000);
        W.setInterval(hideBlockingOverlays, 5000);
    } catch (e) { }

    try {
        D.addEventListener('play', function () { W.setTimeout(attachMediaListeners, 60); }, true);
        D.addEventListener('loadedmetadata', function () { send(true); }, true);
    } catch (e) { }

    var titleObserver = null;
    function observeTitle() {
        try {
            if (!W.MutationObserver) return;
            if (titleObserver) return;
            var titleEl = D.querySelector('title');
            if (!titleEl) return;
            titleObserver = new W.MutationObserver(function () { send(false); });
            titleObserver.observe(titleEl, { childList: true });
        } catch (e) { }
    }
    observeTitle();

    try {
        W.addEventListener('popstate', function () { W.setTimeout(function () { send(true); }, 700); });
        W.addEventListener('hashchange', function () { W.setTimeout(function () { send(true); }, 700); });
    } catch (e) { }

    // Some players only start after the first user gesture; keep the intent in sync.
    try {
        D.addEventListener('click', function (ev) {
            var t = ev.target;
            for (var i = 0; i < 4 && t; i++) {
                try {
                    if (t.tagName === 'VIDEO' || t.tagName === 'AUDIO') {
                        W.setTimeout(function () { send(true); }, 400);
                        break;
                    }
                } catch (e) { }
                t = t.parentNode;
            }
        }, true);
    } catch (e) { }

    W.__bgp = {
        alive: true,
        act: act,
        configure: configure,
        snapshot: snapshot,
        version: 1
    };

    send(true);
})();
