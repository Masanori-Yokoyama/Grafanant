/**
 * AETHER CLOUD - MULTI-TENANT PORTAL APPLICATION JAVASCRIPT
 */

(function () {
    'use strict';

    // Application State
    const state = {
        auth: null,      // { token, tenantId, username, displayName, role }
        items: [],       // Array of ItemResponseDto
        filterQuery: '',
        activeTab: 'itemsTab'
    };

    // DOM Elements Cache
    const el = {
        // Views
        loginView: document.getElementById('loginView'),
        dashboardView: document.getElementById('dashboardView'),
        
        // Login View
        loginForm: document.getElementById('loginForm'),
        tenantIdInput: document.getElementById('tenantIdInput'),
        usernameInput: document.getElementById('usernameInput'),
        passwordInput: document.getElementById('passwordInput'),
        togglePasswordBtn: document.getElementById('togglePasswordBtn'),
        loginSubmitBtn: document.getElementById('loginSubmitBtn'),
        loginSpinner: document.getElementById('loginSpinner'),
        loginAlert: document.getElementById('loginAlert'),
        loginAlertMessage: document.getElementById('loginAlertMessage'),
        demoButtons: document.querySelectorAll('.demo-btn'),
        tenantChips: document.querySelectorAll('.tenant-chip'),

        // Dashboard View
        activeTenantBadge: document.getElementById('activeTenantBadge'),
        userDisplayName: document.getElementById('userDisplayName'),
        userRoleBadge: document.getElementById('userRoleBadge'),
        userAvatar: document.getElementById('userAvatar'),
        logoutBtn: document.getElementById('logoutBtn'),
        dashboardWelcomeTitle: document.getElementById('dashboardWelcomeTitle'),
        currentTenantNameText: document.getElementById('currentTenantNameText'),
        itemsCountBadge: document.getElementById('itemsCountBadge'),
        roleSummaryText: document.getElementById('roleSummaryText'),
        itemSearchInput: document.getElementById('itemSearchInput'),
        refreshItemsBtn: document.getElementById('refreshItemsBtn'),
        itemsGrid: document.getElementById('itemsGrid'),
        emptyStateView: document.getElementById('emptyStateView'),
        emptyStateAddBtn: document.getElementById('emptyStateAddBtn'),
        openCreateItemModalBtn: document.getElementById('openCreateItemModalBtn'),
        testSwitchBtns: document.querySelectorAll('.test-switch-btn'),

        // Dashboard Tabs & Grafana
        tabItemsBtn: document.getElementById('tabItemsBtn'),
        tabGrafanaBtn: document.getElementById('tabGrafanaBtn'),
        itemsTab: document.getElementById('itemsTab'),
        grafanaTab: document.getElementById('grafanaTab'),
        grafanaTenantDisplayBadge: document.getElementById('grafanaTenantDisplayBadge'),
        reloadGrafanaBtn: document.getElementById('reloadGrafanaBtn'),
        openExternalGrafanaBtn: document.getElementById('openExternalGrafanaBtn'),
        grafanaIframe: document.getElementById('grafanaIframe'),
        grafanaLoading: document.getElementById('grafanaLoading'),

        // Modal
        createItemModal: document.getElementById('createItemModal'),
        createItemForm: document.getElementById('createItemForm'),
        modalTenantDisplay: document.getElementById('modalTenantDisplay'),
        newItemNameInput: document.getElementById('newItemNameInput'),
        newItemDescInput: document.getElementById('newItemDescInput'),
        cancelCreateBtn: document.getElementById('cancelCreateBtn'),
        closeModalBtn: document.getElementById('closeModalBtn'),
        submitCreateBtn: document.getElementById('submitCreateBtn'),
        saveSpinner: document.getElementById('saveSpinner'),

        // Toast Container
        toastContainer: document.getElementById('toastContainer')
    };

    /* ==========================================================================
       STORAGE & SESSION MANAGEMENT
       ========================================================================== */
    const STORAGE_KEY = 'aether_tenant_session';

    function loadSession() {
        try {
            const raw = sessionStorage.getItem(STORAGE_KEY);
            if (raw) {
                state.auth = JSON.parse(raw);
                return true;
            }
        } catch (e) {
            console.error('Failed to parse session:', e);
            sessionStorage.removeItem(STORAGE_KEY);
        }
        return false;
    }

    function saveSession(authData) {
        state.auth = authData;
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(authData));
    }

    function clearSession() {
        state.auth = null;
        state.items = [];
        state.activeTab = 'itemsTab';
        if (el.grafanaIframe) {
            el.grafanaIframe.src = 'about:blank';
            el.grafanaIframe.removeAttribute('data-loaded-for');
        }
        sessionStorage.removeItem(STORAGE_KEY);
    }

    /* ==========================================================================
       TOAST NOTIFICATIONS
       ========================================================================== */
    function showToast(message, type = 'info') {
        const toast = document.createElement('div');
        toast.className = `toast toast-${type}`;
        
        let icon = 'ℹ️';
        if (type === 'success') icon = '✅';
        if (type === 'error') icon = '❌';

        toast.innerHTML = `<span>${icon}</span><span>${escapeHtml(message)}</span>`;
        el.toastContainer.appendChild(toast);

        setTimeout(() => {
            toast.style.opacity = '0';
            toast.style.transform = 'translateX(40px)';
            toast.style.transition = 'all 0.3s ease';
            setTimeout(() => toast.remove(), 300);
        }, 3500);
    }

    /* ==========================================================================
       API CLIENT
       ========================================================================== */
    async function apiRequest(url, options = {}) {
        const headers = {
            'Content-Type': 'application/json',
            ...(options.headers || {})
        };

        // Inject active tenant header if authenticated
        if (state.auth && state.auth.tenantId) {
            headers['X-Tenant-ID'] = state.auth.tenantId;
        }

        const config = {
            ...options,
            headers
        };

        const response = await fetch(url, config);
        
        // Parse JSON if available
        let data = null;
        const contentType = response.headers.get('content-type');
        if (contentType && contentType.includes('application/json')) {
            data = await response.json();
        } else {
            data = await response.text();
        }

        if (!response.ok) {
            const errorMsg = (data && data.message) || (data && data.error) || response.statusText || 'リクエストに失敗しました';
            throw new Error(errorMsg);
        }

        return data;
    }

    /* ==========================================================================
       VIEW TRANSITIONS & RENDERING
       ========================================================================== */
    function renderApp() {
        if (state.auth) {
            el.loginView.classList.add('hidden');
            el.dashboardView.classList.remove('hidden');
            updateDashboardHeader();
            switchTab(state.activeTab || 'itemsTab');
            loadItems();
        } else {
            el.dashboardView.classList.add('hidden');
            el.loginView.classList.remove('hidden');
            hideLoginAlert();
        }
    }

    function switchTab(tabName) {
        state.activeTab = tabName;
        if (tabName === 'grafanaTab') {
            el.tabItemsBtn.classList.remove('active');
            el.tabGrafanaBtn.classList.add('active');
            el.itemsTab.classList.add('hidden');
            el.grafanaTab.classList.remove('hidden');
            loadGrafanaDashboard();
        } else {
            el.tabGrafanaBtn.classList.remove('active');
            el.tabItemsBtn.classList.add('active');
            el.grafanaTab.classList.add('hidden');
            el.itemsTab.classList.remove('hidden');
        }
    }

    function loadGrafanaDashboard(forceReload = false) {
        if (!state.auth || !state.auth.tenantId) return;

        const tenantId = state.auth.tenantId;
        if (el.grafanaTenantDisplayBadge) {
            el.grafanaTenantDisplayBadge.textContent = tenantId;
        }

        const dashboardUid = tenantId.toLowerCase();
        const kioskUrl = `/grafana/d/${dashboardUid}?kiosk=tv&theme=dark`;
        const directUrl = `/grafana/d/${dashboardUid}?theme=dark`;

        if (el.openExternalGrafanaBtn) {
            el.openExternalGrafanaBtn.href = directUrl;
        }

        if (el.grafanaIframe) {
            const currentTenant = el.grafanaIframe.getAttribute('data-loaded-for');
            if (forceReload || currentTenant !== tenantId) {
                if (el.grafanaLoading) {
                    el.grafanaLoading.classList.remove('hidden');
                    el.grafanaLoading.style.opacity = '1';
                }
                el.grafanaIframe.setAttribute('data-loaded-for', tenantId);
                el.grafanaIframe.src = kioskUrl;
            }
        }
    }

    function updateDashboardHeader() {
        const auth = state.auth;
        if (!auth) return;

        el.activeTenantBadge.textContent = auth.tenantId;
        el.currentTenantNameText.textContent = auth.tenantId;
        el.userDisplayName.textContent = auth.displayName || auth.username;
        el.userRoleBadge.textContent = auth.role || 'USER';
        el.userAvatar.textContent = (auth.displayName || auth.username || 'U').charAt(0).toUpperCase();
        el.dashboardWelcomeTitle.textContent = `ようこそ、${auth.displayName || auth.username} さん`;
        el.roleSummaryText.textContent = auth.role || 'USER';
        el.modalTenantDisplay.value = auth.tenantId;
    }

    function showLoginAlert(message) {
        el.loginAlertMessage.textContent = message;
        el.loginAlert.classList.remove('hidden');
    }

    function hideLoginAlert() {
        el.loginAlert.classList.add('hidden');
    }

    function setLoginLoading(loading) {
        el.loginSubmitBtn.disabled = loading;
        if (loading) {
            el.loginSubmitBtn.querySelector('.btn-text').classList.add('hidden');
            el.loginSpinner.classList.remove('hidden');
        } else {
            el.loginSubmitBtn.querySelector('.btn-text').classList.remove('hidden');
            el.loginSpinner.classList.add('hidden');
        }
    }

    function setSaveLoading(loading) {
        el.submitCreateBtn.disabled = loading;
        if (loading) {
            el.submitCreateBtn.querySelector('.btn-text').classList.add('hidden');
            el.saveSpinner.classList.remove('hidden');
        } else {
            el.submitCreateBtn.querySelector('.btn-text').classList.remove('hidden');
            el.saveSpinner.classList.add('hidden');
        }
    }

    /* ==========================================================================
       ITEMS CRUD & RENDERING
       ========================================================================== */
    async function loadItems() {
        try {
            el.itemsGrid.innerHTML = '<div style="grid-column: 1/-1; text-align: center; color: var(--text-muted); padding: 2rem;">データ取得中...</div>';
            const items = await apiRequest('/api/v1/items', { method: 'GET' });
            state.items = items || [];
            renderItemsList();
        } catch (err) {
            showToast(err.message, 'error');
            el.itemsGrid.innerHTML = `<div style="grid-column: 1/-1; text-align: center; color: #ef4444; padding: 2rem;">エラー: ${escapeHtml(err.message)}</div>`;
        }
    }

    function renderItemsList() {
        const query = (state.filterQuery || '').toLowerCase().trim();
        const filtered = state.items.filter(item => {
            if (!query) return true;
            return (item.name && item.name.toLowerCase().includes(query)) ||
                   (item.description && item.description.toLowerCase().includes(query));
        });

        el.itemsCountBadge.textContent = state.items.length;

        if (filtered.length === 0) {
            el.itemsGrid.innerHTML = '';
            el.emptyStateView.classList.remove('hidden');
            return;
        }

        el.emptyStateView.classList.add('hidden');
        el.itemsGrid.innerHTML = filtered.map(item => {
            const formattedDate = formatDate(item.createdAt);
            return `
                <article class="item-card">
                    <div>
                        <div class="item-header">
                            <span class="item-id-badge">#${item.id}</span>
                            <span class="item-tenant-chip">${escapeHtml(item.tenantId || state.auth.tenantId)}</span>
                        </div>
                        <h4 class="item-name">${escapeHtml(item.name)}</h4>
                        <p class="item-desc">${item.description ? escapeHtml(item.description) : '<em style="opacity: 0.6">説明はありません</em>'}</p>
                    </div>
                    <div class="item-footer">
                        <span>作成日時: ${formattedDate}</span>
                        <span style="color: var(--success); font-size: 0.7rem;">● 保存済み</span>
                    </div>
                </article>
            `;
        }).join('');
    }

    /* ==========================================================================
       MODAL HANDLING
       ========================================================================== */
    function openCreateModal() {
        if (!state.auth) return;
        el.modalTenantDisplay.value = state.auth.tenantId;
        el.newItemNameInput.value = '';
        el.newItemDescInput.value = '';
        el.createItemModal.classList.remove('hidden');
        setTimeout(() => el.newItemNameInput.focus(), 100);
    }

    function closeCreateModal() {
        el.createItemModal.classList.add('hidden');
    }

    /* ==========================================================================
       EVENT LISTENERS
       ========================================================================== */
    function setupEventListeners() {
        // Toggle Password visibility
        el.togglePasswordBtn.addEventListener('click', () => {
            const isPassword = el.passwordInput.type === 'password';
            el.passwordInput.type = isPassword ? 'text' : 'password';
            el.togglePasswordBtn.textContent = isPassword ? '🔒' : '👁️';
        });

        // Quick tenant chips
        el.tenantChips.forEach(chip => {
            chip.addEventListener('click', () => {
                el.tenantIdInput.value = chip.getAttribute('data-tenant');
                el.usernameInput.focus();
            });
        });

        // Demo quick login buttons
        el.demoButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                el.tenantIdInput.value = btn.getAttribute('data-tenant');
                el.usernameInput.value = btn.getAttribute('data-user');
                el.passwordInput.value = btn.getAttribute('data-pass');
                executeLogin(el.tenantIdInput.value, el.usernameInput.value, el.passwordInput.value);
            });
        });

        // Login Form Submit
        el.loginForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const tenantId = el.tenantIdInput.value.trim();
            const username = el.usernameInput.value.trim();
            const password = el.passwordInput.value;

            if (!tenantId || !username || !password) {
                showLoginAlert('テナントID、ユーザー名、パスワードをすべて入力してください。');
                return;
            }

            executeLogin(tenantId, username, password);
        });

        // Logout Button
        el.logoutBtn.addEventListener('click', async () => {
            try {
                await apiRequest('/api/auth/logout', { method: 'POST' });
            } catch (e) {
                // Ignore logout endpoint error
            }
            clearSession();
            renderApp();
            showToast('ログアウトしました', 'info');
        });

        // Refresh Items Button
        el.refreshItemsBtn.addEventListener('click', () => {
            loadItems();
            showToast('アイテムリストを更新しました', 'info');
        });

        // Search Filter Input
        el.itemSearchInput.addEventListener('input', (e) => {
            state.filterQuery = e.target.value;
            renderItemsList();
        });

        // Modal Open / Close
        el.openCreateItemModalBtn.addEventListener('click', openCreateModal);
        el.emptyStateAddBtn.addEventListener('click', openCreateModal);
        el.closeModalBtn.addEventListener('click', closeCreateModal);
        el.cancelCreateBtn.addEventListener('click', closeCreateModal);
        el.createItemModal.addEventListener('click', (e) => {
            if (e.target === el.createItemModal) {
                closeCreateModal();
            }
        });

        // Create Item Form Submit
        el.createItemForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const name = el.newItemNameInput.value.trim();
            const description = el.newItemDescInput.value.trim();

            if (!name) {
                showToast('アイテム名を入力してください', 'error');
                return;
            }

            setSaveLoading(true);
            try {
                await apiRequest('/api/v1/items', {
                    method: 'POST',
                    body: JSON.stringify({ name, description })
                });
                closeCreateModal();
                showToast(`アイテム「${name}」を登録しました！`, 'success');
                await loadItems();
            } catch (err) {
                showToast(err.message, 'error');
            } finally {
                setSaveLoading(false);
            }
        });

        // Switch Tenant buttons for quick isolation verification
        el.testSwitchBtns.forEach(btn => {
            btn.addEventListener('click', () => {
                const targetTenant = btn.getAttribute('data-switch-tenant');
                clearSession();
                renderApp();
                el.tenantIdInput.value = targetTenant;
                el.usernameInput.value = 'admin';
                el.passwordInput.value = 'password123';
                showToast(`${targetTenant} のログインフォームに切り替えました`, 'info');
            });
        });

        // Tab Navigation
        if (el.tabItemsBtn) {
            el.tabItemsBtn.addEventListener('click', () => switchTab('itemsTab'));
        }
        if (el.tabGrafanaBtn) {
            el.tabGrafanaBtn.addEventListener('click', () => switchTab('grafanaTab'));
        }

        // Grafana Reload Button
        if (el.reloadGrafanaBtn) {
            el.reloadGrafanaBtn.addEventListener('click', () => {
                loadGrafanaDashboard(true);
                showToast('Grafana ダッシュボードを再読み込みしました', 'info');
            });
        }

        // Grafana Iframe Load Event
        if (el.grafanaIframe) {
            el.grafanaIframe.addEventListener('load', () => {
                if (el.grafanaIframe.src && !el.grafanaIframe.src.includes('about:blank')) {
                    setTimeout(() => {
                        if (el.grafanaLoading) {
                            el.grafanaLoading.style.opacity = '0';
                            setTimeout(() => el.grafanaLoading.classList.add('hidden'), 300);
                        }
                    }, 500);
                }
            });
        }
    }

    /* ==========================================================================
       LOGIN EXECUTION
       ========================================================================== */
    async function executeLogin(tenantId, username, password) {
        hideLoginAlert();
        setLoginLoading(true);

        try {
            const result = await apiRequest('/api/auth/login', {
                method: 'POST',
                headers: {
                    'X-Tenant-ID': tenantId
                },
                body: JSON.stringify({ tenantId, username, password })
            });

            saveSession(result);
            showToast(`テナント「${result.tenantId}」にログインしました！`, 'success');
            renderApp();
        } catch (err) {
            showLoginAlert(err.message || '認証に失敗しました。');
            showToast(err.message || 'ログインエラー', 'error');
        } finally {
            setLoginLoading(false);
        }
    }

    /* ==========================================================================
       UTILITY FUNCTIONS
       ========================================================================== */
    function escapeHtml(str) {
        if (!str) return '';
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    function formatDate(dateStr) {
        if (!dateStr) return '-';
        try {
            const d = new Date(dateStr);
            return `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
        } catch (e) {
            return dateStr;
        }
    }

    function pad(n) {
        return n < 10 ? '0' + n : n;
    }

    /* ==========================================================================
       INITIALIZATION
       ========================================================================== */
    function init() {
        setupEventListeners();
        const hasSession = loadSession();
        renderApp();
    }

    // Run on DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
