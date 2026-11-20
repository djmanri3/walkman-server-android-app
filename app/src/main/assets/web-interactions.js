(function () {
    if (window.__wmWebInteractions) return;
    window.__wmWebInteractions = true;

    var lastTheme;
    function syncTheme() {
        var player = document.getElementById('view-playing');
        var value = (document.body && document.body.dataset.theme) || (player && player.dataset.theme) || 'modern';
        var theme = String(value).toLowerCase();
        if (!['modern', 'classic', 'z1', 'music', 'ericsony', 'walkmanhires'].includes(theme)) theme = 'modern';
        if (theme === lastTheme || !window.AndroidBridge || !AndroidBridge.setWebTheme) return;
        lastTheme = theme;
        AndroidBridge.setWebTheme(theme);
    }
    new MutationObserver(syncTheme).observe(document.documentElement, {
        subtree: true, attributes: true, attributeFilter: ['data-theme']
    });
    syncTheme();

    var style = document.createElement('style');
    style.textContent = '.list-item,.queue-item,.music-library-card{-webkit-touch-callout:none;-webkit-user-select:none;user-select:none}';
    document.head.appendChild(style);

    var pending = null, blocked = null;
    function rowFor(target) {
        return target instanceof Element ? target.closest('.list-item,.queue-item,.music-library-card') : null;
    }
    function menuFor(row) {
        return row && row.querySelector('.list-item-more,.music-library-card-more');
    }
    function cancel() {
        if (pending) clearTimeout(pending.timer);
        pending = null;
    }
    function open() {
        if (!pending || pending.fired || !pending.row.isConnected || !pending.menu.isConnected) return;
        clearTimeout(pending.timer);
        pending.fired = true;
        blocked = {row: pending.row, x: pending.x, y: pending.y, until: Date.now() + 1000};
        pending.menu.dispatchEvent(new MouseEvent('click', {
            bubbles: true, cancelable: true, view: window, clientX: pending.x, clientY: pending.y
        }));
    }
    function start(target, x, y, id) {
        cancel();
        blocked = null;
        var row = rowFor(target), menu = menuFor(row);
        if (!menu || menu.disabled) return;
        var control = target.closest('button,a,input,textarea,select,[contenteditable="true"]');
        // The Music theme uses a button for the entire song card.
        if (control && !control.classList.contains('music-library-track')) return;
        pending = {row: row, menu: menu, x: x, y: y, id: id, fired: false};
        pending.timer = setTimeout(open, 550);
    }
    function move(x, y, id) {
        if (!pending || pending.id !== id) return;
        if (Math.hypot(x - pending.x, y - pending.y) > 12) cancel();
    }
    function end(event, id) {
        if (!pending || pending.id !== id) return;
        if (pending.fired) {
            if (event.cancelable) event.preventDefault();
            event.stopImmediatePropagation();
        }
        cancel();
    }
    if (window.PointerEvent) {
        document.addEventListener('pointerdown', function (event) {
            if (!event.isPrimary || event.button !== 0) { cancel(); return; }
            start(event.target, event.clientX, event.clientY, event.pointerId);
        }, true);
        document.addEventListener('pointermove', function (event) {
            move(event.clientX, event.clientY, event.pointerId);
        }, true);
        document.addEventListener('pointerup', function (event) { end(event, event.pointerId); }, true);
        document.addEventListener('pointercancel', cancel, true);
    } else {
        document.addEventListener('touchstart', function (event) {
            if (event.touches.length !== 1) { cancel(); return; }
            var touch = event.touches[0];
            start(event.target, touch.clientX, touch.clientY, touch.identifier);
        }, {capture: true, passive: true});
        document.addEventListener('touchmove', function (event) {
            if (event.touches.length !== 1) { cancel(); return; }
            var touch = event.touches[0];
            move(touch.clientX, touch.clientY, touch.identifier);
        }, {capture: true, passive: true});
        document.addEventListener('touchend', function (event) {
            if (event.changedTouches.length) end(event, event.changedTouches[0].identifier);
        }, {capture: true, passive: false});
        document.addEventListener('touchcancel', cancel, true);
    }
    document.addEventListener('click', function (event) {
        if (!blocked || Date.now() > blocked.until || event.detail === 0) return;
        if (blocked.row.contains(event.target) || Math.hypot(event.clientX - blocked.x, event.clientY - blocked.y) < 24) {
            event.preventDefault();
            event.stopImmediatePropagation();
            blocked = null;
        }
    }, true);
    document.addEventListener('contextmenu', function (event) {
        var row = rowFor(event.target), menu = menuFor(row);
        if (!menu) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        if (blocked && blocked.row === row && Date.now() <= blocked.until) return;
        if (pending && pending.row === row) open();
        else menu.dispatchEvent(new MouseEvent('click', {
            bubbles: true, cancelable: true, view: window, clientX: event.clientX, clientY: event.clientY
        }));
    }, true);
    document.addEventListener('scroll', cancel, {capture: true, passive: true});
    window.addEventListener('blur', cancel);
    document.addEventListener('visibilitychange', function () { if (document.hidden) cancel(); });
})();
