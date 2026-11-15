(function () {
  "use strict";
  if (window.__OC_ANDROID_BOOTED__) return;
  window.__OC_ANDROID_BOOTED__ = true;

  var host = window.Android || window.AndroidBridge || null;
  if (host && !window.AndroidBridge) {
    window.AndroidBridge = host;
  }

  // Unregister any cached Workbox ServiceWorker so WebView always gets fresh local assets
  try {
    if (navigator.serviceWorker && typeof navigator.serviceWorker.getRegistrations === "function") {
      navigator.serviceWorker.getRegistrations().then(function (regs) {
        regs.forEach(function (r) { r.unregister(); });
      }).catch(function () {});
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

  // Exact UTF-8 base64url encoder matching OpenCode's _assets/encode-*.js
  function encodeBase64Url(str) {
    var bytes = new TextEncoder().encode(str);
    var bin = "";
    for (var i = 0; i < bytes.length; i++) {
      bin += String.fromCharCode(bytes[i]);
    }
    return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "");
  }

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

  // 1. Ensure initial workspace defaults to /storage/emulated/0/Documents/OpenCode instead of internal files/
  function ensureDefaultWorkspaceRoute() {
    if (!host || typeof host.getConfig !== "function") return false;
    try {
      var cfg = JSON.parse(host.getConfig() || "{}");
      var filesDir = cfg.home || "/data/user/0/com.oc.ide/files";
      var workspaceDir = cfg.workspaceDir || "/storage/emulated/0/Documents/OpenCode";
      if (!workspaceDir || workspaceDir === filesDir) return false;

      var filesSlug = encodeBase64Url(filesDir);
      var wsSlug = encodeBase64Url(workspaceDir);
      var path = window.location.pathname || "/";

      if (
        (path === "/" + filesSlug || path.indexOf("/" + filesSlug + "/") === 0) &&
        !sessionStorage.getItem("__oc_ws_redirected_v2")
      ) {
        sessionStorage.setItem("__oc_ws_redirected_v2", "1");
        window.location.replace("/" + wsSlug + "/session");
        return true;
      }
    } catch (e) {}
    return false;
  }

  // Watch SPA route changes during initial boot in case SolidJS redirects `/` -> `/<filesSlug>/session`
  var origReplaceState = history.replaceState;
  var origPushState = history.pushState;
  history.replaceState = function () {
    var r = origReplaceState.apply(this, arguments);
    ensureDefaultWorkspaceRoute();
    scheduleNativeSettingsSync();
    return r;
  };
  history.pushState = function () {
    var r = origPushState.apply(this, arguments);
    ensureDefaultWorkspaceRoute();
    scheduleNativeSettingsSync();
    return r;
  };
  window.addEventListener("popstate", function () {
    ensureDefaultWorkspaceRoute();
    scheduleNativeSettingsSync();
  });

  if (ensureDefaultWorkspaceRoute()) {
    return;
  }

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

  // 3. Dismiss native splash once OpenCode's SolidJS #root mounts
  var uiSignaled = false;
  function signalUiReady() {
    if (uiSignaled) return;
    uiSignaled = true;
    try {
      if (host && typeof host.onUiReady === "function") {
        host.onUiReady();
      }
    } catch (e) {}
  }

  function checkRootReady() {
    var root = document.getElementById("root");
    if (root && root.childNodes && root.childNodes.length > 0) {
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
      var heading = document.querySelector(".settings-extension-heading, .settings-tab-header h2");
      if (heading && /extensiones|disponible|proveedores|acerca/i.test(heading.textContent || "")) {
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

  // 7. Native OpenCode Settings Integration:
  //    (A) Ajustes -> Extensiones -> Plugins (native .settings-extension-row catalog)
  //    (B) Ajustes -> Acerca de (native .settings-about-link OTA update action)
  var REMOTE_CATALOG_URL =
    "https://raw.githubusercontent.com/Yhoanes/antigravity-android-plugins/main/catalog.json";
  var catalogCache = null;
  var catalogFetching = false;
  var busyPlugins = {};
  var pluginErrors = {};
  var currentSearchQuery = "";
  var latestUpdateState = { type: "idle" };

  window.__onNativeUpdaterState = function (st) {
    if (st && typeof st === "object") {
      latestUpdateState = st;
      updateAboutLinkLabel();
    }
  };

  function parseInstalledMap() {
    if (!host || typeof host.listAndroidPlugins !== "function") return {};
    try {
      return JSON.parse(host.listAndroidPlugins() || "{}") || {};
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
              if (!slug || !url) return;
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
      })
      .catch(function () {
        catalogFetching = false;
        cb([]);
      });
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

  function createNativeButton(label, variant, disabled, onClick) {
    var btn = document.createElement("button");
    btn.type = "button";
    btn.setAttribute("data-component", "button-v2");
    btn.setAttribute("data-size", "small");
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
    if (!host || busyPlugins[p.name]) return;
    busyPlugins[p.name] = "installing";
    delete pluginErrors[p.name];
    renderPluginsCatalogIfMounted(true);

    setTimeout(function () {
      try {
        var raw = host.installMarketplacePlugin(
          p.name,
          p.archiveUrl || "",
          "antigravity-plugins-official/plugin/" + p.name,
          "antigravity-plugins-official",
          p.version || "1.0.0"
        );
        var res = JSON.parse(raw || "{}");
        delete busyPlugins[p.name];
        if (!res.ok) {
          pluginErrors[p.name] = res.error || tr("Error al instalar el plugin", "Plugin installation failed");
        } else {
          triggerOpenCodeHotReload();
        }
      } catch (e) {
        delete busyPlugins[p.name];
        pluginErrors[p.name] = e.message || String(e);
      }
      renderPluginsCatalogIfMounted(true);
    }, 40);
  }

  function handleTogglePlugin(p, nextEnabled) {
    if (!host || busyPlugins[p.name]) return;
    busyPlugins[p.name] = "toggling";
    delete pluginErrors[p.name];
    renderPluginsCatalogIfMounted(true);

    setTimeout(function () {
      try {
        var raw = host.toggleAndroidPlugin(p.name, nextEnabled);
        var res = JSON.parse(raw || "{}");
        if (res && res.ok === false && res.error) {
          pluginErrors[p.name] = res.error;
        } else {
          triggerOpenCodeHotReload();
        }
      } catch (e) {
        pluginErrors[p.name] = e.message || String(e);
      }
      delete busyPlugins[p.name];
      renderPluginsCatalogIfMounted(true);
    }, 30);
  }

  function handleUninstallPlugin(p) {
    if (!host || busyPlugins[p.name]) return;
    busyPlugins[p.name] = "removing";
    delete pluginErrors[p.name];
    renderPluginsCatalogIfMounted(true);

    setTimeout(function () {
      try {
        host.uninstallMarketplacePlugin(p.name);
        triggerOpenCodeHotReload();
      } catch (e) {
        pluginErrors[p.name] = e.message || String(e);
      }
      delete busyPlugins[p.name];
      renderPluginsCatalogIfMounted(true);
    }, 30);
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

  function readSettingsSearchQuery() {
    var inp = document.querySelector(
      '.settings-search input, input[type="search"], input[placeholder*="Buscar"], input[placeholder*="Search"]'
    );
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
            createNativeButton(instLabel, "submit", !!busyState, function () {
              handleInstallPlugin(p);
            })
          );
        } else {
          var uninstLabel = busyState === "removing"
            ? tr("Quitando…", "Removing…")
            : tr("Desinstalar", "Uninstall");
          trailing.appendChild(
            createNativeButton(uninstLabel, "ghost-muted", !!busyState, function () {
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
    if (st.type === "checking") {
      btn.textContent = tr("Buscando actualizaciones…", "Checking for updates…");
    } else if (st.type === "downloading") {
      var pct = typeof st.progress === "number" ? " (" + Math.round(st.progress) + "%)" : "";
      btn.textContent = tr("Descargando actualización" + pct + "…", "Downloading update" + pct + "…");
    } else if (st.type === "ready") {
      btn.textContent = tr(
        "Instalar actualización disponible (v" + (st.version || "") + ")",
        "Install available update (v" + (st.version || "") + ")"
      );
    } else if (st.type === "up-to-date") {
      btn.textContent = tr("OpenCode está actualizado", "OpenCode is up to date");
    } else if (st.type === "error") {
      btn.textContent = tr("Reintentar búsqueda de actualizaciones", "Retry update check");
    } else {
      btn.textContent = tr("Buscar actualizaciones", "Check for updates");
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
        if (typeof host.applyAppUpdate === "function") host.applyAppUpdate();
      } else {
        latestUpdateState = { type: "checking" };
        updateAboutLinkLabel();
        if (typeof host.checkForAppUpdates === "function") host.checkForAppUpdates(true);
      }
    });
    p.appendChild(btn);
    details.appendChild(p);
    updateAboutLinkLabel();
  }

  var syncScheduled = false;
  function scheduleNativeSettingsSync() {
    if (syncScheduled) return;
    syncScheduled = true;
    requestAnimationFrame(function () {
      syncScheduled = false;
      cleanupLegacyFloatingNodes();
      renderPluginsCatalogIfMounted(false);
      mountAboutUpdaterIfVisible();
    });
  }

  document.addEventListener("input", function (ev) {
    if (ev && ev.target && ev.target.tagName === "INPUT") {
      renderPluginsCatalogIfMounted(false);
    }
  });

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
    scheduleNativeSettingsSync();

    var uiObs = new MutationObserver(function () {
      scheduleNativeSettingsSync();
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

  setTimeout(signalUiReady, 3800);
})();
