import {
    $, escapeHtml, label, number, algorithmName, renderState, renderInspector,
    renderEvent, renderProbes, applyMapTransform, drawLinks
} from "/views.js";

const pageSize = 6;
const eventPageSize = 30;
const mainResources = [
    ["VCORES", "vCPU"], ["MEM_MIB", "ГиБ"], ["SSD_GIB", "ГиБ"], ["HDD_GIB", "ГиБ"],
    ["NVME_GIB", "ГиБ"], ["LAN_IN_MBIT", "Мбит/с"], ["LAN_OUT_MBIT", "Мбит/с"],
    ["NVIDIA_GPUS", "GPU"], ["INTEL_GPUS", "GPU"]
];
const advancedResources = [
    ["NVME_SPINDLES", "spindles"], ["SSD_SPINDLES", "spindles"], ["HDD_SPINDLES", "spindles"],
    ["NONPROD_VCORES", "vCPU"], ["NONPROD_LAN_IN_MBIT", "Мбит/с"],
    ["NONPROD_LAN_OUT_MBIT", "Мбит/с"], ["HW_CORES", "cores"], ["HW_LAN_QUEUES", "queues"]
];

const view = {
    state: null, config: null, algorithms: [], busy: false, playing: false,
    hostOffset: 0, eventOffset: 0, followEvents: true, event: null,
    selection: null, detail: null, zoom: .9, pan: { x: 0, y: 0 }, positions: new Map()
};
let timer;
let inspectorRequest = 0;
let dialogHandler = null;
let suppressClick = false;

async function api(path, options = {}) {
    const response = await fetch(path, {
        ...options,
        headers: { "X-Placement-UI": "1", ...options.headers }
    });
    const body = await response.json();
    if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`);
    return body;
}

function post(path, body) {
    return api(`${path}?summary=1`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body)
    });
}

function notify(message) {
    $("notice").textContent = message;
    $("notice").hidden = !message;
}

function showError(error) {
    $("error").textContent = error.message || String(error);
    $("error").hidden = false;
}

function updateBusy() {
    document.querySelectorAll("button, select").forEach(control => {
        if (control.id === "play" || control.closest(".map-toolbar")) return;
        if (view.busy) {
            control.dataset.wasDisabled = String(control.disabled);
            control.disabled = true;
        } else if (control.dataset.wasDisabled != null) {
            control.disabled = control.dataset.wasDisabled === "true";
            delete control.dataset.wasDisabled;
        }
    });
    $("processing").textContent = view.busy ? "Java обрабатывает запрос…" : "Общий поток событий";
}

async function perform(operation) {
    if (view.busy) return;
    view.busy = true;
    $("error").hidden = true;
    updateBusy();
    try {
        await operation();
    } catch (error) {
        pause();
        showError(error);
    } finally {
        view.busy = false;
        updateBusy();
        if (view.state) {
            renderState(view.state, view);
            drawLinks(view.detail, view.selection);
        }
    }
}

async function loadConfig() {
    const response = await api("/api/scenario");
    view.config = response.config;
    if (view.config && view.config.events == null) {
        view.config.events = view.config.requests.map(request => ({ kind: "create", request: request.name }));
    }
}

function freezeCatalogue(state) {
    if (view.config && view.config.catalogue == null) {
        view.config.catalogue = state.catalogue.map(item => item.name);
    }
}

async function refresh() {
    const state = await api(`/api/state?hostOffset=${view.hostOffset}&hostLimit=${pageSize}`
        + `&eventOffset=${view.eventOffset}&eventLimit=${eventPageSize}`);
    freezeCatalogue(state);
    view.state = state;
    renderState(state, view);
    if (state.failed) {
        pause();
        showError(new Error(state.error || "Сессия остановлена. Начните сначала."));
    }
    if (!view.selection && state.lanes[0]?.hosts[0]) {
        view.selection = { lane: 0, resource: state.lanes[0].hosts[0].id };
    }
    if (view.selection) await inspect(view.selection.lane, view.selection.resource);
}

async function inspect(lane, resource) {
    const request = ++inspectorRequest;
    view.selection = { lane, resource };
    const detail = await api(`/api/inspect?lane=${lane}&resource=${resource}`);
    if (request !== inspectorRequest) return;
    view.detail = detail;
    renderInspector(detail, {
        lane, config: view.config, laneName: algorithmName(view.state.lanes[lane].algorithm.id, view.algorithms)
    });
    document.querySelectorAll(".node.selected, .member-chip.selected").forEach(node => {
        node.classList.remove("selected");
    });
    document.querySelectorAll(`[data-lane="${lane}"][data-resource="${resource}"]`).forEach(node => {
        node.classList.add("selected");
    });
    drawLinks(detail, view.selection);
}

function clearPresentation() {
    inspectorRequest++;
    view.selection = view.detail = view.event = null;
    view.hostOffset = view.eventOffset = 0;
    view.followEvents = true;
    view.positions.clear();
    view.pan = { x: 0, y: 0 };
    $("inspector").textContent = "Выберите хост или pod на карте.";
    $("probes").textContent = "Показатели ещё не рассчитаны. Нажмите «Рассчитать сейчас».";
}

function settings() {
    return view.state.lanes.map(lane => lane.algorithm);
}

async function replaceManual(config, cursor) {
    await post("/api/manual", { config, cursor, algorithms: settings() });
    view.config = config;
    view.event = null;
    await refresh();
}

function pause() {
    view.playing = false;
    clearTimeout(timer);
    $("play").textContent = "▶ Авто";
}

async function advance(count) {
    view.event = null;
    if (view.state.cursor === 0 && view.config?.catalogue?.length === 0 && view.config.requests.length) {
        const config = structuredClone(view.config);
        config.catalogue = sampledCatalogue(config);
        await post("/api/manual", { config, cursor: 0, algorithms: settings() });
        view.config = config;
    }
    const result = await post("/api/step", { count });
    if (view.followEvents) view.eventOffset = Math.max(0, result.cursor - 4);
    await refresh();
    if (view.state.cursor >= view.state.totalEvents) pause();
}

function sampledCatalogue(config) {
    const size = Math.min(32, config.requests.length);
    return Array.from({ length: size }, (_, index) =>
        config.requests[Math.floor(index * config.requests.length / size)].name);
}

async function autoStep() {
    if (!view.playing) return;
    if (view.busy) {
        timer = setTimeout(autoStep, 100);
        return;
    }
    await perform(() => advance(1));
    if (view.playing) timer = setTimeout(autoStep, Number($("speed").value));
}

function openDialog(title, description, html, submitLabel, handler) {
    pause();
    $("dialog-title").textContent = title;
    $("dialog-description").textContent = description;
    $("dialog-content").innerHTML = html;
    $("dialog-error").textContent = "";
    $("dialog-submit").textContent = submitLabel;
    $("dialog-submit").hidden = handler == null;
    dialogHandler = handler;
    $("dialog").showModal();
}

function field(name, title, value, options = {}) {
    const { min = 0, step = "any", wide = false, type = "number", required = false } = options;
    return `<div class="field ${wide ? "wide" : ""}"><label for="field-${name}">${escapeHtml(title)}</label>
        <input id="field-${name}" name="${name}" type="${type}" value="${escapeHtml(value)}"
            ${type === "number" ? `min="${min}" max="1000000" step="${step}"` : "maxlength=80"}
            ${required ? "required" : ""}></div>`;
}

function resourceFields(host) {
    const defaults = host ? { VCORES: 16, MEM_MIB: 64, SSD_GIB: 500, LAN_IN_MBIT: 10000,
        LAN_OUT_MBIT: 10000 } : { VCORES: 2, MEM_MIB: 4, SSD_GIB: 10 };
    const input = ([key, unit]) => field(key, `${label(key)} · ${unit}`, defaults[key] || 0, {
        min: ["VCORES", "MEM_MIB"].includes(key) ? .001 : 0,
        step: key.includes("GPUS") ? host ? "1" : ".05" : "any"
    });
    return `<div class="form-grid">${mainResources.map(input).join("")}</div>
        <details style="margin-top:15px"><summary>Дополнительные ресурсные измерения</summary>
            <div class="form-grid">${advancedResources.map(input).join("")}</div></details>`;
}

function parseResources(data) {
    const resources = {};
    for (const [key] of [...mainResources, ...advancedResources]) {
        const value = Number(data.get(key));
        if (!Number.isFinite(value) || value < 0) throw new Error("Ресурсы должны быть конечными и неотрицательными.");
        if (value !== 0) resources[key] = key === "MEM_MIB" ? value * 1024 : value;
    }
    return resources;
}

function hostDialog() {
    if (!view.config) return;
    openDialog("Добавить хост", "Изменение топологии сбросит все алгоритмы на начало сценария.",
        `<div class="form-grid">${field("name", "Название", `host-${view.config.hosts.length + 1}`,
            { type: "text", wide: true, required: true })}</div>${resourceFields(true)}
        <details><summary>Физические дисковые устройства</summary><div class="form-grid">
            ${field("diskCount", "Число SSD устройств", 1, { step: "1" })}
            ${field("diskSize", "Размер одного SSD, ГиБ", 500)}
            ${field("diskIops", "IOPS на устройство", 100000)}
        </div><p class="hint" style="margin-top:10px">При наличии физических SSD движок использует их проекцию
            ёмкости вместо агрегата SSD. Extent = 64 МиБ.</p></details>`, "Добавить и начать сначала",
        async data => {
            const config = structuredClone(view.config);
            const name = String(data.get("name")).trim();
            if (!name) throw new Error("Укажите название.");
            const count = Number(data.get("diskCount"));
            if (!Number.isInteger(count) || count < 0 || count > 32) throw new Error("Допустимо 0–32 диска.");
            const size = Number(data.get("diskSize"));
            const disks = Array.from({ length: count }, (_, index) => ({
                name: `${name}-ssd-${index + 1}`, media: "SSD", resources: {
                    DEVICE_EXTENTS: Math.ceil(size * 16), DEVICE_SPINDLES: 1,
                    DEVICE_IOPS: Number(data.get("diskIops"))
                }
            }));
            config.hosts.push({ name, resources: parseResources(data), disks });
            await post("/api/manual", { config, cursor: 0, algorithms: settings() });
            view.config = config;
            clearPresentation();
            await refresh();
            notify("Хост добавлен. Все алгоритмы начали сценарий заново.");
        });
}

function requestDialog() {
    if (!view.config) return;
    const hostOptions = view.config.hosts.map(host => `<option value="${escapeHtml(host.name)}">`
        + `${escapeHtml(host.name)}</option>`).join("");
    openDialog("Создать запрос", "Будет добавлен в конец общей очереди. Каталог FitCount не изменится.",
        `<div class="form-grid">${field("name", "Название запроса", `manual-${view.config.requests.length + 1}`,
            { type: "text", wide: true, required: true })}</div>${resourceFields(false)}
        <details><summary>Фильтры и связанный volume</summary><div class="form-grid">
            <div class="field wide"><label for="pinned-host">Только на хост</label>
                <select id="pinned-host" name="pinnedHost"><option value="">Любой</option>${hostOptions}</select></div>
            ${field("allowedHosts", "Разрешённые хосты через запятую", "", { type: "text", wide: true })}
            ${field("volumeSize", "Связанный SSD volume, ГиБ (0 — без volume)", 0, { wide: true })}
        </div><p class="hint" style="margin-top:10px">Volume — отдельный SHARD в том же атомарном запросе.
            Создание и удаление pod + volume выполняются целиком, на одном хосте.</p></details>`, "В очередь",
        async data => {
            const config = structuredClone(view.config);
            const name = String(data.get("name")).trim();
            if (!name) throw new Error("Укажите название.");
            const members = [{
                name: `${name}-pod`, kind: "POD", resources: parseResources(data),
                pinnedHost: data.get("pinnedHost") || null,
                allowedHosts: String(data.get("allowedHosts")).split(",").map(name => name.trim()).filter(Boolean),
                disks: []
            }];
            const volumeSize = Number(data.get("volumeSize"));
            if (volumeSize > 0) members.push({
                name: `${name}-volume`, kind: "SHARD", resources: {}, disks: [{
                    media: "SSD", layout: "LINEAR", extents: Math.ceil(volumeSize * 16),
                    spindles: 0, exclusive: false
                }]
            });
            config.requests.push({ name, members });
            config.events.push({ kind: "create", request: name });
            await replaceManual(config, view.state.cursor);
            notify("Запрос добавлен. Состояние восстановлено на том же шаге с прежним каталогом.");
        });
}

function algorithmDialog(laneIndex = null) {
    const existing = laneIndex == null ? null : view.state.lanes[laneIndex].algorithm;
    const options = view.algorithms.map(algorithm => `<option value="${escapeHtml(algorithm.id)}"
        ${existing?.id === algorithm.id ? "selected" : ""} ${algorithm.requiresPrior ? "disabled" : ""}>`
        + `${escapeHtml(algorithm.name)}${algorithm.requiresPrior ? " — нужен prior, доступен через CLI" : ""}`
        + "</option>").join("");
    openDialog(existing ? "Параметры алгоритма" : "Добавить алгоритм к сравнению",
        "Алгоритм получит независимое облако и воспроизведёт общий поток до текущего шага.",
        `<div class="form-grid"><div class="field wide"><label for="algorithm-select">Java-алгоритм</label>
            <select id="algorithm-select" name="algorithm">${options}</select></div></div>
        <div id="algorithm-parameters"></div>
        <p class="hint" style="margin-top:15px">Наведите на название параметра или поле ввода,
            чтобы увидеть подсказку.
            Rollout с отдельным prior-файлом запускается через CLI; загрузка prior в UI пока не подключена.</p>`,
        existing ? "Применить" : "Добавить к сравнению", async data => {
            const selected = settings();
            const descriptor = view.algorithms.find(algorithm => algorithm.id === data.get("algorithm"));
            const parameters = Object.create(null);
            for (const parameter of descriptor.parameters) {
                const raw = data.get(`parameter-${parameter.key}`);
                const value = parameter.type === "BOOLEAN" ? raw === "on"
                    : parameter.type === "STRING" ? String(raw) : Number(raw);
                if (parameter.type === "INTEGER" && !Number.isSafeInteger(value)) {
                    throw new Error(`${parameter.label}: укажите целое число в допустимом диапазоне.`);
                }
                parameters[parameter.key] = value;
            }
            const setting = { id: descriptor.id, parameters };
            if (laneIndex == null) selected.push(setting);
            else selected[laneIndex] = setting;
            await post("/api/algorithms", { algorithms: selected });
            inspectorRequest++;
            view.selection = view.detail = null;
            view.event = null;
            await refresh();
        });
    const render = () => {
        const descriptor = view.algorithms.find(algorithm => algorithm.id === $("algorithm-select").value);
        const values = descriptor.id === existing?.id ? existing.parameters : {};
        $("algorithm-parameters").innerHTML = algorithmFields(descriptor, values);
    };
    $("algorithm-select").onchange = render;
    render();
}

function algorithmFields(descriptor, values) {
    if (descriptor.parameters.length === 0) {
        return `<p class="hint" style="margin-top:18px">У этого алгоритма нет настраиваемых параметров.</p>`;
    }
    const input = parameter => {
        const value = Object.hasOwn(values, parameter.key) ? values[parameter.key] : parameter.defaultValue;
        const name = `parameter-${parameter.key}`;
        const title = escapeHtml(parameter.label);
        const description = escapeHtml(parameter.description);
        const info = '<span class="parameter-info" aria-hidden="true">ⓘ</span>';
        if (parameter.type === "BOOLEAN") {
            return `<div class="field wide"><label class="checkbox-label" title="${description}">
                <input name="${name}" type="checkbox" title="${description}" aria-description="${description}"
                    ${value ? "checked" : ""}> ${title} ${info}
            </label></div>`;
        }
        const numeric = parameter.type !== "STRING";
        return `<div class="field"><label for="${name}" title="${description}">${title} ${info}</label>
            <input id="${name}" name="${name}" type="${numeric ? "number" : "text"}"
                title="${description}" aria-description="${description}"
                value="${escapeHtml(value)}" ${numeric ? "required" : ""}
                ${parameter.minimum != null ? `min="${parameter.minimum}"` : ""}
                ${parameter.maximum != null ? `max="${parameter.maximum}"` : ""}
                ${numeric ? `step="${parameter.type === "INTEGER" ? "1" : "any"}"` : ""}>
        </div>`;
    };
    const basic = descriptor.parameters.filter(parameter => !parameter.advanced);
    const advanced = descriptor.parameters.filter(parameter => parameter.advanced);
    return (basic.length ? `<div class="form-grid">${basic.map(input).join("")}</div>` : "")
        + (advanced.length ? `<details style="margin-top:18px"><summary>Дополнительно</summary>
            <div class="form-grid">${advanced.map(input).join("")}</div></details>` : "");
}

function loadDialog() {
    openDialog("Открыть сценарий", "Загрузите ручной JSON или исходную пару protobuf-файлов.",
        `<div class="stack" style="margin-top:18px">
            <button type="button" id="choose-json">Открыть ручной JSON</button>
            <button type="button" id="empty-scenario">Новое пустое облако</button>
        </div><details open style="margin-top:18px"><summary>Датасет protobuf</summary>
            <div class="form-grid"><div class="field wide"><label for="snapshot-file">snapshot.pb</label>
                <input id="snapshot-file" type="file" accept=".pb" required></div>
            <div class="field wide"><label for="operations-file">operations.pb</label>
                <input id="operations-file" type="file" accept=".pb" required></div></div>
            <p class="hint" style="margin-top:10px">До 128 МиБ суммарно. Используется штатная нормализация
                Scenario; размещение из snapshot не импортируется. Операции должны поддерживаться раннером.
                Датасет проигрывается без ручного изменения событий.</p></details>`, "Загрузить датасет",
        async () => {
            const snapshot = $("snapshot-file").files[0];
            const operations = $("operations-file").files[0];
            if (!snapshot || !operations) throw new Error("Выберите оба protobuf-файла.");
            if (snapshot.size + operations.size > 128 * 1024 * 1024) throw new Error("Размер превышает 128 МиБ.");
            await api("/api/dataset?summary=1", {
                method: "POST", headers: { "X-Snapshot-Length": String(snapshot.size),
                    "Content-Type": "application/octet-stream" }, body: new Blob([snapshot, operations])
            });
            await loadConfig();
            clearPresentation();
            await refresh();
            fitMap();
            notify("Датасет загружен. Число событий после нормализации показано в параметрах сценария.");
        });
    $("choose-json").onclick = () => { $("json-file").value = ""; $("json-file").click(); };
    $("empty-scenario").onclick = () => perform(async () => {
        await post("/api/manual", { config: { hosts: [], requests: [], events: [], catalogue: [] }, cursor: 0 });
        await loadConfig();
        clearPresentation();
        await refresh();
        $("dialog").close();
        notify("Пустое облако. Добавьте хосты и запросы. До первого шага каталог можно заполнить очередью.");
    });
}

async function deleteUnit(requestIndex) {
    if (!view.config) return;
    const config = structuredClone(view.config);
    const unit = config.requests[requestIndex];
    if (!unit) throw new Error("Запрос не найден в ручном сценарии.");
    const scheduled = config.events.findIndex((event, index) => index >= view.state.cursor
        && event.kind === "delete" && event.request === unit.name);
    if (scheduled >= 0) {
        notify(`Удаление ${unit.name} уже запланировано событием №${scheduled + 1}.`);
        return;
    }
    config.events.splice(view.state.cursor, 0, { kind: "delete", request: unit.name });
    await replaceManual(config, view.state.cursor);
    notify(`Следующий шаг удалит весь запрос ${unit.name}: ${unit.members.map(member => member.name).join(", ")}.`);
}

function setZoom(zoom) {
    view.zoom = Math.max(.2, Math.min(2, zoom));
    applyMapTransform(view);
    drawLinks(view.detail, view.selection);
}

function fitMap() {
    const map = document.querySelector(".map");
    if (!map) return;
    const rows = Math.ceil((view.state.lanes[0]?.hosts.length || 1) / 2);
    view.pan = { x: 0, y: 0 };
    setZoom(Math.min(1, map.clientWidth / 456, map.clientHeight / (rows * 208 + 25)));
}

function bindMap() {
    let drag = null;
    $("clusters").addEventListener("pointerdown", event => {
        const map = event.target.closest(".map");
        if (!map || event.button !== 0 || event.target.closest(".member-chip")) return;
        const heading = event.target.closest("[data-drag-host]");
        if (!heading && event.target.closest(".node")) return;
        const node = heading?.closest(".node");
        drag = { map, host: heading ? Number(heading.dataset.dragHost) : null,
            startX: event.clientX, startY: event.clientY, pan: { ...view.pan },
            origin: node ? { x: node.offsetLeft, y: node.offsetTop } : null, moved: false };
        map.setPointerCapture(event.pointerId);
        map.classList.add("dragging");
    });
    $("clusters").addEventListener("pointermove", event => {
        if (!drag) return;
        const dx = event.clientX - drag.startX;
        const dy = event.clientY - drag.startY;
        if (Math.abs(dx) + Math.abs(dy) < 4) return;
        drag.moved = true;
        if (drag.host != null) {
            const point = { x: drag.origin.x + dx / view.zoom, y: drag.origin.y + dy / view.zoom };
            view.positions.set(drag.host, point);
            document.querySelectorAll(`.node[data-resource="${drag.host}"]`).forEach(node => {
                node.style.left = `${point.x}px`;
                node.style.top = `${point.y}px`;
            });
        } else {
            view.pan = { x: drag.pan.x + dx, y: drag.pan.y + dy };
            applyMapTransform(view);
        }
        drawLinks(view.detail, view.selection);
    });
    const finish = () => {
        if (!drag) return;
        suppressClick = drag.moved;
        if (!drag.moved && drag.host != null) {
            inspect(Number(drag.map.dataset.map), drag.host).catch(showError);
        }
        drag.map.classList.remove("dragging");
        drag = null;
        setTimeout(() => { suppressClick = false; }, 0);
    };
    $("clusters").addEventListener("pointerup", finish);
    $("clusters").addEventListener("pointercancel", finish);
    $("clusters").addEventListener("wheel", event => {
        if (!event.ctrlKey && !event.metaKey) return;
        event.preventDefault();
        setZoom(view.zoom * (event.deltaY < 0 ? 1.1 : .9));
    }, { passive: false });
}

function bindControls() {
    $("next").onclick = () => { pause(); perform(() => advance(1)); };
    $("batch").onclick = () => { pause(); perform(() => advance(10)); };
    $("play").onclick = () => {
        if (view.playing) pause();
        else { view.playing = true; $("play").textContent = "Ⅱ Пауза"; autoStep(); }
    };
    $("reset").onclick = () => { pause(); perform(async () => {
        await post("/api/reset", {});
        view.event = null;
        view.eventOffset = 0;
        await refresh();
    }); };
    $("probe").onclick = () => { pause(); perform(async () => {
        const result = await post("/api/probes", {});
        renderProbes(result, view.algorithms);
    }); };
    $("add-host").onclick = () => { if (!view.busy) hostDialog(); };
    $("add-request").onclick = () => { if (!view.busy) requestDialog(); };
    $("add-algorithm").onclick = () => { if (!view.busy) algorithmDialog(); };
    $("load").onclick = () => { if (!view.busy) loadDialog(); };
    $("zoom-in").onclick = () => setZoom(view.zoom * 1.2);
    $("zoom-out").onclick = () => setZoom(view.zoom / 1.2);
    $("fit-map").onclick = fitMap;
    for (const [id, delta] of [["hosts-prev", -pageSize], ["hosts-next", pageSize]]) {
        $(id).onclick = () => perform(async () => {
            view.hostOffset = Math.max(0, view.hostOffset + delta);
            await refresh();
            fitMap();
        });
    }
    for (const [id, delta] of [["events-prev", -eventPageSize], ["events-next", eventPageSize]]) {
        $(id).onclick = () => perform(async () => {
            view.followEvents = false;
            view.eventOffset = Math.max(0, view.eventOffset + delta);
            await refresh();
        });
    }
    document.addEventListener("click", event => {
        if (suppressClick || view.busy) return;
        const control = event.target.closest("[data-action]");
        if (!control) return;
        const lane = Number(control.dataset.lane);
        switch (control.dataset.action) {
            case "select-resource":
                inspect(lane, Number(control.dataset.resource)).catch(showError);
                break;
            case "select-event":
                pause();
                view.event = view.state.events.find(item => item.index === Number(control.dataset.index));
                renderEvent(view.event);
                break;
            case "algorithm-settings": algorithmDialog(lane); break;
            case "remove-algorithm":
                pause();
                perform(async () => {
                    await post("/api/algorithms", { algorithms: settings().filter((_, index) => index !== lane) });
                    inspectorRequest++;
                    view.selection = view.detail = null;
                    await refresh();
                });
                break;
            case "delete-unit":
                pause();
                perform(() => deleteUnit(Number(control.dataset.unit)));
                break;
            case "refresh-catalogue":
                pause();
                perform(async () => {
                    const config = structuredClone(view.config);
                    config.catalogue = sampledCatalogue(config);
                    await replaceManual(config, 0);
                    notify("Каталог обновлён из текущих запросов. Все алгоритмы начали заново.");
                });
                break;
        }
    });
    $("dialog-close").onclick = $("dialog-cancel").onclick = () => $("dialog").close();
    $("dialog-form").onsubmit = event => {
        event.preventDefault();
        if (!dialogHandler || view.busy) return;
        const data = new FormData(event.target);
        perform(async () => {
            try { await dialogHandler(data); $("dialog").close(); }
            catch (error) { $("dialog-error").textContent = error.message; }
        });
    };
    $("save").onclick = () => {
        if (!view.config) return;
        const url = URL.createObjectURL(new Blob([JSON.stringify(view.config, null, 2)], { type: "application/json" }));
        const link = document.createElement("a");
        link.href = url;
        link.download = "placement-scenario.json";
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    };
    $("json-file").onchange = () => perform(async () => {
        const file = $("json-file").files[0];
        if (!file) return;
        if (file.size > 4 * 1024 * 1024) throw new Error("JSON превышает 4 МиБ.");
        const config = JSON.parse(await file.text());
        await post("/api/manual", { config, cursor: 0, algorithms: settings() });
        await loadConfig();
        clearPresentation();
        await refresh();
        $("dialog").close();
        fitMap();
    });
    bindMap();
}

bindControls();
perform(async () => {
    const response = await api("/api/algorithms");
    view.algorithms = response.algorithms;
    await loadConfig();
    await refresh();
    fitMap();
});
