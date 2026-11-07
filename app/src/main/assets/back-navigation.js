(function () {
    function visible(element) {
        return !!element && element.getClientRects().length > 0 &&
            getComputedStyle(element).visibility !== 'hidden';
    }
    function close(selector, action) {
        if (!visible(document.querySelector(selector)) || typeof window[action] !== 'function') {
            return false;
        }
        window[action]();
        return true;
    }
    try {
        // Cerrar primero las capas superiores, sin navegar bajo un diálogo.
        var panels = [
            ['#playlist-select-overlay.open', 'closePlaylistSelectMenu'],
            ['#context-menu-overlay.open', 'closeContextMenu'],
            ['#album-actions-overlay.open', 'closeAlbumActionsMenu'],
            ['#github-popup-overlay.open', 'closeGithubPopup'],
            ['#emby-modal', 'closeServerModal'],
            ['#library-overlay.open', 'closeLibraryPicker']
        ];
        for (var i = 0; i < panels.length; i++) {
            if (close(panels[i][0], panels[i][1])) return true;
        }
        var section = document.querySelector('.settings-submenu.open');
        if (visible(section) && typeof window.closeSettingsSection === 'function') {
            window.closeSettingsSection(section.id.replace('settings-submenu-', ''));
            return true;
        }
        panels = [
            ['#settings-overlay.open', 'closeSettingsMenu'],
            ['#eq-overlay.open', 'closeEqualizer'],
            ['#search-overlay.open', 'toggleSearchOverlay'],
            ['#queue-drawer.open', 'toggleQueueDrawer'],
            ['#view-lyrics.active', 'toggleLyricsView'],
            ['#visualizer-overlay.open', 'toggleVisualizerView']
        ];
        for (var j = 0; j < panels.length; j++) {
            if (close(panels[j][0], panels[j][1])) return true;
        }
        // Los botones de la web conservan su propio historial de álbumes,
        // artistas, playlists y favoritos; no reconstruirlo desde Android.
        var backButtons = document.querySelectorAll('.back-btn');
        for (var k = 0; k < backButtons.length; k++) {
            if (visible(backButtons[k])) {
                backButtons[k].click();
                return true;
            }
        }
        if (visible(document.querySelector('#view-playing.active')) &&
                typeof window.switchTab === 'function') {
            window.switchTab('music');
            return true;
        }
        // Desde el menú principal de My Music, volver al reproductor.
        if (visible(document.querySelector('#view-music.active'))) {
            if (typeof window.switchTab === 'function') window.switchTab('playing');
            return true;
        }
        return false;
    } catch (error) {
        return false;
    }
})()
