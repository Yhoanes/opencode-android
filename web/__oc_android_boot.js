(function () {
  "use strict";
  if (window.__OC_ANDROID_BOOTED__) return;
  window.__OC_ANDROID_BOOTED__ = true;

  var bridgeToken = typeof window.__OC_BRIDGE_TOKEN__ === "string" ? window.__OC_BRIDGE_TOKEN__ : "";
  try {
    delete window.__OC_BRIDGE_TOKEN__;
  } catch (e) {
    window.__OC_BRIDGE_TOKEN__ = undefined;
  }

  var host = window.Android || window.AndroidBridge || null;
  if (host && !window.AndroidBridge) {
    window.AndroidBridge = host;
  }

  // Unregister & permanently neutralize any Workbox ServiceWorker so WebView always gets fresh local assets
  try {
    if (navigator.serviceWorker) {
      if (typeof navigator.serviceWorker.getRegistrations === "function") {
        navigator.serviceWorker.getRegistrations().then(function (regs) {
          regs.forEach(function (r) { r.unregister(); });
        }).catch(function () {});
      }
      navigator.serviceWorker.register = function () {
        return Promise.resolve({
          scope: "/",
          active: null,
          installing: null,
          waiting: null,
          unregister: function () { return Promise.resolve(true); },
          update: function () { return Promise.resolve(); },
          addEventListener: function () {},
          removeEventListener: function () {}
        });
      };
    }
    if (window.caches && typeof window.caches.keys === "function") {
      window.caches.keys().then(function (keys) {
        keys.forEach(function (k) { window.caches.delete(k); });
      }).catch(function () {});
    }
  } catch (e) {}

  // Remove any legacy floating widget if cached in DOM
  function cleanupLegacyFloatingNodes() {
    ["oc-android-pill", "oc-android-backdrop", "oc-android-hub-styles", "oc-agy-fab"].forEach(function (id) {
      var n = document.getElementById(id);
      if (n && n.parentNode) n.parentNode.removeChild(n);
    });
  }

  // Exact UTF-8 base64url decoder matching OpenCode's _assets/encode-*.js
  function decodeBase64Url(encoded) {
    try {
      var b64 = encoded.replace(/-/g, "+").replace(/_/g, "/");
      var bin = atob(b64);
      var bytes = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) {
        bytes[i] = bin.charCodeAt(i);
      }
      return new TextDecoder().decode(bytes);
    } catch (e) {
      return "";
    }
  }

  // 1. OpenCode v2.3+ route guard & one-time default workspace registration (/storage/emulated/0/Documents/OpenCode)
  var origReplaceState = history.replaceState;
  var origPushState = history.pushState;

  function isRecognizedOpenCodeV2Route(pathname) {
    var segs = String(pathname || "/").split("/").filter(Boolean);
    if (segs.length === 0) return true;
    var first = segs[0];
    if (first === "settings" || first === "connect" || first === "components" || first === "new-session") {
      return true;
    }
    if (first === "server" && segs.length >= 4 && segs[2] === "session") {
      return true;
    }
    return false;
  }

  function normalizeInvalidRouteBeforeMount() {
    try {
      var curPath = window.location.pathname || "/";
      if (!isRecognizedOpenCodeV2Route(curPath)) {
        origReplaceState.call(history, null, "", "/");
      }
    } catch (e) {}
  }

  function seedDefaultWorkspaceOnce() {
    if (!host || typeof host.getConfigAuth !== "function" || !bridgeToken) return;
    try {
      if (localStorage.getItem("__oc_default_ws_seeded_v3") === "1") {
        return;
      }
      var cfg = JSON.parse(host.getConfigAuth(bridgeToken) || "{}");
      var filesDir = cfg.home || "/data/user/0/com.oc.ide/files";
      var workspaceDir = cfg.workspaceDir || "/storage/emulated/0/Documents/OpenCode";
      if (!workspaceDir || workspaceDir === filesDir) return;
      localStorage.setItem("__oc_default_ws_seeded_v3", "1");
      fetch("/api/location?location%5Bdirectory%5D=" + encodeURIComponent(workspaceDir), {
        credentials: "include"
      }).catch(function () {});
    } catch (e) {}
  }

  history.replaceState = function () {
    var r = origReplaceState.apply(this, arguments);
    scheduleNativeUiSync();
    return r;
  };
  history.pushState = function () {
    var r = origPushState.apply(this, arguments);
    scheduleNativeUiSync();
    return r;
  };
  window.addEventListener("popstate", function () {
    normalizeInvalidRouteBeforeMount();
    scheduleNativeUiSync();
  });

  normalizeInvalidRouteBeforeMount();
  seedDefaultWorkspaceOnce();

  // 2. Seed initial host theme before SolidJS mounts if user hasn't explicitly chosen one
  try {
    var existingScheme = localStorage.getItem("opencode-color-scheme");
    var hostHint = window.__OC_HOST_THEME__;
    if (!existingScheme && (hostHint === "dark" || hostHint === "light")) {
      localStorage.setItem("opencode-color-scheme", hostHint);
      document.documentElement.dataset.colorScheme = hostHint;
      document.documentElement.style.backgroundColor = hostHint === "dark" ? "#080808" : "#fafafa";
    }
  } catch (e) {}

  // 3. Dismiss native splash once OpenCode's SolidJS #root mounts (cancels fallback timer cleanly)
  var uiSignaled = false;
  var safetyReadyTimer = null;
  function signalUiReady() {
    if (uiSignaled) return;
    uiSignaled = true;
    if (safetyReadyTimer !== null) {
      clearTimeout(safetyReadyTimer);
      safetyReadyTimer = null;
    }
    try {
      if (host && typeof host.onUiReady === "function") {
        host.onUiReady();
      }
    } catch (e) {}
  }

  function checkRootReady() {
    var root = document.getElementById("root");
    if (root && root.childNodes && root.childNodes.length > 0) {
      if (safetyReadyTimer !== null) {
        clearTimeout(safetyReadyTimer);
        safetyReadyTimer = null;
      }
      requestAnimationFrame(function () {
        requestAnimationFrame(signalUiReady);
      });
      return true;
    }
    return false;
  }

  // 4. Bi-directional theme sync (OpenCode <-> Android status/nav bars & dynamic launcher icon)
  var lastSyncedVisual = "";
  var lastSyncedPref = "";
  function syncHostTheme() {
    if (!host) return;
    try {
      var visual = document.documentElement.dataset.colorScheme;
      if (visual !== "dark" && visual !== "light") {
        visual = window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
      }
      if (visual !== lastSyncedVisual && typeof host.onVisualThemeChanged === "function") {
        lastSyncedVisual = visual;
        host.onVisualThemeChanged(visual);
      }
      var pref = localStorage.getItem("opencode-color-scheme") || "system";
      if (pref !== lastSyncedPref && typeof host.setTheme === "function") {
        lastSyncedPref = pref;
        host.setTheme(pref);
      }
    } catch (e) {}
  }

  try {
    var themeObs = new MutationObserver(syncHostTheme);
    themeObs.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["data-color-scheme", "data-theme", "class"]
    });
  } catch (e) {}

  // 5. SSE & WebSocket Background Resume Guard (reconnects /event & /pty after Android Doze)
  function onHostResume() {
    try {
      window.dispatchEvent(new Event("online"));
      window.dispatchEvent(new Event("focus"));
      syncHostTheme();
    } catch (e) {}
  }
  window.addEventListener("oc:host-resume", onHostResume);
  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState === "visible") {
      onHostResume();
    }
  });

  // 6. Language helper matching OpenCode's active UI language
  function isSpanishUi() {
    try {
      var docLang = (document.documentElement.lang || "").toLowerCase();
      if (docLang.indexOf("es") === 0) return true;
      if (docLang.indexOf("en") === 0) return false;
      var heading = document.querySelector(
        ".settings-extension-heading, .settings-tab-header h2, .directory-picker [data-slot='dialog-title']"
      );
      if (heading && /extensiones|disponible|proveedores|acerca|proyecto|carpeta/i.test(heading.textContent || "")) {
        return true;
      }
      return (navigator.language || "").toLowerCase().indexOf("es") === 0;
    } catch (e) {
      return true;
    }
  }

  function tr(esText, enText) {
    return isSpanishUi() ? esText : enText;
  }

  // 7. Native OpenCode Integrations (zero foreign floating buttons):
  //    (A) Settings -> Extensions -> Plugins (native .settings-extension-row catalog)
  //    (B) Settings -> About (native .settings-about-link GitHub Releases OTA updater)
  //    (C) DirectoryPickerDialog (.directory-picker-actions -> Storage & Android File Manager)
  var REMOTE_CATALOG_URL =
    "https://raw.githubusercontent.com/Yhoanes/antigravity-android-plugins/main/catalog.json";
  var catalogCache = null;
  var catalogFetching = false;
  var busyPlugins = {};
  var pluginErrors = {};
  var currentSearchQuery = "";
  var latestUpdateState = { type: "idle" };
  var dirPickerCallbacks = {};
  var dirPickerSeq = 0;
  var pluginCallbacks = {};
  var pluginOpSeq = 0;

  function handleNativeUpdaterState(st) {
    if (!st) return;
    if (typeof st === "string") {
      try { st = JSON.parse(st); } catch (e) { return; }
    }
    if (st && typeof st === "object") {
      latestUpdateState = st;
      updateAboutLinkLabel();
    }
  }
  window.__ocOnUpdaterStateChanged = handleNativeUpdaterState;
  window.__agOnUpdaterStateChanged = handleNativeUpdaterState;
  window.__onNativeUpdaterState = handleNativeUpdaterState;

  window.__ocOnDirectoryPicked = function (cbId, chosenPath) {
    var fn = cbId && dirPickerCallbacks[cbId];
    if (cbId) delete dirPickerCallbacks[cbId];
    if (typeof fn === "function") {
      fn(chosenPath || "");
    }
  };

  window.__ocOnPluginOpDone = function (cbId, rawJson) {
    var fn = cbId && pluginCallbacks[cbId];
    if (cbId) delete pluginCallbacks[cbId];
    if (typeof fn === "function") {
      var parsed = {};
      try {
        parsed = typeof rawJson === "string" ? JSON.parse(rawJson || "{}") : (rawJson || {});
      } catch (e) {
        parsed = { ok: false, error: String(rawJson || e.message) };
      }
      fn(parsed);
    }
  };

  function parseInstalledMap() {
    if (!host || typeof host.listAndroidPluginsAuth !== "function" || !bridgeToken) return {};
    try {
      return JSON.parse(host.listAndroidPluginsAuth(bridgeToken) || "{}") || {};
    } catch (e) {
      return {};
    }
  }

  function loadCatalog(cb) {
    if (catalogCache) {
      cb(catalogCache);
      return;
    }
    if (host && typeof host.getPluginsCatalogJson === "function") {
      try {
        var rawCat = host.getPluginsCatalogJson();
        var parsedCat = JSON.parse(rawCat || "{}");
        if (parsedCat && Array.isArray(parsedCat.plugins) && parsedCat.plugins.length > 0) {
          catalogCache = parsedCat.plugins;
          cb(catalogCache);
          refreshRemoteCatalogInBackground(catalogCache);
          return;
        }
      } catch (e) {}
    }
    if (catalogFetching) return;
    catalogFetching = true;

    fetch("/__oc_plugins_catalog.json", { cache: "no-store" })
      .then(function (r) { return r.json(); })
      .then(function (localData) {
        var list = (localData && localData.plugins) || [];
        catalogCache = list;
        catalogFetching = false;
        cb(list);
        refreshRemoteCatalogInBackground(list);
      })
      .catch(function () {
        catalogFetching = false;
        cb([]);
      });
  }

  function isAllowedPluginArchiveUrl(url) {
    if (typeof url !== "string") return false;
    return (
      url.indexOf("https://raw.githubusercontent.com/Yhoanes/") === 0 ||
      url.indexOf("https://github.com/Yhoanes/") === 0
    );
  }

  var remoteRefreshed = false;
  function refreshRemoteCatalogInBackground(list) {
    if (remoteRefreshed) return;
    remoteRefreshed = true;
    fetch(REMOTE_CATALOG_URL, { cache: "no-store" })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (remote) {
        if (!remote || !remote.search || !Array.isArray(remote.search.customizations)) return;
        var byName = {};
        list.forEach(function (p) { byName[p.name] = p; });
        remote.search.customizations.forEach(function (c) {
          if (!c || !c.id) return;
          var slug = String(c.id).split("/").pop();
          var url = c.source && c.source.archive && c.source.archive.url;
          if (!slug || !isAllowedPluginArchiveUrl(url)) return;
          if (!byName[slug]) {
            var item = {
              name: slug,
              displayName: c.displayName || slug,
              version: c.version || "1.0.0",
              description: c.description || "",
              descriptionEs: c.description || "",
              archiveUrl: url
            };
            list.push(item);
            byName[slug] = item;
          } else {
            byName[slug].archiveUrl = url;
            if (c.version) byName[slug].version = c.version;
          }
        });
        catalogCache = list;
        renderPluginsCatalogIfMounted(true);
      })
      .catch(function () {});
  }

  // Trigger OpenCode's in-memory /api/location/reload so MCP & Skills tabs refresh immediately over SSE
  function triggerOpenCodeHotReload() {
    try {
      fetch("/api/location/reload", { method: "POST", credentials: "include" }).catch(function () {});
      var seg = (window.location.pathname || "").split("/").filter(Boolean)[0];
      if (seg && seg !== "settings" && seg !== "session") {
        var decoded = decodeBase64Url(seg);
        if (decoded && decoded.charAt(0) === "/") {
          fetch("/api/location/reload?directory=" + encodeURIComponent(decoded), {
            method: "POST",
            credentials: "include"
          }).catch(function () {});
        }
      }
    } catch (e) {}
  }

  function createCubeIconSvg() {
    var ns = "http://www.w3.org/2000/svg";
    var svg = document.createElementNS(ns, "svg");
    svg.setAttribute("data-slot", "icon-svg");
    svg.setAttribute("width", "16");
    svg.setAttribute("height", "16");
    svg.setAttribute("viewBox", "0 0 20 20");
    svg.setAttribute("fill", "none");
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("class", "text-v2-icon-icon-muted shrink-0");

    var p1 = document.createElementNS(ns, "path");
    p1.setAttribute("d", "M10 2.5L16.5 6.25V13.75L10 17.5L3.5 13.75V6.25L10 2.5Z");
    p1.setAttribute("stroke", "currentColor");
    var p2 = document.createElementNS(ns, "path");
    p2.setAttribute("d", "M10 10L16.5 6.25M10 10V17.5M10 10L3.5 6.25");
    p2.setAttribute("stroke", "currentColor");

    svg.appendChild(p1);
    svg.appendChild(p2);
    return svg;
  }

  function createNativeButton(label, variant, size, disabled, onClick) {
    var btn = document.createElement("button");
    btn.type = "button";
    btn.setAttribute("data-component", "button-v2");
    btn.setAttribute("data-size", size || "small");
    btn.setAttribute("data-variant", variant);
    if (disabled) {
      btn.disabled = true;
      btn.setAttribute("aria-busy", "true");
    }
    var span = document.createElement("span");
    span.textContent = label;
    btn.appendChild(span);
    btn.addEventListener("click", onClick);
    return btn;
  }

  function createNativeSwitch(checked, disabled, ariaLabel, onToggle) {
    var root = document.createElement("div");
    root.setAttribute("data-component", "switch");
    root.setAttribute("role", "group");
    if (checked) root.setAttribute("data-checked", "");
    if (disabled) root.setAttribute("data-disabled", "");

    var input = document.createElement("input");
    input.setAttribute("data-slot", "switch-input");
    input.type = "checkbox";
    input.setAttribute("role", "switch");
    input.checked = !!checked;
    input.disabled = !!disabled;
    input.setAttribute("aria-checked", checked ? "true" : "false");
    input.setAttribute("aria-label", ariaLabel);

    var control = document.createElement("div");
    control.setAttribute("data-slot", "switch-control");
    var thumb = document.createElement("div");
    thumb.setAttribute("data-slot", "switch-thumb");
    control.appendChild(thumb);

    root.appendChild(input);
    root.appendChild(control);

    if (!disabled) {
      root.style.cursor = "pointer";
      root.addEventListener("click", function (ev) {
        ev.preventDefault();
        ev.stopPropagation();
        onToggle(!checked);
      });
    }
    return root;
  }

  function handleInstallPlugin(p) {
    if (!host || !bridgeToken || typeof host.installMarketplacePluginAsync !== "function" || busyPlugins[p.name]) {
      return;
    }
    busyPlugins[p.name] = "installing";
    delete pluginErrors[p.name];
    renderPluginsCatalogIfMounted(true);

    var cbId = "pl_inst_" + (++pluginOpSeq) + "_" + Date.now();
    pluginCallbacks[cbId] = function (res) {
      delete busyPlugins[p.name];
      if (!res || !res.ok) {
        pluginErrors[p.name] = (res && res.error) || tr("Error al instalar el plugin", "Plugin installation failed");
      } else {
        triggerOpenCodeHotReload();
      }
      renderPluginsCatalogIfMounted(true);
    };
    try {
      host.installMarketplacePluginAsync(
        bridgeToken,
        cbId,
        p.name,
        p.archiveUrl || "",
        "antigravity-plugins-official/plugin/" + p.name,
        "antigravity-plugins-official",
        p.version || "1.0.0"
      );
    } catch (e) {
      delete pluginCallbacks[cbId];
      delete busyPlugins[p.name];
      pluginErrors[p.name] = e.message || String(e);
      renderPluginsCatalogIfMounted(true);
    }
  }

  function handleTogglePlugin(p, nextEnabled) {
    if (!host || !bridgeToken || typeof host.toggleAndroidPluginAsync !== "function" || busyPlugins[p.name]) {
      return;
    }
    busyPlugins[p.name] = "toggling";
    delete pluginErrors[p.name];
    renderPluginsCatalogIfMounted(true);

    var cbId = "pl_tog_" + (++pluginOpSeq) + "_" + Date.now();
    pluginCallbacks[cbId] = function (res) {
      delete busyPlugins[p.name];
      if (res && res.ok === false && res.error) {
        pluginErrors[p.name] = res.error;
      } else {
        triggerOpenCodeHotReload();
      }
      renderPluginsCatalogIfMounted(true);
    };
    try {
      host.toggleAndroidPluginAsync(bridgeToken, cbId, p.name, nextEnabled);
    } catch (e) {
      delete pluginCallbacks[cbId];
      delete busyPlugins[p.name];
      pluginErrors[p.name] = e.message || String(e);
      renderPluginsCatalogIfMounted(true);
    }
  }

  function handleUninstallPlugin(p) {
    if (!host || !bridgeToken || typeof host.uninstallMarketplacePluginAsync !== "function" || busyPlugins[p.name]) {
      return;
    }
    busyPlugins[p.name] = "removing";
    delete pluginErrors[p.name];
    renderPluginsCatalogIfMounted(true);

    var cbId = "pl_rm_" + (++pluginOpSeq) + "_" + Date.now();
    pluginCallbacks[cbId] = function (res) {
      delete busyPlugins[p.name];
      if (res && res.ok === false && res.error) {
        pluginErrors[p.name] = res.error;
      } else {
        triggerOpenCodeHotReload();
      }
      renderPluginsCatalogIfMounted(true);
    };
    try {
      host.uninstallMarketplacePluginAsync(bridgeToken, cbId, p.name);
    } catch (e) {
      delete pluginCallbacks[cbId];
      delete busyPlugins[p.name];
      pluginErrors[p.name] = e.message || String(e);
      renderPluginsCatalogIfMounted(true);
    }
  }

  function findPluginsSectionElement() {
    var tabs = document.querySelector(".settings-extensions-tabs");
    if (!tabs) return null;

    var pluginsPanel = tabs.querySelector('[data-slot="tabs-v2-content"][id$="-content-plugins"]');
    if (!pluginsPanel) {
      var triggers = tabs.querySelectorAll('[data-slot="tabs-v2-trigger"]');
      if (triggers.length >= 2) {
        var ctrlId = triggers[1].getAttribute("aria-controls");
        if (ctrlId) {
          pluginsPanel = document.getElementById(ctrlId);
        }
      }
    }
    if (!pluginsPanel || pluginsPanel.hasAttribute("hidden")) {
      return null;
    }
    return pluginsPanel.querySelector(".settings-section") || pluginsPanel;
  }

  // Bind input listener ONLY to Settings search input (never global document input — fixes OC-05)
  function findAndBindSettingsSearchInput() {
    var scope = document.querySelector(".settings-extensions-tabs")
      ? (document.querySelector(".settings-extensions-tabs").parentElement || document)
      : null;
    if (!scope) return null;
    var inp = scope.querySelector(
      '.settings-search input, input[type="search"], input[placeholder*="Buscar"], input[placeholder*="Search"]'
    );
    if (inp && !inp.hasAttribute("data-oc-search-bound")) {
      inp.setAttribute("data-oc-search-bound", "1");
      inp.addEventListener("input", function () {
        renderPluginsCatalogIfMounted(false);
      });
    }
    return inp;
  }

  function readSettingsSearchQuery() {
    var inp = findAndBindSettingsSearchInput();
    return inp && inp.value ? String(inp.value).trim().toLowerCase() : "";
  }

  function renderPluginsCatalogIfMounted(forceRebuild) {
    var section = findPluginsSectionElement();
    if (!section) return;

    var container = section.querySelector('[data-oc-native-plugins="1"]');
    if (!container) {
      container = document.createElement("div");
      container.setAttribute("data-component", "settings-list");
      container.setAttribute("data-variant", "catalog");
      container.setAttribute("data-oc-native-plugins", "1");
      section.appendChild(container);
      forceRebuild = true;
    }

    var q = readSettingsSearchQuery();
    if (q !== currentSearchQuery) {
      currentSearchQuery = q;
      forceRebuild = true;
    }
    var sig = q + "|" + Object.keys(busyPlugins).join(",") + "|" + Object.keys(pluginErrors).join(",");
    if (!forceRebuild && container.getAttribute("data-rendered-sig") === sig) {
      return;
    }
    container.setAttribute("data-rendered-sig", sig);

    loadCatalog(function (plugins) {
      var installedMap = parseInstalledMap();
      var es = isSpanishUi();
      var filtered = plugins.filter(function (p) {
        if (!currentSearchQuery) return true;
        var hay = (
          (p.name || "") + " " +
          (p.displayName || "") + " " +
          (p.description || "") + " " +
          (p.descriptionEs || "")
        ).toLowerCase();
        return hay.indexOf(currentSearchQuery) !== -1;
      });

      container.innerHTML = "";
      container.style.display = filtered.length === 0 ? "none" : "";

      filtered.forEach(function (p) {
        var st = installedMap[p.name];
        var isInstalled = !!(st && st.installed);
        var isEnabled = isInstalled ? st.enabled !== false : false;
        var busyState = busyPlugins[p.name];
        var errText = pluginErrors[p.name];
        var ver = (st && st.version) || p.version || "1.0.0";
        var desc = es ? (p.descriptionEs || p.description || "") : (p.description || "");

        var row = document.createElement("div");
        row.className = "settings-extension-row";
        row.setAttribute("data-plugin-id", p.name);

        var lead = document.createElement("div");
        lead.className = "settings-extension-lead";
        lead.style.alignItems = "flex-start";

        var iconWrap = createCubeIconSvg();
        iconWrap.style.marginTop = "2px";
        lead.appendChild(iconWrap);

        var textCol = document.createElement("div");
        textCol.className = "flex min-w-0 flex-col gap-0.5";

        var titleLine = document.createElement("div");
        titleLine.className = "settings-provider-main";

        var nameEl = document.createElement("bdi");
        nameEl.setAttribute("dir", "ltr");
        nameEl.className = "settings-extension-name truncate";
        nameEl.textContent = p.displayName || p.name;
        titleLine.appendChild(nameEl);

        var verEl = document.createElement("span");
        verEl.className = "text-11-regular text-v2-text-text-muted shrink-0";
        verEl.textContent = "v" + ver;
        titleLine.appendChild(verEl);

        textCol.appendChild(titleLine);

        if (desc) {
          var descEl = document.createElement("span");
          descEl.className = "text-11-regular text-v2-text-text-muted";
          descEl.style.display = "-webkit-box";
          descEl.style.webkitLineClamp = "2";
          descEl.style.webkitBoxOrient = "vertical";
          descEl.style.overflow = "hidden";
          descEl.style.whiteSpace = "normal";
          descEl.textContent = desc;
          textCol.appendChild(descEl);
        }

        if (errText) {
          var errEl = document.createElement("span");
          errEl.className = "settings-server-dialog-error";
          errEl.textContent = errText;
          textCol.appendChild(errEl);
        }

        lead.appendChild(textCol);
        row.appendChild(lead);

        var trailing = document.createElement("div");
        trailing.className = "settings-extension-trailing";

        if (!isInstalled) {
          var instLabel = busyState === "installing"
            ? tr("Instalando…", "Installing…")
            : tr("Instalar", "Install");
          trailing.appendChild(
            createNativeButton(instLabel, "submit", "small", !!busyState, function () {
              handleInstallPlugin(p);
            })
          );
        } else {
          var uninstLabel = busyState === "removing"
            ? tr("Quitando…", "Removing…")
            : tr("Desinstalar", "Uninstall");
          trailing.appendChild(
            createNativeButton(uninstLabel, "ghost-muted", "small", !!busyState, function () {
              handleUninstallPlugin(p);
            })
          );
          trailing.appendChild(
            createNativeSwitch(isEnabled, !!busyState, p.displayName || p.name, function (next) {
              handleTogglePlugin(p, next);
            })
          );
        }

        row.appendChild(trailing);
        container.appendChild(row);
      });
    });
  }

  // (B) Native About Page OTA Update Link (.settings-about-details)
  function updateAboutLinkLabel() {
    var btn = document.querySelector('[data-oc-about-update="1"] button');
    if (!btn) return;
    var st = latestUpdateState || { type: "idle" };
    var t = st.type || "idle";
    var ver = (st.update && st.update.version) || st.version || "";
    var installedVer = st.installedVersion || (host && host.getAppVersion ? host.getAppVersion() : "");
    var nextLabel;

    if (t === "checking" || t === "checking for updates") {
      nextLabel = tr("Buscando actualizaciones…", "Checking for updates…");
    } else if (t === "available for download" || t === "downloading") {
      var pct = typeof st.progress === "number" && st.progress >= 0
        ? " (" + Math.round(st.progress) + "%)"
        : "";
      nextLabel = tr(
        "Descargando actualización" + (ver ? " v" + ver : "") + pct + "…",
        "Downloading update" + (ver ? " v" + ver : "") + pct + "…"
      );
    } else if (t === "ready") {
      nextLabel = tr(
        "Instalar actualización disponible" + (ver ? " (v" + ver + ")" : ""),
        "Install available update" + (ver ? " (v" + ver + ")" : "")
      );
    } else if (st.result === "up-to-date") {
      nextLabel = tr(
        "OpenCode está actualizado" + (installedVer ? " (v" + installedVer + ")" : "") + " · Buscar de nuevo",
        "OpenCode is up to date" + (installedVer ? " (v" + installedVer + ")" : "") + " · Check again"
      );
    } else if (st.result === "error") {
      nextLabel = tr("Reintentar búsqueda de actualizaciones", "Retry update check");
    } else {
      nextLabel = tr("Buscar actualizaciones", "Check for updates");
    }

    if (btn.textContent !== nextLabel) {
      btn.textContent = nextLabel;
    }
  }

  function mountAboutUpdaterIfVisible() {
    if (!host) return;
    var details = document.querySelector(".settings-about-details");
    if (!details) return;
    if (details.querySelector('[data-oc-about-update="1"]')) {
      updateAboutLinkLabel();
      return;
    }
    try {
      if (typeof host.getUpdateStateJson === "function") {
        latestUpdateState = JSON.parse(host.getUpdateStateJson() || '{"type":"idle"}');
      }
    } catch (e) {}

    var p = document.createElement("p");
    p.setAttribute("data-oc-about-update", "1");
    var btn = document.createElement("button");
    btn.type = "button";
    btn.className = "settings-about-link";
    btn.addEventListener("click", function () {
      if (latestUpdateState && latestUpdateState.type === "ready") {
        if (typeof host.applyAppUpdateAuth === "function" && bridgeToken) {
          host.applyAppUpdateAuth(bridgeToken);
        }
      } else {
        latestUpdateState = { type: "checking for updates" };
        updateAboutLinkLabel();
        if (typeof host.checkForAppUpdates === "function") host.checkForAppUpdates(true);
      }
    });
    p.appendChild(btn);
    details.appendChild(p);
    updateAboutLinkLabel();
  }

  // (C) Native Android Storage & System Folder Picker inside OpenCode's DirectoryPickerDialog
  function getExtStorageRoot() {
    if (host && typeof host.getExternalStoragePath === "function") {
      try {
        var p = host.getExternalStoragePath();
        if (p && p.charAt(0) === "/") return p;
      } catch (e) {}
    }
    return "/storage/emulated/0";
  }

  function navigateDirectoryPickerTo(picker, targetPath) {
    if (!picker || !targetPath) return false;
    var inp = picker.querySelector("input.directory-picker-input") || picker.querySelector('input[role="combobox"]');
    if (!inp) return false;
    try {
      // Trailing slash guarantees Ye(e) === -1 in OpenCode v2.3.0's combobox so Enter triggers choose.directory directly
      var normalized = targetPath === "/" ? "/" : targetPath.replace(/\/+$/, "") + "/";
      var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value");
      if (setter && setter.set) {
        setter.set.call(inp, normalized);
      } else {
        inp.value = normalized;
      }
      inp.dispatchEvent(new Event("input", { bubbles: true }));
      inp.dispatchEvent(
        new KeyboardEvent("keydown", {
          key: "Enter",
          code: "Enter",
          keyCode: 13,
          which: 13,
          bubbles: true,
          cancelable: true
        })
      );
      return true;
    } catch (e) {
      return false;
    }
  }

  function registerProjectAndGoHome(chosenPath) {
    fetch("/api/location?location%5Bdirectory%5D=" + encodeURIComponent(chosenPath), {
      credentials: "include"
    })
      .catch(function () {})
      .then(function () {
        window.location.assign("/");
      });
  }

  function confirmPickedDirectoryInDialog(chosenPath) {
    if (!chosenPath || chosenPath.charAt(0) !== "/") return;
    var picker = document.querySelector(".directory-picker");
    if (!picker || !navigateDirectoryPickerTo(picker, chosenPath)) {
      registerProjectAndGoHome(chosenPath);
      return;
    }

    var frames = 0;
    function pollConfirmButton() {
      frames++;
      var curPicker = document.querySelector(".directory-picker");
      if (!curPicker) return;
      var submitBtn = curPicker.querySelector('button[data-variant="contrast"]');
      if (submitBtn && !submitBtn.disabled) {
        submitBtn.click();
        return;
      }
      if (frames < 20) {
        requestAnimationFrame(pollConfirmButton);
      } else {
        var closeBtn = curPicker.querySelector('[data-slot="dialog-close-button"]');
        if (closeBtn) closeBtn.click();
        registerProjectAndGoHome(chosenPath);
      }
    }
    requestAnimationFrame(pollConfirmButton);
  }

  function mountDirectoryPickerAndroidActions() {
    if (!host) return;
    var picker = document.querySelector(".directory-picker");
    if (!picker) return;

    // Only enhance folder-selection mode (skip single-file picker mode)
    var submitBtn = picker.querySelector('button[data-variant="contrast"]');
    if (submitBtn && /archivo|file/i.test(submitBtn.textContent || "")) {
      return;
    }

    var actions =
      picker.querySelector(".directory-picker-actions") ||
      picker.querySelector('[data-slot="dialog-footer"]') ||
      (submitBtn && submitBtn.parentElement);
    if (!actions || actions.querySelector('[data-oc-android-dir-btn="1"]')) return;

    var extPath = getExtStorageRoot();

    // 1. Quick jump to /storage/emulated/0 inside OpenCode's built-in tree
    var storageBtn = createNativeButton(
      tr("Almacenamiento", "Storage"),
      "ghost",
      "normal",
      false,
      function (ev) {
        ev.preventDefault();
        navigateDirectoryPickerTo(picker, extPath);
      }
    );
    storageBtn.setAttribute("data-oc-android-dir-btn", "1");

    // 2. Native Android File Manager folder picker (supports creating new folders natively)
    var safBtn = createNativeButton(
      tr("Gestor de archivos", "File Manager"),
      "secondary",
      "normal",
      false,
      function (ev) {
        ev.preventDefault();
        if (typeof host.pickProjectDirectoryAuth !== "function" || !bridgeToken) return;
        var cbId = "dir_" + (++dirPickerSeq) + "_" + Date.now();
        dirPickerCallbacks[cbId] = function (chosenPath) {
          if (chosenPath) {
            confirmPickedDirectoryInDialog(chosenPath);
          }
        };
        host.pickProjectDirectoryAuth(bridgeToken, cbId);
      }
    );
    safBtn.setAttribute("data-oc-android-dir-btn", "2");

    actions.insertBefore(safBtn, actions.firstChild);
    actions.insertBefore(storageBtn, safBtn.nextSibling);

    // Default initial folder tree to /storage/emulated/0 instead of internal /data/user/0 sandbox
    if (!picker.hasAttribute("data-oc-storage-defaulted")) {
      picker.setAttribute("data-oc-storage-defaulted", "1");
      setTimeout(function () {
        var curPicker = document.querySelector(".directory-picker");
        if (!curPicker) return;
        var inp = curPicker.querySelector("input.directory-picker-input") || curPicker.querySelector('input[role="combobox"]');
        var sel =
          curPicker.querySelector(".directory-picker-selection-text") ||
          curPicker.querySelector('.directory-picker-breadcrumb[aria-current="location"]');
        var selTxt = sel ? (sel.textContent || "").trim() : "";
        if ((!inp || !inp.value) && (!selTxt || selTxt === "~" || selTxt.indexOf("/data/user/") === 0)) {
          navigateDirectoryPickerTo(curPicker, extPath);
        }
      }, 180);
    }
  }

  var syncScheduled = false;
  function scheduleNativeUiSync() {
    if (syncScheduled) return;
    syncScheduled = true;
    requestAnimationFrame(function () {
      syncScheduled = false;
      cleanupLegacyFloatingNodes();
      if (document.querySelector(".settings-extensions-tabs")) {
        renderPluginsCatalogIfMounted(false);
      }
      if (document.querySelector(".settings-about-details")) {
        mountAboutUpdaterIfVisible();
      }
      if (document.querySelector(".directory-picker")) {
        mountDirectoryPickerAndroidActions();
      }
    });
  }

  function isInsideInjectedAndroidNode(node) {
    if (!node) return false;
    var el = node.nodeType === 1 ? node : node.parentElement;
    if (!el || typeof el.closest !== "function") return false;
    return !!el.closest('[data-oc-about-update], [data-oc-native-plugins], [data-oc-android-dir-btn]');
  }

  function hasActionableSurfaceInMutations(mutations) {
    for (var i = 0; i < mutations.length; i++) {
      var m = mutations[i];
      if (!m || isInsideInjectedAndroidNode(m.target)) {
        continue;
      }
      if (m.type === "attributes") {
        var tEl = m.target && m.target.nodeType === 1 ? m.target : null;
        if (tEl && typeof tEl.closest === "function" && tEl.closest(".settings-extensions-tabs")) {
          return true;
        }
        continue;
      }
      if (m.type === "childList" && m.addedNodes && m.addedNodes.length > 0) {
        for (var j = 0; j < m.addedNodes.length; j++) {
          var node = m.addedNodes[j];
          if (node && node.nodeType === 1 && !isInsideInjectedAndroidNode(node)) {
            var cls = typeof node.className === "string" ? node.className : "";
            if (
              cls.indexOf("settings") !== -1 ||
              cls.indexOf("dialog") !== -1 ||
              cls.indexOf("directory-picker") !== -1 ||
              (node.querySelector &&
                node.querySelector(".settings-extensions-tabs, .settings-about-details, .directory-picker"))
            ) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  function initOnDomReady() {
    cleanupLegacyFloatingNodes();
    if (!checkRootReady()) {
      var readyObs = new MutationObserver(function () {
        if (checkRootReady()) {
          readyObs.disconnect();
        }
      });
      readyObs.observe(document.documentElement, { childList: true, subtree: true });
    }
    syncHostTheme();
    scheduleNativeUiSync();

    var uiObs = new MutationObserver(function (mutations) {
      if (hasActionableSurfaceInMutations(mutations)) {
        scheduleNativeUiSync();
      }
    });
    if (document.body) {
      uiObs.observe(document.body, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ["hidden", "data-selected", "aria-selected"]
      });
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initOnDomReady);
  } else {
    initOnDomReady();
  }

  safetyReadyTimer = setTimeout(signalUiReady, 6000);
})();
