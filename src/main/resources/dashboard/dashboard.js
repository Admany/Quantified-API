import React from "https://esm.sh/react@18.3.1";
import { createRoot } from "https://esm.sh/react-dom@18.3.1/client";
import htm from "https://esm.sh/htm@3.1.1";

const { useCallback, useEffect, useMemo, useRef, useState } = React;
const html = htm.bind(React.createElement);

const NAV_ITEMS = [
    { id: "overview", label: "Overview" },
    { id: "resources", label: "Resources" },
    { id: "logs", label: "Logs" },
    { id: "controls", label: "Controls" },
    { id: "config", label: "Config" },
    { id: "system", label: "System" },
];

const REFRESH_INTERVAL_MS = 1000;
const RESOURCE_REFRESH_MS = 5000;
const HISTORY_LIMIT = 120;

async function fetchJson(url, options) {
    const fetchOptions = { cache: "no-store", credentials: "same-origin", ...options };
    fetchOptions.headers = {
        Accept: "application/json",
        ...(options && options.headers),
    };

    let response;
    try {
        response = await fetch(url, fetchOptions);
    } catch (err) {
        throw new Error("The API has disconnected or shut down.");
    }
    const text = await response.text();
    let payload = {};
    if (text) {
        try {
            payload = JSON.parse(text);
        } catch (err) {
            if (response.ok) {
                console.warn("Failed to parse JSON response", err);
            }
        }
    }
    if (!response.ok) {
        const message = payload && payload.error ? payload.error : `Request failed (${response.status})`;
        throw new Error(message);
    }
    return payload;
}

const StatusPill = ({ label, variant = "ok" }) =>
    html`<span className=${`status-pill ${variant}`}>${label}</span>`;

const Metric = ({ label, value, sub, className = "metric-item" }) =>
    html`<div className=${className}>
        <div className="metric-value">${value}</div>
        ${sub ? html`<div className="metric-sub">${sub}</div>` : html`<div className="metric-sub">${"\u00A0"}</div>`}
        <div className="metric-sub">${label}</div>
    </div>`;

const ToggleRow = ({ label, description, value, onChange, disabled = false }) =>
    html`<label className="toggle">
        <span>
            <span className="label">${label}</span>
            ${description ? html`<span className="description">${description}</span>` : null}
        </span>
        <span className="toggle-switch" role="switch" aria-checked=${Boolean(value)} aria-disabled=${disabled}>
            <input
                className="toggle-input"
                type="checkbox"
                checked=${Boolean(value)}
                disabled=${disabled}
                onChange=${(event) => {
                    if (typeof onChange === "function") {
                        onChange(event.target.checked);
                    }
                }}
            />
            <span className="toggle-track" aria-hidden="true">
                <span className="toggle-thumb" />
            </span>
        </span>
    </label>`;

const NumericInput = ({ value, onCommit, min, step, disabled }) => {
    const [local, setLocal] = React.useState(value != null ? String(value) : "");
    React.useEffect(() => {
        setLocal(value != null ? String(value) : "");
    }, [value]);

    const commit = () => {
        const parsed = Number.parseInt(local || "0", 10);
        if (Number.isNaN(parsed)) return;
        if (typeof onCommit === "function") onCommit(parsed);
    };

    return html`<input
        className="numeric-input"
        type="number"
        min=${min}
        step=${step}
        value=${local}
        disabled=${disabled}
        onChange=${(e) => setLocal(e.target.value)}
        onKeyDown=${(e) => {
            if (e.key === "Enter") {
                commit();
                e.currentTarget.blur();
            }
        }}
        onBlur=${() => commit()}
    />`;
};

const Card = ({ title, actions = null, className = "", children }) =>
    html`<div className=${`card ${className}`.trim()}>
        <div className="card-header">
            <h2>${title}</h2>
            ${actions}
        </div>
        ${children}
    </div>`;

const AreaChart = ({ values = [], color = "#69f0ae", min = null, max = null, strokeWidth = 1.6 }) => {
    const safeValues = values.length ? values : [0];
    const resolvedMin = min != null ? min : Math.min(...safeValues, 0);
    const resolvedMax = max != null ? max : Math.max(...safeValues, resolvedMin + 1);
    const range = resolvedMax - resolvedMin || 1;
    const points = safeValues.map((value, index) => {
        const x = (index / Math.max(safeValues.length - 1, 1)) * 100;
        const y = 100 - ((value - resolvedMin) / range) * 100;
        return { x, y };
    });

    const path = points
        .map((point, index) => `${index === 0 ? "M" : "L"}${point.x.toFixed(2)},${point.y.toFixed(2)}`)
        .join(" ");
    const fillPath = `${path} L100,100 L0,100 Z`;

    return html`<svg className="chart" viewBox="0 0 100 100" preserveAspectRatio="none">
        <path d=${fillPath} fill=${color} fill-opacity="0.18" />
        <path d=${path} stroke=${color} stroke-width=${strokeWidth} fill="none" stroke-linecap="round" stroke-linejoin="round" />
    </svg>`;
};

const MultiLineChart = ({ series = [], min = null, max = null, strokeWidth = 1.6 }) => {
    const safeSeries = series.map((seriesItem) => {
        const values = Array.isArray(seriesItem?.values) && seriesItem.values.length ? seriesItem.values : [0];
        return { ...seriesItem, values };
    });
    const flattened = safeSeries.flatMap((item) => item.values);
    const resolvedMin = min != null ? min : Math.min(...flattened, 0);
    const resolvedMax = max != null ? max : Math.max(...flattened, resolvedMin + 1);
    const range = resolvedMax - resolvedMin || 1;

    const buildPath = (values) =>
        values
            .map((value, index) => {
                const x = (index / Math.max(values.length - 1, 1)) * 100;
                const y = 100 - ((value - resolvedMin) / range) * 100;
                return `${index === 0 ? "M" : "L"}${x.toFixed(2)},${y.toFixed(2)}`;
            })
            .join(" ");

    return html`<svg className="chart" viewBox="0 0 100 100" preserveAspectRatio="none">
        ${safeSeries.map(
            (item, idx) =>
                html`<path
                    key=${idx}
                    d=${buildPath(item.values)}
                    stroke=${item.color || "#69f0ae"}
                    stroke-width=${strokeWidth}
                    fill="none"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                />`
        )}
    </svg>`;
};

function formatPercent(value) {
    if (value === null || value === undefined || Number.isNaN(value)) {
        return "-";
    }
    return `${(value * 100).toFixed(0)}%`;
}

function formatTemperature(value) {
    if (value === null || value === undefined || Number.isNaN(value)) {
        return "-";
    }
    return `${value.toFixed(1)}\u00B0C`;
}

function formatRate(value) {
    if (value === null || value === undefined || Number.isNaN(value)) {
        return "-";
    }
    return `${value.toFixed(1)} /s`;
}

function formatTime(timestamp) {
    if (!timestamp) {
        return "-";
    }
    const date = new Date(timestamp);
    if (Number.isNaN(date.getTime())) {
        return "-";
    }
    return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function formatBytes(bytes) {
    if (bytes === null || bytes === undefined || Number.isNaN(bytes)) {
        return "-";
    }
    if (bytes === 0) {
        return "0 B";
    }
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB", "TB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    const numeric = bytes / Math.pow(k, i);
    return `${numeric.toFixed(1)} ${sizes[i]}`;
}

function formatMillis(value) {
    if (value === null || value === undefined || Number.isNaN(value)) {
        return "-";
    }
    if (value >= 1000) {
        const seconds = value / 1000;
        const precision = seconds >= 10 ? 1 : 2;
        return `${seconds.toFixed(precision)} s`;
    }
    return `${Math.round(value)} ms`;
}

function escapeRegex(value) {
    return String(value).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function cleanDiskLabel(disk) {
    const raw = String(disk?.name || " Physical Disk");
    let cleaned = raw;
    if (disk?.serial) {
        const serialPattern = new RegExp(`${escapeRegex(disk.serial)}\\.?$`, "i");
        cleaned = cleaned.replace(serialPattern, "");
    }
    cleaned = cleaned.replace(/\s*[a-f0-9]{4}(?:_[a-f0-9]{4}){2,}\.?$/i, "");
    cleaned = cleaned.replace(/\s{2,}/g, " ").trim();
    return cleaned || raw;
}

function getHintIcon(hint) {
    const message = hint.message.toLowerCase();
    if (message.includes("overheating") || message.includes("choking") || message.includes("close to safety guardrails") || message.includes("cpu load > 80%")) {
        return "!!";
    }
    if (hint.severity === "WARNING") {
        return "!";
    }
    if (hint.severity === "INFO") {
        return "i";
    }
    return ".";
}

function formatNumber(value) {
    if (value === null || value === undefined || Number.isNaN(value)) {
        return "-";
    }
    return value.toLocaleString();
}

function formatDecimal(value, digits = 1) {
    if (value === null || value === undefined || Number.isNaN(value)) {
        return "-";
    }
    return Number(value).toFixed(digits);
}

function renderApiLogLine(line, key, modColorMap) {
    if (line.trim() === "[Quantified]") {
        return html`<div className="log-line" key=${key}><span className="log-prefix log-prefix-quantified">[Quantified] mods using the API</span></div>`;
    }
    if (line.trim().startsWith("[Quantified Frontend]")) {
        return html`<div className="log-line" key=${key}><span style=${{ color: "#C4B5FD" }}>${line}</span></div>`;
    }
    if (line.startsWith("[Quantified]")) {
        const prefix = "[Quantified]";
        const rest = line.substring(prefix.length);
        return html`<div className="log-line" key=${key}><span className="log-prefix log-prefix-quantified">${prefix}</span><span className="log-msg">${rest}</span></div>`;
    }
    const match = line.match(/- \[(.*?)\] \(Version (.*?)\) at (\d{2}:\d{2})/);
    if (match) {
        const modNameRaw = match[1];
        const version = match[2];
        const time = match[3];
        const modId = modNameRaw.toLowerCase().includes("quantified") ? "quantified" : modNameRaw.toLowerCase();
        let color = modColorMap.map.get(modId);
        if (modNameRaw === "Quantified Frontend") {
            color = "#C4B5FD";
        } else if (!color) {
            color = modColorMap.palette[modColorMap.idx % modColorMap.palette.length];
            modColorMap.idx += 1;
            modColorMap.map.set(modId, color);
        }
        return html`<div className="log-line" key=${key}><span className="log-prefix log-prefix-quantified">[Quantified]</span> ${"\u00A0"}<span className="log-modname" style=${{ color }}>[${modNameRaw}]</span> <span className="log-msg">(Version ${version}) at ${time}</span></div>`;
    }
    return html`<div className="log-line" key=${key}><span className="log-msg">${line}</span></div>`;
}

const Sidebar = ({ activeView, onSelect, statusEntries }) =>
    html`<aside className="sidebar">
        <div className="sidebar-title">
            <strong>Navigation</strong>
        </div>
        <nav className="sidebar-nav">
            ${NAV_ITEMS.map(
                (item) => html`<button
                    key=${item.id}
                    className=${`nav-item ${item.id === activeView ? "active" : ""}`}
                    onClick=${() => onSelect(item.id)}
                >
                    ${item.label}
                </button>`
            )}
        </nav>
        <div className="sidebar-status">
            <h4>Quick Status</h4>
            ${statusEntries.slice(0, 3).map(
                (entry, index) => html`<div className="sidebar-status-row" key=${index}>
                    <div>
                        <div className="metric-sub">${entry.label}</div>
                        <strong>${entry.detail}</strong>
                    </div>
                    <${StatusPill} label=${entry.badge} variant=${entry.variant} />
                </div>`
            )}
        </div>
    </aside>`;

const StatusBoard = ({ entries }) =>
    html`<div className="status-board">
        ${entries.map(
            (entry, index) => html`<div className=${`status-card ${entry.variant}`} key=${index}>
                <div className="status-card-header">
                    <span>${entry.label}</span>
                    <${StatusPill} label=${entry.badge} variant=${entry.variant} />
                </div>
                <div className="status-card-body">
                    <strong>${entry.detail}</strong>
                    <p>${entry.message}</p>
                </div>
            </div>`
        )}
    </div>`;

const App = () => {
    const animationCount = parseInt(localStorage.getItem("quantifiedAnimationCount") || "0", 10);
    const shouldPlay = animationCount < 4;
    const [state, setState] = useState(null);
    const [timeline, setTimeline] = useState([]);
    const [mods, setMods] = useState([]);
    const [selectedMod, setSelectedMod] = useState(null);
    const [modStats, setModStats] = useState(null);
    const selectedModRef = React.useRef(null);
    const [error, setError] = useState(null);
    const [busy, setBusy] = useState(false);
    const [history, setHistory] = useState([]);
    const [lastUpdated, setLastUpdated] = useState(null);
    const [activeView, setActiveView] = useState("overview");
    const [themeOverride, setThemeOverride] = useState(() => {
        if (typeof window === "undefined") return null;
        const saved =
            window.localStorage.getItem("quantifiedThemeMode") ||
            window.localStorage.getItem("quantifiedThemeOverride") ||
            window.localStorage.getItem("quantifiedTheme");
        if (saved === "light" || saved === "dark") return saved;
        return null;
    });
    const [theme, setTheme] = useState(() => {
        if (typeof window === "undefined") return "light";
        if (themeOverride === "dark" || themeOverride === "light") return themeOverride;
        return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    });
    const [resourceData, setResourceData] = useState(null);
    const [resourceBusy, setResourceBusy] = useState(false);
    const [selectedFiles, setSelectedFiles] = useState(() => new Set());
    const [configGroups, setConfigGroups] = useState([]);
    const [configEdits, setConfigEdits] = useState({});
    const [configLoading, setConfigLoading] = useState(false);
    const [toasts, setToasts] = useState([]);
    const [expandedTaskMods, setExpandedTaskMods] = useState(() => new Set());
    const [expandedCacheMods, setExpandedCacheMods] = useState(() => new Set());
    const toastIdRef = useRef(0);
    const configSaveTimers = useRef(new Map());

    const [loadingState, setLoadingState] = useState({
        isLoading: true,
        hasInitialData: false,
        showOverlay: true,
        overlayStage: "loading",
        minElapsed: false,
        deadlineHit: false,
        loadError: null,
    });

    const removeToast = useCallback((id) => {
        setToasts((prev) => prev.filter((toast) => toast.id !== id));
    }, []);

    const pushToast = useCallback((message, tone = "info", duration = 2600) => {
        const id = ++toastIdRef.current;
        setToasts((prev) => [...prev, { id, message, tone }]);
        if (duration > 0) {
            setTimeout(() => removeToast(id), duration);
        }
        return id;
    }, [removeToast]);

    const formatFetchError = useCallback((err, fallback) => {
        const message = err && err.message ? err.message : "";
        if (message === "Failed to fetch" || message === "NetworkError") {
            return "The API has disconnected or shut down.";
        }
        return message || fallback || "Request failed.";
    }, []);

    const disconnectToastRef = useRef(0);

    const maybeToastDisconnect = useCallback((message) => {
        if (!message || !message.toLowerCase().includes("api has disconnected")) return;
        const now = Date.now();
        if (now - disconnectToastRef.current < 12000) return;
        disconnectToastRef.current = now;
        pushToast(message, "error", 4200);
    }, [pushToast]);

    const loadAll = useCallback(async () => {
        try {
            const [nextState, timelinePayload, modsPayload] = await Promise.all([
                fetchJson("/api/v1/dashboard/state"),
                fetchJson("/api/v1/dashboard/timeline"),
                fetchJson("/api/v1/mods"),
            ]);

            const timelineList = (timelinePayload && timelinePayload.timeline) || [];
            const apiLines = (timelinePayload && timelinePayload.apiLogs) || [];
            const combinedState = { ...nextState, apiLogs: apiLines };
            setState(combinedState);
            setTimeline(timelineList);

            const modsList = ((modsPayload && modsPayload.mods) || []).map((mod) => ({
                ...mod,
                selectValue: mod.origModId || mod.modId,
                displayLabel: mod.displayName || mod.modId,
            }));
            modsList.sort((a, b) => (b.lastActivity || 0) - (a.lastActivity || 0));
            const visibleMods = modsList.slice(0, 8);
            setMods(visibleMods);
            setSelectedMod((current) => {
                if (!current) {
                    return current;
                }
                const match = visibleMods.find((mod) => mod.selectValue === current || mod.modId === current);
                if (match) {
                    return match.selectValue;
                }
                return current;
            });

            const snapshot = nextState && nextState.snapshot ? nextState.snapshot : {};
            const stressCacheSize = Number(nextState?.stressCacheSize ?? 0);
            const totalCacheSize = Number(nextState?.totalCacheSize ?? 0);
            const modsCacheSizeNow = Number(nextState?.modsCacheSize ?? Math.max(0, totalCacheSize - stressCacheSize));
            const cpuSystemLoad = Number(snapshot.cpuSystemLoad ?? nextState?.cpuSystemLoad ?? 0);
            const gpuVramBudgetBytes = Number(snapshot.gpuVramBudgetBytes ?? nextState?.gpuVramBudgetBytes ?? 0);
            const gpuVramUsedBytes = Number(nextState?.gpuVramUsedBytes ?? snapshot.gpuVramUsedBytes ?? 0);
            const gpuMemoryRatio =
                gpuVramBudgetBytes > 0
                    ? Math.max(0, Math.min(1, gpuVramUsedBytes / Math.max(1, gpuVramBudgetBytes)))
                    : Number(snapshot.gpuMemoryUtil ?? 0);
            const gpuVramTelemetryBytes = Number(nextState?.gpuVramUsedTelemetryBytes ?? snapshot.gpuVramUsedBytes ?? 0);
            const gpuVramCacheBytes = Number(nextState?.gpuVramCacheBytes ?? 0);
            const gpuVramTaskBytes = Number(nextState?.gpuVramTaskBytes ?? 0);
            const gpuVramContextBytes = Number(
                nextState?.gpuVramContextBytes ?? Math.max(0, gpuVramTelemetryBytes - gpuVramCacheBytes - gpuVramTaskBytes)
            );
            const modsPacketsSent = Number(nextState?.modsPacketsSent ?? 0);
            const modsPacketsReceived = Number(nextState?.modsPacketsReceived ?? 0);
            const modsNetworkErrors = Number(nextState?.modsNetworkErrors ?? 0);
            const modsNetworkBytes = Number(nextState?.modsNetworkBytes ?? 0);
            const stressTestPacketsSent = Number(nextState?.stressTestPacketsSent ?? 0);
            const stressTestPacketsReceived = Number(nextState?.stressTestPacketsReceived ?? 0);
            const stressTestBytesTransferred = Number(nextState?.stressTestBytesTransferred ?? 0);
            const networkTotals = {
                packetsSent: modsPacketsSent + stressTestPacketsSent,
                packetsReceived: modsPacketsReceived + stressTestPacketsReceived,
                networkErrors: modsNetworkErrors,
                networkBytesTransferred: modsNetworkBytes + stressTestBytesTransferred,
            };

            const historyEntry = {
                timestamp: snapshot.timestamp || Date.now(),
                queueDepth: Number(snapshot.queueDepth ?? 0),
                parallelActiveSlices: Number(snapshot.parallelActiveSlices ?? 0),
                totalWork: Number(snapshot.totalWork ?? 0),
                cpuCompute: Number(snapshot.cpuComputeUtil ?? snapshot.gpuComputeUtil ?? 0),
                gpuCompute: Number(snapshot.gpuComputeUtil ?? 0),
                gpuMemory: gpuMemoryRatio,
                gpuTemperature: Number(snapshot.gpuTemperature ?? 0),
                stressCacheSize,
                totalCacheSize,
                modCacheSize: modsCacheSizeNow,
                cpuSystemLoad,
                gpuVramBudgetBytes,
                gpuVramUsedBytes,
                gpuVramTelemetryBytes,
                gpuVramCacheBytes,
                gpuVramTaskBytes,
                gpuVramContextBytes,
                cacheRamBytes: Number(nextState?.cacheRamBytes ?? 0),
                cacheDiskBytes: Number(nextState?.cacheDiskBytes ?? 0),
                cacheEntryCount: Number(nextState?.cacheEntryCount ?? totalCacheSize),
                packetsSent: networkTotals.packetsSent,
                packetsReceived: networkTotals.packetsReceived,
                networkErrors: networkTotals.networkErrors,
                networkBytesTransferred: networkTotals.networkBytesTransferred,
                modsPacketsSent,
                modsPacketsReceived,
                modsNetworkErrors,
                modsNetworkBytesTransferred: modsNetworkBytes,
                stressPacketsSent: stressTestPacketsSent,
                stressPacketsReceived: stressTestPacketsReceived,
                stressNetworkErrors: Math.max(0, networkTotals.networkErrors - modsNetworkErrors),
                stressNetworkBytesTransferred: stressTestBytesTransferred,
            };
            setHistory((current) => {
                const next = [...current, historyEntry];
                if (next.length > HISTORY_LIMIT) {
                    next.splice(0, next.length - HISTORY_LIMIT);
                }
                return next;
            });
            setLastUpdated(Date.now());
            setError(null);

            setLoadingState((prev) => ({
                ...prev,
                hasInitialData: true,
                loadError: null,
            }));
        } catch (err) {
            console.error("Initial load failed:", err);
            const customMessage = formatFetchError(err, "Failed to load dashboard data");
            setLoadingState((prev) => ({
                ...prev,
                loadError: customMessage,
                hasInitialData: false,
            }));
            maybeToastDisconnect(customMessage);
        }
    }, [formatFetchError, maybeToastDisconnect]);

    const loadModStats = useCallback(
        async (modId) => {
            if (!modId) {
                setModStats(null);
                return;
            }
            try {
                const stats = await fetchJson(`/api/v1/mods/${encodeURIComponent(modId)}/stats?ts=${Date.now()}`);
                setModStats(stats);
                setError(null);
            } catch (err) {
                console.error(err);
                setError(err.message || "Failed to load mod statistics");
                setModStats(null);
            }
        },
        []
    );

    useEffect(() => {
        loadAll();
        const interval = window.setInterval(() => {
            loadAll();
            const activeMod = selectedModRef.current;
            if (activeMod) {
                loadModStats(activeMod);
            }
        }, REFRESH_INTERVAL_MS);

        const minLoadingTimer = setTimeout(() => {
            setLoadingState((prev) => ({ ...prev, minElapsed: true }));
        }, 2000);

        const animationTimer = setTimeout(() => {
            if (shouldPlay) {
                localStorage.setItem("quantifiedAnimationCount", (animationCount + 1).toString());
            }
            setLoadingState((prev) => ({ ...prev, overlayStage: "fading" }));
        }, 3000);

        const deadlineTimer = setTimeout(() => {
            setLoadingState((prev) => ({ ...prev, deadlineHit: true }));
        }, 8000);

        return () => {
            window.clearInterval(interval);
            clearTimeout(minLoadingTimer);
            clearTimeout(animationTimer);
            clearTimeout(deadlineTimer);
        };
    }, [loadAll, loadModStats, shouldPlay, animationCount]);

    useEffect(() => {
        if (loadingState.overlayStage === "fading") {
            const timer = setTimeout(() => {
                setLoadingState((prev) => ({
                    ...prev,
                    showOverlay: false,
                    overlayStage: "hidden",
                    isLoading: false,
                }));
            }, 1000);
            return () => clearTimeout(timer);
        }
    }, [loadingState.overlayStage]);

    useEffect(() => {
        if (loadingState.hasInitialData && loadingState.minElapsed && loadingState.overlayStage === "loading") {
            setLoadingState((prev) => ({ ...prev, overlayStage: "fading" }));
        }
    }, [loadingState.hasInitialData, loadingState.minElapsed, loadingState.overlayStage]);

    useEffect(() => {
        if (loadingState.deadlineHit && !loadingState.hasInitialData && loadingState.loadError) {
            setError(loadingState.loadError);
            setLoadingState((prev) => ({ ...prev, overlayStage: "fading" }));
        }
    }, [loadingState.deadlineHit, loadingState.hasInitialData, loadingState.loadError]);

    useEffect(() => {
        if (selectedMod !== null || !mods.length) {
            return;
        }
        const preferred = mods.find((mod) => (mod.origModId || mod.modId || "").toLowerCase() === "quantified") || mods[0];
        if (preferred) {
            setSelectedMod(preferred.origModId || preferred.modId);
        }
    }, [mods, selectedMod]);

    useEffect(() => {
        if (selectedMod && !mods.some((mod) => (mod.origModId || mod.modId) === selectedMod)) {
            setSelectedMod(null);
        }
    }, [mods, selectedMod]);

    useEffect(() => {
        if (selectedMod) {
            selectedModRef.current = selectedMod;
            loadModStats(selectedMod);
        } else {
            setModStats(null);
            selectedModRef.current = null;
        }
    }, [selectedMod, loadModStats]);

    const fetchResources = useCallback(async () => {
        try {
            const payload = await fetchJson("/api/v1/resources");
            setResourceData(payload);
            setError(null);
        } catch (err) {
            console.error(err);
            setError(err.message || "Failed to load resource data");
        }
    }, []);

    useEffect(() => {
        if (activeView !== "resources") {
            return undefined;
        }
        fetchResources();
        const interval = window.setInterval(fetchResources, RESOURCE_REFRESH_MS);
        return () => window.clearInterval(interval);
    }, [activeView, fetchResources]);

    useEffect(() => {
        setSelectedFiles(new Set());
    }, [resourceData && resourceData.generatedAt]);

    const fetchConfigLayout = useCallback(async () => {
        setConfigLoading(true);
        try {
            const payload = await fetchJson("/api/v1/config");
            setConfigGroups(payload.groups || []);
            setConfigEdits({});
            setError(null);
        } catch (err) {
            console.error(err);
            setError(err.message || "Failed to load config");
        } finally {
            setConfigLoading(false);
        }
    }, []);

    useEffect(() => {
        if (activeView === "config" && !configLoading && !configGroups.length) {
            fetchConfigLayout();
        }
    }, [activeView, configLoading, configGroups.length, fetchConfigLayout]);

    useEffect(() => {
        const boot = typeof document !== "undefined" ? document.getElementById("boot-overlay") : null;
        if (boot && boot.parentNode) {
            boot.parentNode.removeChild(boot);
        }
    }, []);

    useEffect(() => {
        if (typeof window === "undefined") return undefined;
        if (themeOverride) return undefined;
        const media = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;
        if (!media) return undefined;
        const apply = () => setTheme(media.matches ? "dark" : "light");
        apply();
        if (media.addEventListener) {
            media.addEventListener("change", apply);
            return () => media.removeEventListener("change", apply);
        }
        media.addListener(apply);
        return () => media.removeListener(apply);
    }, [themeOverride]);

    useEffect(() => {
        if (typeof document === "undefined") return;
        document.documentElement.dataset.theme = theme;
        if (typeof window !== "undefined") {
            const mode = themeOverride ? themeOverride : "auto";
            window.localStorage.setItem("quantifiedThemeMode", mode);
        }
    }, [theme, themeOverride]);

    const updateToggles = useCallback(
        async (patch) => {
            try {
                setBusy(true);
                await fetchJson("/api/v1/dashboard/toggles", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(patch),
                });
                await loadAll();
                setError(null);
            } catch (err) {
                console.error(err);
                setError(err.message || "Failed to update feature toggles");
            } finally {
                setBusy(false);
            }
        },
        [loadAll]
    );

    const exportDiagnostics = useCallback(async () => {
        try {
            const data = await fetchJson("/api/v1/dashboard/export");
            const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = url;
            a.download = "diagnostics.json";
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        } catch (err) {
            console.error(err);
            setError(err.message || "Failed to export diagnostics");
        }
    }, []);

    const downloadHistory = useCallback(async () => {
        try {
            const response = await fetch("/api/v1/dashboard/history");
            if (!response.ok) {
                throw new Error(`Request failed (${response.status})`);
            }
            const blob = await response.blob();
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = url;
            a.download = "history.json";
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        } catch (err) {
            console.error(err);
            setError(err.message || "Failed to download history");
        }
    }, []);

    const clearStressCache = useCallback(async () => {
        try {
            setBusy(true);
            await fetchJson("/api/v1/stress/clear-cache", { method: "POST" });
            setError(null);
            await loadAll();
        } catch (err) {
            console.error(err);
            setError(err.message || "Failed to clear stress cache");
        } finally {
            setBusy(false);
        }
    }, [loadAll]);

    const updateStressProfileSelection = useCallback(
        async (profileKey) => {
            if (!profileKey) {
                return;
            }
            try {
                setBusy(true);
                await fetchJson("/api/v1/stress/profile", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ profile: profileKey }),
                });
                setError(null);
                await loadAll();
            } catch (err) {
                console.error(err);
                setError(err.message || "Failed to update stress profile");
            } finally {
                setBusy(false);
            }
        },
        [loadAll]
    );

    const runStressCycle = useCallback(async () => {
        try {
            setBusy(true);
            const payload = state?.stressTestProfile ? { profile: state.stressTestProfile } : {};
            await fetchJson("/api/v1/stress/run", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload),
            });
            setError(null);
            await loadAll();
        } catch (err) {
            console.error(err);
            setError(err.message || "Failed to run stress test");
        } finally {
            setBusy(false);
        }
    }, [loadAll, state?.stressTestProfile]);

    const flushResourceTarget = useCallback(
        async (target) => {
            try {
                setResourceBusy(true);
                await fetchJson("/api/v1/resources/flush", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ target }),
                });
                await Promise.all([fetchResources(), loadAll()]);
                setError(null);
            } catch (err) {
                console.error(err);
                setError(err.message || `Failed to flush ${target}`);
            } finally {
                setResourceBusy(false);
            }
        },
        [fetchResources, loadAll]
    );

    const deleteSelectedFiles = useCallback(
        async (items) => {
            if (!items.length) {
                return;
            }
            try {
                setResourceBusy(true);
                await fetchJson("/api/v1/resources/disk", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ delete: items }),
                });
                setSelectedFiles(new Set());
                await fetchResources();
                setError(null);
            } catch (err) {
                console.error(err);
                setError(err.message || "Failed to delete cache entries");
            } finally {
                setResourceBusy(false);
            }
        },
        [fetchResources]
    );

    const purgeModCache = useCallback(
        async (modId) => {
            try {
                setResourceBusy(true);
                await fetchJson("/api/v1/resources/disk", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ purgeMod: modId }),
                });
                await fetchResources();
                setError(null);
            } catch (err) {
                console.error(err);
                setError(err.message || "Failed to purge cache");
            } finally {
                setResourceBusy(false);
            }
        },
        [fetchResources]
    );

    const toggleFileSelection = useCallback((key) => {
        setSelectedFiles((current) => {
            const next = new Set(current);
            if (next.has(key)) {
                next.delete(key);
            } else {
                next.add(key);
            }
            return next;
        });
    }, []);

    const selectedFilePayload = useMemo(
        () =>
            Array.from(selectedFiles).map((key) => {
                const [modId, ...fileParts] = key.split("::");
                return { modId, file: fileParts.join("::") };
            }),
        [selectedFiles]
    );

    const updateConfigGroupValue = useCallback((key, value) => {
        setConfigGroups((groups) => groups.map((group) => {
            if (!group.fields) return group;
            const fields = group.fields.map((field) => {
                if (field.key !== key) return field;
                return { ...field, value };
            });
            return { ...group, fields };
        }));
    }, []);

    const normalizeDeviceName = useCallback((name) => {
        return String(name || "").toLowerCase().replace(/\s+/g, " ").trim();
    }, []);

    const waitForGpuSwitch = useCallback(async (expectedName) => {
        const deadline = Date.now() + 15000;
        let lastState = null;
        const expected = normalizeDeviceName(expectedName);
        while (Date.now() < deadline) {
            const state = await fetchJson("/api/v1/dashboard/state");
            lastState = state;
            const active = normalizeDeviceName(state.openclDeviceName);
            if (state.openclAvailable === false && state.openclFailureReason) {
                return { ok: false, deviceName: state.openclDeviceName || "", reason: state.openclFailureReason };
            }
            if (state.openclAvailable) {
                if (!expected && active) {
                    return { ok: true, deviceName: state.openclDeviceName };
                }
                if (expected && active && (active.includes(expected) || expected.includes(active))) {
                    return { ok: true, deviceName: state.openclDeviceName };
                }
                if (expected && !active) {
                    return { ok: true, deviceName: state.openclDeviceName || expectedName };
                }
            }
            await new Promise((resolve) => setTimeout(resolve, 900));
        }
        return {
            ok: false,
            deviceName: lastState?.openclDeviceName || "",
            reason: lastState?.openclFailureReason || "Timed out waiting for OpenCL switch",
        };
    }, [normalizeDeviceName]);

    const applyConfigUpdate = useCallback(async (key, value, options = {}) => {
        const isGpu = key === "openclDeviceId";
        if (isGpu) {
            pushToast("Applying GPU change...", "info", 2600);
        }
        try {
            await fetchJson("/api/v1/config", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ entries: [{ key, value }] }),
            });
            updateConfigGroupValue(key, value);
            setConfigEdits((current) => {
                const next = { ...current };
                delete next[key];
                return next;
            });
            if (isGpu) {
                const expectedName = options.expectedDeviceName || "";
                const result = await waitForGpuSwitch(expectedName);
                if (result.ok) {
                    const label = result.deviceName
                        ? `GPU change applied - now using ${result.deviceName} for OpenCL tasks.`
                        : "GPU change applied - OpenCL is ready.";
                    pushToast(label, "success", 4200);
                } else {
                    const detail = result.reason ? ` (${result.reason})` : "";
                    pushToast(`GPU change failed${detail}`, "error", 4200);
                }
            } else {
                pushToast("Change applied", "success");
            }
            setError(null);
        } catch (err) {
            console.error(err);
            setError(err.message || "Failed to apply config change");
            pushToast("Change failed", "error", 3200);
        }
    }, [pushToast, updateConfigGroupValue, waitForGpuSwitch]);

    const scheduleConfigUpdate = useCallback((key, value, options = {}) => {
        const timers = configSaveTimers.current;
        const delay = typeof options.delay === "number" ? options.delay : 350;
        if (timers.has(key)) {
            clearTimeout(timers.get(key));
        }
        const timeout = setTimeout(() => {
            applyConfigUpdate(key, value, options);
            timers.delete(key);
        }, delay);
        timers.set(key, timeout);
    }, [applyConfigUpdate]);

    const clearDiskCache = useCallback(async () => {
        try {
            setResourceBusy(true);
            await fetchJson("/api/v1/resources/disk", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ clearAll: true }),
            });
            setSelectedFiles(new Set());
            await fetchResources();
            setError(null);
        } catch (err) {
            console.error(err);
            setError(err.message || "Failed to clear disk cache");
        } finally {
            setResourceBusy(false);
        }
    }, [fetchResources]);

    const queueThreshold = resourceData?.queueWarningThreshold ?? 15000;
    const snapshot = state?.snapshot ?? {};
    const queueDepth = Number(snapshot.queueDepth ?? 0);
    const parallelActiveSlices = Number(snapshot.parallelActiveSlices ?? 0);
    const totalWork = Number(snapshot.totalWork ?? (queueDepth + parallelActiveSlices));
    const queueWarning = queueThreshold * 0.7;
    const gpuCompute = Number(snapshot.gpuComputeUtil ?? 0);
    const gpuMemory = Number(snapshot.gpuMemoryUtil ?? 0);
    const gpuTemperature = Number(snapshot.gpuTemperature ?? state?.systemInfo?.gpuTemperature ?? 0);
    const cpuSystemLoad = Number(snapshot.cpuSystemLoad ?? state?.cpuSystemLoad ?? 0);
    const schedulerRate = Number(snapshot.schedulerExecRate ?? 0);
    const ramCacheBytes = Number(state?.cacheRamBytes ?? 0);
    const diskCacheBytes = Number(state?.cacheDiskBytes ?? 0);
    const cacheEntryCount = Number(state?.cacheEntryCount ?? 0);
    const vramUsedBytes = Number(state?.gpuVramUsedBytes ?? snapshot.gpuVramUsedBytes ?? 0);
    const vramBudgetBytes = Number(state?.gpuVramBudgetBytes ?? snapshot.gpuVramBudgetBytes ?? 0);
    const vramTelemetryBytes = Number(state?.gpuVramUsedTelemetryBytes ?? snapshot.gpuVramUsedBytes ?? 0);
    const vramCacheBytes = Number(state?.gpuVramCacheBytes ?? 0);
    const vramTaskBytes = Number(state?.gpuVramTaskBytes ?? 0);
    const vramContextBytes = Number(state?.gpuVramContextBytes ?? Math.max(0, vramTelemetryBytes - vramCacheBytes - vramTaskBytes));
    const vramRatio = vramBudgetBytes > 0 ? Math.min(1, vramUsedBytes / Math.max(1, vramBudgetBytes)) : gpuMemory;
    const networkErrors = Number(state?.modsNetworkErrors ?? 0) + Number(state?.stressNetworkErrors ?? 0);
    const networkPackets = Number(state?.modsPacketsSent ?? 0) + Number(state?.stressPacketsSent ?? 0);
    const stressCacheSize = Number(state?.stressCacheSize ?? 0);
    const modsCacheSize = Number(state?.modsCacheSize ?? 0);
    const modsOnlineCount = mods.length;
    const hints = state?.hints ?? [];
    const spotlight = state?.spotlight ?? [];
    const systemInfo = state?.systemInfo ?? {};
    const gpuList = Array.isArray(systemInfo.gpuList) ? systemInfo.gpuList : [];
    const gpuInventory = useMemo(() => {
        if (!gpuList.length) return [];
        const classified = gpuList.map((gpu) => {
            const vendor = String(gpu.vendor || "").toLowerCase();
            const vram = Number(gpu.vramBytes || 0);
            const integrated = vendor.includes("intel") || vram <= 0;
            return {
                ...gpu,
                integrated,
                typeLabel: integrated ? "Integrated" : "Dedicated",
                sortKey: integrated ? 0 : 1,
            };
        });
        classified.sort((a, b) => {
            if (a.sortKey !== b.sortKey) return a.sortKey - b.sortKey;
            return Number(b.vramBytes || 0) - Number(a.vramBytes || 0);
        });
        return classified;
    }, [gpuList]);
    const storageVolumes = Array.isArray(systemInfo.storage) ? systemInfo.storage : [];
    const physicalDisks = Array.isArray(systemInfo.physicalDisks) ? systemInfo.physicalDisks : [];
    const logs = (state?.apiLogs || []).slice(-300);
    const timelineEvents = timeline.slice(-160);

    const statusEntries = useMemo(() => {
        if (!state) {
            return [
                {
                    label: "Dashboard",
                    badge: "Waiting",
                    variant: "warn",
                    detail: "Awaiting data",
                    message: "Once the API responds you will see live diagnostics here.",
                },
            ];
        }
        const variantFor = (value, warnLevel, criticalLevel) => {
            if (value >= criticalLevel) return "error";
            if (value >= warnLevel) return "warn";
            return "ok";
        };

        const queueVariant = variantFor(totalWork, queueWarning, queueThreshold);
        const gpuVariant = variantFor(gpuCompute, 0.75, 0.9);
            const memoryVariant = variantFor(vramRatio, 0.8, 0.95);
        const cpuVariant = variantFor(cpuSystemLoad, 0.65, 0.85);
        const cacheVariant = variantFor(diskCacheBytes / Math.max(1, ramCacheBytes + diskCacheBytes), 0.5, 0.75);
        const networkVariant = variantFor(networkErrors / Math.max(1, networkPackets || 1), 0.02, 0.05);

        return [
            {
                label: "API",
                badge: state.dashboardEnabled ? "Online" : "Offline",
                variant: state.dashboardEnabled ? "ok" : "error",
                detail: state.dashboardEnabled ? `Port ${state.port ?? "?"}` : "Disabled",
                message: state.developerMode ? "Developer mode enabled" : "Standard runtime",
            },
            {
                label: "Queue",
                badge: totalWork >= queueThreshold ? "Max" : totalWork >= queueWarning ? "Busy" : "Nominal",
                variant: queueVariant,
                detail: parallelActiveSlices > 0
                    ? `${formatNumber(queueDepth)} tasks / ${formatNumber(parallelActiveSlices)} slices`
                    : `${formatNumber(queueDepth)} tasks`,
                message: `Soft limit ${formatNumber(queueThreshold)} total work units before throttling`,
            },
            {
                label: "GPU",
                badge: formatTemperature(gpuTemperature),
                variant: gpuVariant === "ok" ? memoryVariant : gpuVariant,
                detail: `${formatPercent(gpuCompute)} compute`,
                message: `API VRAM ${formatPercent(vramRatio)} of ${formatBytes(vramBudgetBytes || 0)}`,
            },
            {
                label: "CPU",
                badge: formatPercent(cpuSystemLoad),
                variant: cpuVariant,
                detail: `${formatNumber(Math.max(0, Math.round(schedulerRate)))} exec/s`,
                message: "Scheduler throughput in the last second",
            },
            {
                label: "Caches",
                badge: `${formatNumber(cacheEntryCount)} entries`,
                variant: cacheVariant,
                detail: `${formatBytes(ramCacheBytes)} RAM / ${formatBytes(diskCacheBytes)} disk`,
                message: `Mods cache ${formatNumber(modsCacheSize)} / Stress cache ${formatNumber(stressCacheSize)}`,
            },
            {
                label: "Network",
                badge: `${formatNumber(networkErrors)} errs`,
                variant: networkVariant,
                detail: `${formatNumber(networkPackets)} packets`,
                message: "Combined mod + stress transport counters",
            },
        ];
    }, [
        state,
        queueDepth,
        parallelActiveSlices,
        totalWork,
        queueWarning,
        queueThreshold,
        gpuCompute,
        gpuTemperature,
        vramRatio,
        vramBudgetBytes,
        cpuSystemLoad,
        schedulerRate,
        cacheEntryCount,
        ramCacheBytes,
        diskCacheBytes,
        modsCacheSize,
        stressCacheSize,
        networkErrors,
        networkPackets,
    ]);

    const historySeries = useMemo(() => {
        const queueSeries = history.map((entry) => Number(entry.queueDepth ?? 0));
        const gpuComputeSeries = history.map((entry) => Number(entry.gpuCompute ?? 0));
        const gpuMemorySeries = history.map((entry) => Number(entry.gpuMemory ?? 0));
        const cacheRamSeries = history.map((entry) => Number(entry.cacheRamBytes ?? 0) / (1024 * 1024 * 1024));
        const cacheDiskSeries = history.map((entry) => Number(entry.cacheDiskBytes ?? 0) / (1024 * 1024 * 1024));
        const vramSeries = history.map((entry) => Number(entry.gpuVramUsedBytes ?? 0) / (1024 * 1024 * 1024));
        const vramBudgetSeries = history.map((entry) => Number(entry.gpuVramBudgetBytes ?? 0) / (1024 * 1024 * 1024));
        const gpuTempSeries = history.map((entry) => Number(entry.gpuTemperature ?? 0));

        const withFallback = (series, fallbackValue = 0) => (series.length ? series : [fallbackValue]);

        return {
            queue: withFallback(queueSeries, queueDepth),
            gpuCompute: withFallback(gpuComputeSeries, gpuCompute),
            gpuMemory: withFallback(gpuMemorySeries, gpuMemory),
            cacheRam: withFallback(cacheRamSeries, ramCacheBytes / (1024 * 1024 * 1024)),
            cacheDisk: withFallback(cacheDiskSeries, diskCacheBytes / (1024 * 1024 * 1024)),
            vram: withFallback(vramSeries, vramUsedBytes / (1024 * 1024 * 1024)),
            vramBudget: withFallback(vramBudgetSeries, vramBudgetBytes / (1024 * 1024 * 1024)),
            gpuTemp: withFallback(gpuTempSeries, gpuTemperature),
        };
    }, [
        history,
        queueDepth,
        gpuCompute,
        gpuMemory,
        ramCacheBytes,
        diskCacheBytes,
        vramUsedBytes,
        vramBudgetBytes,
        gpuTemperature,
    ]);

    const modResourceList = useMemo(() => {
        if (!resourceData?.mods) {
            return [];
        }
        const modsWithTotals = resourceData.mods.map((mod) => ({
            ...mod,
            totalBytes: Number(mod.ramBytes ?? 0) + Number(mod.diskBytes ?? 0) + Number(mod.peakVramBytes ?? 0),
        }));
        modsWithTotals.sort((a, b) => b.totalBytes - a.totalBytes || Number(b.queueDepth ?? 0) - Number(a.queueDepth ?? 0));
        return modsWithTotals;
    }, [resourceData]);

    const diskFiles = resourceData?.diskFiles ?? [];
    const caches = resourceData?.caches ?? [];
    const taskKinds = resourceData?.taskKinds?.entries ?? [];
    const taskWindowMs = Number(resourceData?.taskKinds?.windowMs ?? 0);
    const resourceSummary = resourceData?.summary ?? null;
    const selectedCount = selectedFilePayload.length;
    const modColorMap = useMemo(
        () => ({
            map: new Map(),
            palette: ["#60a5fa", "#f472b6", "#34d399", "#facc15", "#f87171", "#a78bfa", "#38bdf8", "#fb7185"],
            idx: 0,
        }),
        []
    );

    const updateConfigValue = useCallback((key, originalValue, incomingValue, options = {}) => {
        const same = JSON.stringify(incomingValue) === JSON.stringify(originalValue);
        setConfigEdits((current) => {
            const next = { ...current };
            if (same) {
                delete next[key];
            } else {
                next[key] = incomingValue;
            }
            return next;
        });
        if (!same) {
            scheduleConfigUpdate(key, incomingValue, options);
        }
    }, [scheduleConfigUpdate]);

    const renderOverview = () => {
        const apiMeta = [
            { label: "Queue Depth", value: formatNumber(queueDepth), sub: `Soft cap ${formatNumber(queueThreshold)}` },
            { label: "GPU Compute", value: formatPercent(gpuCompute), sub: `Temp ${formatTemperature(gpuTemperature)}` },
            { label: "API VRAM (telemetry)", value: formatPercent(vramRatio), sub: formatBytes(vramUsedBytes) },
            { label: "Cache Entries", value: formatNumber(cacheEntryCount), sub: `${formatBytes(ramCacheBytes)} RAM` },
        ];
        const modOptions = mods.map((mod) => html`<option key=${mod.selectValue} value=${mod.selectValue}>${mod.displayLabel}</option>`);
        const throttleLabel =
            modStats && modStats.throttleFactor != null ? `x${Number(modStats.throttleFactor).toFixed(2)}` : "Not throttled";

        return html`<section className="view overview-view">
            <div className="card-grid two">
                <${Card}
                    title="API Status"
                    actions=${html`<div className="card-actions">
                        <span className="meta-label">Snapshot ${lastUpdated ? formatTime(lastUpdated) : "Pending"}</span>
                        <${StatusPill} label=${state?.dashboardEnabled ? "Live" : "Paused"} variant=${state?.dashboardEnabled ? "ok" : "warn"} />
                    </div>`}
                >
                    <div className="metrics-row">
                        ${apiMeta.map((metric, idx) => html`<${Metric} key=${idx} label=${metric.label} value=${metric.value} sub=${metric.sub} />`)}
                    </div>
                    <ul className="api-list">
                        <li><span>Port</span><strong>${state?.port ?? "-"}</strong></li>
                        <li><span>Timeline Frames</span><strong>${formatNumber(state?.timelineSize ?? 0)}</strong></li>
                        <li><span>Replay Frames</span><strong>${formatNumber(state?.replayFrameCount ?? 0)}</strong></li>
                        <li><span>Connected Mods</span><strong>${formatNumber(modsOnlineCount)}</strong></li>
                    </ul>
                </${Card}>
                <${Card} title="Queue Health" className="tall">
                    <div className="chart-pane">
                        <${AreaChart} values=${historySeries.queue} color="#8b5cf6" />
                    </div>
                    <div className="metrics-row">
                        <${Metric}
                            label="Foreground Workers"
                            value=${formatNumber(snapshot.desiredForegroundWorkers ?? 0)}
                            sub="Desired threads"
                        />
                        <${Metric}
                            label="Background Workers"
                            value=${formatNumber(snapshot.desiredBackgroundWorkers ?? 0)}
                            sub="Desired threads"
                        />
                        <${Metric}
                            label="Scheduler Rate"
                            value=${formatNumber(Math.max(0, Math.round(schedulerRate)))}
                            sub="Exec / s"
                        />
                    </div>
                </${Card}>
            </div>
            <${Card} title="Component Status">
                <${StatusBoard} entries=${statusEntries} />
            </${Card}>
              <div className="card-grid three">
                  <${Card} title="GPU Activity">
                    <div className="chart-pane">
                        <${MultiLineChart}
                            series=${[
                                { values: historySeries.gpuCompute, color: "#34d399" },
                                { values: historySeries.gpuMemory, color: "#60a5fa" },
                            ]}
                            min=${0}
                            max=${1}
                        />
                    </div>
                      <div className="metrics-row">
                          <${Metric} label="Compute" value=${formatPercent(gpuCompute)} sub="Current load" />
                          <${Metric} label="API VRAM (telemetry)" value=${formatPercent(vramRatio)} sub="Context excluded" />
                          <${Metric} label="Temperature" value=${formatTemperature(gpuTemperature)} sub="GPU Sensors" />
                      </div>
                  </${Card}>
                  <${Card} title="GPU Memory Breakdown">
                      <div className="metrics-row">
                          <${Metric} label="Cache VRAM" value=${formatBytes(vramCacheBytes)} sub="Tiered GPU cache" />
                          <${Metric} label="In-flight tasks" value=${formatBytes(vramTaskBytes)} sub="Estimated usage" />
                          <${Metric} label="OpenCL context" value=${formatBytes(vramContextBytes)} sub="Ignored in API VRAM" />
                      </div>
                      <div className="breakdown-note">How this is calculated</div>
                      <p className="text-muted">
                          We start with the GPU's reported VRAM use, then subtract what Quantified is actively caching and what tasks are using right now. Anything left is treated as OpenCL context overhead and isn't counted toward the API VRAM ratio.
                      </p>
                  </${Card}>
                  <${Card} title="Cache Footprint">
                    <div className="chart-pane">
                        <${MultiLineChart}
                            series=${[
                                { values: historySeries.cacheRam, color: "#a5b4fc" },
                                { values: historySeries.cacheDisk, color: "#fbbf24" },
                            ]}
                        />
                    </div>
                    <div className="metrics-row">
                        <${Metric} label="RAM Cache" value=${formatBytes(ramCacheBytes)} sub="In-memory" />
                        <${Metric} label="Disk Cache" value=${formatBytes(diskCacheBytes)} sub="Persistent" />
                        <${Metric} label="Stress Cache" value=${formatNumber(stressCacheSize)} sub="Entries" />
                    </div>
                </${Card}>
                <${Card} title="Tuning Hints">
                    <div className="hint-list">
                        ${hints.length
                            ? hints.map(
                                  (hint, idx) => html`<div className="hint-row" key=${idx}>
                                      <span className="hint-icon">${getHintIcon(hint)}</span>
                                      <div>
                                          <div className="hint-title">${hint.severity}</div>
                                          <p>${hint.message}</p>
                                      </div>
                                  </div>`
                              )
                            : html`<p className="text-muted">No automatic tuning suggestions at the moment.</p>`}
                    </div>
                </${Card}>
            </div>
            <div className="card-grid two">
                <${Card}
                    title="Mod Inspector"
                    actions=${html`<select value=${selectedMod || ""} onChange=${(event) => setSelectedMod(event.target.value || null)}>
                        <option value="">Select mod</option>
                        ${modOptions}
                    </select>`}
                >
                    ${selectedMod && modStats
                        ? html`<div className="mod-stat-grid">
                              <div>
                                  <span className="stat-label">Mod</span>
                                  <strong>${modStats.modId}</strong>
                              </div>
                              <div>
                                  <span className="stat-label">Version</span>
                                  <strong>${modStats.version || "-"}</strong>
                              </div>
                              <div>
                                  <span className="stat-label">Queue Depth</span>
                                  <strong>${formatNumber(modStats.currentQueueDepth ?? 0)}</strong>
                              </div>
                              <div>
                                  <span className="stat-label">Tasks / s</span>
                                  <strong>${modStats.tasksPerSecond?.toFixed ? modStats.tasksPerSecond.toFixed(2) : formatNumber(modStats.tasksPerSecond ?? 0)}</strong>
                              </div>
                              <div>
                                  <span className="stat-label">Cache Hit Rate</span>
                                  <strong>${formatPercent(modStats.cacheHitRate)}</strong>
                              </div>
                              <div>
                                  <span className="stat-label">Peak VRAM</span>
                                  <strong>${formatBytes(modStats.peakVRAMUsage || 0)}</strong>
                              </div>
                              <div>
                                  <span className="stat-label">Avg Task Time</span>
                                  <strong>${formatMillis(modStats.averageTaskTimeMs ?? 0)}</strong>
                              </div>
                              <div>
                                  <span className="stat-label">Throttle</span>
                                  <strong>${throttleLabel}</strong>
                              </div>
                          </div>
                          <div className="status-row">
                              <${StatusPill}
                                  label=${modStats.isThrottled ? "Throttled" : "Normal"}
                                  variant=${modStats.isThrottled ? "warn" : "ok"}
                              />
                              <span className="text-muted">Last activity ${formatTime(modStats.lastActivity)}</span>
                          </div>`
                        : html`<p className="text-muted">Select a connected mod to inspect live stats.</p>`}
                </${Card}>
                <${Card} title="Mod Spotlight">
                    <div className="spotlight-list">
                        ${spotlight.length
                            ? spotlight.map(
                                  (entry, idx) => html`<div className="spotlight-row" key=${idx}>
                                      <div>
                                          <strong>${entry.modId}</strong>
                                          <div className="text-muted">v${entry.version}</div>
                                      </div>
                                      <div className="spotlight-metrics">
                                          <span>${formatNumber(entry.tasksInFlight || 0)} in flight</span>
                                          <span>${formatPercent(entry.cacheHitRate)} hit rate</span>
                                      </div>
                                  </div>`
                              )
                            : html`<p className="text-muted">No spotlight entries right now.</p>`}
                    </div>
                </${Card}>
            </div>
        </section>`;
    };

    const renderResources = () => {
        const summaryMetrics = resourceSummary
            ? [
                  {
                      label: "Queue Depth",
                      value: formatNumber(resourceSummary.queueDepth ?? 0),
                      sub: `Warn @ ${formatNumber(queueThreshold)}`,
                  },
                    {
                      label: "API VRAM (telemetry)",
                      value: `${formatBytes(resourceSummary.vramUsedBytes ?? 0)} / ${formatBytes(resourceSummary.vramBudgetBytes ?? 0)}`,
                      sub: formatPercent((resourceSummary.vramUsedBytes || 0) / Math.max(1, resourceSummary.vramBudgetBytes || 1)),
                    },
                  { label: "RAM Cache", value: formatBytes(resourceSummary.cacheRamBytes ?? 0), sub: "Heap usage" },
                  { label: "Disk Cache", value: formatBytes(resourceSummary.cacheDiskBytes ?? 0), sub: "Persistent usage" },
                ]
            : [];
        const taskWindowLabel = taskWindowMs
            ? `${Math.max(1, Math.round(taskWindowMs / 60000))}m window`
            : "Recent";
        const modNameById = new Map(
            (resourceData?.mods ?? []).map((mod) => [mod.modId, mod.displayName || mod.modId])
        );
        const resolveModLabel = (modId) => {
            if (!modId) return "Unknown Mod";
            const direct = modNameById.get(modId);
            if (direct) return direct;
            if (modId.toLowerCase().startsWith("quantified")) {
                return "Quantified API";
            }
            return modId;
        };
        const groupedTaskKinds = taskKinds.reduce((acc, entry) => {
            const key = entry.modId || "unknown-mod";
            if (!acc[key]) {
                acc[key] = [];
            }
            acc[key].push(entry);
            return acc;
        }, {});
        const sortedModIds = Object.keys(groupedTaskKinds).sort((a, b) => {
            const aCount = groupedTaskKinds[a].reduce((sum, entry) => sum + (entry.count || 0), 0);
            const bCount = groupedTaskKinds[b].reduce((sum, entry) => sum + (entry.count || 0), 0);
            return bCount - aCount;
        });
        const toggleTaskMod = (modId) => {
            setExpandedTaskMods((prev) => {
                const next = new Set(prev);
                if (next.has(modId)) {
                    next.delete(modId);
                } else {
                    next.add(modId);
                }
                return next;
            });
        };
        const groupedCaches = caches.reduce((acc, cache) => {
            const fullName = cache.name || "unknown.cache";
            const dotIndex = fullName.indexOf(".");
            let modId = dotIndex > 0 ? fullName.slice(0, dotIndex) : "quantified";
            const cacheName = dotIndex > 0 ? fullName.slice(dotIndex + 1) : fullName;
            if (!modId || modId === "unknown") {
                modId = "quantified";
            }
            if (!acc[modId]) {
                acc[modId] = [];
            }
            acc[modId].push({ ...cache, cacheName, modId });
            return acc;
        }, {});
        const sortedCacheModIds = Object.keys(groupedCaches).sort((a, b) => {
            const aEntries = groupedCaches[a].reduce((sum, entry) => sum + (entry.entries || 0), 0);
            const bEntries = groupedCaches[b].reduce((sum, entry) => sum + (entry.entries || 0), 0);
            return bEntries - aEntries;
        });
        const toggleCacheMod = (modId) => {
            setExpandedCacheMods((prev) => {
                const next = new Set(prev);
                if (next.has(modId)) {
                    next.delete(modId);
                } else {
                    next.add(modId);
                }
                return next;
            });
        };
        return html`<section className="view resources-view">
            <${Card}
                title="Resource Controls"
                actions=${html`<div className="card-actions">
                    <button className="btn btn-ghost" disabled=${resourceBusy} onClick=${fetchResources}>Refresh</button>
                </div>`}
            >
                ${summaryMetrics.length
                    ? html`<div className="metrics-row">
                          ${summaryMetrics.map((metric, idx) => html`<${Metric} key=${idx} label=${metric.label} value=${metric.value} sub=${metric.sub} />`)}
                      </div>`
                    : html`<p className="text-muted">Loading resource telemetry...</p>`}
                <div className="resource-controls">
                    <button className="btn btn-primary" disabled=${resourceBusy} onClick=${() => flushResourceTarget("ram")}>Flush RAM</button>
                    <button className="btn btn-primary" disabled=${resourceBusy} onClick=${() => flushResourceTarget("vram")}>Flush VRAM</button>
                    <button className="btn btn-secondary" disabled=${resourceBusy} onClick=${clearStressCache}>Clear Stress Cache</button>
                    <button className="btn btn-danger" disabled=${resourceBusy || !diskFiles.length} onClick=${clearDiskCache}>Clear Disk Cache</button>
                </div>
            </${Card}>
            <${Card} title="Per-Mod Usage">
                ${modResourceList.length
                    ? html`<div className="table-scroll">
                          <table className="data-table">
                              <thead>
                                  <tr>
                                      <th>Mod</th>
                                      <th>Queue</th>
                                      <th>RAM</th>
                                      <th>VRAM (peak)</th>
                                      <th>Disk</th>
                                      <th>Status</th>
                                      <th>Actions</th>
                                  </tr>
                              </thead>
                              <tbody>
                                  ${modResourceList.map(
                                      (mod) => html`<tr key=${mod.modId}>
                                          <td>
                                              <div className="stacked">
                                                  <strong>${mod.displayName || mod.modId}</strong>
                                                  <span className="text-muted">${mod.modId}</span>
                                              </div>
                                          </td>
                                          <td>${formatNumber(mod.queueDepth ?? 0)}</td>
                                          <td>${formatBytes(mod.ramBytes ?? 0)}</td>
                                          <td>${formatBytes(mod.peakVramBytes ?? 0)}</td>
                                          <td>${formatBytes(mod.diskBytes ?? 0)}</td>
                                          <td>
                                              <${StatusPill}
                                                  label=${mod.online ? (mod.active ? "Active" : "Idle") : "Offline"}
                                                  variant=${mod.online ? (mod.active ? "ok" : "warn") : "error"}
                                              />
                                          </td>
                                          <td>
                                              <button className="btn btn-ghost" disabled=${resourceBusy} onClick=${() => purgeModCache(mod.modId)}>
                                                  Purge Cache
                                              </button>
                                          </td>
                                      </tr>`
                                  )}
                              </tbody>
                          </table>
                      </div>`
                    : html`<p className="text-muted">No mods reported resource usage yet.</p>`}
              </${Card}>
              <${Card} title="CPU Task Kinds" actions=${html`<span className="card-note">${taskWindowLabel}</span>`}>
                  ${taskKinds.length
                      ? html`<div className="task-kind-list">
                            ${sortedModIds.map((modId) => {
                                const entries = groupedTaskKinds[modId] || [];
                                const totalTasks = entries.reduce((sum, entry) => sum + (entry.count || 0), 0);
                                const totalBatches = entries.reduce((sum, entry) => sum + (entry.batchCount || 0), 0);
                                const totalBatchSum = entries.reduce(
                                    (sum, entry) => sum + (entry.batchAvg || 0) * (entry.batchCount || 0),
                                    0
                                );
                                const maxBatch = entries.reduce((max, entry) => Math.max(max, entry.batchMax || 0), 0);
                                const avgBatch = totalBatches ? totalBatchSum / totalBatches : 0;
                                const expanded = expandedTaskMods.has(modId);
                                const modLabel = resolveModLabel(modId);
                                return html`<div className="task-kind-mod" key=${modId}>
                                    <button className="task-kind-header" onClick=${() => toggleTaskMod(modId)}>
                                        <div className="stacked">
                                            <strong>${modLabel}</strong>
                                            <span className="text-muted">${modId}</span>
                                        </div>
                                        <div className="task-kind-summary">
                                            <span>${formatNumber(totalTasks)} tasks</span>
                                            ${totalBatches
                                                ? html`<span>${formatNumber(totalBatches)} batches / avg ${formatDecimal(avgBatch)} / max ${formatNumber(maxBatch)}</span>`
                                                : html`<span className="text-muted">No batches yet</span>`}
                                        </div>
                                        <span className="task-kind-toggle">${expanded ? "-" : "+"}</span>
                                    </button>
                                    ${expanded
                                        ? html`<div className="task-kind-entries">
                                              ${entries.map(
                                                  (entry, idx) => html`<div className="list-row" key=${idx}>
                                                      <div className="stacked">
                                                          <strong>${entry.taskName}</strong>
                                                      </div>
                                                      <div className="stacked right">
                                                          <span>${entry.route}</span>
                                                          <span className="text-muted">${formatNumber(entry.count || 0)} tasks</span>
                                                          ${entry.batchCount
                                                              ? html`<span className="text-muted">
                                                                    ${formatNumber(entry.batchCount)} batches / avg ${formatDecimal(entry.batchAvg)} / max ${formatNumber(entry.batchMax || 0)}
                                                                </span>`
                                                              : null}
                                                      </div>
                                                  </div>`
                                              )}
                                          </div>`
                                        : null}
                                </div>`;
                            })}
                        </div>`
                      : html`<p className="text-muted">No task activity recorded yet.</p>`}
              </${Card}>
              <${Card} title="Cache Breakdown">
                ${caches.length
                    ? html`<div className="cache-breakdown-list">
                          ${sortedCacheModIds.map((modId) => {
                              const entries = groupedCaches[modId] || [];
                              const totalEntries = entries.reduce((sum, entry) => sum + (entry.entries || 0), 0);
                              const totalHits = entries.reduce((sum, entry) => sum + (entry.hitCount || 0), 0);
                              const totalMisses = entries.reduce((sum, entry) => sum + (entry.missCount || 0), 0);
                              const totalEvictions = entries.reduce((sum, entry) => sum + (entry.evictions || 0), 0);
                              const hitRate = totalHits + totalMisses > 0 ? totalHits / (totalHits + totalMisses) : 0;
                              const expanded = expandedCacheMods.has(modId);
                              const modLabel = resolveModLabel(modId);
                              return html`<div className="cache-mod" key=${modId}>
                                  <button className="cache-header" onClick=${() => toggleCacheMod(modId)}>
                                      <div className="stacked">
                                          <strong>${modLabel}</strong>
                                          <span className="text-muted">${modId}</span>
                                      </div>
                                      <div className="cache-summary">
                                          <span>${formatNumber(totalEntries)} entries</span>
                                          <span>Hit ${formatPercent(hitRate)}</span>
                                          <span>${formatNumber(totalEvictions)} evictions</span>
                                      </div>
                                      <span className="cache-toggle">${expanded ? "-" : "+"}</span>
                                  </button>
                                  ${expanded
                                      ? html`<div className="cache-entries">
                                            ${entries.map(
                                                (cache, idx) => html`<div className="cache-row" key=${idx}>
                                                    <div className="stacked">
                                                        <strong>${cache.cacheName}</strong>
                                                        <span className="text-muted">${formatNumber(cache.entries)} entries</span>
                                                    </div>
                                                    <div className="cache-stats">
                                                        <span>Hit ${formatPercent(cache.hitRate)}</span>
                                                        <span>${formatNumber(cache.hitCount || 0)} hits</span>
                                                        <span>${formatNumber(cache.missCount || 0)} misses</span>
                                                    </div>
                                                </div>`
                                            )}
                                        </div>`
                                      : null}
                              </div>`;
                          })}
                      </div>`
                    : html`<p className="text-muted">Cache stats will appear once the API collects inventory data.</p>`}
            </${Card}>
            <${Card}
                title="Disk Cache Manager"
                actions=${html`<div className="card-actions">
                    <span>${formatNumber(selectedCount)} selected</span>
                    <button className="btn btn-primary" disabled=${resourceBusy || !selectedCount} onClick=${() => deleteSelectedFiles(selectedFilePayload)}>
                        Delete Selected
                    </button>
                </div>`}
            >
                <div className="table-actions">
                    <button className="btn btn-secondary" disabled=${resourceBusy} onClick=${fetchResources}>Reload Inventory</button>
                </div>
                <div className="table-scroll tall">
                    <table className="data-table">
                        <thead>
                            <tr>
                                <th></th>
                                <th>Mod</th>
                                <th>File</th>
                                <th>Size</th>
                                <th>Modified</th>
                                <th>Usage</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${diskFiles.length
                                ? diskFiles.map((file, idx) => {
                                      const key = `${file.modId}::${file.file}`;
                                      return html`<tr key=${idx}>
                                          <td>
                                              <input
                                                  type="checkbox"
                                                  checked=${selectedFiles.has(key)}
                                                  onChange=${() => toggleFileSelection(key)}
                                              />
                                          </td>
                                          <td>
                                              <div className="stacked">
                                                  <strong>${file.modId}</strong>
                                                  <span className="text-muted">${file.modOnline ? "Mod online" : "Offline"}</span>
                                              </div>
                                          </td>
                                          <td>${file.file}</td>
                                          <td>${formatBytes(file.sizeBytes ?? 0)}</td>
                                          <td>${formatTime(file.lastModified)}</td>
                                          <td>
                                              <${StatusPill} label=${file.modOnline ? "In Use" : "Cold"} variant=${file.modOnline ? "warn" : "ok"} />
                                          </td>
                                      </tr>`;
                                  })
                                : html`<tr>
                                      <td colspan="6">
                                          <p className="text-muted">No disk cache files detected.</p>
                                      </td>
                                  </tr>`}
                        </tbody>
                    </table>
                </div>
            </${Card}>
        </section>`;
    };

    const renderLogs = () =>
        html`<section className="view logs-view">
            <div className="card-grid two">
                <${Card} title="Timeline Events">
                    <div className="timeline">
                        ${timelineEvents.length
                            ? timelineEvents.map(
                                  (event, idx) => html`<div className="timeline-event" key=${idx}>
                                      <div>
                                          <strong>${formatTime(event.timestamp)}</strong>
                                          <span className="text-muted">${event.type}</span>
                                      </div>
                                      <p>${event.message}</p>
                                      <div className="timeline-meta">
                                          <span>Queue ${formatNumber(event.queueDepth ?? 0)}</span>
                                          <span>GPU ${formatPercent(event.gpuComputeUtil)}</span>
                                      </div>
                                  </div>`
                              )
                            : html`<p className="text-muted">No timeline entries yet.</p>`}
                    </div>
                </${Card}>
                <${Card}
                    title="API Log"
                    actions=${html`<button className="btn btn-ghost" onClick=${downloadHistory}>Download History</button>`}
                >
                    <div className="log-window api-log">
                        ${logs.length ? logs.map((line, idx) => renderApiLogLine(line, idx, modColorMap)) : html`<p className="text-muted">No log lines yet.</p>`}
                    </div>
                </${Card}>
            </div>
        </section>`;

    const toggleDefinitions = [
        { key: "developerMode", label: "Developer Mode", description: "Expose experimental instrumentation" },
        { key: "dashboardEnabled", label: "Dashboard Server", description: "Serve this panel" },
        { key: "timelineEnabled", label: "Timeline Capture", description: "Record telemetry timeline" },
        { key: "replayEnabled", label: "Replay Frames", description: "Allow telemetry playback" },
        { key: "stressTestEnabled", label: "Stress Harness", description: "Keep stress testing utilities active" },
        { key: "modSpotlightEnabled", label: "Mod Spotlight", description: "Highlight heavy mods automatically" },
    ];

    const renderControls = () => {
        const stressProfiles = state?.stressProfiles ?? [];
        const selectedProfile = state?.stressTestProfile ?? (stressProfiles[0]?.key ?? "");
        const selectedProfileLabel =
            stressProfiles.find((option) => option.key === selectedProfile)?.label ?? state?.stressTestProfileLabel ?? "";
        return html`<section className="view controls-view">
            <div className="card-grid two">
                <${Card} title="Feature Toggles">
                    <div className="toggle-list">
                        ${toggleDefinitions.map((toggle) =>
                            html`<${ToggleRow}
                                key=${toggle.key}
                                label=${toggle.label}
                                description=${toggle.description}
                                value=${state ? state[toggle.key] : false}
                                disabled=${busy || !state}
                                onChange=${(value) => updateToggles({ [toggle.key]: value })}
                            />`
                        )}
                    </div>
                </${Card}>
                <${Card} title="Stress & Diagnostics">
                    <div className="metrics-row">
                        <${Metric} label="Stress Cycles" value=${formatNumber(state?.stressTestCycleCount ?? 0)} sub="Completed runs" />
                        <${Metric} label="GPU Tasks" value=${formatNumber(state?.gpuTestComputationCount ?? 0)} sub="Stress GPU ops" />
                        <${Metric} label="CPU Tasks" value=${formatNumber(state?.cpuTestComputationCount ?? 0)} sub="Stress CPU ops" />
                    </div>
                    <div className="form-row">
                        <label className="form-label" htmlFor="stress-profile-select">Stress Profile</label>
                        <select
                            id="stress-profile-select"
                            className="select-input"
                            value=${selectedProfile}
                            disabled=${busy || !stressProfiles.length}
                            onChange=${(event) => updateStressProfileSelection(event.target.value)}
                        >
                            ${stressProfiles.length
                                ? stressProfiles.map((option) => html`<option value=${option.key} key=${option.key}>${option.label}</option>`)
                                : html`<option value="">No profiles available</option>`}
                        </select>
                    </div>
                    <p className="text-muted">
                        ${selectedProfileLabel
                            ? `${selectedProfileLabel}. Manual cycle runs every profile for ~10s.`
                            : "Manual cycle runs every profile for ~10s."}
                    </p>
                    <div className="action-row">
                        <button className="btn btn-primary" disabled=${busy || !state} onClick=${runStressCycle}>Run Stress Cycle</button>
                        <button className="btn btn-secondary" disabled=${busy} onClick=${clearStressCache}>Clear Stress Cache</button>
                        <button className="btn btn-ghost" disabled=${busy} onClick=${exportDiagnostics}>Export Diagnostics</button>
                    </div>
                </${Card}>
            </div>
            <${Card} title="Data Export & Tools">
                <div className="action-row">
                    <button className="btn btn-primary" onClick=${downloadHistory}>Download History JSON</button>
                    <button className="btn btn-secondary" onClick=${exportDiagnostics}>Export Live Snapshot</button>
                </div>
            </${Card}>
        </section>`;
    };

    const renderConfig = () => {
        if (configLoading && !configGroups.length) {
            return html`<section className="view config-view"><p className="text-muted">Loading config metadata...</p></section>`;
        }
        if (!configGroups.length) {
            return html`<section className="view config-view"><p className="text-muted">No configuration metadata available.</p></section>`;
        }
        return html`<section className="view config-view">
            <div className="config-grid">
                ${configGroups.map((group) => {
                    if (!group?.fields?.length) {
                        return null;
                    }
                    return html`<${Card} title=${group.name} key=${group.name}>
                        <div className="config-fields">
                            ${group.fields.map((field) => {
                                const originalValue = field.value;
                                const value = Object.prototype.hasOwnProperty.call(configEdits, field.key) ? configEdits[field.key] : field.value;
                                if (field.type === "boolean") {
                                    return html`<div className="config-field" key=${field.key}>
                                        <div className="config-label">
                                            <strong>${field.label}</strong>
                                            ${field.comment ? html`<p className="config-comment">${field.comment}</p>` : null}
                                        </div>
                                        <${ToggleRow}
                                            label=""
                                            value=${Boolean(value)}
                                            onChange=${(next) => updateConfigValue(field.key, originalValue, next, { delay: 0 })}
                                        />
                                    </div>`;
                                }
                                if (field.type === "select") {
                                    const options = Array.isArray(field.options) ? field.options : [];
                                    const hasCurrent = options.some((option) => option.value === value);
                                    const mergedOptions = hasCurrent || value == null
                                        ? options
                                        : [{ value, label: `${value} (current)` }, ...options];
                                    return html`<div className="config-field" key=${field.key}>
                                        <div className="config-label">
                                            <strong>${field.label}</strong>
                                            ${field.comment ? html`<p className="config-comment">${field.comment}</p>` : null}
                                        </div>
                                        <select
                                            value=${value ?? ""}
                                            onChange=${(event) => {
                                                const nextValue = event.target.value;
                                                const selected = mergedOptions.find((option) => option.value === nextValue);
                                                const expectedDeviceName = field.key === "openclDeviceId" && selected && nextValue !== "auto"
                                                    ? String(selected.label || "").split(" (")[0].trim()
                                                    : "";
                                                updateConfigValue(field.key, originalValue, nextValue, { delay: 0, expectedDeviceName });
                                            }}
                                        >
                                            ${mergedOptions.map((option) => html`<option value=${option.value}>${option.label}</option>`)}
                                        </select>
                                    </div>`;
                                }
                                if (field.type === "number") {
                                    return html`<div className="config-field" key=${field.key}>
                                        <div className="config-label">
                                            <strong>${field.label}</strong>
                                            ${field.comment ? html`<p className="config-comment">${field.comment}</p>` : null}
                                        </div>
                                        <${NumericInput}
                                            value=${Number(value ?? 0)}
                                            onCommit=${(next) => updateConfigValue(field.key, originalValue, next, { delay: 0 })}
                                        />
                                    </div>`;
                                }
                                if (field.type === "list") {
                                    const display = Array.isArray(value) ? value.join("\n") : "";
                                    return html`<div className="config-field" key=${field.key}>
                                        <div className="config-label">
                                            <strong>${field.label}</strong>
                                            ${field.comment ? html`<p className="config-comment">${field.comment}</p>` : null}
                                        </div>
                                        <textarea
                                            rows="4"
                                            value=${display}
                                            onInput=${(event) => {
                                                const next = event.target.value
                                                    .split(/\r?\n/)
                                                    .map((line) => line.trim())
                                                    .filter(Boolean);
                                                updateConfigValue(field.key, originalValue, next, { delay: 650 });
                                            }}
                                        ></textarea>
                                    </div>`;
                                }
                                return html`<div className="config-field" key=${field.key}>
                                    <div className="config-label">
                                        <strong>${field.label}</strong>
                                        ${field.comment ? html`<p className="config-comment">${field.comment}</p>` : null}
                                    </div>
                                    <input
                                        type="text"
                                        value=${value ?? ""}
                                        onInput=${(event) => updateConfigValue(field.key, originalValue, event.target.value, { delay: 650 })}
                                    />
                                </div>`;
                            })}
                        </div>
                    </${Card}>`;
                })}
            </div>
            <div className="config-actions">
                <button className="btn btn-secondary" onClick=${fetchConfigLayout}>Reload Layout</button>
            </div>
        </section>`;
    };

    const renderSystem = () => {
        const ramUsed = systemInfo.ramUsedBytes ?? 0;
        const ramTotal = systemInfo.ramTotalBytes ?? 0;
        const ramAvailable = systemInfo.ramAvailableBytes ?? 0;
        return html`<section className="view system-view">
            <div className="card-grid two">
                <${Card} title="Hardware Overview">
                    <ul className="system-list">
                        <li><span>Operating System</span><strong>${systemInfo.os || "-"}</strong></li>
                        <li><span>CPU</span><strong>${systemInfo.cpu || "-"}</strong></li>
                        <li><span>GPU</span><strong>${systemInfo.gpu || "-"}</strong></li>
                        <li><span>RAM</span><strong>${systemInfo.ram || "-"}</strong></li>
                    </ul>
                </${Card}>
                <${Card} title="Sensors">
                    <div className="metrics-row">
                        <${Metric}
                            label="CPU Temp"
                            value=${Number.isFinite(systemInfo.cpuTemperature) && systemInfo.cpuTemperature > 0
                                ? formatTemperature(systemInfo.cpuTemperature)
                                : "N/A"}
                            sub=${Number.isFinite(systemInfo.cpuTemperature) && systemInfo.cpuTemperature > 0
                                ? "Reported by OSHI"
                                : "No sensor data"}
                        />
                        <${Metric}
                            label="GPU Temp"
                            value=${Number.isFinite(systemInfo.gpuTemperature) && systemInfo.gpuTemperature > 0
                                ? formatTemperature(systemInfo.gpuTemperature)
                                : "N/A"}
                            sub=${Number.isFinite(systemInfo.gpuTemperature) && systemInfo.gpuTemperature > 0
                                ? "Overlay snapshot"
                                : "No sensor data"}
                        />
                        <${Metric} label="RAM Used" value=${formatBytes(ramUsed)} sub=${`${formatBytes(ramTotal)} total`} />
                        <${Metric} label="RAM Free" value=${formatBytes(ramAvailable)} sub="Available" />
                    </div>
                </${Card}>
            </div>
            <div className="card-grid two">
                <${Card} title="GPU Inventory">
                    ${gpuInventory.length
                        ? html`<div className="list">
                              ${gpuInventory.map(
                                  (gpu, idx) => html`<div className="list-row" key=${idx}>
                                        <div>
                                            <strong>GPU ${idx} (${gpu.typeLabel})</strong>
                                            <div>${gpu.name}</div>
                                            <div className="text-muted">${gpu.vendor}</div>
                                        </div>
                                        <span>${formatBytes(gpu.vramBytes || 0)}</span>
                                    </div>`
                                )}
                            </div>`
                        : html`<p className="text-muted">GPU inventory not reported by OSHI.</p>`}
                </${Card}>
                <${Card} title="Storage">
                    ${storageVolumes.length
                        ? html`<div className="list">
                              ${storageVolumes.map(
                                    (drive, idx) => html`<div className="list-row" key=${idx}>
                                        <div>
                                            <strong>${drive.path || drive.name || drive.serial}</strong>
                                            <span className="text-muted">${drive.type || "Volume"}</span>
                                        </div>
                                        <div className="stacked right">
                                            <span>${drive.total || formatBytes(drive.sizeBytes || 0)} total</span>
                                            <span className="text-muted">${drive.free || "-"} free</span>
                                        </div>
                                    </div>`
                                )}
                            </div>`
                        : html`<p className="text-muted">Volume details unavailable.</p>`}
                    ${physicalDisks.length
                        ? html`<div className="list dense">
                                ${physicalDisks.map(
                                    (disk, idx) => html`<div className="list-row" key=${`disk-${idx}`}>
                                        <div>
                                            <strong>${cleanDiskLabel(disk)}</strong>
                                            <span className="text-muted">Physical disk</span>
                                        </div>
                                        <div className="stacked right">
                                            <span>${formatBytes(disk.sizeBytes || 0)}</span>
                                            <span className="text-muted">${formatNumber(disk.reads || 0)} reads / ${formatNumber(disk.writes || 0)} writes</span>
                                        </div>
                                    </div>`
                                )}
                            </div>`
                        : null}
                </${Card}>
            </div>
            <${Card} title="Usage Trends">
                <div className="chart-pane">
                    <${MultiLineChart}
                        series=${[
                            { values: historySeries.vram, color: "#8b5cf6" },
                            { values: historySeries.vramBudget, color: "#f472b6" },
                        ]}
                    />
                </div>
                <div className="chart-legend">
                    <span>API VRAM (GB)</span>
                    <span>Budget (GB)</span>
                </div>
                <div className="chart-pane">
                    <${MultiLineChart}
                        series=${[
                            { values: historySeries.cacheRam, color: "#34d399" },
                            { values: historySeries.cacheDisk, color: "#facc15" },
                        ]}
                    />
                </div>
                <div className="chart-legend">
                    <span>RAM cache (GB)</span>
                    <span>Disk cache (GB)</span>
                </div>
            </${Card}>
        </section>`;
    };

    const renderActiveView = () => {
        switch (activeView) {
            case "resources":
                return renderResources();
            case "logs":
                return renderLogs();
            case "controls":
                return renderControls();
            case "config":
                return renderConfig();
            case "system":
                return renderSystem();
            default:
                return renderOverview();
        }
    };

    const overlay = loadingState.showOverlay
        ? html`<div className=${`loading-screen ${loadingState.overlayStage === "fading" ? "fade-out" : ""}`}>
              <div className="loading-content">
                  <h1>Quantified API</h1>
                  <div className="loading-bar">
                      <div className="loading-progress"></div>
                  </div>
                  ${loadingState.loadError ? html`<div className="loading-error">${loadingState.loadError}</div>` : null}
              </div>
          </div>`
        : null;

      const applyThemeOverride = (value) => {
          if (value === "auto") {
              setThemeOverride(null);
              if (typeof window !== "undefined" && window.matchMedia) {
                  const prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
                  setTheme(prefersDark ? "dark" : "light");
              }
              return;
          }
          setThemeOverride(value);
          setTheme(value);
      };

      return html`<div className="dashboard-shell">
          <div className="theme-toggle">
              <button
                  className=${`theme-toggle__btn ${theme === "light" ? "active" : ""}`}
                  onClick=${() => applyThemeOverride("light")}
                  aria-label="Use light theme"
                  title="Light theme"
              >
                  <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                      <circle cx="12" cy="12" r="4.5"></circle>
                      <path d="M12 2.5v2.5M12 19v2.5M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M2.5 12h2.5M19 12h2.5M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8"></path>
                  </svg>
              </button>
              <button
                  className=${`theme-toggle__btn ${theme === "dark" ? "active" : ""}`}
                  onClick=${() => applyThemeOverride("dark")}
                  aria-label="Use dark theme"
                  title="Dark theme"
              >
                  <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                      <path d="M20.5 14.7a7.8 7.8 0 0 1-10.2-10.2 8.6 8.6 0 1 0 10.2 10.2z"></path>
                  </svg>
              </button>
              <button
                  className=${`theme-toggle__btn ${themeOverride ? "" : "active"}`}
                  onClick=${() => applyThemeOverride("auto")}
                  aria-label="Use system theme"
                  title="System theme"
              >
                  <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                      <path d="M3 5.5h18M6 9h12M8 12.5h8M6 16h12M9 19.5h6"></path>
                  </svg>
              </button>
        </div>
        ${overlay}
        <div className="toast-stack">
            ${toasts.map((toast) => html`
                <div className=${`toast toast-${toast.tone || "info"}`} key=${toast.id}>
                    ${toast.message}
                </div>
            `)}
        </div>
        <div className="dashboard-layout">
              <${Sidebar} activeView=${activeView} onSelect=${setActiveView} statusEntries=${statusEntries} />
            <main className="main-panel">
                <header className="main-header">
                    <div>
                        <h1>Quantified API Webpanel</h1>
                        <p>Live API status with comprehensive mod and API metrics, diagnostics, development tools, tuning recommendations, in-game controls, and real-time monitoring.</p>
                    </div>
                    <div className="header-meta">
                        <span>Last updated ${lastUpdated ? formatTime(lastUpdated) : "waiting..."}</span>
                        <${StatusPill} label=${state?.dashboardEnabled ? "API Online" : "Offline"} variant=${state?.dashboardEnabled ? "ok" : "error"} />
                    </div>
                </header>
                ${error ? html`<div className="error-banner">${error}</div>` : null}
                ${renderActiveView()}
            </main>
        </div>
    </div>`;
};

const container = document.getElementById("root");
if (container) {
    const root = createRoot(container);
    root.render(html`<${App} />`);
}







