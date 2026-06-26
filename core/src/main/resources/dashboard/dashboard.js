import React from "https://esm.sh/react@18.3.1";
import { createRoot } from "https://esm.sh/react-dom@18.3.1/client";
import htm from "https://esm.sh/htm@3.1.1";

const { useCallback, useEffect, useMemo, useRef, useState } = React;
const html = htm.bind(React.createElement);

const NAV_ITEMS = [
    { id: "overview", label: "Overview" },
    { id: "modMetrics", label: "Mod Metrics" },
    { id: "resources", label: "Resources" },
    { id: "logs", label: "Logs" },
    { id: "controls", label: "Controls" },
    { id: "config", label: "Config" },
    { id: "system", label: "System" },
];

const REFRESH_INTERVAL_MS = 2000;
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

const BarChart = ({ values = [], maxBars = 14 }) => {
    const safeValues = Array.isArray(values) && values.length ? values.slice(-maxBars) : [0];
    const maxValue = Math.max(1, ...safeValues);
    const normalized = safeValues.map((value) => Math.max(4, (Number(value || 0) / maxValue) * 100));
    const [animated, setAnimated] = useState(() => normalized.map(() => 4));
    const previousRef = useRef(normalized.map(() => 4));

    useEffect(() => {
        const prefersReducedMotion =
            typeof window !== "undefined" &&
            window.matchMedia &&
            window.matchMedia("(prefers-reduced-motion: reduce)").matches;

        if (prefersReducedMotion) {
            setAnimated(normalized);
            previousRef.current = normalized;
            return;
        }

        const from = previousRef.current.length === normalized.length ? previousRef.current : normalized.map(() => 4);
        const duration = 360;
        let raf = 0;
        let start = 0;

        const tick = (timestamp) => {
            if (!start) {
                start = timestamp;
            }
            const progress = Math.min(1, (timestamp - start) / duration);
            const eased = 1 - Math.pow(1 - progress, 3);
            const next = normalized.map((value, index) => from[index] + (value - from[index]) * eased);
            setAnimated(next);

            if (progress < 1) {
                raf = requestAnimationFrame(tick);
            } else {
                previousRef.current = normalized;
            }
        };

        raf = requestAnimationFrame(tick);
        return () => cancelAnimationFrame(raf);
    }, [normalized.join(",")]);

    return html`<div className="bar-chart" role="img" aria-label="Queue trend bar chart">
        ${animated.map(
            (height, index) => html`<div className="bar-chart-col" key=${index}>
                <span className="bar-chart-bar" style=${{ height: `${height.toFixed(2)}%` }}></span>
            </div>`
        )}
    </div>`;
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

function formatGpuBackendLabel(value) {
    const backend = String(value || "CPU").toUpperCase();
    if (backend === "VULKAN") {
        return "VULKAN";
    }
    if (backend === "OPENCL") {
        return "OpenCL";
    }
    return "CPU";
}

function gpuBackendPillVariant(value) {
    const backend = String(value || "CPU").toUpperCase();
    if (backend === "VULKAN") {
        return "vulkan";
    }
    if (backend === "OPENCL") {
        return "opencl";
    }
    return "cpu";
}

function normalizeLoadRatio(value) {
    const numeric = Number(value);
    if (!Number.isFinite(numeric) || numeric <= 0) {
        return 0;
    }
    if (numeric > 1.5) {
        return Math.min(1, numeric / 100);
    }
    return Math.min(1, numeric);
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

function getHintAction(hint) {
    const message = String(hint?.message || "").toLowerCase();
    if (message.includes("queue") || message.includes("backlog")) {
        return "Reduce burst size or raise worker budget gradually.";
    }
    if (message.includes("vram") || message.includes("gpu")) {
        return "Lower GPU-heavy workload or clear stale cache blocks.";
    }
    if (message.includes("cache")) {
        return "Trim cache TTL and purge cold cache groups.";
    }
    if (message.includes("temperature") || message.includes("overheating")) {
        return "Throttle heavy tasks until thermal headroom recovers.";
    }
    return "Monitor this signal and keep current policy under review.";
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

const NavIcon = ({ id }) => {
    switch (id) {
        case "overview":
            return html`<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 12.5 12 4l9 8.5M6.5 11.5V20h11V11.5"/></svg>`;
        case "resources":
            return html`<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="4" width="16" height="7" rx="2"/><rect x="4" y="13" width="16" height="7" rx="2"/></svg>`;
        case "modMetrics":
            return html`<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 19V5M4 19h16"/><rect x="7" y="11" width="3" height="5" rx="1"/><rect x="12" y="7" width="3" height="9" rx="1"/><rect x="17" y="9" width="3" height="7" rx="1"/></svg>`;
        case "logs":
            return html`<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 4h10l3 3v13H7z"/><path d="M14 4v3h3M10 12h7M10 16h7"/></svg>`;
        case "controls":
            return html`<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 8h9M16 8h3M5 16h3M10 16h9"/><circle cx="13" cy="8" r="2"/><circle cx="8" cy="16" r="2"/></svg>`;
        case "config":
            return html`<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3"/><path d="M19 12a7 7 0 0 0-.1-1l2-1.5-2-3.5-2.3.9a8 8 0 0 0-1.7-1L14.5 3h-5L9 5.9a8 8 0 0 0-1.7 1L5 6l-2 3.5L5 11a7 7 0 0 0 0 2l-2 1.5L5 18l2.3-.9c.5.4 1.1.8 1.7 1l.5 2.9h5l.5-2.9c.6-.2 1.2-.6 1.7-1l2.3.9 2-3.5-2-1.5c.1-.3.1-.7.1-1z"/></svg>`;
        case "system":
            return html`<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="5" width="16" height="11" rx="2"/><path d="M9 20h6M12 16v4"/></svg>`;
        default:
            return html`<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/></svg>`;
    }
};

const Sidebar = ({ activeView, onSelect }) =>
    html`<aside className="sidebar sidebar-rail">
        <div className="rail-brand">
            <img
                src="/logo_white.png"
                alt=""
                aria-hidden="true"
                onError=${(event) => {
                    const img = event.currentTarget;
                    if (!img.dataset.try1) {
                        img.dataset.try1 = "1";
                        img.src = "/dashboard/logo_white.png";
                        return;
                    }
                    if (!img.dataset.try2) {
                        img.dataset.try2 = "1";
                        img.src = "/dashboard-logo.png";
                        return;
                    }
                    if (!img.dataset.try3) {
                        img.dataset.try3 = "1";
                        img.src = "/favicon.ico";
                    }
                }}
            />
        </div>
        <nav className="sidebar-nav" aria-label="Dashboard navigation">
            ${NAV_ITEMS.map(
                (item) => html`<button
                    key=${item.id}
                    className=${`nav-item ${item.id === activeView ? "active" : ""}`}
                    onClick=${() => onSelect(item.id)}
                    title=${item.label}
                    aria-label=${item.label}
                    data-tooltip=${item.label}
                >
                    <span className="nav-item-icon"><${NavIcon} id=${item.id} /></span>
                    <span className="sr-only">${item.label}</span>
                </button>`
            )}
        </nav>
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
    const [configQuery, setConfigQuery] = useState("");
    const [activeConfigGroup, setActiveConfigGroup] = useState(null);
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
    const [modMetrics, setModMetrics] = useState(null);
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

    useEffect(() => {
        if (!configGroups.length) return;
        if (!activeConfigGroup || !configGroups.some((group) => group.name === activeConfigGroup)) {
            setActiveConfigGroup(configGroups[0].name);
        }
    }, [configGroups, activeConfigGroup]);

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
            const cpuSystemLoad = normalizeLoadRatio(snapshot.cpuSystemLoad ?? nextState?.cpuSystemLoad ?? 0);
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
                cpuCompute: normalizeLoadRatio(snapshot.cpuComputeUtil ?? snapshot.cpuSystemLoad ?? 0),
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
        let cancelled = false;
        let refreshInFlight = false;
        const tick = async () => {
            if (cancelled || refreshInFlight || document.hidden) {
                return;
            }
            refreshInFlight = true;
            try {
                await loadAll();
                const activeMod = selectedModRef.current;
                if (activeMod) {
                    await loadModStats(activeMod);
                }
            } finally {
                refreshInFlight = false;
            }
        };

        tick();
        const interval = window.setInterval(tick, REFRESH_INTERVAL_MS);

        const minLoadingTimer = setTimeout(() => {
            setLoadingState((prev) => ({ ...prev, minElapsed: true }));
        }, shouldPlay ? 700 : 250);

        const deadlineTimer = setTimeout(() => {
            setLoadingState((prev) => ({ ...prev, deadlineHit: true }));
        }, 8000);

        return () => {
            cancelled = true;
            window.clearInterval(interval);
            clearTimeout(minLoadingTimer);
            clearTimeout(deadlineTimer);
        };
    }, [loadAll, loadModStats, shouldPlay]);

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

    const fetchModMetrics = useCallback(async () => {
        try {
            const payload = await fetchJson("/api/v1/mod-metrics");
            setModMetrics(payload);
            setError(null);
        } catch (err) {
            console.error(err);
            setError(err.message || "Failed to load mod metrics");
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
        if (activeView !== "modMetrics") {
            return undefined;
        }
        fetchModMetrics();
        const interval = window.setInterval(fetchModMetrics, REFRESH_INTERVAL_MS);
        return () => window.clearInterval(interval);
    }, [activeView, fetchModMetrics]);

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

    const readGpuRuntimeState = useCallback(async () => {
        const deadline = Date.now() + 6500;
        let lastState = null;
        const resolveGpuReason = (runtimeState) => {
            const backend = String(runtimeState?.activeGpuBackend || "CPU").toUpperCase();
            const preferred = String(runtimeState?.configuredGpuBackendPreference || "").toUpperCase();
            if (backend === "VULKAN") {
                return runtimeState?.vulkanFailureReason || "";
            }
            if (preferred.includes("VULKAN") && runtimeState?.vulkanFailureReason) {
                return runtimeState.vulkanFailureReason;
            }
            return runtimeState?.openclFailureReason || runtimeState?.vulkanFailureReason || "";
        };
        while (Date.now() < deadline) {
            const nextState = await fetchJson("/api/v1/dashboard/state");
            lastState = nextState;
            const backend = String(nextState.activeGpuBackend || "CPU").toUpperCase();
            const deviceName = nextState.activeGpuDeviceName || nextState.vulkanDeviceName || nextState.openclDeviceName || "";
            if (backend !== "CPU" || !nextState.enableGpuAcceleration) {
                return {
                    backend,
                    deviceName,
                    reason: resolveGpuReason(nextState),
                };
            }
            await new Promise((resolve) => setTimeout(resolve, 700));
        }
        return {
            backend: String(lastState?.activeGpuBackend || "CPU").toUpperCase(),
            deviceName: lastState?.activeGpuDeviceName || lastState?.vulkanDeviceName || lastState?.openclDeviceName || "",
            reason: resolveGpuReason(lastState),
        };
    }, []);

    const applyConfigUpdate = useCallback(async (key, value, options = {}) => {
        const isGpu = ["preferredGpuBackend", "vulkanDeviceId", "openclDeviceId", "enableGpuAcceleration"].includes(key);
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
                const runtime = await readGpuRuntimeState();
                const backendLabel = formatGpuBackendLabel(runtime.backend);
                const deviceSuffix = runtime.deviceName ? ` on ${runtime.deviceName}` : "";
                const detail = runtime.reason ? ` (${runtime.reason})` : "";
                const tone = runtime.backend === "CPU" ? "info" : "success";
                pushToast(`GPU config applied - active backend ${backendLabel}${deviceSuffix}${detail}.`, tone, 4600);
            } else {
                pushToast("Change applied", "success");
            }
            setError(null);
        } catch (err) {
            console.error(err);
            setError(err.message || "Failed to apply config change");
            pushToast("Change failed", "error", 3200);
        }
    }, [pushToast, readGpuRuntimeState, updateConfigGroupValue]);

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
    const activeGpuBackend = String(state?.activeGpuBackend ?? "CPU").toUpperCase();
    const activeGpuDeviceName = state?.activeGpuDeviceName || state?.vulkanDeviceName || state?.openclDeviceName || "";
    const activeGpuBackendLabel = formatGpuBackendLabel(activeGpuBackend);
    const activeGpuBackendVariant = gpuBackendPillVariant(activeGpuBackend);
    const cpuSystemLoad = normalizeLoadRatio(snapshot.cpuSystemLoad ?? state?.cpuSystemLoad ?? 0);
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
                detail: `${activeGpuBackendLabel} • ${formatPercent(gpuCompute)} compute`,
                message: activeGpuDeviceName
                    ? `${activeGpuDeviceName} • API VRAM ${formatPercent(vramRatio)} of ${formatBytes(vramBudgetBytes || 0)}`
                    : `API VRAM ${formatPercent(vramRatio)} of ${formatBytes(vramBudgetBytes || 0)}`,
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
        const cpuSeries = history.map((entry) => normalizeLoadRatio(entry.cpuSystemLoad ?? entry.cpuCompute ?? 0));
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
            cpu: withFallback(cpuSeries, cpuSystemLoad),
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
        cpuSystemLoad,
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
    const modMetricSummary = modMetrics?.summary ?? null;
    const modMetricRows = modMetrics?.mods ?? [];
    const modMetricTasks = modMetrics?.tasks ?? [];
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
        const filteredEvents = timelineEvents.slice(-12).reverse();
        const queueRatioSeries = historySeries.queue.map((value) => Math.max(0, Math.min(1, value / Math.max(1, queueThreshold))));
        const riskEntries = statusEntries.filter((entry) => entry.variant === "warn" || entry.variant === "error");
        const riskIntensity = (entry) => {
            if (entry.variant === "error") return 0.88;
            if (entry.variant === "warn") return 0.64;
            return 0.36;
        };
        const topMods = modResourceList.length
            ? modResourceList.slice(0, 5).map((mod) => ({
                  id: mod.displayName || mod.modId,
                  queue: formatNumber(mod.queueDepth ?? 0),
                  memory: formatBytes(Number(mod.ramBytes ?? 0) + Number(mod.diskBytes ?? 0)),
                  state: mod.online ? (mod.active ? "Active" : "Idle") : "Offline",
                  variant: mod.online ? (mod.active ? "ok" : "warn") : "error",
              }))
            : spotlight.slice(0, 5).map((mod) => ({
                  id: mod.modId,
                  queue: formatNumber(mod.tasksInFlight || 0),
                  memory: formatPercent(mod.cacheHitRate || 0),
                  state: "Live",
                  variant: "ok",
              }));
        const capacityRows = [
            {
                label: "RAM Cache",
                value: Number(ramCacheBytes),
                max: Math.max(1, Number(ramCacheBytes) + Number(diskCacheBytes)),
                summary: formatBytes(ramCacheBytes),
            },
            {
                label: "Disk Cache",
                value: Number(diskCacheBytes),
                max: Math.max(1, Number(ramCacheBytes) + Number(diskCacheBytes)),
                summary: formatBytes(diskCacheBytes),
            },
            {
                label: "VRAM",
                value: Number(vramUsedBytes),
                max: Math.max(1, Number(vramBudgetBytes) || Number(vramUsedBytes)),
                summary: `${formatBytes(vramUsedBytes)} / ${formatBytes(vramBudgetBytes || 0)}`,
            },
        ];

        return html`<section className="view overview-view">
            <div className="view-head">
                <h2>Operations Overview</h2>
                <p>Live performance, risks, and quick actions in one streamlined workspace.</p>
            </div>
            <div className="overview-shell">
                <div className="overview-column primary">
                    <div className="overview-kpi-grid">
                        <div className="card kpi-card">
                            <p className="kpi-label">Queue</p>
                            <h3>${formatNumber(queueDepth)}</h3>
                            <span>Threshold ${formatNumber(queueThreshold)}</span>
                        </div>
                        <div className="card kpi-card">
                            <div className="kpi-head-row">
                                <p className="kpi-label">GPU Compute</p>
                                <${StatusPill} label=${activeGpuBackendLabel} variant=${activeGpuBackendVariant} />
                            </div>
                            <h3>${formatPercent(gpuCompute)}</h3>
                            <div className="kpi-meta-stack">
                                <span>${activeGpuDeviceName || "No active GPU device"}</span>
                                <span>VRAM ${formatPercent(vramRatio)}</span>
                            </div>
                        </div>
                        <div className="card kpi-card">
                            <p className="kpi-label">Scheduler</p>
                            <h3>${formatNumber(Math.max(0, Math.round(schedulerRate)))}</h3>
                            <span>executions / sec</span>
                        </div>
                        <div className="card kpi-card">
                            <p className="kpi-label">Cache Total</p>
                            <h3>${formatBytes(ramCacheBytes + diskCacheBytes)}</h3>
                            <span>${formatNumber(cacheEntryCount)} entries</span>
                        </div>
                    </div>

                    <div className="overview-chart-grid">
                        <${Card} title="Workload Trend" actions=${html`<span className="card-note">Normalized</span>`}>
                            <div className="chart-pane">
                                <${MultiLineChart}
                                    series=${[
                                        { values: historySeries.cpu, color: "#1f2937" },
                                        { values: queueRatioSeries, color: "#7c879a" },
                                    ]}
                                    min=${0}
                                    max=${1}
                                />
                            </div>
                        </${Card}>
                        <${Card} title="Cache Usage" actions=${html`<span className="card-note">RAM / Disk / VRAM</span>`}>
                            <div className="chart-pane">
                                <${MultiLineChart}
                                    series=${[
                                        { values: historySeries.cacheRam, color: "#2f3f58" },
                                        { values: historySeries.cacheDisk, color: "#6f7f95" },
                                        { values: historySeries.vram, color: "#9aa8bb" },
                                    ]}
                                />
                            </div>
                            <div className="chart-legend clear-legend">
                                <span><strong>Dark line:</strong> RAM cache usage (GB)</span>
                                <span><strong>Mid line:</strong> Disk cache usage (GB)</span>
                                <span><strong>Light line:</strong> VRAM usage (GB)</span>
                            </div>
                        </${Card}>
                    </div>

                    <${Card}
                        title="Recent Activity"
                        actions=${html`<span className="card-note">${filteredEvents.length} events</span>`}
                    >
                        <div className="table-scroll">
                            <table className="data-table">
                                <thead><tr><th>Category</th><th>Activity</th><th>Date</th><th>Status</th><th>Queue</th></tr></thead>
                                <tbody>
                                    ${filteredEvents.length
                                        ? filteredEvents.map(
                                              (event, idx) => html`<tr key=${idx}>
                                                  <td>${event.type || "-"}</td>
                                                  <td>${event.message || "-"}</td>
                                                  <td>${formatTime(event.timestamp)}</td>
                                                  <td><${StatusPill} label=${formatPercent(event.gpuComputeUtil ?? 0)} variant=${(event.gpuComputeUtil ?? 0) > 0.8 ? "warn" : "ok"} /></td>
                                                  <td>${formatNumber(event.queueDepth ?? 0)}</td>
                                              </tr>`
                                          )
                                        : html`<tr><td colspan="5"><p className="text-muted">No matching recent activity.</p></td></tr>`}
                                </tbody>
                            </table>
                        </div>
                    </${Card}>
                </div>

                <aside className="overview-column secondary">
                    <${Card} title="Operational Actions">
                        <div className="action-row">
                            <button className="btn btn-primary" disabled=${busy || !state} onClick=${runStressCycle}>Run Stress Cycle</button>
                            <button className="btn btn-secondary" disabled=${busy} onClick=${clearStressCache}>Clear Stress Cache</button>
                            <button className="btn btn-ghost" disabled=${busy} onClick=${exportDiagnostics}>Export Diagnostics</button>
                        </div>
                    </${Card}>

                    <${Card} title="Top Modules">
                        <div className="top-mods-list">
                            ${topMods.length
                                ? topMods.map(
                                      (mod, idx) => html`<div className="top-mod-row" key=${idx}>
                                          <div className="stacked">
                                              <strong>${mod.id}</strong>
                                              <span className="text-muted">Queue ${mod.queue} • ${mod.memory}</span>
                                          </div>
                                          <${StatusPill} label=${mod.state} variant=${mod.variant} />
                                      </div>`
                                  )
                                : html`<p className="text-muted">No mod telemetry available.</p>`}
                        </div>
                    </${Card}>

                    <${Card} title="Live utilisation">
                        <div className="capacity-list">
                            ${capacityRows.map(
                                (row, idx) => html`<div className="capacity-item" key=${idx}>
                                    <div className="capacity-head">
                                        <strong>${row.label}</strong>
                                        <span className="text-muted">${row.summary}</span>
                                    </div>
                                    <div className="capacity-track">
                                        <span className="capacity-fill" style=${{ width: `${Math.round((Math.max(0, row.value) / Math.max(1, row.max)) * 100)}%` }}></span>
                                    </div>
                                </div>`
                            )}
                        </div>
                    </${Card}>
                </aside>
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
        const topCacheRows = sortedCacheModIds.slice(0, 14).map((modId) => {
            const entries = groupedCaches[modId] || [];
            const totalEntries = entries.reduce((sum, entry) => sum + (entry.entries || 0), 0);
            const totalHits = entries.reduce((sum, entry) => sum + (entry.hitCount || 0), 0);
            const totalMisses = entries.reduce((sum, entry) => sum + (entry.missCount || 0), 0);
            const totalEvictions = entries.reduce((sum, entry) => sum + (entry.evictions || 0), 0);
            const hitRate = totalHits + totalMisses > 0 ? totalHits / (totalHits + totalMisses) : 0;
            return { modId, modLabel: resolveModLabel(modId), totalEntries, hitRate, totalEvictions };
        });
        return html`<section className="view resources-view">
            <div className="view-head">
                <h2>Resource Center</h2>
                <p>Cache, disk and per-mod resource telemetry with quick maintenance actions.</p>
            </div>
            <div className="tab-kpi-row">
                <div className="tab-kpi">
                    <span>Mods tracked</span>
                    <strong>${formatNumber(modResourceList.length)}</strong>
                </div>
                <div className="tab-kpi">
                    <span>Disk files</span>
                    <strong>${formatNumber(diskFiles.length)}</strong>
                </div>
                <div className="tab-kpi">
                    <span>Selected files</span>
                    <strong>${formatNumber(selectedCount)}</strong>
                </div>
                <div className="tab-kpi">
                    <span>Caches</span>
                    <strong>${formatNumber(caches.length)}</strong>
                </div>
            </div>
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
            <div className="tab-layout resources-layout">
                <div className="tab-col">
                    <${Card} title="Per-Mod Usage">
                        ${modResourceList.length
                            ? html`<div className="table-scroll">
                                  <table className="data-table">
                                      <thead>
                                          <tr>
                                              <th>Mod</th><th>Queue</th><th>RAM</th><th>VRAM (peak)</th><th>Disk</th><th>Status</th><th>Actions</th>
                                          </tr>
                                      </thead>
                                      <tbody>
                                          ${modResourceList.map(
                                              (mod) => html`<tr key=${mod.modId}>
                                                  <td><div className="stacked"><strong>${mod.displayName || mod.modId}</strong><span className="text-muted">${mod.modId}</span></div></td>
                                                  <td>${formatNumber(mod.queueDepth ?? 0)}</td>
                                                  <td>${formatBytes(mod.ramBytes ?? 0)}</td>
                                                  <td>${formatBytes(mod.peakVramBytes ?? 0)}</td>
                                                  <td>${formatBytes(mod.diskBytes ?? 0)}</td>
                                                  <td><${StatusPill} label=${mod.online ? (mod.active ? "Active" : "Idle") : "Offline"} variant=${mod.online ? (mod.active ? "ok" : "warn") : "error"} /></td>
                                                  <td><button className="btn btn-ghost" disabled=${resourceBusy} onClick=${() => purgeModCache(mod.modId)}>Purge Cache</button></td>
                                              </tr>`
                                          )}
                                      </tbody>
                                  </table>
                              </div>`
                            : html`<p className="text-muted">No mods reported resource usage yet.</p>`}
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
                                    <tr><th></th><th>Mod</th><th>File</th><th>Size</th><th>Modified</th><th>Usage</th></tr>
                                </thead>
                                <tbody>
                                    ${diskFiles.length
                                        ? diskFiles.map((file, idx) => {
                                              const key = `${file.modId}::${file.file}`;
                                              return html`<tr key=${idx}>
                                                  <td><input type="checkbox" checked=${selectedFiles.has(key)} onChange=${() => toggleFileSelection(key)} /></td>
                                                  <td><div className="stacked"><strong>${file.modId}</strong><span className="text-muted">${file.modOnline ? "Mod online" : "Offline"}</span></div></td>
                                                  <td>${file.file}</td>
                                                  <td>${formatBytes(file.sizeBytes ?? 0)}</td>
                                                  <td>${formatTime(file.lastModified)}</td>
                                                  <td><${StatusPill} label=${file.modOnline ? "In Use" : "Cold"} variant=${file.modOnline ? "warn" : "ok"} /></td>
                                              </tr>`;
                                          })
                                        : html`<tr><td colspan="6"><p className="text-muted">No disk cache files detected.</p></td></tr>`}
                                </tbody>
                            </table>
                        </div>
                    </${Card}>
                </div>
                <div className="tab-col">
                    <${Card} title="Cache Overview" actions=${html`<span className="card-note">Expanded space</span>`}>
                        ${topCacheRows.length
                            ? html`<div className="table-scroll tall">
                                <table className="data-table">
                                    <thead>
                                        <tr><th>Module</th><th>Total Entries</th><th>Hit Rate</th><th>Evictions</th></tr>
                                    </thead>
                                    <tbody>
                                        ${topCacheRows.map((row, idx) => html`<tr key=${idx}>
                                            <td>
                                                <div className="stacked">
                                                    <strong>${row.modLabel}</strong>
                                                    <span className="text-muted">${row.modId}</span>
                                                </div>
                                            </td>
                                            <td>${formatNumber(row.totalEntries)}</td>
                                            <td>${formatPercent(row.hitRate)}</td>
                                            <td>${formatNumber(row.totalEvictions)}</td>
                                        </tr>`)}
                                    </tbody>
                                </table>
                            </div>`
                            : html`<p className="text-muted">No cache telemetry available yet.</p>`}
                        <div className="chart-pane">
                            <${MultiLineChart}
                                series=${[
                                    { values: historySeries.cacheRam, color: "#2b3446" },
                                    { values: historySeries.cacheDisk, color: "#64748b" },
                                    { values: historySeries.vram, color: "#9ca3af" },
                                ]}
                            />
                        </div>
                        <div className="chart-legend clear-legend">
                            <span><strong>Dark line:</strong> RAM cache usage (GB)</span>
                            <span><strong>Mid line:</strong> Disk cache usage (GB)</span>
                            <span><strong>Light line:</strong> VRAM usage (GB)</span>
                        </div>
                    </${Card}>
                </div>
            </div>
        </section>`;
    };

    const renderModMetrics = () => {
        const sortedMods = [...modMetricRows].sort((a, b) => {
            const aScore = Number(a.taskEvents ?? 0) + Number(a.cacheRequests ?? 0) + Number(a.batchTotal ?? 0);
            const bScore = Number(b.taskEvents ?? 0) + Number(b.cacheRequests ?? 0) + Number(b.batchTotal ?? 0);
            return bScore - aScore || String(a.modId || "").localeCompare(String(b.modId || ""));
        });
        const topTasks = [...modMetricTasks]
            .sort((a, b) => Number(b.count ?? 0) - Number(a.count ?? 0))
            .slice(0, 24);
        return html`<section className="view mod-metrics-view">
            <div className="view-head">
                <h2>Mod Metrics</h2>
                <p>Live task routing and cache request pressure grouped by the mod calling Quantified.</p>
            </div>
            <div className="tab-kpi-row">
                <div className="tab-kpi"><span>Mods tracked</span><strong>${formatNumber(modMetricSummary?.modsTracked ?? 0)}</strong></div>
                <div className="tab-kpi"><span>Task events</span><strong>${formatNumber(modMetricSummary?.taskEvents ?? 0)}</strong></div>
                <div className="tab-kpi"><span>Cache requests</span><strong>${formatNumber(modMetricSummary?.cacheRequests ?? 0)}</strong></div>
                <div className="tab-kpi"><span>GPU share</span><strong>${formatPercent(modMetricSummary?.gpuShare ?? 0)}</strong></div>
            </div>
            <div className="tab-layout mod-metrics-layout">
                <div className="tab-col">
                    <${Card}
                        title="Per-Mod Activity"
                        actions=${html`<button className="btn btn-ghost" onClick=${fetchModMetrics}>Refresh</button>`}
                    >
                        <div className="table-scroll tall">
                            <table className="data-table">
                                <thead>
                                    <tr>
                                        <th>Mod</th><th>Tasks</th><th>GPU</th><th>Parallel</th><th>Cache</th><th>Hit Rate</th><th>Batch Avg</th><th>Status</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    ${sortedMods.length
                                        ? sortedMods.map((mod) => html`<tr key=${mod.modId}>
                                            <td><div className="stacked"><strong>${mod.displayName || mod.modId}</strong><span className="text-muted">${mod.modId}</span></div></td>
                                            <td>${formatNumber(mod.taskEvents ?? 0)}</td>
                                            <td>${formatNumber(mod.gpuEvents ?? 0)}</td>
                                            <td>${formatNumber(mod.parallelEvents ?? 0)}</td>
                                            <td>
                                                <div className="stacked">
                                                    <strong>${formatNumber(mod.cacheRequests ?? 0)}</strong>
                                                    <span className="text-muted">${formatNumber(mod.cacheEntries ?? 0)} entries</span>
                                                </div>
                                            </td>
                                            <td>
                                                <div className="mini-meter" title=${formatPercent(mod.cacheHitRate ?? 0)}>
                                                    <span style=${{ width: `${Math.round(Math.max(0, Math.min(1, Number(mod.cacheHitRate ?? 0))) * 100)}%` }}></span>
                                                </div>
                                                <span className="text-muted">${formatPercent(mod.cacheHitRate ?? 0)}</span>
                                            </td>
                                            <td>${formatDecimal(mod.batchAvg ?? 0, 1)} <span className="text-muted">max ${formatNumber(mod.batchMax ?? 0)}</span></td>
                                            <td><${StatusPill} label=${mod.online ? (mod.active ? "Active" : "Idle") : "Offline"} variant=${mod.online ? (mod.active ? "ok" : "warn") : "error"} /></td>
                                        </tr>`)
                                        : html`<tr><td colspan="8"><p className="text-muted">No mod task or cache telemetry yet.</p></td></tr>`}
                                </tbody>
                            </table>
                        </div>
                    </${Card}>
                </div>
                <div className="tab-col">
                    <${Card} title="Hot Task Routes">
                        <div className="table-scroll tall">
                            <table className="data-table">
                                <thead>
                                    <tr><th>Mod</th><th>Task</th><th>Route</th><th>Events</th><th>Batch</th><th>Last Seen</th></tr>
                                </thead>
                                <tbody>
                                    ${topTasks.length
                                        ? topTasks.map((task, idx) => html`<tr key=${idx}>
                                            <td><div className="stacked"><strong>${task.displayName || task.modId}</strong><span className="text-muted">${task.modId}</span></div></td>
                                            <td>${task.taskName}</td>
                                            <td><${StatusPill} label=${task.route || "Unknown"} variant=${String(task.route || "").includes("GPU") ? "vulkan" : "ok"} /></td>
                                            <td>${formatNumber(task.count ?? 0)}</td>
                                            <td>${formatDecimal(task.batchAvg ?? 0, 1)} <span className="text-muted">max ${formatNumber(task.batchMax ?? 0)}</span></td>
                                            <td>${formatTime(task.lastSeenMs)}</td>
                                        </tr>`)
                                        : html`<tr><td colspan="6"><p className="text-muted">No task route telemetry in the current window.</p></td></tr>`}
                                </tbody>
                            </table>
                        </div>
                    </${Card}>
                    <${Card} title="Cache Pressure">
                        <div className="cache-pressure-list">
                            ${sortedMods.slice(0, 10).map((mod) => {
                                const requests = Number(mod.cacheRequests ?? 0);
                                const maxRequests = Math.max(1, ...sortedMods.map((row) => Number(row.cacheRequests ?? 0)));
                                return html`<div className="capacity-item" key=${mod.modId}>
                                    <div className="capacity-head">
                                        <strong>${mod.displayName || mod.modId}</strong>
                                        <span className="text-muted">${formatNumber(requests)} req / ${formatPercent(mod.cacheHitRate ?? 0)} hit</span>
                                    </div>
                                    <div className="capacity-track">
                                        <span className="capacity-fill" style=${{ width: `${Math.round((requests / maxRequests) * 100)}%` }}></span>
                                    </div>
                                </div>`;
                            })}
                            ${!sortedMods.length ? html`<p className="text-muted">No cache traffic yet.</p>` : null}
                        </div>
                    </${Card}>
                </div>
            </div>
        </section>`;
    };

    const renderLogs = () =>
        html`<section className="view logs-view">
            <div className="view-head">
                <h2>Timeline & Logs</h2>
                <p>Recent events and API runtime logs.</p>
            </div>
            <div className="tab-kpi-row">
                <div className="tab-kpi"><span>Timeline events</span><strong>${formatNumber(timelineEvents.length)}</strong></div>
                <div className="tab-kpi"><span>Log lines</span><strong>${formatNumber(logs.length)}</strong></div>
                <div className="tab-kpi"><span>Queue now</span><strong>${formatNumber(queueDepth)}</strong></div>
                <div className="tab-kpi"><span>GPU now</span><strong>${formatPercent(gpuCompute)}</strong></div>
            </div>
            <div className="tab-layout logs-layout">
                <${Card}
                    title="API Log"
                    actions=${html`<button className="btn btn-ghost" onClick=${downloadHistory}>Download History</button>`}
                >
                    <div className="log-window api-log wide-log">
                        ${logs.length ? logs.map((line, idx) => renderApiLogLine(line, idx, modColorMap)) : html`<p className="text-muted">No log lines yet.</p>`}
                    </div>
                </${Card}>
                <${Card} title="Timeline Events" className="compact-timeline-card">
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
            </div>
        </section>`;

    const toggleDefinitions = [
        { key: "dashboardEnabled", label: "Dashboard Server", description: "Serve this panel" },
        { key: "stressTestEnabled", label: "Stress Harness", description: "Keep stress testing utilities active" },
    ];

    const renderControls = () => {
        const stressProfiles = state?.stressProfiles ?? [];
        const selectedProfile = state?.stressTestProfile ?? (stressProfiles[0]?.key ?? "");
        const selectedProfileLabel =
            stressProfiles.find((option) => option.key === selectedProfile)?.label ?? state?.stressTestProfileLabel ?? "";
        return html`<section className="view controls-view">
            <div className="view-head">
                <h2>Controls</h2>
                <p>Operational controls for runtime availability and stress testing.</p>
            </div>
            <div className="tab-kpi-row">
                <div className="tab-kpi"><span>Stress cycles</span><strong>${formatNumber(state?.stressTestCycleCount ?? 0)}</strong></div>
                <div className="tab-kpi"><span>GPU tests</span><strong>${formatNumber(state?.gpuTestComputationCount ?? 0)}</strong></div>
                <div className="tab-kpi"><span>CPU tests</span><strong>${formatNumber(state?.cpuTestComputationCount ?? 0)}</strong></div>
                <div className="tab-kpi"><span>Profile</span><strong>${selectedProfileLabel || "Default"}</strong></div>
            </div>
            <div className="tab-layout controls-layout">
                <div className="tab-col controls-left-col">
                    <${Card} title="Runtime Switches">
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
                    <${Card} title="Export & Maintenance" className="controls-maintenance-card">
                        <div className="action-row">
                            <button className="btn btn-primary" onClick=${downloadHistory}>Download Timeline JSON</button>
                            <button className="btn btn-secondary" onClick=${exportDiagnostics}>Export Live Snapshot</button>
                            <button className="btn btn-ghost" disabled=${busy || !diskFiles.length} onClick=${clearDiskCache}>Clear Disk Cache</button>
                        </div>
                    </${Card}>
                </div>
                <${Card} title="Stress Operations" className="stress-orchestration-card">
                    <div className="stress-stats">
                        <div className="stress-stat">
                            <span>Total runs</span>
                            <strong>${formatNumber(state?.stressTestCycleCount ?? 0)}</strong>
                        </div>
                        <div className="stress-stat">
                            <span>GPU jobs</span>
                            <strong>${formatNumber(state?.gpuTestComputationCount ?? 0)}</strong>
                        </div>
                        <div className="stress-stat">
                            <span>CPU jobs</span>
                            <strong>${formatNumber(state?.cpuTestComputationCount ?? 0)}</strong>
                        </div>
                    </div>
                    <div className="stress-profile-block">
                        <label className="form-label" htmlFor="stress-profile-select">Execution profile</label>
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
                        <p className="stress-profile-note">
                            ${selectedProfileLabel
                                ? `Current profile: ${selectedProfileLabel}.`
                                : "Select a profile to run a validation cycle."}
                        </p>
                    </div>
                    <div className="action-row stress-actions">
                        <button className="btn btn-primary" disabled=${busy || !state} onClick=${runStressCycle}>Run cycle now</button>
                        <button className="btn btn-secondary" disabled=${busy} onClick=${clearStressCache}>Purge stress cache</button>
                        <button className="btn btn-ghost" disabled=${busy} onClick=${exportDiagnostics}>Export snapshot</button>
                    </div>
                </${Card}>
            </div>
        </section>`;
    };

    const renderConfig = () => {
        if (configLoading && !configGroups.length) {
            return html`<section className="view config-view"><p className="text-muted">Loading config metadata...</p></section>`;
        }
        if (!configGroups.length) {
            return html`<section className="view config-view"><p className="text-muted">No configuration metadata available.</p></section>`;
        }
        const query = configQuery.trim().toLowerCase();
        const visibleGroups = configGroups
            .map((group) => {
                const fields = (group.fields || []).filter((field) => {
                    if (!query) return true;
                    const haystack = `${field.label || ""} ${field.key || ""} ${field.comment || ""}`.toLowerCase();
                    return haystack.includes(query);
                });
                return { ...group, fields };
            })
            .filter((group) => group.fields.length);
        const selectedGroup = visibleGroups.find((group) => group.name === activeConfigGroup) || visibleGroups[0];
        const renderConfigMeta = (field) => html`<div className="config-label">
            <strong>${field.label}</strong>
            <code>${field.key}</code>
            <p className="config-comment">${field.comment || "No description provided."}</p>
        </div>`;
        const renderConfigField = (field) => {
            const originalValue = field.value;
            const value = Object.prototype.hasOwnProperty.call(configEdits, field.key) ? configEdits[field.key] : field.value;

            if (field.type === "boolean") {
                return html`<div className="config-field" key=${field.key}>
                    ${renderConfigMeta(field)}
                    <label className="config-boolean">
                        <span className="toggle-switch">
                            <input
                                className="toggle-input"
                                type="checkbox"
                                checked=${Boolean(value)}
                                onChange=${(event) => updateConfigValue(field.key, originalValue, event.target.checked, { delay: 0 })}
                            />
                            <span className="toggle-track" aria-hidden="true">
                                <span className="toggle-thumb"></span>
                            </span>
                        </span>
                        <span>${Boolean(value) ? "Enabled" : "Disabled"}</span>
                    </label>
                </div>`;
            }

            if (field.type === "select") {
                const options = Array.isArray(field.options) ? field.options : [];
                const hasCurrent = options.some((option) => option.value === value);
                const mergedOptions = hasCurrent || value == null ? options : [{ value, label: `${value} (current)` }, ...options];
                return html`<div className="config-field" key=${field.key}>
                    ${renderConfigMeta(field)}
                    <select
                        value=${value ?? ""}
                        onChange=${(event) => {
                            const nextValue = event.target.value;
                            const selected = mergedOptions.find((option) => option.value === nextValue);
                            const expectedDeviceName =
                                field.key === "openclDeviceId" && selected && nextValue !== "auto"
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
                    ${renderConfigMeta(field)}
                    <${NumericInput}
                        value=${Number(value ?? 0)}
                        onCommit=${(next) => updateConfigValue(field.key, originalValue, next, { delay: 0 })}
                    />
                </div>`;
            }

            if (field.type === "list") {
                const display = Array.isArray(value) ? value.join("\n") : "";
                return html`<div className="config-field" key=${field.key}>
                    ${renderConfigMeta(field)}
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
                ${renderConfigMeta(field)}
                <input
                    type="text"
                    value=${value ?? ""}
                    onInput=${(event) => updateConfigValue(field.key, originalValue, event.target.value, { delay: 650 })}
                />
            </div>`;
        };

        return html`<section className="view config-view">
            <div className="view-head">
                <h2>Configuration</h2>
                <p>Grouped settings with inline autosave.</p>
            </div>
            <div className="config-toolbar">
                <input
                    type="search"
                    value=${configQuery}
                    onInput=${(event) => setConfigQuery(event.target.value)}
                    placeholder="Search settings by name, key, or note..."
                    aria-label="Search settings"
                />
                <span className="text-muted">${visibleGroups.reduce((sum, group) => sum + group.fields.length, 0)} fields</span>
            </div>
            <div className="config-layout">
                <aside className="config-groups">
                    ${visibleGroups.map(
                        (group) => html`<button
                            key=${group.name}
                            className=${`config-group-btn ${selectedGroup?.name === group.name ? "active" : ""}`}
                            onClick=${() => setActiveConfigGroup(group.name)}
                        >
                            <span>${group.name}</span>
                            <span className="config-group-count">${group.fields.length}</span>
                        </button>`
                    )}
                </aside>
                <div className="config-main">
                    ${selectedGroup
                        ? html`<${Card} title=${selectedGroup.name} key=${selectedGroup.name}>
                            <div className="config-fields">
                                ${selectedGroup.fields.map((field) => renderConfigField(field))}
                            </div>
                        </${Card}>`
                        : html`<p className="text-muted">No matching settings for this filter.</p>`}
                </div>
            </div>
        </section>`;
    };

    const renderSystem = () => {
        const ramUsed = systemInfo.ramUsedBytes ?? 0;
        const ramTotal = systemInfo.ramTotalBytes ?? 0;
        const ramAvailable = systemInfo.ramAvailableBytes ?? 0;
        const ramUsedRatio = ramTotal > 0 ? Math.min(1, ramUsed / ramTotal) : 0;
        const ramFreeRatio = ramTotal > 0 ? Math.min(1, ramAvailable / ramTotal) : 0;
        const schedulerExec = Math.max(0, Math.round(schedulerRate));
        return html`<section className="view system-view">
            <div className="view-head">
                <h2>System</h2>
                <p>Host hardware, storage and usage trends.</p>
            </div>
            <div className="tab-kpi-row">
                <div className="tab-kpi"><span>RAM used</span><strong>${formatBytes(ramUsed)}</strong></div>
                <div className="tab-kpi"><span>RAM free</span><strong>${formatBytes(ramAvailable)}</strong></div>
                <div className="tab-kpi"><span>Queue depth</span><strong>${formatNumber(queueDepth)}</strong></div>
                <div className="tab-kpi"><span>Scheduler</span><strong>${formatNumber(Math.max(0, Math.round(schedulerRate)))}/s</strong></div>
            </div>
            <div className="card-grid two">
                <${Card} title="Hardware Overview">
                    <ul className="system-list">
                        <li><span>Operating System</span><strong>${systemInfo.os || "-"}</strong></li>
                        <li><span>CPU</span><strong>${systemInfo.cpu || "-"}</strong></li>
                        <li><span>GPU</span><strong>${systemInfo.gpu || "-"}</strong></li>
                        <li><span>RAM</span><strong>${systemInfo.ram || "-"}</strong></li>
                    </ul>
                </${Card}>
                <${Card} title="Live Runtime Utilisation" className="runtime-summary-card">
                    <div className="runtime-summary-layout">
                        <div className="runtime-memory-panel">
                            <div className="runtime-memory-head">
                                <strong>Memory utilisation</strong>
                                <span>${formatBytes(ramTotal)} total</span>
                            </div>
                            <div className="runtime-primary-value">${formatBytes(ramUsed)}</div>
                            <div className="runtime-primary-label">RAM used</div>
                            <div className="runtime-track">
                                <span style=${{ width: `${Math.round(ramUsedRatio * 100)}%` }}></span>
                            </div>
                            <div className="runtime-foot-row">
                                <span>Free ${formatBytes(ramAvailable)}</span>
                                <span>${formatPercent(ramUsedRatio)} used</span>
                            </div>
                        </div>
                        <div className="runtime-mini-grid">
                            <div className="runtime-mini-card">
                                <span>RAM free</span>
                                <strong>${formatBytes(ramAvailable)}</strong>
                                <em>${formatPercent(ramFreeRatio)} of total</em>
                            </div>
                            <div className="runtime-mini-card">
                                <span>Queue depth</span>
                                <strong>${formatNumber(queueDepth)}</strong>
                                <em>Live backlog</em>
                            </div>
                            <div className="runtime-mini-card">
                                <span>Scheduler</span>
                                <strong>${formatNumber(schedulerExec)}/s</strong>
                                <em>Current throughput</em>
                            </div>
                        </div>
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
            <div className="card-grid two">
                <${Card} title="VRAM Trend">
                    <div className="chart-pane">
                        <${MultiLineChart}
                            series=${[
                                { values: historySeries.vram, color: "#2b3446" },
                                { values: historySeries.vramBudget, color: "#8a94a9" },
                            ]}
                        />
                    </div>
                    <div className="chart-legend clear-legend">
                        <span><strong>Dark line:</strong> API VRAM used (GB)</span>
                        <span><strong>Light line:</strong> VRAM budget (GB)</span>
                    </div>
                </${Card}>
                <${Card} title="Cache Trend">
                    <div className="chart-pane">
                        <${MultiLineChart}
                            series=${[
                                { values: historySeries.cacheRam, color: "#4d5d76" },
                                { values: historySeries.cacheDisk, color: "#97a3b9" },
                            ]}
                        />
                    </div>
                    <div className="chart-legend clear-legend">
                        <span><strong>Dark line:</strong> RAM cache (GB)</span>
                        <span><strong>Light line:</strong> Disk cache (GB)</span>
                    </div>
                </${Card}>
            </div>
        </section>`;
    };

    const renderActiveView = () => {
        switch (activeView) {
            case "modMetrics":
                return renderModMetrics();
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

    const handleSelectView = useCallback(
        (nextView) => {
            if (nextView !== activeView) {
                setActiveView(nextView);
            }
        },
        [activeView]
    );

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

    const playerName = useMemo(() => {
        const candidates = [
            state?.playerName,
            state?.player?.name,
            state?.minecraftPlayerName,
            state?.username,
            state?.playerUsername,
        ];
        return candidates.find((value) => typeof value === "string" && value.trim().length > 0)?.trim() || "";
    }, [state]);

    const greeting = playerName ? `Welcome back, ${playerName}.` : "Quantified API dashboard";

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
        <div className="dashboard-bg-stage" aria-hidden="true">
            <div className="dashboard-bg-grid"></div>
            <div className="dashboard-bg-glow dashboard-bg-glow-a"></div>
            <div className="dashboard-bg-glow dashboard-bg-glow-b"></div>
            <div className="dashboard-bg-sweep"></div>
            <div className="dashboard-bg-aurora"></div>
            <div className="dashboard-bg-noise"></div>
        </div>
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
        <div className="toast-stack">
            ${toasts.map((toast) => html`
                <div className=${`toast toast-${toast.tone || "info"}`} key=${toast.id}>
                    ${toast.message}
                </div>
            `)}
        </div>
        <div className="dashboard-layout">
            <${Sidebar} activeView=${activeView} onSelect=${handleSelectView} />
            <main className="main-panel">
                <header className="main-header">
                    <div className="main-header-copy">
                        <h1>${greeting}</h1>
                        <p>Quantified API by Admany. Code is code, so if the API decides to explode, do random stuff, report it xd :DDD</p>
                    </div>
                    <div className="header-meta user-meta">
                        <span>Last updated ${lastUpdated ? formatTime(lastUpdated) : "waiting..."}</span>
                        <${StatusPill} label=${state?.dashboardEnabled ? "API Online" : "Offline"} variant=${state?.dashboardEnabled ? "ok" : "error"} />
                    </div>
                </header>
                ${error ? html`<div className="error-banner">${error}</div>` : null}
                <div className="view-transition" key=${activeView}>
                    ${renderActiveView()}
                </div>
            </main>
        </div>
        ${overlay}
    </div>`;
};

const container = document.getElementById("root");
if (container) {
    const root = createRoot(container);
    root.render(html`<${App} />`);
}







